package dev.lilguy.runlog

import dev.lilguy.runlog.Tables.Type.Time
import dev.lilguy.runlog.Tables.Type.Uuid
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialInfo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * A column's Postgres type where the Kotlin type doesn't say it: a String that holds a UUID or an
 * instant. Everything else follows from the property's type ([Tables.typeOf]).
 */
@OptIn(ExperimentalSerializationApi::class)
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class Pg(val type: Tables.Type)

/** A table's primary key, comma-separated column names. */
@OptIn(ExperimentalSerializationApi::class)
@SerialInfo
@Target(AnnotationTarget.CLASS)
annotation class Key(val columns: String)

/**
 * The run database's rows: the one definition of its tables. Each class is a table (its
 * `@SerialName`), each property a column; [Tables] reads them from the serial descriptors for
 * the device's SQLite store, and [RowSchema] writes them as the JSON Schema the server's Pydantic
 * models are generated from (`make server-schema`). Postgres's own tables are numbered migrations
 * in `server/sql/migrations/`, checked against that schema by the server's tests.
 *
 * One row of a table. Encoded with [RowJson], a row is `{"table": "decisions", ...columns}`:
 * the shape the local store writes and the server's ingest takes. Field names are the columns.
 */
@Serializable
sealed interface Row {
    fun json(): JsonObject = RowJson.encodeToJsonElement(serializer(), this).jsonObject
}

val RowJson = Json { encodeDefaults = true; explicitNulls = true; classDiscriminator = "table"; ignoreUnknownKeys = true }

/** Where a fact row sits: whose life, which process, its place in the run's order, and the game time. */
data class Stamp(val runId: String, val sessionId: String, val seq: Long, val tick: Long, val day: Int?, val minuteOfDay: Int?)

@Serializable @SerialName("content_versions") @Key("hash")
data class ContentVersionRow(val hash: String, val files: JsonObject, @Pg(Time) @SerialName("created_at") val createdAt: String) : Row

@Serializable @SerialName("batches") @Key("id")
data class BatchRow(@Pg(Uuid) val id: String, val name: String?, val spec: JsonElement?, @Pg(Time) @SerialName("created_at") val createdAt: String, val notes: String? = null) : Row

@Serializable @SerialName("runs") @Key("id")
data class RunRow(
    @Pg(Uuid) val id: String,
    @Pg(Uuid) @SerialName("parent_id") val parentId: String? = null,
    @Pg(Uuid) @SerialName("forked_from") val forkedFrom: String? = null,
    @Pg(Uuid) @SerialName("batch_id") val batchId: String? = null,
    val label: String? = null,
    val scenario: String,
    val platform: String,
    val host: String?,
    @SerialName("git_sha") val gitSha: String?,
    @SerialName("git_dirty") val gitDirty: Boolean?,
    @SerialName("content_hash") val contentHash: String?,
    val seed: Long,
    @SerialName("ticks_per_minute") val ticksPerMinute: Int,
    val personality: JsonElement? = null,
    val granted: List<String>? = null,
    /** Per role (decision, memory, writer, recall, painter): endpoint, model id, prices, beam. */
    val models: JsonObject,
    val settings: JsonObject? = null,
    @Pg(Time) @SerialName("started_at") val startedAt: String,
    @Pg(Time) @SerialName("ended_at") val endedAt: String? = null,
    /** 'days reached', 'decision cap', 'stopped', 'restarted', 'crashed'. */
    @SerialName("end_reason") val endReason: String? = null,
    val notes: String? = null,
) : Row

@Serializable @SerialName("sessions") @Key("id")
data class SessionRow(
    @Pg(Uuid) val id: String,
    @Pg(Uuid) @SerialName("run_id") val runId: String,
    @Pg(Uuid) @SerialName("from_checkpoint") val fromCheckpoint: String? = null,
    @SerialName("git_sha") val gitSha: String?,
    @SerialName("git_dirty") val gitDirty: Boolean?,
    @SerialName("content_hash") val contentHash: String?,
    val platform: String,
    val host: String?,
    @Pg(Time) @SerialName("started_at") val startedAt: String,
    @Pg(Time) @SerialName("ended_at") val endedAt: String? = null,
) : Row

