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
    data class Event(val tick: Long, val text: String, val outcome: Boolean = false, val place: String? = null)

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
    /** What he has invented, oldest first: put back into the content before the rest is loaded. */
    val inventions: List<Invention> = emptyList(),
    /** Outdoor places he has explored, by id. */
    val explored: List<String> = emptyList(),
    /** Patch to the tick it grows back by, while bare. */
    val bare: Map<String, Long> = emptyMap(),
    /** Lamps he switched on or off: item id to `on` or `off`; the rest are on their timers. */
    val lamps: Map<String, String> = emptyMap(),
    /** Rooms built onto the house: room id to site id. */
    val house: Map<String, String> = emptyMap(),
    /** Letters waiting at the mail hatch, oldest first. */
    val mail: List<Letter> = emptyList(),
    /** The ids of the letters he has opened. */
    val opened: List<Long> = emptyList(),
    /** The letters he has opened, oldest first, for writing back. */
    val read: List<Letter> = emptyList(),
    /** The letters he has written, oldest first. */
    val sent: List<OutLetter> = emptyList(),
    /** The verbs he wrote, oldest first (a rewrite replaces its verb in place): put back after the inventions. */
    val coded: List<CodedVerb> = emptyList(),
    /** What his code stored on things: item id (or `self`) to key to value. */
    @SerialName("thing_state") val thingState: Map<String, Map<String, kotlinx.serialization.json.JsonPrimitive>> = emptyMap(),
    /** What his code changed about his things: where they stand, what they're called, their colour. */
    val custom: Map<String, Custom> = emptyMap(),
    /** What his things do by themselves: item id to its code. */
    val behaviors: Map<String, ThingCode> = emptyMap(),
    /** What his recorders logged: thing id to key to its latest readings, oldest first ([Records]). */
    val records: Map<String, Map<String, List<Reading>>> = emptyMap(),
    /** How his things look by their code, their timers, and what they did where he wasn't ([Machines]). */
    val machines: MachineState = MachineState(),
    /** How familiar each feature of what he does is ([Familiarity]): its level and the tick it was set. */
    val familiar: Map<String, Wear> = emptyMap(),
    /** Item id to the tick he first had it, for getting used to it (comfort `adapt`); null in older saves, where he has had all of it long enough. */
    @SerialName("owned_since") val ownedSince: Map<String, Long>? = null,
    /** What his machines hold and have made, how far into a batch and how worn ([Works]). */
    val works: Map<String, Store> = emptyMap(),
    /** What his code has his things give off, the planters' soil, and when visitors are back ([Surroundings]). */
    val surroundings: SurroundingsState = SurroundingsState(),
    /** The wares selling below their usual price: kind to its demand (1 is usual) and the tick it was set ([Market]). */
    val market: Map<String, Wear> = emptyMap(),
) {
    /** One thing as his code left it: its [slot], [name] and [color], each null when unchanged. */
    @Serializable
    data class Custom(val slot: String? = null, val name: String? = null, val color: String? = null)

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
        /** 2: positions are on the whole map (the lot), no longer the bedroom's own rows. */
        const val VERSION = 2
    }
}
