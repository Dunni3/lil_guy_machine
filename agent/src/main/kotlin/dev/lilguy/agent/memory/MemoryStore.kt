package dev.lilguy.agent.memory

/**
 * The long-term memory's storage, kept apart from what is stored so :agent stays free of any
 * database. SQLite lives in `:memory-sqlite`; [InMemoryStore] backs tests and the TV until it
 * has a database of its own.
 *
 * Memory is a hierarchy. Level 0 is the event stream: what he was shown and what he did, as
 * free text. Level 1 memories summarize a run of events, level 2 would summarize level 1, and so
 * on. Everything is scoped to a [run] (one life), so a fresh world starts with a clean past.
 */
interface MemoryStore : AutoCloseable {
    /** Appends to the event stream and returns the new event's id; ids grow with time. */
    fun appendEvent(run: String, tick: Long, day: Int?, time: String?, kind: EventKind, text: String): Long

    /** Up to [limit] events with an id above [afterId], oldest first. */
    fun eventsAfter(run: String, afterId: Long, limit: Int): List<MemoryEvent>

    fun countEventsAfter(run: String, afterId: Long): Int

    /** Stores a summary of the level-below items `fromId..toId` (event ids for level 1). */
    fun addMemory(run: String, level: Int, fromId: Long, toId: Long, span: Span, text: String, source: String): Long

    /** Memories of one level, oldest first. */
    fun memories(run: String, level: Int): List<StoredMemory>

    /** The highest id the memories of [level] already cover, or 0: where the next summary starts. */
    fun coveredThrough(run: String, level: Int): Long = memories(run, level).maxOfOrNull { it.toId } ?: 0L
}

enum class EventKind(val id: String) {
    /** The system state the agent was shown at a decision point. */
    Observation("observation"),

    /** What the agent chose to do. */
    Action("action");

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
    override fun addMemory(run: String, level: Int, fromId: Long, toId: Long, span: Span, text: String, source: String): Long {
        val id = memories.size + 1L
        memories += StoredMemory(id, run, level, fromId, toId, span, text, source)
        return id
    }

    @Synchronized
    override fun memories(run: String, level: Int) = memories.filter { it.run == run && it.level == level }

    override fun close() {}
}
