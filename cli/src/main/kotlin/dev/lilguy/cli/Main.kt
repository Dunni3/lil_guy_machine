package dev.lilguy.cli

import dev.lilguy.agent.AgentRuntime
import dev.lilguy.agent.BedroomReport
import dev.lilguy.agent.ContentDocs
import dev.lilguy.agent.DaySummary
import dev.lilguy.agent.DecisionLog
import dev.lilguy.agent.HourSample
import dev.lilguy.agent.ReplayDecisionModel
import dev.lilguy.agent.TokenCall
import dev.lilguy.agent.TokenDay
import dev.lilguy.agent.TokenLedger
import dev.lilguy.agent.TokenPrice
import dev.lilguy.agent.TokenPurpose
import dev.lilguy.agent.TokenReport
import dev.lilguy.agent.TokenTotals
import dev.lilguy.agent.memory.AgentMemory
import dev.lilguy.agent.memory.Answer
import dev.lilguy.agent.memory.InMemoryStore
import dev.lilguy.agent.memory.Oracle
import dev.lilguy.agent.art.ArtStudio
import dev.lilguy.agent.art.CommissionVoice
import dev.lilguy.agent.art.Drawing
import dev.lilguy.agent.art.Painter
import dev.lilguy.agent.memory.MemoryScorer
import dev.lilguy.agent.memory.SelfModeler
import dev.lilguy.agent.memory.SelfRevision
import dev.lilguy.agent.memory.Reader
import dev.lilguy.agent.memory.Thinker
import dev.lilguy.agent.memory.MemoryStore
import dev.lilguy.agent.memory.Summarizer
import dev.lilguy.memory.SqliteMemoryStore
import dev.lilguy.agent.Scenario
import dev.lilguy.agent.runLockstep
import dev.lilguy.decision.DecisionModel
import dev.lilguy.decision.Endpoints
import dev.lilguy.decision.Scorer
import dev.lilguy.decision.StructuredOutput
import dev.lilguy.decision.withBeam
import dev.lilguy.sim.BedroomContent
import dev.lilguy.sim.BedroomWorld
import dev.lilguy.sim.Personality
import dev.lilguy.sim.GameClock
import dev.lilguy.sim.Intent
import dev.lilguy.sim.Need
import dev.lilguy.sim.NeedChange
import dev.lilguy.sim.Transaction
import dev.lilguy.sim.World
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private const val USAGE = """lil guy machine cli: lockstep runs of the key-and-door room or the bedroom.

  --scenario S        key-door (default) or bedroom
  --endpoint NAME     endpoint from the config, or "scripted" (default)
  --runs N            number of runs (default 1)
  --days N            bedroom: game days per run (default 3)
  --minute-ticks N    bedroom: ticks per game minute, the clock scale (default 5)
  --ticks N           sim ticks per run before giving up (default: key-door 2000, bedroom the days)
  --max-decisions N   decisions per run before giving up (default: key-door 60, bedroom 60 a day)
  --config PATH       endpoint config (default config/endpoints.yaml)
  --strategy S        override the endpoint's structured_output: json_schema, tools or text
  --beam N            typesafe: walk the menu tree keeping N paths; 0 picks from the flat list
                      (default: the endpoint's beam, 3)
  --log-dir DIR       where run logs go (default runs/); --no-log to skip
  --replay FILE       play back a decision log instead of calling a model
  --memory FILE       keep long-term memory in this SQLite file: how each action turned out, what
                      happened around him, a daily snapshot, his thoughts; plus memories when
                      --memory-endpoint is set. Recalled memories and thoughts join each prompt
  --memory-endpoint N endpoint that writes the memories, of stretches and of whole days (must write
                      text, so not typesafe)
  --writer-endpoint N bedroom: endpoint that writes his thoughts when he thinks, revises his
                      self-model each night (with --memory-endpoint), and words each day's request
                      for a piece (must write text). Defaults to --memory-endpoint; "none" for off.
                      The run ends with every version of his self-model, in self-NN.txt.
                      (--journal-endpoint is its old name)
  --recall-endpoint N typesafe endpoint that scores which memories and thoughts reach each prompt,
                      and how significant each event is (which decides when memories are written).
                      Defaults to the deciding endpoint when it's typesafe; "none" for keywords
                      His art is drawn by the deciding model itself, one closed pick a stroke, so
                      typesafe draws too (a PNG per piece in art-NN/ beside the log); the scripted
                      policy and a replay don't draw
  --grant IDS         bedroom: unlock gated content, comma-separated ids (e.g. paint,easel,canvas,painting)
  --affinities Q=W    bedroom: his personality for these runs, quality=weight pairs over the content's
                      default (e.g. calm=2,loud=-1); weights -1 to 2
  --curiosity X       bedroom: scales how fast stimulation drains and how much a first time pays (default 1)
  --quiet             one line per run instead of every decision and event
  --content-docs      write the bedroom content's JSON Schemas (sim/src/main/resources/content/bedroom/schema/)
                      and its grammar reference page (build/grammar-reference.html), then exit

Key-and-door exits non-zero unless every run solved the room; the bedroom, unless every run
lived its days without hitting the decision cap. Bedroom runs also write hours-NN.jsonl (money
and needs each game hour), money-NN.jsonl (every transaction) and needs-NN.jsonl (every need
change) next to the decision log, tokens-NN.jsonl (every model call: sim tick, game day, purpose, tokens, cost when the endpoint has
price_in/price_out) and self-NN.txt when his self-model is revised. Drawings are
logged in the decision log too, as lines with "kind": "art"."""

