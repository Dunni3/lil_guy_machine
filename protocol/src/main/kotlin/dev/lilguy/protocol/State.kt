package dev.lilguy.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One life as every viewer sees it, split by how often each part changes so a [Delta] carries
 * only what did:
 * - [run]: the run and its controls, on a command or when it ends;
 * - [fast]: the clock, needs, positions and the activity under way, every publish while he moves;
 * - [easel]: the piece under way, as far as the screen has got, on every stroke;
 * - the slow sections ([wallet], [growth], [gallery], [shop], [mind], [tokens]), when they change;
 * - [logs]: what only grows (money, news, decisions, thoughts, memories, trips, art), as new entries.
 * The static part is [Content], keyed by [content].
 */
@Serializable
data class LifeState(
    val life: String,
    val seq: Long,
    /** The [Content.hash] this life's static content is under. */
    val content: String,
    val run: RunInfo,
    val fast: FastState,
    val easel: Easel,
    val wallet: Wallet,
    val growth: Growth,
    val gallery: Gallery,
    val shop: ShopState,
    val mind: Mind,
    val tokens: Tokens,
    val logs: Logs,
) {
    /** This state with [d] applied; [d] must be for this life and run, the next in sequence. */
    fun apply(d: Delta): LifeState {
        require(d.life == life) { "a delta for ${d.life} applied to $life" }
        require(d.seq == seq + 1) { "delta ${d.seq} after $seq: a message was missed; rejoin for a snapshot" }
        return copy(
            seq = d.seq,
            run = d.run ?: run,
            fast = d.fast ?: fast,
            easel = d.easel ?: easel,
            wallet = d.wallet ?: wallet,
            growth = d.growth ?: growth,
            gallery = d.gallery ?: gallery,
            shop = d.shop ?: shop,
            mind = d.mind ?: mind,
            tokens = d.tokens ?: tokens,
            logs = d.logs?.let(logs::merge) ?: logs,
        )
    }

    /** The delta that takes [this] to [next] (the same life and run), or null when nothing changed. */
    fun diff(next: LifeState): Delta? {
        val logs = logs.since(next.logs)
        val d = Delta(
            life = next.life,
            seq = seq + 1,
            run = next.run.takeIf { it != run },
            fast = next.fast.takeIf { it != fast },
            easel = next.easel.takeIf { it != easel },
            wallet = next.wallet.takeIf { it != wallet },
            growth = next.growth.takeIf { it != growth },
            gallery = next.gallery.takeIf { it != gallery },
            shop = next.shop.takeIf { it != shop },
            mind = next.mind.takeIf { it != mind },
            tokens = next.tokens.takeIf { it != tokens },
            logs = logs.takeUnless { it.empty },
        )
        return d.takeUnless { it == Delta(next.life, seq + 1) }
    }
}

@Serializable
enum class RunState {
    @SerialName("ready") Ready,
    @SerialName("running") Running,
    @SerialName("paused") Paused,
    @SerialName("ended") Ended,
}

/** A life's run knobs. Presentation timing is here, not with a viewer: every viewer shares the clock. */
@Serializable
data class Settings(
    /** Bedroom: game days a run lasts; null for no end. */
    val days: Int?,
    /** Game minutes per real second. */
    val speed: Int,
    /** Clock resolution, ticks per game minute; takes effect on the next run. */
    @SerialName("ticks_per_minute") val ticksPerMinute: Int,
    /** Game minutes the clock may run while a decision is out; 0 holds at once, null never holds. */
    @SerialName("decide_cap") val decideCap: Int?,
    /** How many times faster the clock runs through [quietVerbs]; 1 for not at all. */
    val quiet: Int,
    @SerialName("quiet_verbs") val quietVerbs: List<String>,
    /** Hold the clock while a finished piece is still going on screen. */
    @SerialName("hold_for_art") val holdForArt: Boolean,
)

@Serializable
data class RunInfo(
    @SerialName("run_id") val runId: String,
    /** Runs since the host started this life, the first 1. */
    val run: Int,
    val scenario: String,
    val state: RunState,
    val settings: Settings,
    /** The settings each run since the host started began with, oldest first: what [Command.Replay] picks from. */
    val starts: List<Settings>,
    @SerialName("ticks_per_minute") val ticksPerMinute: Int,
    /** The tick the run ends on, or null if it doesn't end on its own. */
    @SerialName("end_tick") val endTick: Long?,
    /** The fewest days [Settings.days] can be set to now. */
    @SerialName("min_days") val minDays: Int,
    @SerialName("ended_because") val endedBecause: String?,
    /** Why the configured models aren't all in use, and the latest failures of the memory and writer models. */
    val notices: List<String>,
    val models: Models,
)

