package dev.lilguy.agent

import dev.lilguy.agent.art.Drawing
import dev.lilguy.agent.memory.AgentMemory
import dev.lilguy.agent.memory.Answer
import dev.lilguy.agent.memory.RunMemoryStore
import dev.lilguy.agent.memory.Thought
import dev.lilguy.agent.shop.Trip
import dev.lilguy.decision.Attempt
import dev.lilguy.decision.DecisionRequest
import dev.lilguy.decision.Usage
import dev.lilguy.runlog.ArtPieceRow
import dev.lilguy.runlog.AuthoredRow
import dev.lilguy.runlog.Blobs
import dev.lilguy.runlog.CheckpointRow
import dev.lilguy.runlog.ContentVersionRow
import dev.lilguy.runlog.DecisionRow
import dev.lilguy.runlog.HourlyRow
import dev.lilguy.runlog.Ids
import dev.lilguy.runlog.LedgerRow
import dev.lilguy.runlog.ModelCallRow
import dev.lilguy.runlog.NeedRow
import dev.lilguy.runlog.RowJson
import dev.lilguy.runlog.RunRecorder
import dev.lilguy.runlog.RunRow
import dev.lilguy.runlog.RunStore
import dev.lilguy.runlog.SessionRow
import dev.lilguy.runlog.Stamp
import dev.lilguy.runlog.Watermarks
import dev.lilguy.sim.BedroomContent
import dev.lilguy.sim.BedroomState
import dev.lilguy.sim.BedroomWorld
import dev.lilguy.sim.GameClock
import dev.lilguy.sim.Intent
import dev.lilguy.sim.IntentStatus
import dev.lilguy.sim.Need
import dev.lilguy.sim.Observation
import dev.lilguy.sim.Personality
import dev.lilguy.sim.SleepOp
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** What a checkpoint keeps of the agent side: the loop's short-term memory and the long-term memory's bookkeeping. */
@Serializable
data class AgentState(
    val version: Int = VERSION,
    val runtime: AgentRuntime.State,
    val memory: AgentMemory.State? = null,
) {
    companion object {
        const val VERSION = 1
    }
}

/**
 * One life's rows, from the moments the run already has: each decision and how it ended, every
 * model call with its prompt and reply, the ledger and need changes and an hourly sample as the sim
 * ticks, thoughts, answers, shopping trips and drawings, and a checkpoint each morning when he
 * wakes. Everything goes through [recorder], which never waits on the disk; the memory rows go
 * through [RunMemoryStore].
 *
 * Call [beforeStep] and [afterStep] around every tick, on the sim thread: that's where ledger and
 * need changes are collected and checkpoints are taken. The rest is safe from any thread.
 */
class LifeRecorder(val recorder: RunRecorder) {
    val runId: String get() = recorder.runId
    val sessionId: String get() = recorder.sessionId

    @Volatile private var clock: GameClock? = null
    private var world: BedroomWorld? = null
    private var runtime: AgentRuntime? = null
    private var memory: AgentMemory? = null
    private var memoryStore: RunMemoryStore? = null
    private var run: RunRow? = null
    private var session: SessionRow? = null
    private var contentHash: String? = null
    private var gitSha: String? = null
    private var restVerbs: Set<String> = emptySet()

    /** The latest decision's row, until its outcome is known. */
    private var open: DecisionRow? = null
    private val decisionLock = Any()
    /** The latest decision-purpose model call not yet tied to a decision. */
    @Volatile private var decisionCall: String? = null

    private var ledgerSeen = 0
    private var needsSeen = 0
    private var wantCheckpoint = false
    private var held = false

    /** Checkpoints written this session, newest last. */
    val checkpoints = java.util.concurrent.CopyOnWriteArrayList<CheckpointRow>()

    /** Records the run (new or carried on), this session, and the content it runs on. */
    fun begin(run: RunRow, session: SessionRow, content: ContentVersionRow?) {
        content?.let(recorder::record)
        recorder.record(run)
        recorder.record(session)
        this.run = run
        this.session = session
        contentHash = session.contentHash
        gitSha = session.gitSha
    }

    /** Ties the recorder to the world and agent it records; checkpoints need all three. Call before the run starts. */
    fun attach(world: BedroomWorld, runtime: AgentRuntime, memory: AgentMemory?, memoryStore: RunMemoryStore?) {
        this.world = world
        this.runtime = runtime
        this.memory = memory
        this.memoryStore = memoryStore
        clock = world.clock
        restVerbs = world.content.verbs.filter { v -> v.effects.any { it is SleepOp } }.map { it.id }.toSet()
    }

    /** Stamps calls in rooms without a game clock with the tick alone. */
    fun attachClock(clock: GameClock?) {
        this.clock = clock
    }

