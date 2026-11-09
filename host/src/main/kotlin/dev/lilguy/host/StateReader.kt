package dev.lilguy.host

import dev.lilguy.agent.LifeRecorder
import dev.lilguy.agent.Scenario
import dev.lilguy.agent.TokenPurpose
import dev.lilguy.agent.art.Underway
import dev.lilguy.agent.live.RunSettings
import dev.lilguy.agent.live.SimSession
import dev.lilguy.agent.memory.Describe
import dev.lilguy.agent.memory.SelfModel
import dev.lilguy.agent.memory.SelfVersion
import dev.lilguy.agent.memory.StoredMemory
import dev.lilguy.agent.memory.Thought
import dev.lilguy.protocol.AgentView
import dev.lilguy.protocol.AnswerView
import dev.lilguy.protocol.BookView
import dev.lilguy.protocol.CatalogItem
import dev.lilguy.protocol.CatalogState
import dev.lilguy.protocol.CellView
import dev.lilguy.protocol.Clock
import dev.lilguy.protocol.CommissionView
import dev.lilguy.protocol.Content
import dev.lilguy.protocol.DecisionView
import dev.lilguy.protocol.DrawingView
import dev.lilguy.protocol.Easel
import dev.lilguy.protocol.Entity
import dev.lilguy.protocol.EventView
import dev.lilguy.protocol.FastState
import dev.lilguy.protocol.Gallery
import dev.lilguy.protocol.Growth
import dev.lilguy.protocol.IntentView
import dev.lilguy.protocol.LifeState
import dev.lilguy.protocol.Logs
import dev.lilguy.protocol.MapView
import dev.lilguy.protocol.MemoryView
import dev.lilguy.protocol.Mind
import dev.lilguy.protocol.Models
import dev.lilguy.protocol.Needs
import dev.lilguy.protocol.PickView
import dev.lilguy.protocol.PieceView
import dev.lilguy.protocol.RevisionView
import dev.lilguy.protocol.RunInfo
import dev.lilguy.protocol.RunState
import dev.lilguy.protocol.SelfModelView
import dev.lilguy.protocol.Settings
import dev.lilguy.protocol.ShopState
import dev.lilguy.protocol.StatusView
import dev.lilguy.protocol.Tally
import dev.lilguy.protocol.Things
import dev.lilguy.protocol.ThoughtView
import dev.lilguy.protocol.TokenDay
import dev.lilguy.protocol.TokenTotals
import dev.lilguy.protocol.Tokens
import dev.lilguy.protocol.TransactionView
import dev.lilguy.protocol.TripView
import dev.lilguy.protocol.UnderwayView
import dev.lilguy.protocol.VerbInfo
import dev.lilguy.protocol.VerbState
import dev.lilguy.protocol.Wallet
import dev.lilguy.protocol.Wire
import dev.lilguy.sim.EntitySnapshot
import dev.lilguy.sim.Intent
import dev.lilguy.sim.IntentStatus
import dev.lilguy.sim.Terrain
import dev.lilguy.sim.WorldSnapshot
import java.security.MessageDigest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Reads one [SimSession] into the protocol's types: [content] once a run, [state] at every
 * publish. Everything the app's views read from agent objects is here too: the piece under way as
 * the screen should show it ([SimSession.show]), thoughts, memories, the self-model and its
 * revisions, trips, and token totals. Call only from the life's thread.
 *
 * What lives in the run database (memories, the self-model) is read at most every [dbEvery];
 * everything else at every call.
 */
class StateReader(private val dbEvery: Duration = 1.seconds) {
    private var dbRead: TimeSource.Monotonic.ValueTimeMark? = null
    private var dbRun: String? = null
    private var memories: List<MemoryView> = emptyList()
    private var selfText: String? = null
    private var selfModel: SelfModelView? = null

