package dev.lilguy.agent.memory

/**
 * The long-term memory's storage, kept apart from what is stored so :agent stays free of any
 * database. SQLite lives in `:memory-sqlite`; [InMemoryStore] backs tests and the TV until it
 * has a database of its own.
 *
 * Memory is a hierarchy. Level 0 is the event stream: how each thing he did turned out, what
 * happened around him, his thoughts and his art, as free text. Level 1 memories summarize a run of
 * events, level 2 summarizes a day of level-1 memories, and level 3 is his self-model, revised
 * from the day summaries ([SelfModeler]). Everything is scoped to a [run] (one life), so a fresh
 * world starts with a clean past.
 */
interface MemoryStore : AutoCloseable {
    /** Appends to the event stream and returns the new event's id; ids grow with time. */
    fun appendEvent(run: String, tick: Long, day: Int?, time: String?, kind: EventKind, text: String): Long

    /** Up to [limit] events with an id above [afterId], oldest first. */
    fun eventsAfter(run: String, afterId: Long, limit: Int): List<MemoryEvent>

    fun countEventsAfter(run: String, afterId: Long): Int

    /** Records how much event [id] matters to him, 0 (routine) to 1 (it changes his life), once it's scored. */
    fun setSignificance(run: String, id: Long, significance: Double)

    /**
     * Stores a summary of the level-below items `fromId..toId` (event ids for level 1, level-1 ids
     * for level 2, level-2 ids for level 3). [importance] is 0 to 1, from the events it covers.
     */
    fun addMemory(run: String, level: Int, fromId: Long, toId: Long, span: Span, text: String, source: String, importance: Double? = null): Long

    /** Memories of one level, oldest first. */
    fun memories(run: String, level: Int): List<StoredMemory>

    /** The highest id the memories of [level] already cover, or 0: where the next summary starts. */
    fun coveredThrough(run: String, level: Int): Long = memories(run, level).maxOfOrNull { it.toId } ?: 0L
}

enum class EventKind(val id: String) {
    /** How something he did turned out: what it gave and cost, or why it failed. */
    Outcome("outcome"),

    /** Something that happened around him: a delivery, a request coming in, a new day. */
    World("world"),

    /** How things stood (money, needs, what he owns), once a day when he wakes. */
    Snapshot("snapshot"),

    /** What he thought when he stopped to think, in his own words. */
    Thought("thought"),

    /** He finished a piece of art: its title and what it shows; the picture is a file beside the run. */
    Art("art");

    companion object {
        fun of(id: String): EventKind = entries.first { it.id == id }
    }
}

data class MemoryEvent(
    val id: Long,
    val run: String,
    val tick: Long,
    val day: Int?,
    val time: String?,
    val kind: EventKind,
    val text: String,
    /** 0 to 1, or null until it's scored (or when nothing scores). */
    val significance: Double? = null,
) {
    /** "d2 14:30", or "t=140" in rooms without a clock. */
    val stamp: String get() = if (day != null) "d$day $time" else "t=$tick"
}

/** When a memory's events happened: game day and time at the first and last event. */
data class Span(val fromDay: Int?, val fromTime: String?, val toDay: Int?, val toTime: String?) {
    val label: String
        get() = when {
            fromDay == null -> ""
            fromDay == toDay && fromTime == toTime -> "Day $fromDay $fromTime"
            fromDay == toDay -> "Day $fromDay $fromTime-$toTime"
            else -> "Day $fromDay $fromTime to day $toDay $toTime"
        }
}

data class StoredMemory(
    val id: Long,
    val run: String,
    val level: Int,
    val fromId: Long,
    val toId: Long,
    val span: Span,
    val text: String,
    /** What wrote it, such as the summarizing model's id. */
    val source: String,
    /** 0 to 1, from the significance of what it covers; null when nothing was scored. */
    val importance: Double? = null,
)

/** Everything in memory, for tests and for platforms without a database yet. */
class InMemoryStore : MemoryStore {
    private val events = mutableListOf<MemoryEvent>()
    private val memories = mutableListOf<StoredMemory>()

    @Synchronized
    override fun appendEvent(run: String, tick: Long, day: Int?, time: String?, kind: EventKind, text: String): Long {
        val id = events.size + 1L
        events += MemoryEvent(id, run, tick, day, time, kind, text)
        return id
    }

    @Synchronized
    override fun eventsAfter(run: String, afterId: Long, limit: Int) =
        events.filter { it.run == run && it.id > afterId }.take(limit)

    @Synchronized
    override fun countEventsAfter(run: String, afterId: Long) = events.count { it.run == run && it.id > afterId }

    @Synchronized
    override fun setSignificance(run: String, id: Long, significance: Double) {
        val i = events.indexOfFirst { it.run == run && it.id == id }
        if (i >= 0) events[i] = events[i].copy(significance = significance)
    }

    @Synchronized
    override fun addMemory(run: String, level: Int, fromId: Long, toId: Long, span: Span, text: String, source: String, importance: Double?): Long {
        val id = memories.size + 1L
        memories += StoredMemory(id, run, level, fromId, toId, span, text, source, importance)
        return id
    }

    @Synchronized
    override fun memories(run: String, level: Int) = memories.filter { it.run == run && it.level == level }

    override fun close() {}
}
