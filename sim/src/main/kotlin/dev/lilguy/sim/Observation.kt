package dev.lilguy.sim

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
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
    /** Every do() activity: available ones with what they take and give, locked ones with why. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val activities: List<ActivityOption>? = null,
    /** Item-on-item uses that are possible now. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val uses: List<UseOption>? = null,
    /** Everything for sale he doesn't already have. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val shop: List<ShopOption>? = null,
    /** How long since each activity his mood depends on, such as "last journal 20h ago; ...". */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val neglect: String? = null,
)

/** An activity for do(). Exactly one of [locked] and ([takes], [gives]) is set. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ActivityOption(
    val id: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val takes: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val gives: String? = null,
    /** Why it can't be done now, such as "needs an easel ($120)". */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val locked: String? = null,
)

@Serializable
data class UseOption(val itemId: String, val on: String, val takes: String, val gives: String)

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
    /** The mood his mood drifts toward: the baseline plus the room's comfort. */
    val moodTarget: Double,
    /** Why his mood is falling instead of drifting, when a neglect rule is in force. */
    val moodSinking: String? = null,
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

data class WorldEvent(val tick: Long, val text: String)