/** Which model does what this run; null for nothing. */
@Serializable
data class Models(
    val decision: String,
    val memory: String?,
    val thoughts: String?,
    val self: String?,
    val scorer: String?,
    val artist: String?,
)

/** What changes every tick: draw from this, interpolating between two at [tick] and the one before. */
@Serializable
data class FastState(
    val tick: Long,
    /** Null in rooms without a clock. */
    val clock: Clock?,
    val needs: Needs?,
    /** The activity under way (after any walk to it), and how far through it is, 0..1. */
    val doing: String?,
    val progress: Float?,
    val music: String?,
    val entities: List<Entity>,
    val agents: List<AgentView>,
    /** True once the key-and-door character has walked out. */
    val completed: Boolean,
    /** The clock is held: [heldFor] says on what ("decision", "art", or an authored op such as "draw"). */
    val held: Boolean,
    @SerialName("held_for") val heldFor: List<String>,
    /** How many times faster than [Settings.speed] the clock runs now (a quiet stretch). */
    @SerialName("fast_forward") val fastForward: Int,
    /** Real milliseconds the model has been deciding, or null when it isn't. */
    @SerialName("thinking_ms") val thinkingMs: Long?,
    /** Real seconds into [Easel.phase], so a renderer that joins part way through a stroke starts where the host is. */
    @SerialName("easel_time") val easelTime: Float,
)

@Serializable
data class Clock(val day: Int, val time: String)

/** 0 to 100. [moodTarget] is what mood drifts toward. */
@Serializable
data class Needs(
    val energy: Double,
    val fullness: Double,
    val mood: Double,
    val stimulation: Double,
    val frustration: Double,
    @SerialName("mood_target") val moodTarget: Double,
)

/** Grid space: tile (x, y) spans [x, x+1) by [y, y+1). [carriedBy] is set while it's in someone's inventory (not on the map). */
@Serializable
data class Entity(
    val id: String,
    val kind: String,
    val x: Float,
    val y: Float,
    val height: Float,
    @SerialName("facing_degrees") val facingDegrees: Float,
    val state: String?,
    @SerialName("carried_by") val carriedBy: String?,
)

/** What a character is doing: its intent and how far along, the walk left, and what it's saying. */
@Serializable
data class AgentView(
    val id: String,
    val intent: IntentView?,
    val status: StatusView?,
    val inventory: List<String>,
    val path: List<CellView>,
    val saying: String?,
)

/**
 * An intent as screens need it: [type] is its wire name (move_to, do, buy, ...), [text] it in
 * words, and the fields a screen reacts to ([activity], [item]: the thing bought or used).
 */
@Serializable
data class IntentView(val type: String, val text: String, val activity: String?, val item: String?)

/** submitted, validated, executing, succeeded, failed (with [reason]) or interrupted. */
@Serializable
data class StatusView(val state: String, val reason: String?)

@Serializable
data class CellView(val x: Int, val y: Int)

/**
 * The piece under way as every screen should show it right now ([underway], the picture after the
 * stroke going on, if one is), and what the host is doing with it: [phase] is ponder, dip, stroke,
 * flourish or admire, lasting [phaseSeconds] real seconds (0 for one that waits on the model). In
 * a stroke, the cells that differ from the picture before go on over [phaseSeconds].
 */
@Serializable
data class Easel(
    val underway: UnderwayView?,
    val phase: String,
    @SerialName("phase_seconds") val phaseSeconds: Float,
    /** Strokes wholly on screen, and how many the model has drawn so far. */
    val shown: Int,
    val drawn: Int,
)

/**
 * [rows] is the picture, one string per row, one letter per cell: a for [palette] 0 (the paper), b
 * for 1, and so on; colours are 0xRRGGBB. [state] is drawing, done or dropped. [tick] says which piece.
 */
@Serializable
data class UnderwayView(
    val tick: Long,
    val item: String,
    val title: String,
    val verb: String?,
    val palette: List<Int>,
    val strokes: Int,
    val of: Int,
    val crayon: Int?,
    val state: String,
    val size: Int,
    val rows: List<String>,
)

