package dev.lilguy.app

import dev.lilguy.agent.AgentRuntime
import dev.lilguy.agent.DecisionLog
import dev.lilguy.agent.memory.AgentMemory
import dev.lilguy.agent.memory.InMemoryStore
import dev.lilguy.agent.memory.Summarizer
import dev.lilguy.decision.DecisionModel
import dev.lilguy.sim.GridWorld
import dev.lilguy.sim.World
import dev.lilguy.sim.WorldSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.min
import kotlin.time.Duration

/**
 * One run of [ModelSetup.scenario] at a time, under the viewer's control. A new run waits in
 * [RunState.Ready] until [start]; it can be paused and resumed; it ends on its own (the
 * key-and-door room solved, or the bedroom's [ModelSetup.days] lived) and then stays ended.
 * [restart] throws the run away and sets up a blank one, which again waits for [start].
 *
 * Ticks at a fixed 20 Hz from the render thread (which makes the render thread the sim thread).
 * The agent and the memory writer run in a per-run child of [scope], off that thread. Every
 * decision is kept with the world as it was when it was made ([history]), so the view can flip
 * back through the whole run.
 */
class SimSession(
    private val scope: CoroutineScope,
    val setup: ModelSetup,
) {
    enum class RunState { Ready, Running, Paused, Ended }

    /** One decision and the world it was made in. */
    class Moment(val decision: AgentRuntime.DecisionRecord, val snapshot: WorldSnapshot)

    var state = RunState.Ready
        private set

    /** Counts runs since the app started; the first is 1. */
    var run = 0
        private set

    lateinit var world: GridWorld
        private set
    lateinit var runtime: AgentRuntime
        private set

    /** This run's long-term memory: the event stream always, memories when [ModelSetup.memoryModel] is set. */
    lateinit var memory: AgentMemory
        private set

    /** The memory writer's latest failure this run, if any. */
    @Volatile var memoryError: String? = null
        private set

    /** Why the run ended, once it has. */
    var endedBecause: String? = null
        private set

    /** The live world, and the tick before it for interpolating between the two. */
    lateinit var current: WorldSnapshot
        private set
    lateinit var previous: WorldSnapshot
        private set

    private val moments = ArrayDeque<Moment>()

    /** Every decision this run, oldest first, each with the world it was made in. */
    val history: List<Moment> get() = moments

    /** The agent's latest decision. */
    val decision: AgentRuntime.DecisionRecord? get() = runtime.lastDecision

    /** How long the agent has been waiting on the model, or null if it isn't. */
    val thinkingFor: Duration? get() = runtime.decidingSince?.elapsedNow()

    /** How far the clock is between [previous] and [current], 0..1. */
    val alpha: Float get() = if (state == RunState.Running) accumulator / TICK else 1f

    private var runScope: CoroutineScope? = null
    private var agent: Job? = null
    private var model: DecisionModel? = null
    private var writer: DecisionModel? = null
    private var log: DecisionLog? = null
    /** Decisions from the agent coroutine, waiting for the render thread to file them in [history]. */
    private var inbox = ConcurrentLinkedQueue<AgentRuntime.DecisionRecord>()
    /** Recent ticks, to find the world a decision was made in once it arrives. */
    private val recent = ArrayDeque<WorldSnapshot>()
    private var accumulator = 0f

    init {
        setUp()
    }

    /** Starts a ready run, or resumes a paused one. */
    fun start() {
        when (state) {
            RunState.Ready -> {
                val r = runtime
                agent = runScope?.launch { r.run() }
                state = RunState.Running
            }
            RunState.Paused -> state = RunState.Running
            else -> Unit
        }
    }

    fun pause() {
        if (state == RunState.Running) state = RunState.Paused
    }

    /** OK on the remote: start, pause or resume. Does nothing once the run has ended. */
    fun toggle() = if (state == RunState.Running) pause() else start()

    /** Throws this run away and sets up a fresh one, which waits for [start]. */
    fun restart() {
        tearDown()
        setUp()
    }

    /** Advances real time by [delta] seconds: files new decisions, and runs whole ticks while running. */
    fun update(delta: Float) {
        collectDecisions()
        if (state != RunState.Running) return
        accumulator += min(delta, MAX_FRAME)
        while (accumulator >= TICK && state == RunState.Running) {
            tick()
            accumulator -= TICK
        }
    }

    /** Runs [ticks] ticks by hand; only while paused, so the clock can't run away. */
    fun step(ticks: Int) {
        repeat(ticks) {
            if (state != RunState.Paused) return
            tick()
            collectDecisions()
        }
    }

    fun dispose() = tearDown()

    private fun setUp() {
        run++
        val thisRun = run
        val m = setup.create()
        val runLog = setup.logFor(run, m.id)
        val runInbox = ConcurrentLinkedQueue<AgentRuntime.DecisionRecord>()
        val rs = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        val store = InMemoryStore()
        val runId = "run-$run"
        val w = setup.memoryModel?.invoke()
        val summarizer = w?.let {
            Summarizer(it, store, runId, onFailure = { e -> if (run == thisRun) memoryError = e.message ?: e.toString() })
        }
        memory = AgentMemory(store, runId, summarizer, scope = rs)
        val (gw, r) = setup.scenario.create(m, setup.timeout, setup.ticksPerMinute, longTerm = memory) {
            runCatching { runLog?.write(it) }
            runInbox.add(it)
        }
        model = m
        writer = w
        log = runLog
        inbox = runInbox
        runScope = rs
        world = gw
        runtime = r
        current = gw.snapshot()
        previous = current
        recent.addLast(current)
        state = RunState.Ready
        memoryError = null
        endedBecause = null
        accumulator = 0f
    }

    private fun tearDown() {
        runScope?.cancel()
        (model as? AutoCloseable)?.close()
        (writer as? AutoCloseable)?.close()
        log?.close()
        runScope = null
        agent = null
        model = null
        writer = null
        log = null
        moments.clear()
        recent.clear()
    }

    private fun tick() {
        world.step()
        previous = current
        current = world.snapshot()
        recent.addLast(current)
        if (recent.size > MAX_RECENT) recent.removeFirst()
        val endTick = setup.endTick
        when {
            current.completed -> end("walked out after ${current.tick} ticks")
            endTick != null && current.tick >= endTick -> end("lived ${setup.days} day${if (setup.days == 1) "" else "s"}")
        }
    }

    /** Stops the agent and writes up whatever memory is left; the run stays on screen. */
    private fun end(why: String) {
        state = RunState.Ended
        endedBecause = why
        agent?.cancel()
        val mem = memory
        runScope?.launch { mem.flush() }
    }

    private fun collectDecisions() {
        while (true) {
            val d = inbox.poll() ?: return
            val seen = recent.lastOrNull { it.tick == d.tick } ?: recent.firstOrNull() ?: current
            moments.addLast(Moment(d, seen))
            if (moments.size > MAX_MOMENTS) moments.removeFirst()
        }
    }

    private companion object {
        const val TICK = 1f / World.TICKS_PER_SECOND
        /** Don't try to catch up more than this after a stall (a GC, the app in the background). */
        const val MAX_FRAME = 0.25f
        /** A minute of ticks: longer than any decision may take. */
        const val MAX_RECENT = 60 * World.TICKS_PER_SECOND
        const val MAX_MOMENTS = 5000
    }
}
