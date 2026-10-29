package dev.lilguy.sim

import kotlinx.serialization.Serializable

@JvmInline
@Serializable
value class EntityId(val value: String)

/**
 * The only door into the simulation. The sim owns its state on one thread; [observe] and
 * [snapshot] return immutable values and [submit] enqueues work for the start of the next tick.
 */
interface World {
    val tick: Long

    /** Advance one fixed tick (20 Hz). */
    fun step()

    /** What this character can perceive; never the whole world. */
    fun observe(agentId: EntityId): Observation

    fun submit(agentId: EntityId, intent: Intent): IntentHandle

    /** Immutable state for rendering and replay. */
    fun snapshot(): WorldSnapshot

    /** Suspends until the character needs a new intent: done, nearly done, or interrupted. */
    suspend fun awaitDecisionPoint(agentId: EntityId)

    companion object {
        const val TICKS_PER_SECOND = 20
    }
}
