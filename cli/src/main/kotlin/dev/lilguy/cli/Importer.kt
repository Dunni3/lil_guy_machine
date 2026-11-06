package dev.lilguy.cli

import dev.lilguy.agent.LifeRecorder
import dev.lilguy.runlog.ArtPieceRow
import dev.lilguy.runlog.AuthoredRow
import dev.lilguy.runlog.Blobs
import dev.lilguy.runlog.DecisionRow
import dev.lilguy.runlog.HourlyRow
import dev.lilguy.runlog.Ids
import dev.lilguy.runlog.LedgerRow
import dev.lilguy.runlog.ModelCallRow
import dev.lilguy.runlog.NeedRow
import dev.lilguy.runlog.RecordRow
import dev.lilguy.runlog.Row
import dev.lilguy.runlog.RowJson
import dev.lilguy.runlog.RunRow
import dev.lilguy.runlog.RunStore
import dev.lilguy.runlog.SessionRow
import dev.lilguy.sim.GameClock
import dev.lilguy.sim.Intent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Reads old run logs into the run database (`make import-runs`): every `run-NN.jsonl` (the CLI's)
 * or `run-NNN.jsonl` (the app's, the TV's under `runs/tv/`) with the files beside it, as one run
 * each, marked `settings.imported`. Ids are made from the file's path, so importing twice changes
 * nothing. What an old log can't say (the host, the seed, the content it ran on) is left null.
 */
internal class Importer(private val store: RunStore) {
    private val runFile = Regex("""run-(\d+)\.jsonl""")
    private val dirStamp = Regex("""(\d{8}-\d{6})""")

    fun importAll(root: File) {
        require(root.isDirectory) { "no directory ${root.path}" }
        val dirs = (root.listFiles().orEmpty().toList() + File(root, "tv").listFiles().orEmpty().toList()).filter { it.isDirectory }.sortedBy { it.path }
        var runs = 0
        var rows = 0
        for (dir in dirs) {
            for (f in dir.listFiles().orEmpty().sortedBy { it.name }) {
                val n = runFile.matchEntire(f.name)?.groupValues?.get(1) ?: continue
                val got = runCatching { importRun(root, dir, f, n) }
                got.onSuccess { rows += it; runs++ }.onFailure { System.err.println("import: skipped ${f.path}: ${it.message}") }
            }
        }
        println("imported $runs runs, $rows rows, from ${root.path}")
    }

    private class Pending(val tick: Long, val make: (seq: Long) -> Row)

    private fun importRun(root: File, dir: File, file: File, n: String): Int {
        val rel = dir.relativeTo(root).path
        val runId = Ids.named("import:$rel:$n")
        val sessionId = Ids.named("import:$rel:$n:session")
        val tv = rel.startsWith("tv")
        val app = dir.name.startsWith("app")
        val scenario = if ("bedroom" in dir.name) "bedroom" else "key-door"
        val started = dirStamp.find(dir.name)?.groupValues?.get(1)?.let {
            LocalDateTime.parse(it, DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")).atZone(ZoneId.systemDefault()).toInstant().toString()
        } ?: Ids.now()
        val ended = java.time.Instant.ofEpochMilli(file.lastModified()).toString()
        val lines = file.readLines().filter { it.isNotBlank() }.mapNotNull { runCatching { RowJson.parseToJsonElement(it).jsonObject }.getOrNull() }
        val first = lines.firstOrNull { it["kind"] == null }
        val pending = mutableListOf<Pending>()
        val pad = if (n.length == 3) "%03d" else "%02d"

        lines.forEachIndexed { i, o ->
            val tick = o.long("tick") ?: 0L
            val (day, minute) = when (val clock = (o["observation"] as? JsonObject)?.get("clock") as? JsonObject) {
                null -> o.int("day") to o.str("time")?.let(GameClock::parseHhmm)
                else -> clock.int("day") to clock.str("time")?.let(GameClock::parseHhmm)
            }
            when (val kind = o.str("kind")) {
                null -> {
                    val callId = Ids.named("$runId:call:$i")
                    val request = o["request"] as? JsonObject
                    pending += Pending(tick) { seq ->
                        ModelCallRow(
                            callId, runId, sessionId, seq, tick, day, minute, purpose = "decision", endpoint = o.str("model") ?: "?", model = o.str("model") ?: "?",
                            calls = (o["attempts"] as? JsonArray)?.size?.coerceAtLeast(1) ?: 1, promptTokens = o.int("prompt_tokens"), completionTokens = o.int("completion_tokens"),
                            latencyMs = o.long("latency_ms")?.toInt(), failed = o.bool("fallback") == true,
                            systemBlob = request?.str("system")?.let { blob(it.toByteArray(), "text/plain") },
                            requestBlob = request?.let { r -> blob(JsonObject(r - "system").toString().toByteArray(), "application/json") },
                            responseBlob = blob(buildJsonObject { put("raw", o.str("raw")); o["attempts"]?.let { put("attempts", it) } }.toString().toByteArray(), "application/json"),
                        )
                    }
                    val intent = o["intent"] ?: JsonNull
                    pending += Pending(tick) { seq ->
                        DecisionRow(
                            Ids.named("$runId:decision:$i"), runId, sessionId, seq, tick, day, minute, modelCallId = callId, intent = intent,
                            intentLabel = runCatching { LifeRecorder.label(RowJson.decodeFromJsonElement(Intent.serializer(), intent)) }.getOrElse { (intent as? JsonObject)?.str("type") ?: "?" },
                            thought = o.str("thought"), fallback = o.bool("fallback") == true, error = o.str("error"),
                            observationBlob = (o["observation"] as? JsonObject)?.let { blob(it.toString().toByteArray(), "application/json") },
                        )
                    }
                    (o["think"] as? JsonObject)?.let { t ->
                        pending += Pending(tick) { seq ->
                            AuthoredRow(Ids.named("$runId:think:$i"), runId, sessionId, seq, tick, day, minute, "thought", null, t.str("text") != null, t.str("thinker"), buildJsonObject { put("text", t.str("text")) }, t.str("error"))
                        }
                    }
                }
                "art" -> {
                    val piece = o.int("piece")
                    pending += Pending(tick) { seq -> AuthoredRow(Ids.named("$runId:art:$i"), runId, sessionId, seq, tick, day, minute, "draw", null, piece != null, o.str("artist"), o, o.str("error")) }
                    if (piece != null && o.str("error") == null) {
                        val png = File(dir, "art-$pad".format(n.toInt()) + "/piece-%03d.png".format(piece)).takeIf { it.isFile }?.let { blob(it.readBytes(), "image/png") }
                        pending += Pending(tick) { seq ->
                            ArtPieceRow(
                                runId, sessionId, seq, tick, day, minute, piece, o.str("item") ?: "?", o.str("title"), o.strings("tags"), o.str("shows"),
                                o.strings("palette"), o.strings("letters"), o.int("ops") ?: 0, png, o.str("commission"), o.str("artist"),
                            )
                        }
                    }
                }
                "trip" -> pending += Pending(tick) { seq -> AuthoredRow(Ids.named("$runId:trip:$i"), runId, sessionId, seq, tick, day, minute, "shopping", null, o.str("error") == null, o.str("shopper"), o, o.str("error")) }
                "answer" -> pending += Pending(tick) { seq -> AuthoredRow(Ids.named("$runId:answer:$i"), runId, sessionId, seq, tick, day, minute, "answer", null, o.str("answer") != null, o.str("answerer"), o, o.str("error")) }
                else -> pending += Pending(tick) { seq -> RecordRow(runId, sessionId, seq, tick, day, minute, kind, o) }
            }
        }
        jsonl(File(dir, "tokens-$n.jsonl")).filter { it.str("purpose") != "decision" }.forEachIndexed { i, o ->
            val tick = o.long("tick") ?: 0L
            pending += Pending(tick) { seq ->
                ModelCallRow(
                    Ids.named("$runId:tokens:$i"), runId, sessionId, seq, tick, o.int("day"), o.str("time")?.let(GameClock::parseHhmm), purpose = o.str("purpose") ?: "?",
                    endpoint = o.str("model") ?: "?", model = o.str("model") ?: "?", calls = o.int("calls") ?: 1, promptTokens = o.int("prompt_tokens"),
                    completionTokens = o.int("completion_tokens"), latencyMs = o.long("latency_ms")?.toInt(), costUsd = o.double("cost_usd"), failed = o.bool("failed") == true,
                )
            }
        }
        jsonl(File(dir, "money-$n.jsonl")).forEach { o ->
            pending += Pending(o.long("tick") ?: 0) { seq -> LedgerRow(runId, sessionId, seq, o.long("tick") ?: 0, o.int("day"), o.str("time")?.let(GameClock::parseHhmm), o.int("amount") ?: 0, o.int("balance") ?: 0, o.str("reason") ?: "") }
        }
        jsonl(File(dir, "needs-$n.jsonl")).forEach { o ->
            pending += Pending(o.long("tick") ?: 0) { seq ->
                NeedRow(runId, sessionId, seq, o.long("tick") ?: 0, o.int("day"), o.str("time")?.let(GameClock::parseHhmm), o.str("need") ?: "?", o.double("delta") ?: 0.0, o.double("value") ?: 0.0, o.str("reason") ?: "")
            }
        }
        jsonl(File(dir, "hours-$n.jsonl")).forEach { o ->
            val minute = o.str("time")?.let(GameClock::parseHhmm)
            pending += Pending(o.long("tick") ?: 0) { seq ->
                HourlyRow(
                    runId, sessionId, seq, o.long("tick") ?: 0, o.int("day"), minute, hour = (minute ?: 0) / 60, money = o.int("money") ?: 0,
                    needs = JsonObject(listOf("energy", "fullness", "mood", "stimulation").mapNotNull { k -> o[k]?.let { k to it } }.toMap()), doing = o.str("doing"),
                )
            }
        }
        File(dir, "self-$n.txt").takeIf { it.isFile }?.let { f ->
            pending += Pending(Long.MAX_VALUE) { seq -> RecordRow(runId, sessionId, seq, lines.lastOrNull()?.long("tick") ?: 0, null, null, "self_models", JsonPrimitive(f.readText())) }
        }

        val rows = mutableListOf<Row>()
        rows += RunRow(
            id = runId, label = dir.name, scenario = scenario, platform = if (tv) "tv" else if (app) "desktop" else "cli", host = null, gitSha = null, gitDirty = null,
            contentHash = null, seed = 0, ticksPerMinute = GameClock.DEFAULT_TICKS_PER_MINUTE,
            models = buildJsonObject { put("decision", buildJsonObject { put("id", first?.str("model")) }) },
            settings = buildJsonObject { put("imported", true); put("source", "$rel/${file.name}") },
            startedAt = started, endedAt = ended, endReason = "imported",
        )
        rows += SessionRow(sessionId, runId, null, null, null, null, if (tv) "tv" else if (app) "desktop" else "cli", null, started, ended)
        pending.sortedBy { it.tick }.forEachIndexed { i, p -> rows += p.make(i + 1L) }
        rows.chunked(500).forEach(store::write)
        return rows.size
    }

    private fun blob(bytes: ByteArray, mediaType: String): String {
        val hash = Blobs.sha256(bytes)
        store.putBlob(hash, mediaType, Blobs.gzip(bytes), bytes.size)
        return hash
    }

    private fun jsonl(f: File): List<JsonObject> =
        if (!f.isFile) emptyList() else f.readLines().filter { it.isNotBlank() }.mapNotNull { runCatching { RowJson.parseToJsonElement(it).jsonObject }.getOrNull() }

    private fun JsonObject.p(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }
    private fun JsonObject.str(k: String) = p(k)?.contentOrNull
    private fun JsonObject.int(k: String) = p(k)?.intOrNull
    private fun JsonObject.long(k: String) = p(k)?.longOrNull
    private fun JsonObject.double(k: String) = p(k)?.doubleOrNull
    private fun JsonObject.bool(k: String) = p(k)?.booleanOrNull
    private fun JsonObject.strings(k: String): List<String> = (this[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
}
