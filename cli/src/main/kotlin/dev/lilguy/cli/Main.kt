package dev.lilguy.cli

import dev.lilguy.agent.AgentRuntime
import dev.lilguy.agent.BedroomReport
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
import dev.lilguy.agent.memory.InMemoryStore
import dev.lilguy.agent.art.ArtStudio
import dev.lilguy.agent.art.CommissionVoice
import dev.lilguy.agent.art.Drawing
import dev.lilguy.agent.art.Painter
import dev.lilguy.agent.memory.JournalSession
import dev.lilguy.agent.memory.JournalWriter
import dev.lilguy.agent.memory.Thinker
import dev.lilguy.agent.memory.MemoryStore
import dev.lilguy.agent.memory.Summarizer
import dev.lilguy.memory.SqliteMemoryStore
import dev.lilguy.agent.Scenario
import dev.lilguy.agent.runLockstep
import dev.lilguy.decision.DecisionModel
import dev.lilguy.decision.Endpoints
import dev.lilguy.decision.StructuredOutput
import dev.lilguy.sim.BedroomWorld
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
  --log-dir DIR       where run logs go (default runs/); --no-log to skip
  --replay FILE       play back a decision log instead of calling a model
  --memory FILE       keep long-term memory in this SQLite file: every observation and action, plus
                      summaries when --memory-endpoint is set; recalled memories join each prompt
  --memory-endpoint N endpoint that writes the summaries (must write text, so not typesafe)
  --journal-endpoint N bedroom: endpoint that writes his journal when he journals (must write text);
                      entries join every later prompt, and the run ends with them and his wants.
                      Kept in --memory FILE if given, otherwise in memory for the run. It also
                      writes his thoughts when he thinks, and words each day's request for a piece.
                      Defaults to --memory-endpoint; "none" for off
                      His art is drawn by the deciding model itself, one closed pick a stroke, so
                      typesafe draws too (a PNG per piece in art-NN/ beside the log); the scripted
                      policy and a replay don't draw
  --grant IDS         bedroom: unlock gated content, comma-separated ids (e.g. paint,easel,canvas,painting)
  --quiet             one line per run instead of every decision and event

