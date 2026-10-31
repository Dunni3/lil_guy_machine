package dev.lilguy.app

import dev.lilguy.agent.AgentRuntime
import dev.lilguy.agent.DecisionLog
import dev.lilguy.agent.Scenario
import dev.lilguy.agent.TokenLedger
import dev.lilguy.agent.TokenPurpose
import dev.lilguy.agent.art.ArtStudio
import dev.lilguy.agent.art.CommissionVoice
import dev.lilguy.agent.art.Painter
import dev.lilguy.agent.memory.AgentMemory
import dev.lilguy.agent.memory.InMemoryStore
import dev.lilguy.agent.memory.MemoryScorer
import dev.lilguy.agent.memory.SelfModeler
import dev.lilguy.agent.memory.Reader
import dev.lilguy.agent.memory.Thinker
import dev.lilguy.agent.memory.Summarizer
import dev.lilguy.decision.DecisionModel
import dev.lilguy.sim.BedroomContent
import dev.lilguy.sim.BedroomWorld
import dev.lilguy.sim.GameClock
import dev.lilguy.sim.GridWorld
import dev.lilguy.sim.WorldSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.min
import kotlin.time.Duration

/**
 * One run of [ModelSetup.scenario] at a time, under the viewer's control. A new run waits in
 * [RunState.Ready] until [start]; it can be paused and resumed; it ends on its own (the
 * key-and-door room solved, or the bedroom's [RunSettings.days] lived) and then stays ended,
 * unless [configure] gives it more days. [restart] throws the run away and sets up a blank one
 * with the current [settings], which again waits for [start]; [replay] does the same with the
 * settings an earlier run started with.
 *
 * Ticks from the render thread (which makes the render thread the sim thread), as fast as
 * [settings] say, holding the clock while a slow decision is out ([RunSettings.decideCap]) and
 * while an authored op's call (a drawing, a thought) is still out after its verb has ended ([waitingOnAuthor]).
 * The agent, the memory writers and the scorer run in a per-run child of [scope], off that thread. Every
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

    /** The Run page's values: days, speed and the decision cap apply to this run as they change, the resolution from the next. */
    var settings = setup.settings
        private set

    private val starts = mutableListOf<RunSettings>()

    /** The settings each run since the app started began with, oldest first; this run's is last. */
    val runStarts: List<RunSettings> get() = starts

    /** This run's clock resolution, ticks per game minute. */
    val ticksPerMinute: Int get() = starts.last().ticksPerMinute

    /** The tick this run ends on, or null if it doesn't end on its own. */
    val endTick: Long? get() = settings.days?.takeIf { setup.scenario == Scenario.Bedroom }?.let { it * ticksPerDay }

    /** The fewest days this run can be set to: the days it has lived, plus the one under way unless it has ended. */
    val minDays: Int get() = ((current.tick / ticksPerDay).toInt() + if (state == RunState.Ended) 0 else 1).coerceAtLeast(1)

    /** True while the clock waits on the model: a decision has been out longer than [RunSettings.decideCap] allows. */
    var holding = false
        private set

    lateinit var world: GridWorld
        private set
    lateinit var runtime: AgentRuntime
        private set

    /** This run's long-term memory: the event stream always, memories when [ModelSetup.memoryModel] is set. */
    lateinit var memory: AgentMemory
        private set

    /** Every model call this run (decisions, thoughts, summaries, the self-model, scoring) with the game time it was made at. */
    lateinit var tokens: TokenLedger
        private set

    /** The memory writer's latest failure this run, if any. */
    @Volatile var memoryError: String? = null
        private set

    /** The latest failure of the self-model's revision or of recall scoring this run, if any. */
    @Volatile var writerError: String? = null
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
    val alpha: Float get() = if (state == RunState.Running) (accumulator / settings.tickSeconds(ticksPerMinute)).coerceAtMost(1f) else 1f

    private var runScope: CoroutineScope? = null
    private var agent: Job? = null
    private var model: DecisionModel? = null
    private var writer: DecisionModel? = null
    private var textWriter: DecisionModel? = null
    private var scorerModel: Any? = null
    private var log: DecisionLog? = null
    /** Decisions from the agent coroutine, waiting for the render thread to file them in [history]. */
    private var inbox = ConcurrentLinkedQueue<AgentRuntime.DecisionRecord>()
    /** Recent ticks, to find the world a decision was made in once it arrives. */
    private val recent = ArrayDeque<WorldSnapshot>()
    private var accumulator = 0f
    /** Ticks run while the agent has been deciding. */
    private var waitTicks = 0L
    /** The run ended because its days were up, so more days can reopen it. */
    private var endedOnDays = false
    private val ticksPerDay: Long get() = GameClock.MINUTES_PER_DAY.toLong() * ticksPerMinute

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

    /** Throws this run away and sets up a fresh one with [settings], which waits for [start]. */
    fun restart() {
        tearDown()
        setUp()
    }

    /** Throws this run away for a fresh one set up as run [n] (1-based) started. */
    fun replay(n: Int) {
        settings = starts[n - 1]
        restart()
    }

    /**
     * New Run page values. Days, speed and the decision cap take hold now; days are kept to at
     * least [minDays]. More days for a run that ended on its days reopen it, paused.
     */
    fun configure(next: RunSettings) {
        settings = next.days?.let { if (it < minDays) next.copy(days = minDays) else null } ?: next
        val end = endTick
        if (state == RunState.Ended && endedOnDays && (end == null || current.tick < end)) {
            state = RunState.Paused
            endedBecause = null
            endedOnDays = false
            val r = runtime
            agent = runScope?.launch { r.run() }
        }
    }

    /** Advances real time by [delta] seconds: files new decisions, and runs whole ticks while running. */
    fun update(delta: Float) {
        collectDecisions()
        if (state != RunState.Running) return
        val tickSeconds = settings.tickSeconds(ticksPerMinute)
        accumulator += min(delta, MAX_FRAME)
        while (accumulator >= tickSeconds && state == RunState.Running && !mustHold()) {
            tick()
            accumulator -= tickSeconds
        }
        holding = state == RunState.Running && mustHold()
        if (holding) accumulator = accumulator.coerceAtMost(tickSeconds)
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
        starts += settings
        val thisRun = run
        val ledger = TokenLedger()
        val rawModel = setup.create()
        val m = ledger.meter(rawModel, TokenPurpose.Decision, setup.prices.decision)
        val runLog = setup.logFor(run, m.id)
        val runInbox = ConcurrentLinkedQueue<AgentRuntime.DecisionRecord>()
        val rs = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        val store = InMemoryStore()
        val runId = "run-$run"
        val w = setup.memoryModel?.invoke()
        val summarizer = w?.let {
            Summarizer(
                ledger.meter(it, TokenPurpose.Summary, setup.prices.memory), store, runId,
                threshold = if (setup.recallScorer != null) Summarizer.THRESHOLD else null,
                onFailure = { e -> if (run == thisRun) memoryError = e.message ?: e.toString() },
            )
        }
        val jw = setup.writerModel?.invoke()
        val rawScorer = setup.recallScorer?.invoke()
        val scorer = rawScorer?.let { MemoryScorer(ledger.meter(it, TokenPurpose.Recall, setup.prices.recall), ledger.meter(it, TokenPurpose.Significance, setup.prices.recall), onFailure = { e -> if (run == thisRun) writerError = "scoring: ${e.message ?: e}" }) }
        val personality = if (setup.scenario == Scenario.Bedroom) BedroomContent.default().personality else null
        val selfModeler = if (w == null || personality == null) null else jw?.let {
            SelfModeler(ledger.meter(it, TokenPurpose.SelfModel, setup.prices.writer), store, runId, personality.core.you, personality.selfSeed)
        }
        // The deciding model draws his art, pick by pick; the writer writes his thoughts and words each day's request.
        val studio = if (!setup.paints || setup.scenario != Scenario.Bedroom) null else ArtStudio(
            Painter(ledger.meter(rawModel, TokenPurpose.Art, setup.prices.decision)), store, runId, rs, setup.logDir?.let { d -> File(d, "art-%03d".format(thisRun)) },
            voice = jw?.let { CommissionVoice(ledger.meter(it, TokenPurpose.Art, setup.prices.writer)) },
        ) { drawing -> runCatching { runLog?.write(drawing) } }
        memory = AgentMemory(
            store, runId, summarizer, scope = rs, scorer = scorer, selfModeler = selfModeler,
            onSelf = { r -> if (run == thisRun && r.error != null) writerError = "self-model: ${r.error}" },
            thinker = jw?.let { Thinker(ledger.meter(it, TokenPurpose.Think, setup.prices.writer)) }, studio = studio,
            reader = jw?.takeIf { setup.scenario == Scenario.Bedroom }?.let { Reader(ledger.meter(it, TokenPurpose.Reading, setup.prices.writer)) },
        )
        val (gw, r) = setup.scenario.create(m, setup.timeout, settings.ticksPerMinute, longTerm = memory, startItems = setup.startItems) {
            runCatching { runLog?.write(it) }
            runInbox.add(it)
        }
        ledger.attach({ gw.tick }, (gw as? BedroomWorld)?.clock)
        tokens = ledger
        model = rawModel
        writer = w
        textWriter = jw
        scorerModel = rawScorer
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
        writerError = null
        endedBecause = null
        endedOnDays = false
        holding = false
        accumulator = 0f
        waitTicks = 0
    }

    private fun tearDown() {
        runScope?.cancel()
        // Summaries finish in the background after a run ends, so the file is written once the run is thrown away.
        setup.logDir?.let { dir -> runCatching { tokens.writeJsonl(File(dir, "tokens-%03d.jsonl".format(run))) } }
        (model as? AutoCloseable)?.close()
        (writer as? AutoCloseable)?.close()
        (textWriter as? AutoCloseable)?.close()
        (scorerModel as? AutoCloseable)?.close()
        log?.close()
        runScope = null
        agent = null
        model = null
        writer = null
        textWriter = null
        scorerModel = null
        log = null
        moments.clear()
        recent.clear()
    }

    private fun tick() {
        if (world.awaitingDecision(world.agentId)) waitTicks++ else waitTicks = 0
        world.step()
        previous = current
        current = world.snapshot()
        // Decisions are made only at decision points, so only those ticks are kept to file them against.
        if (world.awaitingDecision(world.agentId)) {
            recent.addLast(current)
            if (recent.size > MAX_RECENT) recent.removeFirst()
        }
        val endTick = endTick
        when {
            current.completed -> end("walked out after ${current.tick} ticks")
            endTick != null && current.tick >= endTick -> {
                val days = (endTick / ticksPerDay).toInt()
                end("lived $days day${if (days == 1) "" else "s"}")
                endedOnDays = true
            }
        }
    }

    /** What the clock is held for: handlers whose call has outlasted its verb ([AgentMemory.holding]), such as "draw". */
    val waitingOnAuthor: List<String> get() = memory.holdingFor

    /** A decision has been out for as many game minutes as [RunSettings.decideCap] allows. */
    private fun mustHold(): Boolean {
        // Until a drawing lands its piece has no title or tags, so selling it couldn't meet a request. Every authored call
        // holds the clock the same way.
        if (memory.holding) return true
        val cap = settings.decideCap ?: return false
        return waitTicks >= cap.toLong() * ticksPerMinute && world.awaitingDecision(world.agentId)
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
        /** Don't try to catch up more than this after a stall (a GC, the app in the background). */
        const val MAX_FRAME = 0.25f
        /** Ticks at decision points: more than a slow decision spans at a fast speed with the clock running. */
        const val MAX_RECENT = 5000
        const val MAX_MOMENTS = 5000
    }
}