    private fun stamp(tick: Long): Stamp {
        val c = clock
        return recorder.stamp(tick, c?.day(tick), c?.minuteOfDay(tick))
    }

    // --- model calls and decisions ---------------------------------------------------------------

    /** One finished model call; returns its row id. Called by [TokenLedger]'s metered models. */
    fun modelCall(
        tick: Long,
        purpose: TokenPurpose,
        model: String,
        price: TokenPrice?,
        usage: Usage,
        calls: Int,
        latencyMs: Long,
        failed: Boolean,
        system: String? = null,
        request: JsonElement? = null,
        response: JsonElement? = null,
    ): String {
        val s = stamp(tick)
        val id = Ids.uuid7()
        recorder.record(
            ModelCallRow(
                id, s.runId, s.sessionId, s.seq, s.tick, s.day, s.minuteOfDay,
                purpose = RowJson.encodeToJsonElement(TokenPurpose.serializer(), purpose).let { (it as JsonPrimitive).content },
                endpoint = model, model = model, calls = calls.coerceAtLeast(1),
                promptTokens = usage.promptTokens, completionTokens = usage.completionTokens, totalTokens = usage.promptTokens + usage.completionTokens,
                latencyMs = latencyMs.toInt(), priceIn = price?.inPerMillion, priceOut = price?.outPerMillion, costUsd = price?.cost(usage), failed = failed,
                systemBlob = system?.let { recorder.blob(it) }, requestBlob = request?.let(recorder::blob), responseBlob = response?.let(recorder::blob),
            ),
        )
        if (purpose == TokenPurpose.Decision) decisionCall = id
        return id
    }

    /** The decision he just made, and how the one before it ended (this one's observation says). */
    fun decision(d: AgentRuntime.DecisionRecord) {
        val call = decisionCall.also { decisionCall = null }
        d.observation?.let(::settle)
        val s = stamp(d.tick)
        val row = DecisionRow(
            Ids.uuid7(), s.runId, s.sessionId, s.seq, s.tick, s.day, s.minuteOfDay,
            modelCallId = call.takeIf { d.latency.isPositive() || d.raw != null || d.error != null },
            intent = RowJson.encodeToJsonElement(Intent.serializer(), d.intent),
            intentLabel = label(d.intent),
            thought = d.rationale,
            fallback = d.fellBack,
            error = d.error,
            observationBlob = d.observation?.let { recorder.blob(RowJson.encodeToJsonElement(Observation.serializer(), it)) },
        )
        synchronized(decisionLock) { open = row }
        recorder.record(row)
        d.thought?.let { thought(it, d.intent) }
    }

    /** Fills in how the open decision ended once [obs] reports it; also at the end of a run, from the final observation. */
    fun settle(obs: Observation) {
        val status = obs.lastResult
        val text = when (status) {
            IntentStatus.Succeeded -> "succeeded"
            is IntentStatus.Failed -> "failed"
            IntentStatus.Interrupted -> "interrupted"
            else -> return
        }
        val row = synchronized(decisionLock) {
            val o = open ?: return
            if (obs.lastIntent != null && RowJson.encodeToJsonElement(Intent.serializer(), obs.lastIntent!!) != o.intent) return
            open = null
            o
        }
        recorder.record(row.copy(outcomeStatus = text, outcomeText = obs.outcome ?: (status as? IntentStatus.Failed)?.reason))
    }

    // --- authored ops ----------------------------------------------------------------------------

    private fun authored(tick: Long, op: String, verb: String?, kept: Boolean, writer: String?, result: JsonElement, error: String?) {
        val s = stamp(tick)
        recorder.record(AuthoredRow(Ids.uuid7(), s.runId, s.sessionId, s.seq, s.tick, s.day, s.minuteOfDay, op, verb, kept, writer, result, error))
    }

    private fun thought(t: Thought, intent: Intent) = authored(
        t.tick, "thought", (intent as? Intent.Do)?.activity, kept = t.text != null, writer = t.thinker,
        result = buildJsonObject {
            put("text", t.text)
            putJsonArray("asked") { t.asked.forEach { a -> add(buildJsonObject { put("question", a.question); put("answer", a.reply.text) }) } }
        },
        error = t.error,
    )

    fun answer(a: Answer) = authored(
        a.tick, "answer", null, kept = a.reply.text != null, writer = a.reply.thinker,
        result = buildJsonObject {
            put("question", a.question)
            putJsonArray("opened") { a.opened.forEach { add(JsonPrimitive(it)) } }
            put("answer", a.reply.text)
        },
        error = a.reply.error,
    )

