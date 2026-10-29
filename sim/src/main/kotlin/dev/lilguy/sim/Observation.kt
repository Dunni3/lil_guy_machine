package dev.lilguy.sim

import kotlinx.serialization.Serializable
import kotlin.math.atan2
import kotlin.math.roundToInt

/** One character's view of the world, rendered as JSON for typed prompts and logs. */
@Serializable
data class Observation(
    val tick: Long,
    val self: SelfState,
    val nearby: List<SeenEntity>,
    /** Small local map, one string per row, centered on the character. */
    val localMap: List<String>,
    val lastIntent: Intent?,
    val lastResult: IntentStatus?,
    val recentEvents: List<String>,
)

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
    val direction: Direction,
    val distance: Float,
    /** Kind-specific state, such as "locked" or "open" for a door. */
    val state: String? = null,
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
