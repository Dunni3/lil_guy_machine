package dev.lilguy.sim

import kotlinx.serialization.Serializable

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
)

@Serializable
enum class Direction { N, NE, E, SE, S, SW, W, NW }

/** Immutable world state; the renderer interpolates between two of these. */
data class WorldSnapshot(
    val tick: Long,
    val entities: List<EntitySnapshot>,
)

data class EntitySnapshot(
    val id: EntityId,
    val kind: String,
    val x: Float,
    val y: Float,
    val height: Float,
    val facingDegrees: Float,
)
