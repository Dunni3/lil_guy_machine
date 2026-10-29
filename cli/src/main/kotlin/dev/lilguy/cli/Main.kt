package dev.lilguy.cli

import dev.lilguy.agent.AgentRuntime
import dev.lilguy.agent.DecisionLog
import dev.lilguy.agent.KeyDoorPolicy
import dev.lilguy.agent.ReplayDecisionModel
import dev.lilguy.agent.keyDoorScenario
import dev.lilguy.agent.runLockstep
import dev.lilguy.decision.DecisionModel
import dev.lilguy.decision.Endpoints
import dev.lilguy.decision.StructuredOutput
import dev.lilguy.sim.Intent
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

private const val USAGE = """lil guy machine cli: lockstep runs of the key-and-door room.

  --endpoint NAME     endpoint from the config, or "scripted" (default)
  --runs N            number of runs (default 1)
  --ticks N           sim ticks per run before giving up (default 2000)
  --max-decisions N   decisions per run before giving up (default 60)
  --config PATH       endpoint config (default config/endpoints.yaml)
  --strategy S        override the endpoint's structured_output: json_schema, tools or text
  --log-dir DIR       where run logs go (default runs/); --no-log to skip
  --replay FILE       play back a decision log instead of calling a model
  --quiet             one line per run instead of every decision and event

Exits non-zero unless every run solved the room."""

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
        val KNOWN = FLAGS + setOf("--endpoint", "--runs", "--ticks", "--max-decisions", "--config", "--strategy", "--log-dir", "--replay")
    }
}

@Serializable
private data class RunResult(
    val run: Int,
    val solved: Boolean,
    val ticks: Long,
    val decisions: Int,
    val fallbacks: Int,
    val retries: Int,
    @SerialName("prompt_tokens") val promptTokens: Int,
    @SerialName("completion_tokens") val completionTokens: Int,
    @SerialName("wall_ms") val wallMs: Long,
    val log: String?,
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
    val endpoint = args.string("--endpoint") ?: "scripted"
    val replay = args.string("--replay")?.let(::File)
    val runs = if (replay != null) 1 else args.int("--runs", 1)
    val maxTicks = args.int("--ticks", 2000).toLong()
    val maxDecisions = args.int("--max-decisions", 60)
    val quiet = args.flag("--quiet")

    // Each run gets a fresh model, so nothing carries over between runs.
    val (label, timeout, newModel) = try {
        modelSource(endpoint, replay, args)
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        exitProcess(2)
    }

    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val logDir = if (args.flag("--no-log") || replay != null) null else File(args.string("--log-dir") ?: "runs", "${stamp}_$label")
    println("lil guy machine cli: key-and-door, model '$label', $runs run(s), max $maxTicks ticks / $maxDecisions decisions per run")
    logDir?.let { println("logs: ${it.path}/") }

    val results = (1..runs).map { n ->
        val model = newModel()
        val log = logDir?.let { DecisionLog(File(it, "run-%02d.jsonl".format(n)), run = "$stamp-$n", model = model.id) }
        try {
            runOnce(n, model, timeout, maxTicks, maxDecisions, quiet, log)
        } finally {
            log?.close()
            (model as? AutoCloseable)?.close()
        }
    }

    summarize(results, logDir)
    if (results.any { !it.solved }) exitProcess(1)
}

private fun modelSource(endpoint: String, replay: File?, args: Args): Triple<String, Duration, () -> DecisionModel> {
    if (replay != null) {
        require(replay.isFile) { "no log at ${replay.path}" }
        val entries = DecisionLog.read(replay)
        return Triple("replay", 20.seconds) { ReplayDecisionModel(entries, id = "replay:${entries.firstOrNull()?.model}") }
    }
    if (endpoint == "scripted") return Triple("scripted", 20.seconds) { KeyDoorPolicy() }
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
    model: DecisionModel,
    timeout: Duration,
    maxTicks: Long,
    maxDecisions: Int,
    quiet: Boolean,
    log: DecisionLog?,
): RunResult {
    val records = mutableListOf<AgentRuntime.DecisionRecord>()
    val (world, runtime) = keyDoorScenario(model, timeout) { d ->
        records += d
        log?.write(d)
        if (!quiet) {
            val intent = Json.encodeToString(Intent.serializer(), d.intent)
            val note = if (d.error != null) "FALLBACK: ${d.error}" else d.rationale.orEmpty()
            val tries = if (d.attempts.size > 1) " [${d.attempts.size} tries]" else ""
            println("t=%4d  decide  %-52s %5d ms%s  %s".format(d.tick, intent, d.latency.inWholeMilliseconds, tries, note))
        }
    }
    if (!quiet) println("--- run $n ---")

    var printed = 0L
    val started = TimeSource.Monotonic.markNow()
    val endTick = runBlocking {
        runLockstep(world, runtime, maxTicks, shouldStop = { records.size >= maxDecisions }) {
            if (!quiet) {
                for (e in world.snapshot().events) {
                    if (e.tick > printed) println("t=%4d  event   %s".format(e.tick, e.text))
                }
            }
            printed = world.tick
        }
    }
    val wall = started.elapsedNow()
    val solved = world.snapshot().completed
    val result = RunResult(
        run = n,
        solved = solved,
        ticks = endTick,
        decisions = records.size,
        fallbacks = records.count { it.fellBack },
        retries = records.sumOf { (it.attempts.size - 1).coerceAtLeast(0) },
        promptTokens = records.sumOf { it.usage.promptTokens },
        completionTokens = records.sumOf { it.usage.completionTokens },
        wallMs = wall.inWholeMilliseconds,
        log = log?.file?.path,
    )
    val outcome = if (solved) "SOLVED in %4d ticks (%.1f s sim)".format(endTick, endTick.toDouble() / World.TICKS_PER_SECOND)
    else "NOT SOLVED at tick %d".format(endTick) + if (records.size >= maxDecisions) " (decision cap)" else ""
    println("run %2d: %-32s %2d decisions, %d fallbacks, %d retries, %d+%d tokens, %.1f s wall".format(
        n, outcome, result.decisions, result.fallbacks, result.retries, result.promptTokens, result.completionTokens, wall.inWholeMilliseconds / 1000.0,
    ))
    return result
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
    println("solved ${solved.size}/${results.size}; median ticks (solved runs) ${median?.let { "%.0f".format(it) } ?: "n/a"}; " +
        "decisions ${results.sumOf { it.decisions }}, fallbacks ${results.sumOf { it.fallbacks }}, retries ${results.sumOf { it.retries }}, " +
        "tokens ${results.sumOf { it.promptTokens }}+${results.sumOf { it.completionTokens }}")
    logDir?.let { dir ->
        dir.mkdirs()
        File(dir, "summary.json").writeText(Json { prettyPrint = true }.encodeToString(ListSerializer(RunResult.serializer()), results))
    }
}