private class Args(argv: Array<String>) {
    private val values = mutableMapOf<String, String>()
    private val flags = mutableSetOf<String>()

    init {
        var i = 0
        while (i < argv.size) {
            val a = argv[i]
            require(a.startsWith("--")) { "unexpected argument '$a'" }
            if (a in FLAGS) flags += a else {
                require(i + 1 < argv.size) { "$a needs a value" }
                values[a] = argv[++i]
            }
            i++
        }
        val unknown = (values.keys + flags) - KNOWN
        require(unknown.isEmpty()) { "unknown option ${unknown.first()}" }
    }

    fun string(name: String) = values[name]
    fun int(name: String, default: Int) = values[name]?.toIntOrNull() ?: default
    fun flag(name: String) = name in flags

    companion object {
        val FLAGS = setOf("--quiet", "--no-log", "--help", "--content-docs")
        val KNOWN = FLAGS + setOf(
            "--scenario", "--endpoint", "--runs", "--days", "--minute-ticks", "--ticks", "--max-decisions", "--config", "--strategy", "--beam", "--log-dir", "--replay", "--memory", "--memory-endpoint", "--writer-endpoint", "--journal-endpoint", "--recall-endpoint", "--grant",
            "--affinities", "--curiosity",
        )
    }
}

@Serializable
private data class RunResult(
    val run: Int,
    val scenario: String,
    /** Key-and-door: walked out. Bedroom: lived the days without hitting the decision cap. */
    val solved: Boolean,
    val ticks: Long,
    val decisions: Int,
    val fallbacks: Int,
    val retries: Int,
    @SerialName("prompt_tokens") val promptTokens: Int,
    @SerialName("completion_tokens") val completionTokens: Int,
    @SerialName("wall_ms") val wallMs: Long,
    val log: String?,
    /** Intents the sim refused, with their reasons. */
    @SerialName("failed_intents") val failedIntents: Int = 0,
    val bedroom: BedroomResult? = null,
    /** Every model call by game day and purpose; prompt_tokens and completion_tokens above are its total. */
    val tokens: TokenResult? = null,
)

@Serializable
private data class TokenResult(
    val total: TokenTotals,
    @SerialName("by_purpose") val byPurpose: Map<TokenPurpose, TokenTotals>,
    @SerialName("by_day") val byDay: List<TokenDay>,
    /** Total tokens over game days elapsed; null for a room with no clock. */
    @SerialName("tokens_per_game_day") val tokensPerGameDay: Double? = null,
)

@Serializable
private data class BedroomResult(
    val days: List<DaySummary>,
    /** Lowest level of each need over the run, checked every tick. */
    val lowest: Map<String, Double>,
    /** Ticks with energy, fullness or mood at 0 (stimulation at 0 is just bored, and frustration at 0 is good). */
    @SerialName("ticks_at_zero") val ticksAtZero: Long,
    @SerialName("money_end") val moneyEnd: Int,
    val bought: List<String>,
    /** Decisions per intent: "do sketch" to 40. */
    val choices: Map<String, Int>,
    /** His self-model's nightly revisions, when something revises it. */
    val self: SelfResult? = null,
)

