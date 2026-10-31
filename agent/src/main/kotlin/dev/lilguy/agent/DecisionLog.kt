package dev.lilguy.agent

import dev.lilguy.agent.art.Drawing
import dev.lilguy.decision.ActionSchema
import dev.lilguy.decision.Capabilities
import dev.lilguy.decision.Decision
import dev.lilguy.decision.DecisionFailed
import dev.lilguy.decision.DecisionModel
import dev.lilguy.decision.DecisionRequest
import dev.lilguy.decision.Usage
import dev.lilguy.sim.Intent
import dev.lilguy.sim.Observation
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.Closeable
import java.io.File
import kotlin.time.Duration

/** One line of a decision log. Field names are snake_case for reading it from Python. */
@Serializable
data class DecisionLogEntry(
    val run: String,
    val model: String,
    val tick: Long,
    val observation: Observation?,
    val request: LoggedRequest?,
    /** Every reply in order, with the validation error of each rejected one. */
    val attempts: List<LoggedAttempt>,
    /** The last reply, valid or not. */
    val raw: String?,
    val thought: String?,
    /** What was submitted: the model's intent, or the fallback. */
    val intent: Intent,
    @SerialName("latency_ms") val latencyMs: Long,
    @SerialName("prompt_tokens") val promptTokens: Int,
    @SerialName("completion_tokens") val completionTokens: Int,
    val error: String? = null,
    val fallback: Boolean = false,
    /** When the intent was think: the thinker's call. */
    val think: LoggedThink? = null,
)

@Serializable
data class LoggedThink(
    val thinker: String,
    val text: String?,
    val raw: String?,
    @SerialName("latency_ms") val latencyMs: Long,
    @SerialName("prompt_tokens") val promptTokens: Int,
    @SerialName("completion_tokens") val completionTokens: Int,
    val error: String? = null,
)

@Serializable
data class LoggedRequest(
    val system: String,
    val messages: List<LoggedMessage>,
    val temperature: Double,
    /** The per-request reply schema, ids and all. */
    val schema: JsonObject?,
    /** For closed-choice models: the state and options they picked from. */
    val choices: LoggedChoices? = null,
)

@Serializable
data class LoggedChoices(
    val instructions: String,
    val state: JsonObject,
    /** Label to description. */
    val options: Map<String, String>,
)

@Serializable
data class LoggedMessage(val role: String, val content: String)

/** A piece he drew, logged between the decisions, marked `"kind": "art"`; the picture itself is a PNG beside the log. */
@Serializable
data class ArtLogEntry(
    val kind: String = KIND,
    val run: String,
    val artist: String,
    val tick: Long,
    val day: Int?,
    val time: String?,
    val item: String,
    val piece: Int?,
    val commission: String?,
    val title: String?,
    val tags: List<String>,
    val ops: Int,
    /** Model calls the piece took: one per pick. */
    val calls: Int,
    /** The picture as letters, one row a line (a is palette color 0). */
    val letters: List<String>,
    val palette: List<String>,
    val raw: String?,
    @SerialName("latency_ms") val latencyMs: Long,
    @SerialName("prompt_tokens") val promptTokens: Int,
    @SerialName("completion_tokens") val completionTokens: Int,
    val error: String? = null,
) {
    companion object {
        const val KIND = "art"
    }
}

@Serializable
data class LoggedAttempt(val raw: String, val error: String? = null)

/**
 * Appends one JSON line per decision to [file]. Every line is flushed, so a killed run keeps
 * what it logged. The system prompt is the same on every line; it's repeated so each line stands
 * alone.
 */
class DecisionLog(val file: File, private val run: String, private val model: String) : Closeable {
    private val out = file.also { it.parentFile?.mkdirs() }.bufferedWriter()

    fun write(record: AgentRuntime.DecisionRecord) = append(json.encodeToString(DecisionLogEntry.serializer(), entryOf(run, model, record)))

