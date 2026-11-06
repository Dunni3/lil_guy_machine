package dev.lilguy.runlog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * One row of a [Tables] table. Encoded with [RowJson], a row is `{"table": "decisions", ...columns}`:
 * the shape the local store writes and the server's ingest takes. Field names are the columns.
 */
@Serializable
sealed interface Row {
    fun json(): JsonObject = RowJson.encodeToJsonElement(serializer(), this).jsonObject
}

val RowJson = Json { encodeDefaults = true; explicitNulls = true; classDiscriminator = "table"; ignoreUnknownKeys = true }

/** Where a fact row sits: whose life, which process, its place in the run's order, and the game time. */
data class Stamp(val runId: String, val sessionId: String, val seq: Long, val tick: Long, val day: Int?, val minuteOfDay: Int?)

@Serializable @SerialName("content_versions")
data class ContentVersionRow(val hash: String, val files: JsonObject, @SerialName("created_at") val createdAt: String) : Row

@Serializable @SerialName("batches")
data class BatchRow(val id: String, val name: String?, val spec: JsonElement?, @SerialName("created_at") val createdAt: String, val notes: String? = null) : Row

@Serializable @SerialName("runs")
data class RunRow(
    val id: String,
    @SerialName("parent_id") val parentId: String? = null,
    @SerialName("forked_from") val forkedFrom: String? = null,
    @SerialName("batch_id") val batchId: String? = null,
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
    @SerialName("started_at") val startedAt: String,
    @SerialName("ended_at") val endedAt: String? = null,
    /** 'days reached', 'decision cap', 'stopped', 'restarted', 'crashed'. */
    @SerialName("end_reason") val endReason: String? = null,
    val notes: String? = null,
) : Row

@Serializable @SerialName("sessions")
data class SessionRow(
    val id: String,
    @SerialName("run_id") val runId: String,
    @SerialName("from_checkpoint") val fromCheckpoint: String? = null,
    @SerialName("git_sha") val gitSha: String?,
    @SerialName("git_dirty") val gitDirty: Boolean?,
    @SerialName("content_hash") val contentHash: String?,
    val platform: String,
    val host: String?,
    @SerialName("started_at") val startedAt: String,
    @SerialName("ended_at") val endedAt: String? = null,
) : Row

@Serializable @SerialName("model_calls")
data class ModelCallRow(
    val id: String,
    @SerialName("run_id") val runId: String,
    @SerialName("session_id") val sessionId: String,
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

@Serializable @SerialName("decisions")
data class DecisionRow(
    val id: String,
    @SerialName("run_id") val runId: String,
    @SerialName("session_id") val sessionId: String,
    val seq: Long,
    val tick: Long,
    val day: Int?,
    @SerialName("minute_of_day") val minuteOfDay: Int?,
    /** Null for the scripted policy and for a forced reflection. */
    @SerialName("model_call_id") val modelCallId: String?,
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

@Serializable @SerialName("ledger_entries")
data class LedgerRow(
    @SerialName("run_id") val runId: String,
    @SerialName("session_id") val sessionId: String,
    val seq: Long,
    val tick: Long,
    val day: Int?,
    @SerialName("minute_of_day") val minuteOfDay: Int?,
    val amount: Int,
    val balance: Int,
    val reason: String,
) : Row

@Serializable @SerialName("need_changes")
data class NeedRow(
    @SerialName("run_id") val runId: String,
    @SerialName("session_id") val sessionId: String,
    val seq: Long,
    val tick: Long,
    val day: Int?,
    @SerialName("minute_of_day") val minuteOfDay: Int?,
    val need: String,
    val delta: Double,
    val value: Double,
    val reason: String,
) : Row

@Serializable @SerialName("hourly_state")
data class HourlyRow(
    @SerialName("run_id") val runId: String,
    @SerialName("session_id") val sessionId: String,
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

@Serializable @SerialName("memory_events")
data class MemoryEventRow(
    @SerialName("run_id") val runId: String,
    @SerialName("session_id") val sessionId: String,
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

@Serializable @SerialName("memories")
data class MemoryRow(
    @SerialName("run_id") val runId: String,
    @SerialName("session_id") val sessionId: String,
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

@Serializable @SerialName("authored")
data class AuthoredRow(
    val id: String,
    @SerialName("run_id") val runId: String,
    @SerialName("session_id") val sessionId: String,
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

@Serializable @SerialName("art_pieces")
data class ArtPieceRow(
    @SerialName("run_id") val runId: String,
    @SerialName("session_id") val sessionId: String,
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

@Serializable @SerialName("checkpoints")
data class CheckpointRow(
    val id: String,
    @SerialName("run_id") val runId: String,
    @SerialName("session_id") val sessionId: String,
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
    @SerialName("created_at") val createdAt: String,
) : Row

@Serializable
data class Watermarks(
    val seq: Long,
    @SerialName("memory_event") val memoryEvent: Long,
    val memory: Long,
)

@Serializable @SerialName("run_lineage")
data class LineageRow(
    @SerialName("run_id") val runId: String,
    @SerialName("source_run_id") val sourceRunId: String,
    /** 'memories', 'self_model', 'skills', 'knowledge', 'inventions', 'art'. */
    val scope: String,
    /** 'seed' (copied in at the start) or 'recall' (searchable during the run). */
    val mode: String,
    val note: String? = null,
) : Row

@Serializable @SerialName("run_records")
data class RecordRow(
    @SerialName("run_id") val runId: String,
    @SerialName("session_id") val sessionId: String,
    val seq: Long,
    val tick: Long,
    val day: Int?,
    @SerialName("minute_of_day") val minuteOfDay: Int?,
    val kind: String,
    val data: JsonElement,
) : Row