@Serializable
private data class SelfResult(
    /** Nights it was revised, changed or not. */
    val nights: Int,
    /** Versions written: nights it changed. */
    val versions: Int,
    /** Nights the call failed. */
    val failed: Int,
)

fun main(argv: Array<String>) {
    val args = try {
        Args(argv)
    } catch (e: IllegalArgumentException) {
        System.err.println("${e.message}\n\n$USAGE")
        exitProcess(2)
    }
    if (args.flag("--help")) {
        println(USAGE)
        return
    }
    if (args.flag("--content-docs")) {
        val page = File("build/grammar-reference.html")
        ContentDocs.write(BedroomContent.default(), File("sim/src/main/resources/content/bedroom/schema"), page)
        println("wrote the content schemas and ${page.path}")
        return
    }
    val scenario = try {
        Scenario.parse(args.string("--scenario") ?: Scenario.KeyDoor.id)
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        exitProcess(2)
    }
    val endpoint = args.string("--endpoint") ?: "scripted"
    val replay = args.string("--replay")?.let(::File)
    val runs = if (replay != null) 1 else args.int("--runs", 1)
    val minuteTicks = args.int("--minute-ticks", GameClock.DEFAULT_TICKS_PER_MINUTE)
    val days = args.int("--days", 3)
    val maxTicks = args.string("--ticks")?.toLongOrNull() ?: when (scenario) {
        Scenario.KeyDoor -> 2000L
        Scenario.Bedroom -> days.toLong() * GameClock.MINUTES_PER_DAY * minuteTicks
    }
    val maxDecisions = args.int("--max-decisions", if (scenario == Scenario.Bedroom) 60 * days else 60)
    val quiet = args.flag("--quiet")
    val granted = args.string("--grant")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()
    val personality = personalityOf(args)

    // Each run gets a fresh model, so nothing carries over between runs.
    val (label, timeout, newModel) = try {
        modelSource(scenario, endpoint, replay, args)
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        exitProcess(2)
    }

    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val prefix = if (scenario == Scenario.KeyDoor) "" else "${scenario.id}_"
    val logDir = if (args.flag("--no-log") || replay != null) null else File(args.string("--log-dir") ?: "runs", "${stamp}_$prefix$label")
    val span = if (scenario == Scenario.Bedroom) "$days days at $minuteTicks ticks a game minute" else "max $maxTicks ticks"
    println("lil guy machine cli: ${scenario.id}, model '$label', $runs run(s), $span / $maxDecisions decisions per run")
    logDir?.let { println("logs: ${it.path}/") }

    val results = (1..runs).map { n ->
        val ledger = TokenLedger()
        val config by lazy { Endpoints.load(File(args.string("--config") ?: Endpoints.DEFAULT_PATH)) }
        fun priceOf(name: String) = config.endpoints[name]?.let(TokenPrice::of)
        val decisionModel = newModel()
        val model = ledger.meter(decisionModel, TokenPurpose.Decision, if (replay == null && endpoint != "scripted") priceOf(endpoint) else null)
        val log = logDir?.let { DecisionLog(File(it, "run-%02d.jsonl".format(n)), run = "$stamp-$n", model = model.id) }
        val summaryEndpoint = args.string("--memory-endpoint")
        val summaryModel = summaryEndpoint?.let { name ->
            requireNotNull(args.string("--memory")) { "--memory-endpoint needs --memory FILE" }
            Endpoints.create(config, name)
        }
        // Like the app: the writer (thoughts, the self-model, commissions) defaults to the memory endpoint; "none" turns it off.
        val writerEndpoint = (args.string("--writer-endpoint") ?: args.string("--journal-endpoint") ?: args.string("--memory-endpoint"))?.takeIf { it != "none" }
        val writerModel = writerEndpoint?.let { Endpoints.create(config, it) }
        // Recall and significance are scored by a typesafe endpoint: the one named, or the deciding one if it can score.
        val recallEndpoint = args.string("--recall-endpoint")
        val ownScorer = recallEndpoint?.takeIf { it != "none" && it != endpoint }?.let { Endpoints.scorer(config, it) }
        val rawScorer: Scorer? = when {
            recallEndpoint == "none" -> null
            ownScorer != null -> ownScorer
            else -> decisionModel as? Scorer
        }
        val scorerName = recallEndpoint?.takeIf { it != "none" } ?: endpoint
        // The deciding model draws his art, pick by pick, so a picking model can; the scripted policy and a replay can't.
        val painting = scenario == Scenario.Bedroom && replay == null && endpoint != "scripted"
        val store: MemoryStore? = args.string("--memory")?.let { SqliteMemoryStore(File(it)) } ?: if (writerModel != null || painting || rawScorer != null) InMemoryStore() else null
        val revisions = mutableListOf<SelfRevision>()
        val core = (personality ?: BedroomContent.default().personality)
        val memory = store?.let { st ->
            val run = "$stamp-$n"
            val studio = if (!painting) null else ArtStudio(
                Painter(ledger.meter(decisionModel, TokenPurpose.Art, priceOf(endpoint))), st, run, dir = logDir?.let { d -> File(d, "art-%02d".format(n)) },
                voice = writerModel?.let { CommissionVoice(ledger.meter(it, TokenPurpose.Art, priceOf(writerEndpoint!!))) },
            ) { d ->
                log?.write(d)
                if (!quiet) printDrawing(d)
            }
            val scorer = rawScorer?.let { MemoryScorer(ledger.meter(it, TokenPurpose.Recall, priceOf(scorerName)), ledger.meter(it, TokenPurpose.Significance, priceOf(scorerName)), onFailure = { e -> System.err.println("scoring failed: ${e.message}") }) }
            AgentMemory(
                st, run,
                studio = studio,
                summarizer = summaryModel?.let {
                    Summarizer(
                        ledger.meter(it, TokenPurpose.Summary, priceOf(summaryEndpoint!!)), st, run,
                        threshold = if (scorer != null) Summarizer.THRESHOLD else null,
                        onFailure = { e -> System.err.println("summary failed: ${e.message}") },
                    )
                },
                scorer = scorer,
                selfModeler = if (summaryModel == null || scenario != Scenario.Bedroom) null else writerModel?.let {
                    SelfModeler(ledger.meter(it, TokenPurpose.SelfModel, priceOf(writerEndpoint!!)), st, run, core.core.you, core.selfSeed)
                },
                onSelf = { r ->
                    revisions += r
                    if (!quiet) printSelf(r)
                },
                // While he thinks he may ask the oracle (the writer again), which searches the world's docs and answers.
                thinker = writerModel?.let { w ->
                    Thinker(
                        ledger.meter(w, TokenPurpose.Think, priceOf(writerEndpoint!!)),
                        oracle = Oracle(ledger.meter(w, TokenPurpose.Ask, priceOf(writerEndpoint))).takeIf { scenario == Scenario.Bedroom },
                    )
                },
                reader = writerModel?.takeIf { scenario == Scenario.Bedroom }?.let { Reader(ledger.meter(it, TokenPurpose.Reading, priceOf(writerEndpoint!!))) },
                onAnswer = { a ->
                    log?.write(a)
                    if (!quiet) printAnswer(a)
                },
            )
        }
        try {
            runOnce(n, scenario, model, timeout, minuteTicks, maxTicks, maxDecisions, quiet, log, logDir, ledger, memory, granted, personality, revisions)
        } finally {
            log?.close()
            store?.close()
            (summaryModel as? AutoCloseable)?.close()
            (writerModel as? AutoCloseable)?.close()
            (ownScorer as? AutoCloseable)?.close()
            (decisionModel as? AutoCloseable)?.close()
        }
    }

    summarize(results, logDir)
    if (results.any { !it.solved }) exitProcess(1)
}

