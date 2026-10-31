package dev.lilguy.sim

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * One character's view of the world, rendered as JSON for typed prompts and logs. The fields
 * after [recentEvents] are the bedroom's life state; rooms without one leave them null, and null
 * ones are left out of the JSON, so the key-and-door observation reads as it did.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Observation(
    val tick: Long,
    val self: SelfState,
    val nearby: List<SeenEntity>,
    /** Small local map, one string per row, centered on the character (the bedroom shows all of it). */
    val localMap: List<String>,
    val lastIntent: Intent?,
    val lastResult: IntentStatus?,
    val recentEvents: List<String>,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val clock: ClockReading? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val money: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val needs: NeedLevels? = null,
    /** Carried items, one entry each: art waiting to be sold. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val holding: List<String>? = null,
    /** Everything else he owns, item id to count. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val owns: Map<String, Int>? = null,
    /** What's playing in the background, such as "jazz_record, 32 min left". */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val music: String? = null,
    /**
     * Every do() verb: available ones with what they take and give, one per way to fill their
     * arguments (`what`, `on`) with things he owns; locked ones once, with why.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val activities: List<ActivityOption>? = null,
    /** Everything for sale he doesn't already have. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val shop: List<ShopOption>? = null,
    /** Today's request for a piece, if one is open. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val commission: CommissionView? = null,
    /** The art he has made and still has, held or hung, oldest first. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val art: List<PieceView>? = null,
    /** His skills, name to level (0 up, one decimal). */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val skills: Map<String, Double>? = null,
    /** The books he owns and how far he has read. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val books: List<BookView>? = null,
    /** Authored ops waiting for (or given) their results, for the agent layer; never shown to models. */
    @Transient val authored: List<AuthoredView> = emptyList(),
    /**
     * How [lastIntent] ended, in the sim's words: what its ops did when it finished, a purchase, or
     * why it failed. Null while it runs or when nothing was said (a wait). For memory; models read
     * [lastResult] and [recentEvents].
     */
    @Transient val outcome: String? = null,
    /** [recentEvents] without the outcomes: deliveries, requests, the day changing. For memory. */
    @Transient val news: List<String> = emptyList(),
)

/**
 * A request for a piece: [wants] is the adjective and noun ("a sleepy lighthouse"), [request] the
 * customer's words once a model has written them. Open until [until] ("d2 09:00").
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CommissionView(
    val id: Int,
    val wants: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val request: String? = null,
    val reward: Int,
    val until: String,
)

/**
 * A piece of art he made and hasn't sold: [hung] on the wall, or held to sell for [sellsFor].
 * [title] is null until it has been drawn (or when drawing failed). [commission] says how it
 * stands against the open request, if it was made for one.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PieceView(
    val id: Int,
    val item: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val title: String? = null,
    val hung: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val sellsFor: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val commission: String? = null,
)

/**
 * A book he owns: [read] of [chapters] chapters so far. [raises]: the skills studying it raises.
 * [teaches]: what finishing it would teach him, while there's some left to learn. [next]: the
 * next chapter's text, for the agent layer (what he reads next); never shown to models.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class BookView(
    val id: String,
    val title: String,
    val read: Int,
    val chapters: Int,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val raises: List<String>? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val teaches: String? = null,
    @Transient val next: String? = null,
)

/**
 * A verb for do(), with the items its arguments are bound to ([what], [on]) when it has any.
 * Exactly one of [locked] and ([takes], [gives]) is set.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ActivityOption(
    val id: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val what: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val on: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val takes: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val gives: String? = null,
    /** Why it can't be done now, such as "needs an easel ($120)". */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val locked: String? = null,
)

@Serializable
data class ShopOption(val id: String, val price: Int, val affordable: Boolean, val gives: String)

@Serializable
data class SelfState(
    val id: EntityId,
    val x: Float,
    val y: Float,
    val facing: Direction,
    val inventory: List<String>,
)

@Serializable
data class SeenEntity(
    val id: String,
    val kind: String,
    /** The tile it stands on, so a model can target tiles around it without arithmetic. */
    val x: Int,
    val y: Int,
    val direction: Direction,
    val distance: Float,
    /** Kind-specific state, such as "locked" or "open" for a door. */
    val state: String? = null,
    /** On or beside your tile, so pick_up and use can reach it. Computed so models needn't. */
    val inReach: Boolean = false,
)

/** Compass directions; north is toward row 0. */
@Serializable
enum class Direction(val degrees: Float) {
    N(90f), NE(45f), E(0f), SE(315f), S(270f), SW(225f), W(180f), NW(135f);

    companion object {
        private val BY_SECTOR = arrayOf(E, NE, N, NW, W, SW, S, SE)

        /** Nearest compass direction of the vector (dx, dy) in grid space (y grows south). */
        fun of(dx: Float, dy: Float): Direction {
            val degrees = Math.toDegrees(atan2(-dy.toDouble(), dx.toDouble()))
            return BY_SECTOR[Math.floorMod((degrees / 45.0).roundToInt(), 8)]
        }
    }
}

/** Immutable world state; the renderer interpolates between two of these. */
data class WorldSnapshot(
    val tick: Long,
    val map: TileMap,
    val entities: List<EntitySnapshot>,
    val agents: List<AgentSnapshot> = emptyList(),
    /** Oldest first. */
    val events: List<WorldEvent> = emptyList(),
    /** True once the character has walked out of the room. */
    val completed: Boolean = false,
    /** The bedroom's clock, money and needs; null in rooms without them. */
    val life: LifeSnapshot? = null,
)

/** Clock, money and needs for HUDs, at full precision. */
data class LifeSnapshot(
    val clock: ClockReading,
    val money: Int,
    val energy: Double,
    val fullness: Double,
    val mood: Double,
    /** Low is bored. */
    val stimulation: Double,
    /** High is frustrated. */
    val frustration: Double = 0.0,
    /** Skill name to level. */
    val skills: Map<String, Double> = emptyMap(),
    /** The books he owns and how far he has read. */
    val books: List<BookView> = emptyList(),
    /** The mood his mood drifts toward: the baseline plus the room's comfort, less boredom and frustration. */
    val moodTarget: Double,
    /** The activity under way (after any walk to it), and how far through it is, 0..1. */
    val doing: String?,
    val progress: Float?,
    val music: String?,
    val holding: List<String>,
    val owns: Map<String, Int>,
    /** Newest last. */
    val transactions: List<Transaction>,
    /** The clock that turns this world's ticks into game time, for stamping events. */
    val clockScale: GameClock,
    val commission: CommissionView? = null,
    /** Held and hung art, oldest first. */
    val art: List<PieceView> = emptyList(),
)

data class EntitySnapshot(
    val id: EntityId,
    val kind: String,
    /** Grid space: tile (x, y) spans [x, x+1) by [y, y+1). */
    val x: Float,
    val y: Float,
    val height: Float,
    val facingDegrees: Float,
    val state: String? = null,
    /** Set while the entity is in someone's inventory; such entities aren't on the map. */
    val carriedBy: EntityId? = null,
)

/** What a character's controller is doing, for HUDs and debug views. */
data class AgentSnapshot(
    val id: EntityId,
    val intent: Intent?,
    val status: IntentStatus?,
    val inventory: List<String>,
    /** Remaining waypoints of the current move, nearest first. */
    val path: List<Cell>,
    /** Speech bubble text while a Say is showing. */
    val saying: String?,
)

/** [outcome]: it says how an intent ended (a finish, a purchase, a failure) rather than what happened around him. */
data class WorldEvent(val tick: Long, val text: String, val outcome: Boolean = false)