@Serializable
data class Wallet(val money: Int, val holding: List<String>, val owns: Map<String, Int>)

@Serializable
data class Growth(val skills: Map<String, Double>, val books: List<BookView>, val done: Map<String, Tally>)

@Serializable
data class BookView(val id: String, val title: String, val read: Int, val chapters: Int, val raises: List<String>?, val teaches: String?)

/** How often he has finished a verb this run, and when he first did. */
@Serializable
data class Tally(val count: Int, @SerialName("first_tick") val firstTick: Long)

@Serializable
data class Gallery(val art: List<PieceView>, val commission: CommissionView?)

@Serializable
data class PieceView(val id: Int, val item: String, val title: String?, val hung: Boolean, @SerialName("sells_for") val sellsFor: Int?, val commission: String?)

@Serializable
data class CommissionView(val id: Int, val wants: String, val request: String?, val reward: Int, val until: String)

/** What changes about the catalog and the verbs as he lives; the rest of them is in [Content]. */
@Serializable
data class ShopState(val catalog: List<CatalogState>, val verbs: List<VerbState>)

@Serializable
data class CatalogState(val id: String, val owned: Int, val affordable: Boolean, val gives: String, @SerialName("taught_by") val taughtBy: String?)

@Serializable
data class VerbState(val id: String, val locked: String?, val gives: String?, @SerialName("taught_by") val taughtBy: String?)

/** His mind as the prompts show it: the latest kept thought as his plan, and how he sees himself. */
@Serializable
data class Mind(
    val plan: ThoughtView?,
    /** The self-model as the prompts show it (the seed before the first revision), or null when nothing revises it. */
    @SerialName("self_text") val selfText: String?,
    @SerialName("self_model") val selfModel: SelfModelView?,
    /** True while a memory, day memory or self-model is being written. */
    val summarizing: Boolean,
)

@Serializable
data class SelfModelView(
    val who: String,
    val cares: String,
    val believes: String,
    @SerialName("working_toward") val workingToward: String,
    val learned: String,
)

/** Tokens and cost so far, by purpose (decision, think, summary, self, recall, art, ...) and by game day. */
@Serializable
data class Tokens(
    val total: TokenTotals,
    @SerialName("by_purpose") val byPurpose: Map<String, TokenTotals>,
    val days: List<TokenDay>,
)

@Serializable
data class TokenTotals(
    val decides: Int,
    val calls: Int,
    @SerialName("prompt_tokens") val promptTokens: Int,
    @SerialName("completion_tokens") val completionTokens: Int,
    @SerialName("latency_ms") val latencyMs: Long,
    @SerialName("cost_usd") val costUsd: Double?,
)

@Serializable
data class TokenDay(val day: Int, val total: TokenTotals, @SerialName("by_purpose") val byPurpose: Map<String, TokenTotals>)

/**
 * What only grows, each list oldest first and keyed by `seq` (increasing): a delta carries the
 * entries new or changed, [merge] puts them in, and a client keeps the newest [CAPS] of each, as
 * the host's snapshot does.
 */