    /** The static part of [s]'s run. */
    fun content(s: SimSession): Content {
        val now = s.current
        val life = now.life
        val partial = Content(
            hash = "",
            scenario = s.setup.scenario.id,
            map = map(now),
            catalog = life?.catalog.orEmpty().map { CatalogItem(it.id, it.name, it.group, it.price, it.pack, it.stackable) },
            verbs = life?.verbs.orEmpty().map { VerbInfo(it.id, it.phrase) },
        )
        // The bedroom's static part is a function of its content files, so it goes under their hash.
        val hash = if (s.setup.scenario == Scenario.Bedroom) contentHash else sha256(Wire.json.encodeToString(Content.serializer(), partial))
        return partial.copy(hash = hash)
    }

    fun state(life: String, seq: Long, content: String, s: SimSession): LifeState {
        if (dbRun != s.runId || dbRead.let { it == null || it.elapsedNow() >= dbEvery }) readDb(s)
        val now = s.current
        return LifeState(
            life = life,
            seq = seq,
            content = content,
            run = run(s),
            fast = fast(s, now),
            things = Things(now.entities.filter { !it.isCharacter }.map(::entity)),
            easel = easel(s),
            wallet = now.life?.let { Wallet(it.money, it.holding, it.owns) } ?: Wallet(0, emptyList(), emptyMap()),
            growth = now.life?.let { l ->
                Growth(l.skills, l.books.map { BookView(it.id, it.title, it.read, it.chapters, it.raises, it.teaches) }, l.done.mapValues { (_, t) -> Tally(t.count, t.firstTick) })
            } ?: Growth(emptyMap(), emptyList(), emptyMap()),
            gallery = now.life?.let { l ->
                Gallery(
                    l.art.map { PieceView(it.id, it.item, it.title, it.hung, it.sellsFor, it.commission) },
                    l.commission?.let { CommissionView(it.id, it.wants, it.request, it.reward, it.until) },
                )
            } ?: Gallery(emptyList(), null),
            shop = now.life?.let { l ->
                ShopState(
                    l.catalog.map { CatalogState(it.id, it.owned, it.affordable, it.gives, it.taughtBy) },
                    l.verbs.map { VerbState(it.id, it.locked, it.gives, it.taughtBy) },
                )
            } ?: ShopState(emptyList(), emptyList()),
            mind = Mind(s.memory.plan?.let { thought(it.tick, it) }, selfText, selfModel, s.memory.summarizing),
            tokens = tokens(s),
            logs = logs(s, now),
        )
    }

    private fun readDb(s: SimSession) {
        dbRun = s.runId
        dbRead = TimeSource.Monotonic.markNow()
        memories = (s.memory.memories() + s.memory.days()).sortedBy { it.id }.map(::memory)
        selfText = s.memory.selfText()
        selfModel = s.memory.selfModel()?.self?.let(::self)
    }

    private fun run(s: SimSession) = RunInfo(
        runId = s.runId,
        run = s.run,
        scenario = s.setup.scenario.id,
        state = when (s.state) {
            SimSession.RunState.Ready -> RunState.Ready
            SimSession.RunState.Running -> RunState.Running
            SimSession.RunState.Paused -> RunState.Paused
            SimSession.RunState.Ended -> RunState.Ended
        },
        settings = settings(s.settings),
        starts = s.runStarts.map(::settings),
        ticksPerMinute = s.ticksPerMinute,
        endTick = s.endTick,
        minDays = s.minDays,
        endedBecause = s.endedBecause,
        notices = listOfNotNull(s.setup.notice, s.resumeError?.let { "resume: $it" }, s.memoryError?.let { "memory: $it" }, s.writerError),
        models = Models(s.runtime.modelId, s.memory.writer, s.memory.thoughtWriter, s.memory.selfWriter, s.memory.scorerId, s.memory.studio?.artist),
    )