/** --affinities and --curiosity over the content's default personality; null when neither is given. */
private fun personalityOf(args: Args): Personality? {
    val affinities = args.string("--affinities")
    val curiosity = args.string("--curiosity")
    if (affinities == null && curiosity == null) return null
    val base = BedroomContent.default().personality
    val changed = affinities.orEmpty().split(',').filter { it.isNotBlank() }.associate { pair ->
        val parts = pair.split('=')
        require(parts.size == 2) { "--affinities takes quality=weight pairs, like calm=2,loud=-1" }
        parts[0].trim() to (parts[1].trim().toDoubleOrNull() ?: throw IllegalArgumentException("--affinities: '${parts[1]}' isn't a number"))
    }
    val c = curiosity?.let { it.toDoubleOrNull() ?: throw IllegalArgumentException("--curiosity takes a number") } ?: base.curiosity
    return base.copy(affinities = base.affinities + changed, curiosity = c).also { BedroomContent.default().withPersonality(it) } // fail fast on an unknown quality
}

private fun modelSource(scenario: Scenario, endpoint: String, replay: File?, args: Args): Triple<String, Duration, () -> DecisionModel> {
    if (replay != null) {
        require(replay.isFile) { "no log at ${replay.path}" }
        val entries = DecisionLog.read(replay)
        return Triple("replay", 20.seconds) { ReplayDecisionModel(entries, id = "replay:${entries.firstOrNull()?.model}", upgrade = scenario.upgrade()) }
    }
    if (endpoint == "scripted") return Triple("scripted", 20.seconds) { scenario.scriptedPolicy() }
    var config = Endpoints.load(File(args.string("--config") ?: Endpoints.DEFAULT_PATH))
    args.string("--strategy")?.let { s ->
        val strategy = StructuredOutput.entries.firstOrNull { it.name.equals(s.replace("_", ""), ignoreCase = true) }
            ?: throw IllegalArgumentException("unknown strategy '$s'; use json_schema, tools or text")
        val ep = config.endpoints[endpoint] ?: throw IllegalArgumentException("no endpoint '$endpoint' in the config")
        config = config.copy(endpoints = config.endpoints + (endpoint to ep.copy(structuredOutput = strategy)))
    }
    config = config.withBeam(endpoint, args.string("--beam")?.toInt())
    val ep = config.endpoints[endpoint] ?: throw IllegalArgumentException("no endpoint '$endpoint' in the config; have ${config.endpoints.keys.sorted()}")
    Endpoints.create(config, endpoint).let { (it as? AutoCloseable)?.close() } // fail fast on a missing key
    val label = endpoint + (args.string("--strategy")?.let { "-$it" } ?: "") + (args.string("--beam")?.let { "-beam$it" } ?: "")
    return Triple(label, ep.decisionTimeout) { Endpoints.create(config, endpoint) }
}