@Serializable
data class Logs(
    val transactions: List<TransactionView> = emptyList(),
    /** What happened around him, in the sim's words. */
    val events: List<EventView> = emptyList(),
    val decisions: List<DecisionView> = emptyList(),
    /** Every thought, failed ones included, with what he asked while thinking. */
    val thoughts: List<ThoughtView> = emptyList(),
    /** Level 1 (stretches) and level 2 (days); seq is the memory's id. */
    val memories: List<MemoryView> = emptyList(),
    /** Every nightly revision of his self-model, changed or not. */
    val revisions: List<RevisionView> = emptyList(),
    /** Shopping trips, failed ones included. */
    val trips: List<TripView> = emptyList(),
    /** Pieces drawn and kept, with the picture. */
    val drawings: List<DrawingView> = emptyList(),
) {
    val empty: Boolean get() = this == Logs()

    /** [delta]'s entries put in by seq, each list cut to its newest [CAPS]. */
    fun merge(delta: Logs) = Logs(
        transactions = merge(transactions, delta.transactions, CAPS.getValue("transactions")) { it.seq },
        events = merge(events, delta.events, CAPS.getValue("events")) { it.seq },
        decisions = merge(decisions, delta.decisions, CAPS.getValue("decisions")) { it.seq },
        thoughts = merge(thoughts, delta.thoughts, CAPS.getValue("thoughts")) { it.seq },
        memories = merge(memories, delta.memories, CAPS.getValue("memories")) { it.seq },
        revisions = merge(revisions, delta.revisions, CAPS.getValue("revisions")) { it.seq },
        trips = merge(trips, delta.trips, CAPS.getValue("trips")) { it.seq },
        drawings = merge(drawings, delta.drawings, CAPS.getValue("drawings")) { it.seq },
    )

    /** The entries of [next] that aren't in this, or differ. */
    fun since(next: Logs) = Logs(
        transactions = since(transactions, next.transactions) { it.seq },
        events = since(events, next.events) { it.seq },
        decisions = since(decisions, next.decisions) { it.seq },
        thoughts = since(thoughts, next.thoughts) { it.seq },
        memories = since(memories, next.memories) { it.seq },
        revisions = since(revisions, next.revisions) { it.seq },
        trips = since(trips, next.trips) { it.seq },
        drawings = since(drawings, next.drawings) { it.seq },
    )

    companion object {
        /** The newest entries of each log a snapshot carries and a client keeps. */
        val CAPS = mapOf(
            "transactions" to 200, "events" to 50, "decisions" to 100, "thoughts" to 300,
            "memories" to 1000, "revisions" to 100, "trips" to 100, "drawings" to 100,
        )

        private fun <T> merge(have: List<T>, add: List<T>, cap: Int, seq: (T) -> Long): List<T> {
            if (add.isEmpty()) return have
            val bySeq = LinkedHashMap<Long, T>()
            have.forEach { bySeq[seq(it)] = it }
            add.forEach { bySeq[seq(it)] = it }
            return bySeq.values.sortedBy(seq).takeLast(cap)
        }

        private fun <T> since(have: List<T>, next: List<T>, seq: (T) -> Long): List<T> {
            if (have == next) return emptyList()
            val old = have.associateBy(seq)
            return next.filter { old[seq(it)] != it }
        }
    }
}

@Serializable
data class TransactionView(val seq: Long, val tick: Long, val day: Int, val time: String, val amount: Int, val balance: Int, val reason: String)

/** [outcome]: it says how an intent ended, rather than what happened around him. */
@Serializable
data class EventView(val seq: Long, val tick: Long, val text: String, val outcome: Boolean)

@Serializable
data class DecisionView(
    val seq: Long,
    val tick: Long,
    val intent: IntentView,
    val rationale: String?,
    /** Set when the model failed and the intent came from the fallback. */
    val error: String?,
    @SerialName("latency_ms") val latencyMs: Long,
)

@Serializable
data class ThoughtView(
    val seq: Long,
    val tick: Long,
    val day: Int?,
    val time: String?,
    val thinker: String,
    /** Null when the model failed ([error]). */
    val text: String?,
    val error: String?,
    val asked: List<AnswerView>,
)

/** One question he asked the oracle while thinking: the doc sections it [opened], and its answer (null when it failed). */
@Serializable
data class AnswerView(val question: String, val opened: List<String>, val answer: String?)

@Serializable
data class MemoryView(
    val seq: Long,
    val level: Int,
    /** Such as "Day 2 09:10-13:40". */
    val span: String,
    @SerialName("from_day") val fromDay: Int?,
    @SerialName("to_day") val toDay: Int?,
    val text: String,
    val importance: Double?,
)

/** One night's revision; [self] is the new version when it [changed]. */
@Serializable
data class RevisionView(val seq: Long, val day: Int?, val changed: Boolean, val note: String?, val self: SelfModelView?, val error: String?)

@Serializable
data class TripView(
    val seq: Long,
    val tick: Long,
    val day: Int?,
    val time: String?,
    val shopper: String,
    /** What he was shown, a line an item. */
    val catalog: List<String>,
    val buy: List<PickView>,
    @SerialName("save_for") val saveFor: String?,
    @SerialName("chose_saving") val choseSaving: Boolean,
    val note: String?,
    val error: String?,
    @SerialName("fell_back") val fellBack: String?,
)

@Serializable
data class PickView(val item: String, val why: String?)

/** A kept piece; [rows] and [palette] as in [UnderwayView]. */
@Serializable
data class DrawingView(
    val seq: Long,
    val piece: Int?,
    val tick: Long,
    val day: Int?,
    val time: String?,
    val item: String,
    val title: String?,
    val tags: List<String>,
    val commission: String?,
    val shows: String?,
    val palette: List<Int>,
    val size: Int,
    val rows: List<String>,
)
