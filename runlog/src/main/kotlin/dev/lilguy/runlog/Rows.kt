package dev.lilguy.runlog

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * Everything a run records, one class per table of the run database
 * (`research_notes/2026-11-01_2236_run-database-design.md`). A row's `table` names its class
 * ([Rows.json]); fields are snake_case in JSON, for reading from Python and SQL.
 */
@Serializable
sealed interface Row

/** A row that belongs to a run. [seq] comes from one counter per run, so a run's rows sort across tables. */
sealed interface RunScoped : Row {
    val runId: String
    val seq: Long
}

/** A run's row with the sim time it happened at: [minute] is the minute of the game day, both null in rooms with no clock. */
sealed interface Timed : RunScoped {
    val tick: Long
    val day: Int?
    val minute: Int?
}

/** The content files a world was built from, stored once per [hash]. */
@Serializable
@SerialName("content_versions")
data class ContentVersionRow(val hash: String, val files: Map<String, String>) : Row

/** A big body stored once, by the SHA-256 of its bytes: [data] is the text, or base64 for [BlobRow.BASE64]. */
@Serializable
@SerialName("blobs")
data class BlobRow(val hash: String, val mediaType: String, val encoding: String, val data: String, val size: Int) : Row {
    companion object {
        const val UTF8 = "utf8"
        const val BASE64 = "base64"
    }
}

/** What a role's model is, and what it costs per million tokens when known. */
@Serializable
data class ModelRole(val model: String, val priceIn: Double? = null, val priceOut: Double? = null)

/** A life starting: what built it and how it was set up. */
@Serializable
@SerialName("runs")
data class RunRow(
    override val runId: String,
    override val seq: Long,
    /** The run this one was forked from, and the checkpoint it started at. */
    val parentId: String? = null,
    val forkedFrom: String? = null,
    val batchId: String? = null,
    val label: String? = null,
    val scenario: String,
    /** "tv", "android", "desktop", "cli". */
    val platform: String,
    val host: String? = null,
    val gitSha: String? = null,
    val gitDirty: Boolean? = null,
    val contentHash: String? = null,
    val seed: Long,
    val ticksPerMinute: Int,
    val personality: JsonElement? = null,
    val granted: List<String> = emptyList(),
    /** By role: decision, writer, memory, recall, painter. */
    val models: Map<String, ModelRole> = emptyMap(),
    val settings: JsonObject = JsonObject(emptyMap()),
    /** ISO-8601, UTC. */
    val startedAt: String,
) : RunScoped

/** A life ending, or the last session of it closing. */
@Serializable
@SerialName("run_ends")
data class RunEndRow(
    override val runId: String,
    override val seq: Long,
    override val tick: Long,
    override val day: Int?,
    override val minute: Int?,
    val endedAt: String,
    /** "lived 3 days", "decision cap", "restarted", "closed". */
    val reason: String,
) : Timed

/** One process lifetime inside a run: the first, or a resume. */
@Serializable
@SerialName("sessions")
data class SessionRow(
    override val runId: String,
    override val seq: Long,
    val sessionId: String,
    val fromCheckpoint: String? = null,
    val gitSha: String? = null,
    val gitDirty: Boolean? = null,
    val contentHash: String? = null,
    val platform: String,
    val host: String? = null,
    val startedAt: String,
) : RunScoped

@Serializable
@SerialName("session_ends")
data class SessionEndRow(
    override val runId: String,
    override val seq: Long,
    val sessionId: String,
    val tick: Long,
    val endedAt: String,
    val reason: String,
) : RunScoped

/** One model call, failed or not: what the token ledger counts. */
@Serializable
@SerialName("model_calls")
data class ModelCallRow(
    override val runId: String,
    override val seq: Long,
    override val tick: Long,
    override val day: Int?,
    override val minute: Int?,
    val sessionId: String,
    val purpose: String,
    val model: String,
    /** HTTP attempts. */
    val calls: Int,
    val promptTokens: Int,
    val completionTokens: Int,
    val latencyMs: Long,
    val costUsd: Double? = null,
    val failed: Boolean = false,
) : Timed