    /** Drawings go in the same file, marked `"kind": "art"`. */
    fun write(d: Drawing) = append(
        json.encodeToString(
            ArtLogEntry.serializer(),
            ArtLogEntry(
                run = run, artist = d.artist, tick = d.tick, day = d.day, time = d.time, item = d.item, piece = d.piece,
                commission = d.commission, title = d.title, tags = d.tags, ops = d.ops, calls = d.calls,
                letters = d.artwork?.letters().orEmpty(), palette = d.artwork?.palette?.map { "#%06x".format(it) }.orEmpty(),
                raw = d.raw, latencyMs = d.latency.inWholeMilliseconds, promptTokens = d.usage.promptTokens,
                completionTokens = d.usage.completionTokens, error = d.error,
            ),
        ),
    )

    private fun append(line: String) {
        synchronized(out) {
            out.write(line)
            out.newLine()
            out.flush()
        }
    }

    override fun close() = synchronized(out) { out.close() }

    companion object {
        val json = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = true }

        fun entryOf(run: String, model: String, r: AgentRuntime.DecisionRecord) = DecisionLogEntry(
            run = run,
            model = model,
            tick = r.tick,
            observation = r.observation,
            request = r.request?.let { req ->
                LoggedRequest(
                    req.system,
                    req.messages.map { LoggedMessage(it.role.name.lowercase(), it.content) },
                    req.temperature,
                    r.schema,
                    req.choices?.let { c -> LoggedChoices(c.instructions, c.state, c.options.associate { it.label to it.description }) },
                )
            },
            attempts = r.attempts.map { LoggedAttempt(it.raw, it.error) },
            raw = r.raw,
            thought = r.rationale,
            intent = r.intent,
            latencyMs = r.latency.inWholeMilliseconds,
            promptTokens = r.usage.promptTokens,
            completionTokens = r.usage.completionTokens,
            error = r.error,
            fallback = r.fellBack,
            think = r.thought?.let { t ->
                LoggedThink(t.thinker, t.text, t.raw, t.latency.inWholeMilliseconds, t.usage.promptTokens, t.usage.completionTokens, t.error)
            },
        )

        /** The decisions in a log, leaving out art lines (and journal lines from older logs). */
        fun read(file: File): List<DecisionLogEntry> = lines(file).filter { kindOf(it) == null }.map { json.decodeFromString(DecisionLogEntry.serializer(), it) }

        private fun lines(file: File) = file.readLines().filter { it.isNotBlank() }

        /** Null for a decision line, otherwise "art" (or "journal", in older logs). */
        private fun kindOf(line: String) = (json.parseToJsonElement(line).jsonObject["kind"] as? JsonPrimitive)?.content
    }
}

/**
 * Plays a decision log back without calling the model: each decide() returns the next logged
 * reply through the same schema, and a logged fallback fails again so the runtime falls back the
 * same way. Lockstep runs are deterministic, so a replay retraces the run tick for tick; a tick
 * that doesn't match the log means the sim changed since it was recorded, and is an error.
 */
class ReplayDecisionModel(
    entries: List<DecisionLogEntry>,
    override val id: String = "replay",
    /** Maps an intent from an older log onto today's action space ([Scenario.upgrade]). */
    private val upgrade: (Intent) -> Intent = { it },
) : DecisionModel {
    private val remaining = ArrayDeque(entries)

    override val capabilities = Capabilities(jsonSchema = true)

    override suspend fun <A> decide(request: DecisionRequest, schema: ActionSchema<A>): Decision<A> {
        val entry = remaining.removeFirstOrNull() ?: throw IllegalStateException("the replay log has no more decisions")
        val tick = PromptBuilder.observationOf(request)?.tick
        check(tick == null || tick == entry.tick) { "replay out of sync: the sim is at tick $tick but the log's next decision is at tick ${entry.tick}" }
        if (entry.fallback) throw DecisionFailed("replayed fallback: ${entry.error}")
        val reply = json.encodeToString(AgentReply.serializer(), AgentReply(entry.thought.orEmpty(), upgrade(entry.intent)))
        val value = schema.decode(reply)
        return Decision(value, schema.rationaleOf(value), Usage(entry.promptTokens, entry.completionTokens), Duration.ZERO, entry.raw ?: reply)
    }

    private companion object {
        val json = Json { encodeDefaults = true; explicitNulls = true }
    }
}