    private fun fast(s: SimSession, now: WorldSnapshot): FastState {
        val life = now.life
        // The session keeps its last word on holding through a pause; only a running clock is held.
        val held = s.holding && s.state == SimSession.RunState.Running
        val heldFor = if (!held) emptyList() else buildList {
            addAll(s.waitingOnAuthor)
            if (s.presenting) add("art")
            if (isEmpty()) add("decision")
        }
        return FastState(
            tick = now.tick,
            clock = life?.clock?.let { Clock(it.day, it.time) },
            needs = life?.let { Needs(it.energy, it.fullness, it.mood, it.stimulation, it.frustration, it.moodTarget) },
            doing = life?.doing,
            progress = life?.progress,
            music = life?.music,
            entities = now.entities.filter { it.isCharacter }.map(::entity),
            agents = now.agents.map { a ->
                AgentView(a.id.value, a.intent?.let(::intent), a.status?.let(::status), a.inventory, a.path.map { CellView(it.x, it.y) }, a.saying)
            },
            completed = now.completed,
            held = held,
            heldFor = heldFor,
            fastForward = s.fastForward,
            thinkingMs = s.thinkingFor?.inWholeMilliseconds,
            easelTime = s.show.phaseTime,
        )
    }

    private val EntitySnapshot.isCharacter get() = kind == "guy"

    private fun entity(e: EntitySnapshot) = Entity(e.id.value, e.kind, e.x, e.y, e.height, e.facingDegrees, e.state, e.carriedBy?.value)

    private fun easel(s: SimSession): Easel {
        val show = s.show
        return Easel(show.presented?.let(::underway), show.phase.name.lowercase(), show.phaseSeconds, show.shown, show.latest?.strokes ?: 0)
    }

    private fun tokens(s: SimSession): Tokens {
        val t = s.tokens
        fun key(p: TokenPurpose) = p.name.lowercase()
        return Tokens(
            total = totals(t.total()),
            byPurpose = t.byPurpose().entries.associate { (p, v) -> key(p) to totals(v) },
            days = t.byDay().map { d -> TokenDay(d.day, totals(d.total), d.byPurpose.entries.associate { (p, v) -> key(p) to totals(v) }) },
        )
    }

    private fun logs(s: SimSession, now: WorldSnapshot): Logs {
        val caps = Logs.CAPS
        val transactions = now.life?.transactions.orEmpty()
        val firstTx = transactions.size - minOf(transactions.size, caps.getValue("transactions"))
        val moments = s.history.takeLast(caps.getValue("decisions"))
        val firstDecision = s.filed - moments.size + 1
        val thoughts = s.memory.thoughts()
        val revisions = s.memory.revisions()
        val trips = s.memory.trips()
        val drawings = s.memory.studio?.pieces().orEmpty()
        return Logs(
            transactions = transactions.drop(firstTx).mapIndexed { i, t -> TransactionView((firstTx + i).toLong(), t.tick, t.day, t.time, t.amount, t.balance, t.reason) },
            events = events(now),
            decisions = moments.mapIndexed { i, m ->
                val d = m.decision
                DecisionView(firstDecision + i, d.tick, intent(d.intent), d.rationale, d.error, d.latency.inWholeMilliseconds)
            },
            thoughts = indexed(thoughts, "thoughts") { i, t -> thought(i, t) },
            memories = memories.takeLast(caps.getValue("memories")),
            revisions = indexed(revisions, "revisions") { i, r ->
                RevisionView(i, r.day, r.changed, r.note, r.version?.let { v -> runCatching { SelfVersion.decode(v.text).self }.getOrNull()?.let(::self) }, r.error)
            },
            trips = indexed(trips, "trips") { i, t ->
                TripView(i, t.tick, t.day, t.time, t.shopper, t.catalog, t.buy.map { PickView(it.item, it.why) }, t.saveFor, t.choseSaving, t.note, t.error, t.fellBack)
            },
            drawings = indexed(drawings, "drawings") { i, d ->
                val art = d.artwork
                DrawingView(i, d.piece, d.tick, d.day, d.time, d.item, d.title, d.tags, d.commission, d.shows, art?.palette.orEmpty(), art?.size ?: 0, art?.letters().orEmpty())
            },
        )
    }