private fun runOnce(
    n: Int,
    scenario: Scenario,
    model: DecisionModel,
    timeout: Duration,
    minuteTicks: Int,
    maxTicks: Long,
    maxDecisions: Int,
    quiet: Boolean,
    log: DecisionLog?,
    logDir: File?,
    ledger: TokenLedger,
    memory: AgentMemory? = null,
    granted: Set<String> = emptySet(),
    personality: Personality? = null,
    revisions: List<SelfRevision> = emptyList(),
): RunResult {
    val records = mutableListOf<AgentRuntime.DecisionRecord>()
    lateinit var stampOf: (Long) -> String
    val (world, runtime) = scenario.create(model, timeout, minuteTicks, longTerm = memory, granted = granted, personality = personality) { d ->
        records += d
        log?.write(d)
        if (!quiet) {
            val intent = Json.encodeToString(Intent.serializer(), d.intent)
            val note = if (d.error != null) "FALLBACK: ${d.error}" else d.rationale.orEmpty()
            val tries = if (d.attempts.size > 1) " [${d.attempts.size} tries]" else ""
            println("%s  decide  %-52s %5d ms%s  %s".format(stampOf(d.tick), intent, d.latency.inWholeMilliseconds, tries, note))
            d.thought?.let { println("%s  thought %s".format(stampOf(d.tick), it.text?.let { t -> "\"$t\"" } ?: "FAILED: ${it.error}")) }
        }
    }
    val bedroom = world as? BedroomWorld
    ledger.attach({ world.tick }, bedroom?.clock)
    stampOf = if (bedroom != null) { t -> bedroom.clock.reading(t).let { "d%2d %s".format(it.day, it.time) } } else { t -> "t=%4d".format(t) }
    if (!quiet) println("--- run $n ---")

    val hours = mutableListOf<HourSample>()
    val lowest = Need.entries.associateWith { Double.MAX_VALUE }.toMutableMap()
    var ticksAtZero = 0L
    var printed = 0L
    var failed = 0
    val started = TimeSource.Monotonic.markNow()
    val endTick = runBlocking {
        runLockstep(world, runtime, maxTicks, shouldStop = { records.size >= maxDecisions }) {
            for (e in world.snapshot().events) {
                if (e.tick > printed) {
                    if (" failed: " in e.text) failed++
                    if (!quiet) println("%s  event   %s".format(stampOf(e.tick), e.text))
                }
            }
            printed = world.tick
            if (bedroom != null) {
                Need.entries.forEach { lowest[it] = minOf(lowest.getValue(it), bedroom.needs[it]) }
                if (Need.entries.any { it != Need.Stimulation && it != Need.Frustration && bedroom.needs[it] <= 0.0 }) ticksAtZero++
                if (bedroom.clock.isMinuteStart(world.tick) && bedroom.clock.minuteOfDay(world.tick) % 60 == 0) hours += BedroomReport.sample(bedroom)
            }
        }
    }
    memory?.let { runBlocking { it.flush() } }
    val wall = started.elapsedNow()
    val capped = records.size >= maxDecisions
    val solved = if (bedroom != null) !capped else world.snapshot().completed
    val life = bedroom?.let { b ->
        val days = BedroomReport.days(b, hours)
        logDir?.let { dir ->
            writeJsonl(File(dir, "hours-%02d.jsonl".format(n)), hours, HourSample.serializer())
            writeJsonl(File(dir, "money-%02d.jsonl".format(n)), b.ledger.transactions, Transaction.serializer())
            writeJsonl(File(dir, "needs-%02d.jsonl".format(n)), b.needs.changes, NeedChange.serializer())
        }
        BedroomResult(
            days = days,
            lowest = lowest.mapKeys { it.key.id }.mapValues { Math.round(it.value * 10) / 10.0 },
            ticksAtZero = ticksAtZero,
            moneyEnd = b.ledger.balance,
            bought = days.flatMap { it.bought },
            choices = records.groupingBy { b.describe(it.intent).split(' ').take(2).joinToString(" ") }.eachCount().toList().sortedByDescending { it.second }.toMap(),
            self = memory?.takeIf { it.selfWriter != null }?.let { m ->
                logDir?.let { dir -> File(dir, "self-%02d.txt".format(n)).writeText(BedroomReport.selfModels(m.selfVersions(), m.selfText().orEmpty()) + "\n") }
                SelfResult(nights = revisions.size, versions = m.selfVersions().size, failed = revisions.count { it.error != null })
            },
        )
    }
    logDir?.let { dir -> writeJsonl(File(dir, "tokens-%02d.jsonl".format(n)), ledger.calls(), TokenCall.serializer()) }
    val spent = ledger.total()
    val gameDays = endTick.toDouble() / (minuteTicks * GameClock.MINUTES_PER_DAY)
    val tokens = TokenResult(spent, ledger.byPurpose(), ledger.byDay(), if (bedroom != null && gameDays > 0) spent.tokens / gameDays else null)
    val result = RunResult(
        run = n,
        scenario = scenario.id,
        solved = solved,
        ticks = endTick,
        decisions = records.size,
        fallbacks = records.count { it.fellBack },
        retries = records.sumOf { (it.attempts.size - 1).coerceAtLeast(0) },
        promptTokens = spent.promptTokens,
        completionTokens = spent.completionTokens,
        wallMs = wall.inWholeMilliseconds,
        log = log?.file?.path,
        failedIntents = failed,
        bedroom = life,
        tokens = tokens,
    )
    val outcome = when {
        life != null -> "LIVED to ${bedroom.clock.label(endTick)}" + if (capped) " (decision cap)" else ""
        solved -> "SOLVED in %4d ticks (%.1f s sim)".format(endTick, endTick.toDouble() / World.TICKS_PER_SECOND)
        else -> "NOT SOLVED at tick %d".format(endTick) + if (capped) " (decision cap)" else ""
    }
    println("run %2d: %-32s %2d decisions, %d fallbacks, %d retries, %d failed intents, %d+%d tokens, %.1f s wall".format(
        n, outcome, result.decisions, result.fallbacks, result.retries, failed, result.promptTokens, result.completionTokens, wall.inWholeMilliseconds / 1000.0,
    ))
    life?.let { printBedroom(it) }
    if (bedroom != null) println(TokenReport.format(ledger, minuteTicks, endTick).prependIndent("  "))
    memory?.takeIf { it.selfWriter != null && bedroom != null }?.let { m -> println(BedroomReport.selfModels(m.selfVersions(), bedroom!!.content.personality.selfSeed).prependIndent("  ")) }
    return result
}

