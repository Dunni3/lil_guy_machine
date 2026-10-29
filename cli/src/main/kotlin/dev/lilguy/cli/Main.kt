package dev.lilguy.cli

import dev.lilguy.agent.AgentRuntime
import dev.lilguy.agent.BedroomReport
import dev.lilguy.agent.DaySummary
import dev.lilguy.agent.DecisionLog
import dev.lilguy.agent.HourSample
import dev.lilguy.agent.ReplayDecisionModel
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
  --quiet             one line per run instead of every decision and event

Key-and-door exits non-zero unless every run solved the room; the bedroom, unless every run
lived its days without hitting the decision cap. Bedroom runs also write hours-NN.jsonl (money
and needs each game hour), money-NN.jsonl (every transaction) and needs-NN.jsonl (every need
change) next to the decision log."""

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
            "--scenario", "--endpoint", "--runs", "--days", "--minute-ticks", "--ticks", "--max-decisions", "--config", "--strategy", "--log-dir", "--replay",
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
)

@Serializable
private data class BedroomResult(
    val days: List<DaySummary>,
    /** Lowest level of each need over the run, checked every tick. */
    val lowest: Map<String, Double>,
    /** Ticks with a need at 0. */
    @SerialName("ticks_at_zero") val ticksAtZero: Long,
    @SerialName("money_end") val moneyEnd: Int,
    val bought: List<String>,
    /** Decisions per intent: "do sketch" to 40. */
    val choices: Map<String, Int>,
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
        val model = newModel()
        val log = logDir?.let { DecisionLog(File(it, "run-%02d.jsonl".format(n)), run = "$stamp-$n", model = model.id) }
        try {
            runOnce(n, scenario, model, timeout, minuteTicks, maxTicks, maxDecisions, quiet, log, logDir)
        } finally {
            log?.close()
            (model as? AutoCloseable)?.close()
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
): RunResult {
    val records = mutableListOf<AgentRuntime.DecisionRecord>()
    lateinit var stampOf: (Long) -> String
    val (world, runtime) = scenario.create(model, timeout, minuteTicks) { d ->
        records += d
        log?.write(d)
        if (!quiet) {
            val intent = Json.encodeToString(Intent.serializer(), d.intent)
            val note = if (d.error != null) "FALLBACK: ${d.error}" else d.rationale.orEmpty()
            val tries = if (d.attempts.size > 1) " [${d.attempts.size} tries]" else ""
            println("%s  decide  %-52s %5d ms%s  %s".format(stampOf(d.tick), intent, d.latency.inWholeMilliseconds, tries, note))
        }
    }
    val bedroom = world as? BedroomWorld
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
                if (Need.entries.any { bedroom.needs[it] <= 0.0 }) ticksAtZero++
                if (bedroom.clock.isMinuteStart(world.tick) && bedroom.clock.minuteOfDay(world.tick) % 60 == 0) hours += BedroomReport.sample(bedroom)
            }
        }
    }
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
        )
    }
    val result = RunResult(
        run = n,
        scenario = scenario.id,
        solved = solved,
        ticks = endTick,
        decisions = records.size,
        fallbacks = records.count { it.fellBack },
        retries = records.sumOf { (it.attempts.size - 1).coerceAtLeast(0) },
        promptTokens = records.sumOf { it.usage.promptTokens },
        completionTokens = records.sumOf { it.usage.completionTokens },
        wallMs = wall.inWholeMilliseconds,
        log = log?.file?.path,
        failedIntents = failed,
        bedroom = life,
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
    return result
}

private fun printBedroom(life: BedroomResult) {
    life.days.forEach { d ->
        val mood = if (d.moodMin == null) "-" else "%.0f-%.0f".format(d.moodMin, d.moodMax)
        println("  day %2d: money %5d  earned %4d  spent %4d  mood %-7s bought %s".format(d.day, d.moneyEnd, d.earned, d.spent, mood, d.bought.joinToString().ifEmpty { "-" }))
    }
    println("  lowest needs ${life.lowest}; ticks with a need at 0: ${life.ticksAtZero}")
    println("  choices ${life.choices}")
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
