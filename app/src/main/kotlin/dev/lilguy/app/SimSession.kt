package dev.lilguy.app

import dev.lilguy.agent.AgentRuntime
import dev.lilguy.agent.Carried
import dev.lilguy.agent.LifeRecorder
import dev.lilguy.agent.carried
import dev.lilguy.agent.memory.MemoryStore
import dev.lilguy.agent.memory.RunMemoryStore
import dev.lilguy.runlog.Ids
import dev.lilguy.runlog.RowJson
import dev.lilguy.runlog.RunRecorder
import dev.lilguy.runlog.RunRow
import dev.lilguy.runlog.SessionRow
import dev.lilguy.runlog.SyncWorker
import dev.lilguy.sim.Personality
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.seconds
import dev.lilguy.agent.DecisionLog
import dev.lilguy.agent.Scenario
import dev.lilguy.agent.TokenLedger
import dev.lilguy.agent.TokenPurpose
import dev.lilguy.agent.art.ArtStudio
import dev.lilguy.agent.art.CommissionVoice
import dev.lilguy.agent.art.Painter
import dev.lilguy.agent.memory.Oracle
import dev.lilguy.agent.memory.AgentMemory
import dev.lilguy.agent.memory.InMemoryStore
import dev.lilguy.agent.memory.MemoryScorer
import dev.lilguy.agent.memory.SelfModeler
import dev.lilguy.agent.memory.Reader
import dev.lilguy.agent.memory.Thinker
import dev.lilguy.agent.shop.Shopper
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
    /** Where runs are recorded and synced from; null records nothing. */
    private val db: RunDatabase? = null,
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
    val alpha: Float get() = if (state == RunState.Running) (accumulator / tickSeconds()).coerceAtMost(1f) else 1f

    /**
     * How many times faster than [RunSettings.speed] the clock runs now: [RunSettings.quiet] while he
     * is settled into a quiet verb ([RunSettings.QUIET_VERBS]; not on his way to it), else 1.
     */
    val fastForward: Int get() {
        if (state != RunState.Running || current.life?.doing !in RunSettings.QUIET_VERBS) return 1
        val guy = current.entities.firstOrNull { it.kind == "guy" } ?: return 1
        val before = previous.entities.firstOrNull { it.id == guy.id } ?: guy
        return if (before.x == guy.x && before.y == guy.y) settings.quiet else 1
    }

    private fun tickSeconds(): Float = settings.tickSeconds(ticksPerMinute, fastForward)

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

    /** This run's rows in the run database, or null without one. */
    var life: LifeRecorder? = null
        private set
    private var recorder: RunRecorder? = null

    /** Sends the run database's rows to the server in the background, for as long as the app runs. */
    val sync: SyncWorker? = db?.let { d -> d.sync?.let { SyncWorker(d.store, it, client = "${d.platform}@${d.host ?: "?"}") } }

    /** Why the run asked to be resumed couldn't be, if it couldn't. */
    var resumeError: String? = null
        private set

    init {
        sync?.start(scope)
        val carried = db?.let { d -> d.resume?.let { id -> runCatching { carried(d.store, id) }.onFailure { resumeError = it.message }.getOrNull() } }
        setUp(carried)
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
        accumulator += min(delta, MAX_FRAME)
        // Each tick at the rate of the moment: a quiet stretch can start or end partway through a frame.
        while (accumulator >= tickSeconds() && state == RunState.Running && !mustHold()) {
            accumulator -= tickSeconds()
            tick()
        }
        holding = state == RunState.Running && mustHold()
        if (holding) accumulator = accumulator.coerceAtMost(tickSeconds())
        if (lingerUpdates > 0) lingerUpdates--
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

    private fun setUp(carried: Carried? = null) {
        run++
        if (carried != null) settings = settings.copy(ticksPerMinute = carried.run.ticksPerMinute)
        starts += settings
        val thisRun = run
        val ledger = TokenLedger()
        val runId = carried?.run?.id ?: Ids.uuid7()
        val rec = db?.let { RunRecorder(it.store, runId, Ids.uuid7()) }
        val lr = rec?.let(::LifeRecorder)
        val memStore = rec?.let { RunMemoryStore(db!!.store, it, RunMemoryStore.lineage(db.store, carried?.resume?.checkpoint?.id)) }
        val rawModel = setup.create()
        val m = ledger.meter(rawModel, TokenPurpose.Decision, setup.prices.decision)
        val runLog = setup.logFor(run, m.id)
        val runInbox = ConcurrentLinkedQueue<AgentRuntime.DecisionRecord>()
        val rs = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        val store: MemoryStore = memStore ?: InMemoryStore()
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
        val personality = if (setup.scenario == Scenario.Bedroom) carried?.personality ?: BedroomContent.default().personality else null
        val selfModeler = if (w == null || personality == null) null else jw?.let {
            SelfModeler(ledger.meter(it, TokenPurpose.SelfModel, setup.prices.writer), store, runId, personality.core.you, personality.selfSeed)
        }
        // The deciding model draws his art, pick by pick; the writer writes his thoughts and words each day's request.
        val drawer = setup.painter?.invoke() ?: rawModel.takeIf { setup.paints }
        val studio = if (drawer == null || setup.scenario != Scenario.Bedroom) null else ArtStudio(
            Painter(ledger.meter(drawer, TokenPurpose.Art, setup.prices.decision)), store, runId, rs, setup.logDir?.let { d -> File(d, "art-%03d".format(thisRun)) },
            voice = jw?.let { CommissionVoice(ledger.meter(it, TokenPurpose.Art, setup.prices.writer)) },
        ) { drawing -> runCatching { runLog?.write(drawing) }; lr?.drawing(drawing) }
        memory = AgentMemory(
            store, runId, summarizer, scope = rs, scorer = scorer, selfModeler = selfModeler,
            onSelf = { r -> if (run == thisRun && r.error != null) writerError = "self-model: ${r.error}" },
            // While he thinks he may ask the oracle (the writer again), which searches the world's docs and answers.
            thinker = jw?.let { w ->
                Thinker(
                    ledger.meter(w, TokenPurpose.Think, setup.prices.writer),
                    oracle = Oracle(ledger.meter(w, TokenPurpose.Ask, setup.prices.writer)).takeIf { setup.scenario == Scenario.Bedroom },
                )
            },
            studio = studio,
            reader = jw?.takeIf { setup.scenario == Scenario.Bedroom }?.let { Reader(ledger.meter(it, TokenPurpose.Reading, setup.prices.writer)) },
            onAnswer = { a -> runCatching { runLog?.write(a) }; lr?.answer(a) },
            // A shopping trip's picks: the writer with reasons, falling back to the deciding model's one pick.
            shopper = if (setup.scenario != Scenario.Bedroom) null else {
                val picker = rawModel.takeIf { setup.paints }?.let { ledger.meter(it, TokenPurpose.Shop, setup.prices.decision) }
                jw?.let { Shopper(ledger.meter(it, TokenPurpose.Shop, setup.prices.writer), fallback = picker) } ?: picker?.let { Shopper(it) }
            },
            onTrip = { t -> runCatching { runLog?.write(t) }; lr?.trip(t) },
        )
        val (gw, r) = setup.scenario.create(
            m, setup.timeout, settings.ticksPerMinute, longTerm = memory, startItems = setup.startItems,
            personality = carried?.personality, from = carried?.resume?.world,
        ) {
            runCatching { runLog?.write(it) }
            lr?.decision(it)
            runInbox.add(it)
        }
        ledger.attach({ gw.tick }, (gw as? BedroomWorld)?.clock)
        carried?.resume?.agent?.let { a ->
            r.restore(a.runtime)
            a.memory?.let(memory::restore)
        }
        lr?.let { l ->
            val d = db!!
            val content = LifeRecorder.content().takeIf { setup.scenario == Scenario.Bedroom }
            val runRow = carried?.run?.copy(endedAt = null, endReason = null) ?: RunRow(
                id = runId, scenario = setup.scenario.id, platform = d.platform, host = d.host, gitSha = d.gitSha, gitDirty = d.gitDirty,
                contentHash = content?.hash, seed = 0, ticksPerMinute = settings.ticksPerMinute,
                personality = personality?.let { RowJson.encodeToJsonElement(Personality.serializer(), it) },
                models = buildJsonObject {
                    put("decision", buildJsonObject { put("id", m.id); setup.prices.decision?.let { put("price_in", it.inPerMillion); put("price_out", it.outPerMillion) } })
                    w?.let { put("memory", buildJsonObject { put("id", it.id) }) }
                    jw?.let { put("writer", buildJsonObject { put("id", it.id) }) }
                    rawScorer?.let { put("recall", buildJsonObject { put("id", it.id) }) }
                },
                settings = buildJsonObject {
                    settings.days?.let { put("days", it) }
                    put("speed", settings.speed)
                    put("quiet", settings.quiet)
                    if (setup.startItems.isNotEmpty()) put("start_items", buildJsonObject { setup.startItems.forEach { (k, v) -> put(k, v) } })
                },
                startedAt = Ids.now(),
            )
            l.begin(runRow, SessionRow(l.sessionId, runId, carried?.resume?.checkpoint?.id, d.gitSha, d.gitDirty, content?.hash, d.platform, d.host, Ids.now()), content)
            ledger.record(l)
            if (gw is BedroomWorld) l.attach(gw, r, memory, memStore) else l.attachClock(null)
        }
        life = lr
        recorder = rec
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
        life?.let { l ->
            runCatching { l.checkpointNow("shutdown") }
            l.end(endedBecause ?: "stopped")
        }
        recorder?.let { r ->
            runCatching { runBlocking { withTimeoutOrNull(2.seconds) { r.flush() } } }
            r.close()
        }
        life = null
        recorder = null
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
        life?.beforeStep()
        world.step()
        life?.afterStep()
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

    /** Updates left before the view's last [linger] lapses. */
    private var lingerUpdates = 0

    /**
     * Holds the clock through the next [LINGER_UPDATES] updates for the view: the room asks every
     * frame while he finishes a piece on screen after its verb has ended. Counted in updates, not
     * seconds, so a slow frame can't let a tick slip through; it lapses by itself when the room stops
     * asking (another page, the piece done).
     */
    fun linger() {
        lingerUpdates = LINGER_UPDATES
    }

    /** True while the clock waits on the view's [linger]. */
    val lingering: Boolean get() = lingerUpdates > 0

    /** A decision has been out for as many game minutes as [RunSettings.decideCap] allows. */
    private fun mustHold(): Boolean {
        if (lingering) return true
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
        life?.let { l -> l.settle(world.observe(world.agentId)); l.end(why) }
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
        /** A [linger] holds the clock this many updates: the one before the view's next frame, and one spare. */
        const val LINGER_UPDATES = 2
    }
}