private fun printBedroom(life: BedroomResult) {
    life.days.forEach { d ->
        val mood = if (d.moodMin == null) "-" else "%.0f-%.0f".format(d.moodMin, d.moodMax)
        println("  day %2d: money %5d  earned %4d  spent %4d  mood %-7s bought %s".format(d.day, d.moneyEnd, d.earned, d.spent, mood, d.bought.joinToString().ifEmpty { "-" }))
    }
    println("  lowest needs ${life.lowest}; ticks with a need at 0: ${life.ticksAtZero}")
    println("  choices ${life.choices}")
    life.self?.let { s -> println("  self-model: ${s.nights} nights, ${s.versions} versions, ${s.failed} failed") }
}

private fun printDrawing(d: Drawing) {
    val stamp = if (d.day != null) "d%2d %s".format(d.day, d.time) else "t=%4d".format(d.tick)
    val what = if (d.artwork == null) "FAILED: ${d.error} (after ${d.calls} picks)" else "${d.item} ${d.piece ?: "?"} \"${d.title}\" (${d.tags.joinToString()}; shows ${d.shows ?: "no subject"}; ${d.ops} ops in ${d.calls} picks, ${d.latency.inWholeSeconds}s)"
    println("%s  drew %s".format(stamp, what))
    d.artwork?.letters()?.forEach { println("        $it") }
}

