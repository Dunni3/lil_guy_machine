package dev.lilguy.agent

import dev.lilguy.agent.art.Drawing
import dev.lilguy.agent.memory.Answer
import dev.lilguy.agent.memory.EventKind
import dev.lilguy.agent.memory.MemoryStore
import dev.lilguy.agent.memory.Span
import dev.lilguy.agent.shop.Trip
import dev.lilguy.runlog.ArtPieceRow
import dev.lilguy.runlog.AuthoredRow
import dev.lilguy.runlog.BlobRow
import dev.lilguy.runlog.BuildInfo
import dev.lilguy.runlog.ContentVersionRow
import dev.lilguy.runlog.DecisionRow
import dev.lilguy.runlog.HourRow
import dev.lilguy.runlog.Ids
import dev.lilguy.runlog.LedgerRow
import dev.lilguy.runlog.MemoryEventRow
import dev.lilguy.runlog.MemoryRow
import dev.lilguy.runlog.ModelCallRow
import dev.lilguy.runlog.ModelRole
import dev.lilguy.runlog.NeedChangeRow
import dev.lilguy.runlog.Row
import dev.lilguy.runlog.RunEndRow
import dev.lilguy.runlog.RunRecorder
import dev.lilguy.runlog.RunRow
import dev.lilguy.runlog.SessionEndRow
import dev.lilguy.runlog.SessionRow
import dev.lilguy.runlog.SignificanceRow
import dev.lilguy.sim.BedroomContent
import dev.lilguy.sim.BedroomWorld
import dev.lilguy.sim.GameClock
import dev.lilguy.sim.Intent
import dev.lilguy.sim.Need
import dev.lilguy.sim.Observation
import dev.lilguy.sim.Personality
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.add
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToLong

/**
 * Turns what one run does into rows of the run database (`:runlog`) and hands them to a
 * [RunRecorder]: the run and session starting and ending, every decision, model call, answer,
 * shopping trip and drawing, every ledger entry and need change, the state on each hour, and
 * every write to his memory ([store]). One [runId] is one life; this process's part of it is
 * [sessionId].
 *
 * Callable from any thread, except [afterTick], which reads the world and belongs to the sim
 * thread. Rows of one run get [seq] numbers from one counter, in the order they're recorded.
 */
