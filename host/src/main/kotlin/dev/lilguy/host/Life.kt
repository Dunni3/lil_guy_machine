package dev.lilguy.host

import dev.lilguy.agent.live.ModelSetup
import dev.lilguy.agent.live.RunDatabase
import dev.lilguy.agent.live.SimSession
import dev.lilguy.protocol.Command
import dev.lilguy.protocol.CommandResult
import dev.lilguy.protocol.Content
import dev.lilguy.protocol.LifeState
import dev.lilguy.protocol.LifeSummary
import dev.lilguy.protocol.RunList
import dev.lilguy.protocol.SavedRun
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
import kotlinx.coroutines.withTimeoutOrNull
import dev.lilguy.sim.BedroomWorld
import dev.lilguy.sim.Letter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.Executors
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
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
    /** Where viewers' letters to this life wait; null for no mail. */
    private val mail: Accounts? = null,
    private val mailEvery: Duration = 2.seconds,
) {
    /** What a viewer joining now gets: the run's static content and its whole state. */
    class Published(val content: Content, val state: LifeState)

    private val thread: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor { Thread(it, "life-$id").apply { isDaemon = true } }.asCoroutineDispatcher()
    private val agents = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val commands = Channel<Pending>(Channel.UNLIMITED)
    private val reader = StateReader()
    private val out = MutableSharedFlow<ServerMessage>(extraBufferCapacity = BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var job: Job? = null
    private var poller: Job? = null
    /** Letters read from [mail] on the IO side, handed to the life's thread; [wake] has the poller look at once. */
    private val mailIn = Channel<List<Letter>>(Channel.CONFLATED)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    /** Opened letters already told to [mail]. */
    private val reported = mutableSetOf<Long>()
    private lateinit var session: SimSession

    private class Pending(val command: Command, val done: CompletableDeferred<CommandResult>)

    /** Deltas, and on a new run its content and snapshot, in order. Missed ones show as a gap in seq. */
    val messages: SharedFlow<ServerMessage> get() = out

    /** The latest published state; null until the life has started. Readable from any thread. */
    @Volatile var latest: Published? = null
        private set

    val summary: LifeSummary? get() = latest?.let { LifeSummary(id, it.content.scenario, it.state.run.runId, it.state.run.state) }

    /**
     * Sets the life up on its thread and runs it until [stop]; [autostart] starts the first run at once, as does a run
     * carried on that was running when the host last stopped.
     */
    suspend fun start(scope: CoroutineScope, autostart: Boolean = false) {
        withContext(thread) {
            session = SimSession(agents, setup, db)
            if (autostart || session.wasRunning) session.start()
            publish()
        }
        job = scope.launch(thread) { loop() }
        if (mail != null) poller = scope.launch(Dispatchers.IO) {
            while (isActive) {
                runCatching { mail.waiting(id) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { mailIn.trySend(it) }
                withTimeoutOrNull(mailEvery) { wake.receive() }
            }
        }
    }

    /** A letter to this life was just sent: look for it now rather than at the next poll. */
    fun mailSent() {
        wake.trySend(Unit)
    }

    /**
     * The runs this life can go back to, from the run database (empty without one), the most recently played first: its
     * own scenario's, leaving out those that never got to a decision unless it's on one now. Reads the store from any thread.
     */
    fun runs(limit: Int = MAX_RUNS): RunList {
        val store = db?.store ?: return RunList(emptyList())
        val live = latest?.state?.run?.runId
        val out = store.played(limit * 4).asSequence().filter { it.scenario == setup.scenario.id }.mapNotNull { r ->
            val last = store.lastTick(r.id)
            if (last == null && r.id != live) return@mapNotNull null
            val save = store.latestSave(r.id)
            val savedAt = save?.let { (it.day - 1) * MINUTES_PER_DAY + it.minuteOfDay }
            val reachedAt = if (save == null || last == null) savedAt else savedAt!! + ((last - save.tick).coerceAtLeast(0) / r.ticksPerMinute).toInt()
            SavedRun(
                runId = r.id, scenario = r.scenario, platform = r.platform,
                model = (r.models["decision"] as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull,
                startedAt = r.startedAt, playedAt = store.lastPlayed(r.id) ?: r.startedAt, endedAt = r.endedAt, endReason = r.endReason,
                finished = r.endedAt != null && !SimSession.left(r.endReason),
                saved = savedAt?.let(::gameTime), reached = reachedAt?.let(::gameTime), days = reachedAt?.let { it / MINUTES_PER_DAY + 1 } ?: 0,
                live = r.id == live,
            )
        }.take(limit).toList()
        return RunList(out)
    }

    /** Runs [command] on the life's thread between ticks. */
    suspend fun command(command: Command): CommandResult {
        val done = CompletableDeferred<CommandResult>()
        commands.send(Pending(command, done))
        return done.await()
    }

    fun stop() {
        poller?.cancel()
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
            mailIn.tryReceive().getOrNull()?.let(::post)
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

    /**
     * Puts [letters] through the hatch of the run that's going, and tells [mail] which run has them. Only while it runs:
     * otherwise they wait for the next poll, so nothing is on its way in when a stopped run is saved.
     */
    private fun post(letters: List<Letter>) {
        val world = session.world as? BedroomWorld ?: return
        if (session.state != SimSession.RunState.Running) return
        letters.forEach(world::post)
        val run = session.runId
        agents.launch(Dispatchers.IO) { runCatching { mail!!.delivered(letters.map { it.id }, run) } }
    }

    /** Tells [mail] about letters he has opened since the last time. */
    private fun reportOpened() {
        val m = mail ?: return
        val opened = (session.world as? BedroomWorld)?.lettersOpened ?: return
        val new = opened - reported
        if (new.isEmpty()) return
        reported += new
        agents.launch(Dispatchers.IO) { runCatching { m.opened(new) } }
    }

    /** Reads the session, and sends what changed: a delta, or for a new run its content and a snapshot. */
    private fun publish() {
        reportOpened()
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
        const val MINUTES_PER_DAY = 1440
        /** Runs [runs] lists at most. */
        const val MAX_RUNS = 20

        /** "d3 14:20" for [minutes] since day 1 00:00. */
        fun gameTime(minutes: Int) = "d${minutes / MINUTES_PER_DAY + 1} %02d:%02d".format(minutes % MINUTES_PER_DAY / 60, minutes % 60)

        /** Messages kept for a viewer that's behind before it has to resync from a snapshot. */
        const val BUFFER = 256
        const val MAX_STEP = 10_000
    }
}