private fun printAnswer(a: Answer) {
    val r = a.reply
    val stamp = if (r.day != null) "d%2d %s".format(r.day, r.time) else "t=%4d".format(r.tick)
    println("%s  asked \"%s\" (opened %s): %s".format(stamp, a.question, a.opened.joinToString(), r.text ?: "FAILED: ${r.error}"))
}

private fun printSelf(r: SelfRevision) {
    val what = when {
        r.error != null -> "FAILED: ${r.error}"
        r.changed -> "revised: ${r.note}"
        else -> "unchanged: ${r.note}"
    }
    println("night %2s  self    %s".format(r.day ?: "-", what))
}

private fun <T> writeJsonl(file: File, rows: List<T>, serializer: kotlinx.serialization.KSerializer<T>) {
    file.parentFile?.mkdirs()
    file.bufferedWriter().use { out -> rows.forEach { out.write(Json.encodeToString(serializer, it)); out.newLine() } }
}

private fun summarize(results: List<RunResult>, logDir: File?) {
    val solved = results.filter { it.solved }
    val ticks = solved.map { it.ticks }.sorted()
    val median = when {
        ticks.isEmpty() -> null
        ticks.size % 2 == 1 -> ticks[ticks.size / 2].toDouble()
        else -> (ticks[ticks.size / 2 - 1] + ticks[ticks.size / 2]) / 2.0
    }
    println()
    val word = if (results.firstOrNull()?.bedroom != null) "lived" else "solved"
    println("$word ${solved.size}/${results.size}; median ticks (solved runs) ${median?.let { "%.0f".format(it) } ?: "n/a"}; " +
        "decisions ${results.sumOf { it.decisions }}, fallbacks ${results.sumOf { it.fallbacks }}, retries ${results.sumOf { it.retries }}, " +
        "tokens ${results.sumOf { it.promptTokens }}+${results.sumOf { it.completionTokens }}")
    logDir?.let { dir ->
        dir.mkdirs()
        File(dir, "summary.json").writeText(Json { prettyPrint = true }.encodeToString(ListSerializer(RunResult.serializer()), results))
    }
}