Key-and-door exits non-zero unless every run solved the room; the bedroom, unless every run
lived its days without hitting the decision cap. Bedroom runs also write hours-NN.jsonl (money
and needs each game hour), money-NN.jsonl (every transaction) and needs-NN.jsonl (every need
change) next to the decision log, tokens-NN.jsonl (every model call: sim tick, game day, purpose, tokens, cost when the endpoint has
price_in/price_out) and journal-NN.txt when there is a journal. Journal sessions
and drawings are logged in the decision log too, as lines with "kind": "journal" and "art"."""

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
        val FLAGS = setOf("--quiet", "--no-log", "--help")
        val KNOWN = FLAGS + setOf(
            "--scenario", "--endpoint", "--runs", "--days", "--minute-ticks", "--ticks", "--max-decisions", "--config", "--strategy", "--log-dir", "--replay", "--memory", "--memory-endpoint", "--journal-endpoint", "--grant",
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
    /** Ticks with energy, fullness or mood at 0 (stimulation at 0 is just bored). */
    @SerialName("ticks_at_zero") val ticksAtZero: Long,
    @SerialName("money_end") val moneyEnd: Int,
    val bought: List<String>,
    /** Decisions per intent: "do sketch" to 40. */
    val choices: Map<String, Int>,
    val journal: JournalResult? = null,
)

@Serializable
private data class JournalResult(
    /** Entries kept: written and the activity succeeded. */
    val entries: Int,
    /** Sessions the writer failed. */
    val failed: Int,
    val wants: Int,
    @SerialName("prompt_tokens") val promptTokens: Int,
    @SerialName("completion_tokens") val completionTokens: Int,
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
        // Like the app: the journal (and thinking) defaults to the memory endpoint; "none" turns it off.
        val journalEndpoint = (args.string("--journal-endpoint") ?: args.string("--memory-endpoint"))?.takeIf { it != "none" }
        val journalModel = journalEndpoint?.let { Endpoints.create(config, it) }
        // The deciding model draws his art, pick by pick, so a picking model can; the scripted policy and a replay can't.
        val painting = scenario == Scenario.Bedroom && replay == null && endpoint != "scripted"
        val store: MemoryStore? = args.string("--memory")?.let { SqliteMemoryStore(File(it)) } ?: if (journalModel != null || painting) InMemoryStore() else null
        val sessions = mutableListOf<JournalSession>()
        val memory = store?.let { st ->
            val run = "$stamp-$n"
            val studio = if (!painting) null else ArtStudio(
                Painter(ledger.meter(decisionModel, TokenPurpose.Art, priceOf(endpoint))), st, run, dir = logDir?.let { d -> File(d, "art-%02d".format(n)) },
                voice = journalModel?.let { CommissionVoice(ledger.meter(it, TokenPurpose.Art, priceOf(journalEndpoint!!))) },
            ) { d ->
                log?.write(d)
                if (!quiet) printDrawing(d)
            }
            AgentMemory(
                st, run,
                studio = studio,
                summarizer = summaryModel?.let { Summarizer(ledger.meter(it, TokenPurpose.Summary, priceOf(summaryEndpoint!!)), st, run, onFailure = { e -> System.err.println("summary failed: ${e.message}") }) },
                journal = journalModel?.let { JournalWriter(ledger.meter(it, TokenPurpose.Journal, priceOf(journalEndpoint!!)), st, run) },
                thinker = journalModel?.let { Thinker(ledger.meter(it, TokenPurpose.Think, priceOf(journalEndpoint!!))) },
                onJournal = { s ->
                    sessions += s
                    log?.write(s)
                    if (!quiet) printJournal(s)
                },
            )
        }
        try {
            runOnce(n, scenario, model, timeout, minuteTicks, maxTicks, maxDecisions, quiet, log, logDir, ledger, memory, granted, sessions)
        } finally {
            log?.close()
            store?.close()
            (summaryModel as? AutoCloseable)?.close()
            (journalModel as? AutoCloseable)?.close()
            (decisionModel as? AutoCloseable)?.close()
        }
    }

    summarize(results, logDir)
    if (results.any { !it.solved }) exitProcess(1)
}

private fun modelSource(scenario: Scenario, endpoint: String, replay: File?, args: Args): Triple<String, Duration, () -> DecisionModel> {
    if (replay != null) {
        require(replay.isFile) { "no log at ${replay.path}" }
        val entries = DecisionLog.read(replay)
        return Triple("replay", 20.seconds) { ReplayDecisionModel(entries, id = "replay:${entries.firstOrNull()?.model}") }
    }
    if (endpoint == "scripted") return Triple("scripted", 20.seconds) { scenario.scriptedPolicy() }
    var config = Endpoints.load(File(args.string("--config") ?: Endpoints.DEFAULT_PATH))
    args.string("--strategy")?.let { s ->
        val strategy = StructuredOutput.entries.firstOrNull { it.name.equals(s.replace("_", ""), ignoreCase = true) }
            ?: throw IllegalArgumentException("unknown strategy '$s'; use json_schema, tools or text")
        val ep = config.endpoints[endpoint] ?: throw IllegalArgumentException("no endpoint '$endpoint' in the config")
        config = config.copy(endpoints = config.endpoints + (endpoint to ep.copy(structuredOutput = strategy)))
    }
    val ep = config.endpoints[endpoint] ?: throw IllegalArgumentException("no endpoint '$endpoint' in the config; have ${config.endpoints.keys.sorted()}")
    Endpoints.create(config, endpoint).let { (it as? AutoCloseable)?.close() } // fail fast on a missing key
    val label = endpoint + (args.string("--strategy")?.let { "-$it" } ?: "")
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
    sessions: List<JournalSession> = emptyList(),
): RunResult {
    val records = mutableListOf<AgentRuntime.DecisionRecord>()
    lateinit var stampOf: (Long) -> String
    val (world, runtime) = scenario.create(model, timeout, minuteTicks, longTerm = memory, granted = granted) { d ->
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
                if (Need.entries.any { it != Need.Stimulation && bedroom.needs[it] <= 0.0 }) ticksAtZero++
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
            journal = memory?.takeIf { it.journaling }?.let { m ->
                val entries = m.journalEntries()
                logDir?.let { dir -> File(dir, "journal-%02d.txt".format(n)).writeText(BedroomReport.journal(entries) + "\n") }
                JournalResult(
                    entries = entries.size,
                    failed = sessions.count { it.status == JournalSession.Status.Failed },
                    wants = BedroomReport.wants(entries).size,
                    promptTokens = sessions.sumOf { it.usage.promptTokens },
                    completionTokens = sessions.sumOf { it.usage.completionTokens },
                )
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
    memory?.takeIf { it.journaling }?.let { println(BedroomReport.journal(it.journalEntries()).prependIndent("  ")) }
    return result
}

private fun printBedroom(life: BedroomResult) {
    life.days.forEach { d ->
        val mood = if (d.moodMin == null) "-" else "%.0f-%.0f".format(d.moodMin, d.moodMax)
        println("  day %2d: money %5d  earned %4d  spent %4d  mood %-7s bought %s".format(d.day, d.moneyEnd, d.earned, d.spent, mood, d.bought.joinToString().ifEmpty { "-" }))
    }
    println("  lowest needs ${life.lowest}; ticks with a need at 0: ${life.ticksAtZero}")
    println("  choices ${life.choices}")
    life.journal?.let { j ->
        println("  journal: ${j.entries} entries, ${j.failed} failed, ${j.wants} wants, ${j.promptTokens}+${j.completionTokens} tokens")
    }
}

private fun printDrawing(d: Drawing) {
    val stamp = if (d.day != null) "d%2d %s".format(d.day, d.time) else "t=%4d".format(d.tick)
    val what = if (d.artwork == null) "FAILED: ${d.error} (after ${d.calls} picks)" else "${d.item} ${d.piece ?: "?"} \"${d.title}\" (${d.tags.joinToString()}; ${d.ops} ops in ${d.calls} picks, ${d.latency.inWholeSeconds}s)"
    println("%s  drew %s".format(stamp, what))
    d.artwork?.letters()?.forEach { println("        $it") }
}

private fun printJournal(s: JournalSession) {
    val stamp = if (s.day != null) "d%2d %s".format(s.day, s.time) else "t=%4d".format(s.tick)
    val what = when (s.status) {
        JournalSession.Status.Failed -> "FAILED: ${s.error}"
        else -> "${s.status.name.lowercase()}: ${s.searches} searches, ${s.wants.size} wants, ${s.latency.inWholeMilliseconds} ms"
    }
    println("%s  journal %s".format(stamp, what))
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