    /**
     * The world's recent events, keyed so a key never changes while the event is in the window: by
     * tick, then counted from the end of its tick's group (the window drops events from the front,
     * and a tick's group is whole by the time it's read).
     */
    private fun events(now: WorldSnapshot): List<EventView> {
        val all = now.events
        val out = ArrayList<EventView>(all.size)
        var i = 0
        while (i < all.size) {
            var j = i
            while (j < all.size && all[j].tick == all[i].tick) j++
            for (k in i until j) out += EventView(all[k].tick * 1000 + 999 - (j - 1 - k), all[k].tick, all[k].text, all[k].outcome)
            i = j
        }
        return out.takeLast(Logs.CAPS.getValue("events"))
    }

    private fun <T, V> indexed(all: List<T>, log: String, view: (Long, T) -> V): List<V> {
        val first = all.size - minOf(all.size, Logs.CAPS.getValue(log))
        return all.drop(first).mapIndexed { i, t -> view((first + i).toLong(), t) }
    }

    companion object {
        /** The bedroom's content files' hash, as the run database keeps them. */
        val contentHash: String by lazy { LifeRecorder.content().hash }

        fun settings(r: RunSettings) = Settings(r.days, r.speed, r.ticksPerMinute, r.decideCap, r.quiet, r.quietVerbs.sorted(), r.holdForArt)

        fun runSettings(s: Settings) = RunSettings(
            days = s.days, speed = s.speed, ticksPerMinute = s.ticksPerMinute, decideCap = s.decideCap,
            quiet = s.quiet, quietVerbs = s.quietVerbs.toSet(), holdForArt = s.holdForArt,
        )

        fun intent(i: Intent) = IntentView(
            type = when (i) {
                is Intent.MoveTo -> "move_to"
                is Intent.PickUp -> "pick_up"
                is Intent.Use -> "use"
                is Intent.Say -> "say"
                is Intent.Wait -> "wait"
                is Intent.Do -> "do"
                is Intent.Buy -> "buy"
                Intent.Think -> "think"
            },
            text = Describe.action(i),
            activity = (i as? Intent.Do)?.activity,
            item = when (i) {
                is Intent.Buy -> i.item
                is Intent.Use -> i.itemId
                is Intent.PickUp -> i.itemId
                is Intent.Do -> i.what
                else -> null
            },
        )

        fun status(s: IntentStatus) = when (s) {
            IntentStatus.Submitted -> StatusView("submitted", null)
            IntentStatus.Validated -> StatusView("validated", null)
            IntentStatus.Executing -> StatusView("executing", null)
            IntentStatus.Succeeded -> StatusView("succeeded", null)
            is IntentStatus.Failed -> StatusView("failed", s.reason)
            IntentStatus.Interrupted -> StatusView("interrupted", null)
        }

        fun underway(u: Underway) = UnderwayView(
            u.tick, u.item, u.title, u.verb, u.art.palette, u.strokes, u.of, u.crayon, u.state.name.lowercase(), u.art.size, u.art.letters(),
        )

        private fun thought(seq: Long, t: Thought) = ThoughtView(
            seq, t.tick, t.day, t.time, t.thinker, t.text, t.error, t.asked.map { AnswerView(it.question, it.opened, it.reply.text) },
        )

        private fun memory(m: StoredMemory) = MemoryView(m.id, m.level, m.span.label, m.span.fromDay, m.span.toDay, m.text, m.importance)

        private fun self(m: SelfModel) = SelfModelView(m.who, m.cares, m.believes, m.workingToward, m.learned)

        private fun totals(t: dev.lilguy.agent.TokenTotals) = TokenTotals(t.decides, t.calls, t.promptTokens, t.completionTokens, t.latencyMs, t.costUsd)

        private fun map(now: WorldSnapshot): MapView {
            val m = now.map
            val rows = (0 until m.height).map { y ->
                String(CharArray(m.width) { x ->
                    when (m[x, y]) {
                        Terrain.Floor -> '.'
                        Terrain.Wall -> '#'
                        Terrain.Void -> ' '
                    }
                })
            }
            return MapView(m.width, m.height, rows)
        }

        private fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