class Recording(
    private val recorder: RunRecorder,
    val runId: String = Ids.uuid7(),
    val sessionId: String = Ids.uuid7(),
) : AutoCloseable {
    /** What a run row says about how the run was set up; the content hash and build come from here. */
    data class RunInfo(
        val scenario: Scenario,
        /** "cli", "desktop", "android". */
        val platform: String,
        val host: String? = null,
        val ticksPerMinute: Int,
        val label: String? = null,
        val personality: Personality? = null,
        val granted: Set<String> = emptySet(),
        /** By role: decision, writer, memory, recall, painter. */
        val models: Map<String, ModelRole> = emptyMap(),
        val settings: JsonObject = JsonObject(emptyMap()),
        val seed: Long = 0,
    )

    /** False for [RunRecorder.None]: nothing is built or hashed. */
    private val active = recorder !== RunRecorder.None
    private val lock = Any()
    private var seq = 0L
    private val blobs = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var tickNow: () -> Long = { 0L }
    @Volatile private var clock: GameClock? = null
    @Volatile private var ended = false
    // Sim thread only.
    private var ledgerSeen = 0
    private var needsSeen = 0

    /** Stamps rows from here on with [tick] and [clock]'s day and minute. */
    fun attach(tick: () -> Long, clock: GameClock?) {
        tickNow = tick
        this.clock = clock
    }

    /** The run row, the content it was built from, and this session. */
    fun start(info: RunInfo) {
        if (!active) return
        val files = if (info.scenario == Scenario.Bedroom) BedroomContent.shipped() else null
        val hash = files?.let(::contentHash)
        if (files != null && hash != null) recorder.record(ContentVersionRow(hash, files))
        val now = Instant.now().toString()
        emit { s ->
            RunRow(
                runId, s, label = info.label, scenario = info.scenario.id, platform = info.platform, host = info.host,
                gitSha = BuildInfo.gitSha, gitDirty = BuildInfo.gitDirty, contentHash = hash, seed = info.seed,
                ticksPerMinute = info.ticksPerMinute,
                personality = info.personality?.let { DecisionLog.json.encodeToJsonElement(Personality.serializer(), it) },
                granted = info.granted.sorted(), models = info.models, settings = info.settings, startedAt = now,
            )
        }
        emit { s -> SessionRow(runId, s, sessionId, gitSha = BuildInfo.gitSha, gitDirty = BuildInfo.gitDirty, contentHash = hash, platform = info.platform, host = info.host, startedAt = now) }
    }

    /** The run ending; later calls do nothing. */
    fun end(reason: String) {
        if (!active || ended) return
        ended = true
        val tick = tickNow()
        val (day, minute) = stamp(tick)
        val now = Instant.now().toString()
        emit { s -> RunEndRow(runId, s, tick, day, minute, now, reason) }
        emit { s -> SessionEndRow(runId, s, sessionId, tick, now, reason) }
    }

    fun decision(r: AgentRuntime.DecisionRecord, model: String) {
        if (!active) return
        val e = DecisionLog.entryOf(runId, model, r)
        val observation = e.observation?.let { blob(DecisionLog.json.encodeToString(Observation.serializer(), it)) }
        val request = e.request?.let { blob(DecisionLog.json.encodeToString(LoggedRequest.serializer(), it)) }
        val (day, minute) = stamp(r.tick)
        emit { s ->
            DecisionRow(
                runId, s, r.tick, day, minute, sessionId, model,
                intent = DecisionLog.json.encodeToJsonElement(Intent.serializer(), r.intent), intentLabel = intentLabel(r.intent),
                thought = r.rationale, raw = r.raw, attempts = r.attempts.size, error = r.error, fallback = r.fellBack,
                latencyMs = r.latency.inWholeMilliseconds, promptTokens = r.usage.promptTokens, completionTokens = r.usage.completionTokens,
                observationBlob = observation, requestBlob = request,
                think = e.think?.let { DecisionLog.json.encodeToJsonElement(LoggedThink.serializer(), it) },
            )
        }
    }

    fun call(c: TokenCall) {
        if (!active) return
        val (day, minute) = stamp(c.tick)
        val purpose = TokenPurpose.serializer().descriptor.getElementName(c.purpose.ordinal)
        emit { s -> ModelCallRow(runId, s, c.tick, day, minute, sessionId, purpose, c.model, c.calls, c.promptTokens, c.completionTokens, c.latencyMs, c.costUsd, c.failed) }
    }

    fun answer(a: Answer) {
        if (!active) return
        val r = a.reply
        val result = buildJsonObject {
            put("question", a.question)
            putJsonArray("opened") { a.opened.forEach { add(it) } }
            put("answer", r.text)
            put("raw", r.raw)
        }
        val (day, minute) = stamp(r.tick)
        emit { s -> AuthoredRow(runId, s, r.tick, day, minute, sessionId, "answer", r.thinker, result, r.latency.inWholeMilliseconds, r.usage.promptTokens, r.usage.completionTokens, r.error) }
    }

    fun trip(t: Trip) {
        if (!active) return
        val result = buildJsonObject {
            putJsonArray("catalog") { t.catalog.forEach { add(it) } }
            putJsonArray("buy") { t.buy.forEach { b -> addJsonObject { put("item", b.item); put("why", b.why) } } }
            put("save_for", t.saveFor)
            put("chose_saving", t.choseSaving)
            put("note", t.note)
            put("raw", t.raw)
            put("fell_back", t.fellBack)
        }
        val (day, minute) = stamp(t.tick)
        emit { s -> AuthoredRow(runId, s, t.tick, day, minute, sessionId, "shopping", t.shopper, result, t.latency.inWholeMilliseconds, t.usage.promptTokens, t.usage.completionTokens, t.error) }
    }

    fun drawing(d: Drawing) {
        if (!active) return
        val png = d.artwork?.let { blob(it.png(), "image/png") }
        val (day, minute) = stamp(d.tick)
        emit { s ->
            ArtPieceRow(
                runId, s, d.tick, day, minute, piece = d.piece, medium = d.item, artist = d.artist, title = d.title, tags = d.tags,
                shows = d.shows, commission = d.commission, strokes = d.ops, calls = d.calls,
                palette = d.artwork?.palette?.map { "#%06x".format(it) }.orEmpty(), pngBlob = png, error = d.error,
            )
        }
    }

    /** New ledger entries and need changes since the last tick, and the state on the hour. Sim thread only. */
    fun afterTick(world: BedroomWorld) {
        if (!active) return
        val transactions = world.ledger.transactions
        while (ledgerSeen < transactions.size) {
            val t = transactions[ledgerSeen++]
            emit { s -> LedgerRow(runId, s, t.tick, t.day, world.clock.minuteOfDay(t.tick), t.amount, t.balance, t.reason) }
        }
        val changes = world.needs.changes
        while (needsSeen < changes.size) {
            val c = changes[needsSeen++]
            emit { s -> NeedChangeRow(runId, s, c.tick, c.day, world.clock.minuteOfDay(c.tick), c.need.id, c.delta, c.value, c.reason) }
        }
        val tick = world.tick
        if (world.clock.isMinuteStart(tick) && world.clock.minuteOfDay(tick) % 60 == 0) {
            val needs = Need.entries.associate { it.id to (world.needs[it] * 10).roundToLong() / 10.0 }
            val doing = BedroomReport.sample(world).doing
            emit { s -> HourRow(runId, s, tick, world.clock.day(tick), world.clock.minuteOfDay(tick), world.ledger.balance, needs, doing) }
        }
    }

    /** [inner], with every event, significance and memory written to it recorded too. */
    fun store(inner: MemoryStore): MemoryStore = if (!active) inner else object : MemoryStore by inner {
        override fun appendEvent(run: String, tick: Long, day: Int?, time: String?, kind: EventKind, text: String): Long =
            inner.appendEvent(run, tick, day, time, kind, text).also { id ->
                emit { s -> MemoryEventRow(runId, s, tick, day, stamp(tick).second, id, kind.id, text) }
            }

        override fun setSignificance(run: String, id: Long, significance: Double) {
            inner.setSignificance(run, id, significance)
            emit { s -> SignificanceRow(runId, s, id, significance) }
        }

        override fun addMemory(run: String, level: Int, fromId: Long, toId: Long, span: Span, text: String, source: String, importance: Double?): Long =
            inner.addMemory(run, level, fromId, toId, span, text, source, importance).also { id ->
                emit { s -> MemoryRow(runId, s, id, level, fromId, toId, span.fromDay, span.fromTime, span.toDay, span.toTime, text, source, importance) }
            }
    }

    override fun close() = recorder.close()

    private fun emit(make: (Long) -> Row) = synchronized(lock) { recorder.record(make(seq++)) }

    private fun stamp(tick: Long): Pair<Int?, Int?> = clock?.let { it.day(tick) to it.minuteOfDay(tick) } ?: (null to null)

    /** Records [text] once and returns its hash. */
    private fun blob(text: String, mediaType: String = "application/json"): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val hash = Ids.sha256(bytes)
        if (blobs.add(hash)) recorder.record(BlobRow(hash, mediaType, BlobRow.UTF8, text, bytes.size))
        return hash
    }

    private fun blob(bytes: ByteArray, mediaType: String): String {
        val hash = Ids.sha256(bytes)
        if (blobs.add(hash)) recorder.record(BlobRow(hash, mediaType, BlobRow.BASE64, Base64.getEncoder().encodeToString(bytes), bytes.size))
        return hash
    }

    companion object {
        /** One hash over the content files, in name order, so the same files always give the same hash. */
        fun contentHash(files: Map<String, String>): String =
            Ids.sha256(files.toSortedMap().entries.joinToString("") { (name, text) -> "$name\n${text.length}\n$text" })
    }
}