@Serializable @SerialName("model_calls") @Key("id")
data class ModelCallRow(
    @Pg(Uuid) val id: String,
    @Pg(Uuid) @SerialName("run_id") val runId: String,
    @Pg(Uuid) @SerialName("session_id") val sessionId: String,
    val seq: Long,
    val tick: Long,
    val day: Int?,
    @SerialName("minute_of_day") val minuteOfDay: Int?,
    /** decision, think, ask, shop, art, recall, significance, summary, self, reading. */
    val purpose: String,
    val endpoint: String,
    val model: String,
    /** HTTP attempts, retries after a bad reply included. */
    val calls: Int,
    @SerialName("prompt_tokens") val promptTokens: Int?,
    @SerialName("completion_tokens") val completionTokens: Int?,
    @SerialName("reasoning_tokens") val reasoningTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null,
    @SerialName("latency_ms") val latencyMs: Int?,
    @SerialName("price_in") val priceIn: Double? = null,
    @SerialName("price_out") val priceOut: Double? = null,
    @SerialName("cost_usd") val costUsd: Double? = null,
    val failed: Boolean = false,
    /** The system prompt on its own, since it repeats from call to call. */
    @SerialName("system_blob") val systemBlob: String? = null,
    /** The rest of the request: messages, temperature, schema or choice set. */
    @SerialName("request_blob") val requestBlob: String? = null,
    /** Every reply, with why each rejected one was. */
    @SerialName("response_blob") val responseBlob: String? = null,
) : Row

@Serializable @SerialName("decisions") @Key("id")
data class DecisionRow(
    @Pg(Uuid) val id: String,
    @Pg(Uuid) @SerialName("run_id") val runId: String,
    @Pg(Uuid) @SerialName("session_id") val sessionId: String,
    val seq: Long,
    val tick: Long,
    val day: Int?,
    @SerialName("minute_of_day") val minuteOfDay: Int?,
    /** Null for the scripted policy and for a forced reflection. */
    @Pg(Uuid) @SerialName("model_call_id") val modelCallId: String?,
    val intent: JsonElement,
    /** 'do:sketch', 'buy:meal': for group by. */
    @SerialName("intent_label") val intentLabel: String,
    val thought: String?,
    val choices: JsonElement? = null,
    val fallback: Boolean,
    val error: String? = null,
    /** succeeded, failed or interrupted, once the next observation says. */
    @SerialName("outcome_status") val outcomeStatus: String? = null,
    @SerialName("outcome_text") val outcomeText: String? = null,
    @SerialName("observation_blob") val observationBlob: String? = null,
) : Row

@Serializable @SerialName("ledger_entries") @Key("run_id, seq")
data class LedgerRow(
    @Pg(Uuid) @SerialName("run_id") val runId: String,
    @Pg(Uuid) @SerialName("session_id") val sessionId: String,
    val seq: Long,
    val tick: Long,
    val day: Int?,
    @SerialName("minute_of_day") val minuteOfDay: Int?,
    val amount: Int,
    val balance: Int,
    val reason: String,
) : Row

@Serializable @SerialName("need_changes") @Key("run_id, seq")
data class NeedRow(
    @Pg(Uuid) @SerialName("run_id") val runId: String,
    @Pg(Uuid) @SerialName("session_id") val sessionId: String,
    val seq: Long,
    val tick: Long,
    val day: Int?,
    @SerialName("minute_of_day") val minuteOfDay: Int?,
    val need: String,
    val delta: Double,
    val value: Double,
    val reason: String,
) : Row

@Serializable @SerialName("hourly_state") @Key("run_id, seq")
data class HourlyRow(
    @Pg(Uuid) @SerialName("run_id") val runId: String,
    @Pg(Uuid) @SerialName("session_id") val sessionId: String,
    val seq: Long,
    val tick: Long,
    val day: Int?,
    @SerialName("minute_of_day") val minuteOfDay: Int?,
    val hour: Int,
    val money: Int,
    val needs: JsonObject,
    val skills: JsonObject? = null,
    val doing: String?,
) : Row

@Serializable @SerialName("memory_events") @Key("run_id, id")
data class MemoryEventRow(
    @Pg(Uuid) @SerialName("run_id") val runId: String,
    @Pg(Uuid) @SerialName("session_id") val sessionId: String,
    val seq: Long,
    val tick: Long,
    val day: Int?,
    @SerialName("minute_of_day") val minuteOfDay: Int?,
    val id: Long,
    /** "14:30", as the memory layer shows it. */
    val time: String?,
    val kind: String,
    val text: String,
    val significance: Double? = null,
) : Row

@Serializable @SerialName("memories") @Key("run_id, id")
data class MemoryRow(
    @Pg(Uuid) @SerialName("run_id") val runId: String,
    @Pg(Uuid) @SerialName("session_id") val sessionId: String,
    val seq: Long,
    val tick: Long,
    val day: Int?,
    @SerialName("minute_of_day") val minuteOfDay: Int?,
    val id: Long,
    val level: Int,
    @SerialName("from_id") val fromId: Long,
    @SerialName("to_id") val toId: Long,
    @SerialName("from_day") val fromDay: Int?,
    /** "14:30" */
    @SerialName("from_minute") val fromMinute: String?,
    @SerialName("to_day") val toDay: Int?,
    @SerialName("to_minute") val toMinute: String?,
    val text: String,
    val source: String,
    val importance: Double? = null,
) : Row

