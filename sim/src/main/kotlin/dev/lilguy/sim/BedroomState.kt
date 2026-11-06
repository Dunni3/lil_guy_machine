package dev.lilguy.sim

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What [GridWorld] holds of a world at rest: the clock, where he stands, the recent events his
 * prompts show, and how his last intent ended (which is what the next observation reports).
 */
@Serializable
data class GridState(
    val tick: Long,
    val x: Float,
    val y: Float,
    val facing: Direction,
    val events: List<Event>,
    val last: Last?,
    val saying: String?,
    @SerialName("say_until") val sayUntil: Long,
) {
    @Serializable
    data class Event(val tick: Long, val text: String, val outcome: Boolean = false)

    @Serializable
    data class Last(val intent: Intent, val status: IntentStatus?, val outcome: String?)
}

/**
 * A bedroom saved at a moment nothing is in flight ([BedroomWorld.exportState]): everything that
 * isn't rebuilt from the content. Caches (options, the known-content copy) rebuild; the authored,
 * delivery and staging queues are empty by rule when a checkpoint is taken. [version] lets an old
 * checkpoint be migrated or refused with a reason.
 */
@Serializable
data class BedroomState(
    val version: Int = VERSION,
    val grid: GridState,
    val money: Int,
    /** Need id to its exact level, 0 to 100. */
    val needs: Map<String, Double>,
    /** Rate changes since the last hourly flush, not yet logged as need changes. */
    @SerialName("needs_pending") val needsPending: List<PendingNeed>,
    val owned: Map<String, Int>,
    val skills: Map<String, Double>,
    val chapters: Map<String, Int>,
    val known: List<String>,
    /** Habit key to its wear level and the tick it was set. */
    val wear: Map<String, Wear>,
    val tried: List<String>,
    /** Item id to the tick he bought it, while it still feels new. */
    @SerialName("new_things") val newThings: Map<String, Long>,
    val done: Map<String, VerbTally>,
    val music: Music?,
    @SerialName("saving_for") val savingFor: String?,
    val pieces: List<Piece>,
    val commission: Commission?,
    /** Commissions so far: each one's random is seeded from the world's seed and this count, so it's all the randomness to keep. */
    val commissions: Int,
    val authored: List<AuthoredView>,
    @SerialName("authored_count") val authoredCount: Int,
) {
    @Serializable
    data class PendingNeed(val need: String, val reason: String, val delta: Double)

    @Serializable
    data class Wear(val level: Double, val tick: Long)

    /** [op]: the name of the `background` op that plays it. */
    @Serializable
    data class Music(val item: String, val on: String?, val op: String, @SerialName("end_tick") val endTick: Long, val fresh: Double)

    @Serializable
    data class Piece(
        val id: Int,
        val item: String,
        val commission: Int?,
        val skill: Double,
        /** held, hung or sold. */
        val state: String,
        val title: String?,
        val tags: List<String>,
        val shows: String?,
    )

    @Serializable
    data class Commission(
        val id: Int,
        val adjective: String,
        val noun: String,
        val wants: String,
        val until: Long,
        val request: String?,
        val fulfilled: Boolean,
    )

    companion object {
        const val VERSION = 1
    }
}