    fun trip(t: Trip) = authored(
        t.tick, "shopping", null, kept = t.error == null, writer = t.shopper,
        result = buildJsonObject {
            putJsonArray("catalog") { t.catalog.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("buy") { t.buy.forEach { b -> add(buildJsonObject { put("item", b.item); put("why", b.why) }) } }
            put("save_for", t.saveFor)
            put("note", t.note)
            put("fell_back", t.fellBack)
        },
        error = t.error,
    )

    /** A finished drawing: an authored row, and the piece itself when it was kept. */
    fun drawing(d: Drawing) {
        val art = d.artwork
        authored(
            d.tick, "draw", null, kept = d.piece != null && art != null, writer = d.artist,
            result = buildJsonObject {
                put("item", d.item)
                put("piece", d.piece)
                put("title", d.title)
                putJsonArray("tags") { d.tags.forEach { add(JsonPrimitive(it)) } }
                put("shows", d.shows)
                put("commission", d.commission)
                put("ops", d.ops)
                put("calls", d.calls)
            },
            error = d.error,
        )
        val piece = d.piece ?: return
        if (art == null) return
        val s = stamp(d.tick)
        recorder.record(
            ArtPieceRow(
                s.runId, s.sessionId, s.seq, s.tick, s.day, s.minuteOfDay, piece, d.item, d.title, d.tags, d.shows,
                palette = art.palette.map { "#%06x".format(it) }, letters = art.letters(), ops = d.ops,
                pngBlob = recorder.blob(art.png(), "image/png"), commission = d.commission, artist = d.artist,
            ),
        )
    }

    // --- the sim thread --------------------------------------------------------------------------

    /** Before a tick: while he sleeps, holds the agent loop at its wait so a wake-up checkpoint can read it with the world. */
    fun beforeStep() {
        val w = world ?: return
        if (!wantCheckpoint && w.snapshot().life?.doing in restVerbs) wantCheckpoint = true
        if (wantCheckpoint) held = runtime?.hold() == true
    }

    /** After a tick: new ledger and need changes, the hourly sample, and the morning checkpoint once he's up. */
    fun afterStep() {
        val w = world ?: return
        try {
            collect(w)
            if (wantCheckpoint && held && w.awaitingDecision(w.agentId) && w.snapshot().life?.doing !in restVerbs) {
                if (checkpoint(w, "woke") != null) wantCheckpoint = false
            }
        } finally {
            if (held) runtime?.release()
            held = false
        }
    }

    private fun collect(w: BedroomWorld) {
        val tx = w.ledger.transactions
        while (ledgerSeen < tx.size) {
            val t = tx[ledgerSeen++]
            val s = stamp(t.tick)
            recorder.record(LedgerRow(s.runId, s.sessionId, s.seq, s.tick, s.day, s.minuteOfDay, t.amount, t.balance, t.reason))
        }
        val nc = w.needs.changes
        while (needsSeen < nc.size) {
            val c = nc[needsSeen++]
            val s = stamp(c.tick)
            recorder.record(NeedRow(s.runId, s.sessionId, s.seq, s.tick, s.day, s.minuteOfDay, c.need.id, c.delta, c.value, c.reason))
        }
        val tick = w.tick
        if (w.clock.isMinuteStart(tick) && w.clock.minuteOfDay(tick) % 60 == 0) {
            val h = BedroomReport.sample(w)
            val s = stamp(tick)
            recorder.record(
                HourlyRow(
                    s.runId, s.sessionId, s.seq, s.tick, s.day, s.minuteOfDay, hour = w.clock.minuteOfDay(tick) / 60, money = h.money,
                    needs = buildJsonObject { Need.entries.forEach { put(it.id, Math.round(w.needs[it] * 10) / 10.0) } },
                    skills = w.observe(w.agentId).skills?.let { sk -> buildJsonObject { sk.forEach { (k, v) -> put(k, v) } } },
                    doing = h.doing,
                ),
            )
        }
    }

    /**
     * Saves the world and the agent now, if nothing is in flight: the agent loop must be held (by
     * [beforeStep], or [hold] for a manual save), the world at rest, and no memory work running.
     * Returns the row, or null when it isn't a moment that can be saved.
     */
    fun checkpoint(w: BedroomWorld, reason: String): CheckpointRow? {
        val m = memory
        if (!w.canExport || (m != null && !m.quiet)) return null
        val r = runtime ?: return null
        val state = w.exportState()
        val agent = AgentState(runtime = r.state(), memory = m?.state())
        val (event, mem) = memoryStore?.watermarks() ?: (0L to 0L)
        val tick = w.tick
        val row = CheckpointRow(
            Ids.uuid7(), runId, sessionId, tick, w.clock.day(tick), w.clock.minuteOfDay(tick), reason,
            contentHash = contentHash ?: "", gitSha = gitSha,
            worldState = RowJson.encodeToJsonElement(BedroomState.serializer(), state),
            agentState = RowJson.encodeToJsonElement(AgentState.serializer(), agent),
            watermarks = Watermarks(recorder.last(), event, mem),
            createdAt = Ids.now(),
        )
        recorder.record(row)
        checkpoints += row
        return row
    }

    /** A save on request ('manual', 'shutdown'): holds the agent loop for the moment it takes. Sim thread; null when it can't be saved now. */
    fun checkpointNow(reason: String): CheckpointRow? {
        val w = world ?: return null
        val r = runtime ?: return null
        if (!w.awaitingDecision(w.agentId) || !r.hold()) return null
        try {
            return checkpoint(w, reason)
        } finally {
            r.release()
        }
    }

    /** Marks the session and the run ended, for [reason] ('days reached', 'stopped', 'restarted'). */
    fun end(reason: String?) {
        val now = Ids.now()
        session?.let { recorder.record(it.copy(endedAt = now)) }
        run?.let { recorder.record(it.copy(endedAt = now, endReason = reason)) }
    }

    companion object {
        /** 'do:sketch', 'buy:meal', 'wait': what a decision did, for group by. */
        fun label(intent: Intent): String = when (intent) {
            is Intent.Do -> "do:${intent.activity}"
            is Intent.Buy -> "buy:${intent.item}"
            is Intent.Use -> "use:${intent.itemId}"
            is Intent.PickUp -> "pick_up:${intent.itemId}"
            is Intent.MoveTo -> "move_to"
            is Intent.Say -> "say"
            is Intent.Wait -> "wait"
            Intent.Think -> "think"
        }

        /** The content bundle a run is built from, once per hash: the shipped files, sorted by name. */
        fun content(files: Map<String, String> = BedroomContent.defaultFiles()): ContentVersionRow {
            val sorted = files.toSortedMap()
            val hash = Blobs.sha256(sorted.entries.joinToString("") { (k, v) -> "$k.yaml\u0000$v\u0000" }.toByteArray())
            return ContentVersionRow(hash, JsonObject(sorted.mapKeys { "${it.key}.yaml" }.mapValues { JsonPrimitive(it.value) }), Ids.now())
        }

        /** The request a metered model was sent, without the system prompt (stored on its own, since it repeats). */
        fun requestJson(request: DecisionRequest, schema: JsonObject?): JsonObject = buildJsonObject {
            putJsonArray("messages") { request.messages.forEach { m -> add(buildJsonObject { put("role", m.role.name.lowercase()); put("content", m.content) }) } }
            put("temperature", request.temperature)
            schema?.let { put("schema", it) }
            request.choices?.let { c ->
                put("choices", buildJsonObject {
                    put("instructions", c.instructions)
                    put("state", c.state)
                    put("options", buildJsonObject { c.options.forEach { put(it.label, it.description) } })
                })
            }
        }

        /** Every reply a model call got, with why each rejected one was. */
        fun responseJson(raw: String?, attempts: List<Attempt>, error: String? = null): JsonObject = buildJsonObject {
            put("raw", raw)
            put("attempts", buildJsonArray { attempts.forEach { a -> add(buildJsonObject { put("raw", a.raw); put("error", a.error) }) } })
            error?.let { put("error", it) }
        }
    }
}

/** The bits of a checkpoint a resume needs, decoded. */
class Resume(val checkpoint: CheckpointRow) {
    val world: BedroomState = RowJson.decodeFromJsonElement(BedroomState.serializer(), checkpoint.worldState)
    val agent: AgentState = RowJson.decodeFromJsonElement(AgentState.serializer(), checkpoint.agentState)
}

/** A run to carry on: its row and the checkpoint it resumes from. */
class Carried(val run: RunRow, val resume: Resume) {
    val personality: Personality? = run.personality?.let { RowJson.decodeFromJsonElement(Personality.serializer(), it) }
}

/** The run [id], or the newest bedroom run with a checkpoint for "latest". */
fun carried(store: RunStore, id: String): Carried {
    val run = if (id == "latest") {
        store.runs(200).firstOrNull { it.scenario == "bedroom" && store.latestCheckpoint(it.id) != null }
            ?: throw IllegalArgumentException("no bedroom run in the database has a checkpoint yet")
    } else {
        store.run(id) ?: throw IllegalArgumentException("no run '$id' in the database")
    }
    val cp = store.latestCheckpoint(run.id) ?: throw IllegalArgumentException("run ${run.id} has no checkpoint to resume from")
    return Carried(run, Resume(cp))
}