@Serializable @SerialName("authored") @Key("id")
data class AuthoredRow(
    @Pg(Uuid) val id: String,
    @Pg(Uuid) @SerialName("run_id") val runId: String,
    @Pg(Uuid) @SerialName("session_id") val sessionId: String,
    val seq: Long,
    val tick: Long,
    val day: Int?,
    @SerialName("minute_of_day") val minuteOfDay: Int?,
    /** 'thought', 'answer', 'shopping', 'draw'. */
    val op: String,
    val verb: String? = null,
    val kept: Boolean,
    val writer: String?,
    val result: JsonElement,
    val error: String? = null,
) : Row

@Serializable @SerialName("art_pieces") @Key("run_id, seq")
data class ArtPieceRow(
    @Pg(Uuid) @SerialName("run_id") val runId: String,
    @Pg(Uuid) @SerialName("session_id") val sessionId: String,
    val seq: Long,
    val tick: Long,
    val day: Int?,
    @SerialName("minute_of_day") val minuteOfDay: Int?,
    val piece: Int,
    /** The item: sketch, painting. */
    val medium: String,
    val title: String?,
    val tags: List<String>,
    val shows: String?,
    /** "#rrggbb" per color; [letters] index into it from 'a'. */
    val palette: List<String>,
    val letters: List<String>,
    val ops: Int,
    @SerialName("png_blob") val pngBlob: String?,
    val commission: String?,
    val artist: String?,
) : Row

@Serializable @SerialName("checkpoints") @Key("id")
data class CheckpointRow(
    @Pg(Uuid) val id: String,
    @Pg(Uuid) @SerialName("run_id") val runId: String,
    @Pg(Uuid) @SerialName("session_id") val sessionId: String,
    val tick: Long,
    val day: Int,
    @SerialName("minute_of_day") val minuteOfDay: Int,
    /** 'woke', 'manual', 'shutdown'. */
    val reason: String,
    @SerialName("content_hash") val contentHash: String,
    @SerialName("git_sha") val gitSha: String?,
    @SerialName("world_state") val worldState: JsonElement,
    @SerialName("agent_state") val agentState: JsonElement,
    /** The run's last seq, and the last memory event and memory id, this checkpoint covers. */
    val watermarks: Watermarks,
    @Pg(Time) @SerialName("created_at") val createdAt: String,
) : Row

@Serializable
data class Watermarks(
    val seq: Long,
    @SerialName("memory_event") val memoryEvent: Long,
    val memory: Long,
)

@Serializable @SerialName("run_lineage") @Key("run_id, source_run_id, scope")
data class LineageRow(
    @Pg(Uuid) @SerialName("run_id") val runId: String,
    @Pg(Uuid) @SerialName("source_run_id") val sourceRunId: String,
    /** 'memories', 'self_model', 'skills', 'knowledge', 'inventions', 'art'. */
    val scope: String,
    /** 'seed' (copied in at the start) or 'recall' (searchable during the run). */
    val mode: String,
    val note: String? = null,
) : Row

@Serializable @SerialName("run_records") @Key("run_id, seq")
data class RecordRow(
    @Pg(Uuid) @SerialName("run_id") val runId: String,
    @Pg(Uuid) @SerialName("session_id") val sessionId: String,
    val seq: Long,
    val tick: Long,
    val day: Int?,
    @SerialName("minute_of_day") val minuteOfDay: Int?,
    val kind: String,
    val data: JsonElement,
) : Row

/** Reserved for the phone and letters (phase 3): nothing writes it yet. */
@Serializable @SerialName("letters") @Key("id")
data class LetterRow(
    @Pg(Uuid) val id: String,
    @Pg(Uuid) @SerialName("run_id") val runId: String,
    /** 'to_him' or 'from_him'. */
    val direction: String,
    val tick: Long?,
    val day: Int?,
    @SerialName("minute_of_day") val minuteOfDay: Int?,
    val text: String,
    @Pg(Uuid) @SerialName("reply_to") val replyTo: String? = null,
    @Pg(Time) @SerialName("written_at") val writtenAt: String? = null,
    @Pg(Time) @SerialName("delivered_at") val deliveredAt: String? = null,
    @Pg(Time) @SerialName("read_at") val readAt: String? = null,
) : Row