/** One decision the deciding model (or the scripted policy) made, with what it saw by blob. */
@Serializable
@SerialName("decisions")
data class DecisionRow(
    override val runId: String,
    override val seq: Long,
    override val tick: Long,
    override val day: Int?,
    override val minute: Int?,
    val sessionId: String,
    val model: String,
    val intent: JsonElement,
    /** "do:sketch", "buy:meal", "wait": for grouping. */
    val intentLabel: String,
    val thought: String? = null,
    /** The last reply, valid or not: Jev's probabilities and beam paths are in it. */
    val raw: String? = null,
    val attempts: Int,
    val error: String? = null,
    val fallback: Boolean = false,
    val latencyMs: Long,
    val promptTokens: Int,
    val completionTokens: Int,
    val observationBlob: String? = null,
    val requestBlob: String? = null,
    /** When the intent was think: the thinker's call. */
    val think: JsonElement? = null,
) : Timed

@Serializable
@SerialName("ledger_entries")
data class LedgerRow(
    override val runId: String,
    override val seq: Long,
    override val tick: Long,
    override val day: Int?,
    override val minute: Int?,
    val amount: Int,
    val balance: Int,
    val reason: String,
) : Timed

@Serializable
@SerialName("need_changes")
data class NeedChangeRow(
    override val runId: String,
    override val seq: Long,
    override val tick: Long,
    override val day: Int?,
    override val minute: Int?,
    val need: String,
    val delta: Double,
    val value: Double,
    val reason: String,
) : Timed

/** How things stood on the hour. */
@Serializable
@SerialName("hourly_state")
data class HourRow(
    override val runId: String,
    override val seq: Long,
    override val tick: Long,
    override val day: Int?,
    override val minute: Int?,
    val money: Int,
    val needs: Map<String, Double>,
    val doing: String? = null,
) : Timed

/** An event in his memory's stream (level 0), with the store's own id. */
@Serializable
@SerialName("memory_events")
data class MemoryEventRow(
    override val runId: String,
    override val seq: Long,
    override val tick: Long,
    override val day: Int?,
    override val minute: Int?,
    val eventId: Long,
    val kind: String,
    val text: String,
) : Timed

/** An event's significance, scored after it was written. */
@Serializable
@SerialName("event_significance")
data class SignificanceRow(
    override val runId: String,
    override val seq: Long,
    val eventId: Long,
    val significance: Double,
) : RunScoped

/** A memory: level 1 a stretch of events, 2 a day, 3 the self-model. */
@Serializable
@SerialName("memories")
data class MemoryRow(
    override val runId: String,
    override val seq: Long,
    val memoryId: Long,
    val level: Int,
    val fromId: Long,
    val toId: Long,
    val fromDay: Int? = null,
    val fromTime: String? = null,
    val toDay: Int? = null,
    val toTime: String? = null,
    val text: String,
    val source: String,
    val importance: Double? = null,
) : RunScoped

/** What an authored op's model wrote: an answer he asked for, a shopping trip's picks. */
@Serializable
@SerialName("authored")
data class AuthoredRow(
    override val runId: String,
    override val seq: Long,
    override val tick: Long,
    override val day: Int?,
    override val minute: Int?,
    val sessionId: String,
    /** "answer", "shopping". */
    val op: String,
    val model: String,
    val result: JsonElement,
    val latencyMs: Long,
    val promptTokens: Int,
    val completionTokens: Int,
    val error: String? = null,
) : Timed

/** A piece he drew, with the picture by blob. */
@Serializable
@SerialName("art_pieces")
data class ArtPieceRow(
    override val runId: String,
    override val seq: Long,
    override val tick: Long,
    override val day: Int?,
    override val minute: Int?,
    val piece: Int? = null,
    /** The item: "sketch", "painting". */
    val medium: String,
    val artist: String,
    val title: String? = null,
    val tags: List<String> = emptyList(),
    val shows: String? = null,
    val commission: String? = null,
    val strokes: Int,
    val calls: Int,
    val palette: List<String> = emptyList(),
    val pngBlob: String? = null,
    val error: String? = null,
) : Timed

/** Anything without a table of its own yet. */
@Serializable
@SerialName("run_records")
data class RecordRow(
    override val runId: String,
    override val seq: Long,
    override val tick: Long,
    override val day: Int?,
    override val minute: Int?,
    val kind: String,
    val data: JsonElement,
) : Timed

object Rows {
    @OptIn(ExperimentalSerializationApi::class)
    val json = Json {
        classDiscriminator = "table"
        namingStrategy = JsonNamingStrategy.SnakeCase
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    fun encode(row: Row): String = json.encodeToString(Row.serializer(), row)

    fun decode(line: String): Row = json.decodeFromString(Row.serializer(), line)

    /** Every row in a `rows-NN.jsonl` file, in the order written. */
    fun read(file: File): List<Row> = file.readLines().filter { it.isNotBlank() }.map(::decode)
}
