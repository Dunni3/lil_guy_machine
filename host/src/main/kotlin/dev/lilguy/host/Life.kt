package dev.lilguy.host

import dev.lilguy.agent.live.ModelSetup
import dev.lilguy.agent.live.RunDatabase
import dev.lilguy.agent.live.SimSession
import dev.lilguy.protocol.Command
import dev.lilguy.protocol.CommandResult
import dev.lilguy.protocol.Content
import dev.lilguy.protocol.LifeState
import dev.lilguy.protocol.LifeSummary
import dev.lilguy.protocol.ServerMessage
import dev.lilguy.protocol.Snapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * One running life on the host: a [SimSession] (the same loop the app runs from its render
 * thread) owned by a coroutine actor on its own thread. The loop advances real time every
 * [frame], takes [Command]s between ticks, and every [publishEvery] reads the session into a
 * [LifeState] and sends what changed as a [dev.lilguy.protocol.Delta] on [messages]; a new run
 * sends its [Content] and a whole [Snapshot] instead. The agent and the model calls run on
 * [Dispatchers.Default], recording on the recorder's IO coroutine, and sending to viewers never
 * waits: [messages] drops the oldest when a viewer falls behind, and the viewer resyncs from
 * [latest]. So nothing a viewer or a model does can hold up a tick.
 */
class Life(
    val id: String,
    private val setup: ModelSetup,
    private val db: RunDatabase?,
    private val frame: Duration = 50.milliseconds,
    private val publishEvery: Duration = 100.milliseconds,
) {
    /** What a viewer joining now gets: the run's static content and its whole state. */
    class Published(val content: Content, val state: LifeState)

    private val thread: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor { Thread(it, "life-$id").apply { isDaemon = true } }.asCoroutineDispatcher()
    private val agents = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val commands = Channel<Pending>(Channel.UNLIMITED)
    private val reader = StateReader()
    private val out = MutableSharedFlow<ServerMessage>(extraBufferCapacity = BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var job: Job? = null
    private lateinit var session: SimSession

    private class Pending(val command: Command, val done: CompletableDeferred<CommandResult>)

    /** Deltas, and on a new run its content and snapshot, in order. Missed ones show as a gap in seq. */
    val messages: SharedFlow<ServerMessage> get() = out

    /** The latest published state; null until the life has started. Readable from any thread. */
    @Volatile var latest: Published? = null
        private set

    val summary: LifeSummary? get() = latest?.let { LifeSummary(id, it.content.scenario, it.state.run.runId, it.state.run.state) }

    /** Sets the life up on its thread and runs it until [stop]; [autostart] starts the first run at once. */
    suspend fun start(scope: CoroutineScope, autostart: Boolean = false) {
        withContext(thread) {
            session = SimSession(agents, setup, db)
            if (autostart) session.start()
            publish()
        }
        job = scope.launch(thread) { loop() }
    }

    /** Runs [command] on the life's thread between ticks. */
    suspend fun command(command: Command): CommandResult {
        val done = CompletableDeferred<CommandResult>()
        commands.send(Pending(command, done))
        return done.await()
    }

    fun stop() {
        job?.cancel()
        runCatching { kotlinx.coroutines.runBlocking(thread) { session.dispose() } }
        agents.cancel()
        thread.close()
    }

    private suspend fun loop() {
        var last = TimeSource.Monotonic.markNow()
        var sincePublish = Duration.ZERO
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            while (true) {
                val p = commands.tryReceive().getOrNull() ?: break
                p.done.complete(run(p.command))
            }
            val now = TimeSource.Monotonic.markNow()
            val delta = now - last
            last = now
            session.update(delta.inWholeMicroseconds / 1e6f)
            sincePublish += delta
            if (sincePublish >= publishEvery) {
                sincePublish = Duration.ZERO
                publish()
            }
            delay(frame)
        }
    }

    private fun run(c: Command): CommandResult {
        val s = session
        val error: String? = when (c) {
            Command.Start -> if (s.state == SimSession.RunState.Ended) "the run has ended" else { s.start(); null }
            Command.Pause -> if (s.state != SimSession.RunState.Running) "not running" else { s.pause(); null }
            Command.Restart -> { s.restart(); null }
            is Command.Replay -> if (c.run !in 1..s.runStarts.size) "no run ${c.run}" else { s.replay(c.run); null }
            is Command.Configure -> settingsError(c) ?: run { s.configure(StateReader.runSettings(c.settings)); null }
            is Command.Resume -> if (s.resume(c.run)) null else s.resumeError ?: "can't resume ${c.run}"
            is Command.Step -> if (s.state != SimSession.RunState.Paused) "only while paused" else { s.step(c.ticks.coerceIn(1, MAX_STEP)); null }
        }
        publish()
        return CommandResult(ok = error == null, error = error, seq = latest?.state?.seq, state = latest?.state?.run?.state)
    }

    private fun settingsError(c: Command.Configure): String? {
        val s = c.settings
        return when {
            s.speed < 1 -> "speed must be at least 1"
            s.ticksPerMinute < 1 -> "ticks_per_minute must be at least 1"
            s.quiet < 1 -> "quiet must be at least 1"
            (s.days ?: 1) < 1 -> "days must be at least 1, or null for no end"
            (s.decideCap ?: 0) < 0 -> "decide_cap can't be negative"
            else -> null
        }
    }

    /** Reads the session, and sends what changed: a delta, or for a new run its content and a snapshot. */
    private fun publish() {
        val before = latest
        val newRun = before == null || before.state.run.runId != session.runId || before.state.run.run != session.run
        val content = if (newRun) reader.content(session) else before!!.content
        val seq = (before?.state?.seq ?: 0L) + 1
        val next = reader.state(id, seq, content.hash, session)
        if (newRun) {
            latest = Published(content, next)
            out.tryEmit(content)
            out.tryEmit(Snapshot(next))
            return
        }
        val delta = before!!.state.diff(next) ?: return
        latest = Published(content, next)
        out.tryEmit(delta)
    }

    private companion object {
        /** Messages kept for a viewer that's behind before it has to resync from a snapshot. */
        const val BUFFER = 256
        const val MAX_STEP = 10_000
    }
}
