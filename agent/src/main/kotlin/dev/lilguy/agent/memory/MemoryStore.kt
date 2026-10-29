package dev.lilguy.agent.memory

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The long-term memory's storage, kept apart from what is stored so :agent stays free of any
 * database. SQLite lives in `:memory-sqlite`; [InMemoryStore] backs tests and the TV until it
 * has a database of its own.
 *
 * Memory is a hierarchy. Level 0 is the event stream: what he was shown and what he did, as
 * free text. Level 1 memories summarize a run of events, level 2 would summarize level 1, and so
 * on. Everything is scoped to a [run] (one life), so a fresh world starts with a clean past.
 *
 * The journal sits beside the hierarchy: entries he wrote himself, with the wants he argued for.
 * Its full text stays out of the event stream (only "I wrote in my journal" goes there), so
 * memories don't paraphrase his entries back to him.
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

    /** Stores a journal entry; [day] and [time] are when he sat down to write. */
    fun addJournal(run: String, tick: Long, day: Int?, time: String?, text: String, wants: List<Want>, source: String): Long

    /** Journal entries, oldest first. */
    fun journal(run: String): List<JournalEntry>

    /**
     * Up to [limit] items from one [source] that share words with [query], best first (newest
     * first on ties), scored by [Keywords]. Stores may narrow the candidates first, but must rank
     * the same way.
     */
    fun search(run: String, source: SearchSource, query: String, limit: Int = 5): List<SearchHit> = when (source) {
        SearchSource.Memories -> Keywords.rank(memories(run, 1), query, limit, { it.text }, { it.id }).map { (m, n) -> SearchHit(source, m.id, m.span.label, m.text, n) }
        SearchSource.Events -> Keywords.rank(eventsAfter(run, 0, Int.MAX_VALUE), query, limit, { it.text }, { it.id }).map { (e, n) -> SearchHit(source, e.id, e.stamp, e.text, n) }
        SearchSource.Journal -> Keywords.rank(journal(run), query, limit, { it.searchText }, { it.id }).map { (j, n) -> SearchHit(source, j.id, j.label, j.render(withLabel = false), n) }
    }
}

/** Where a journal search looks: his memories, the raw event stream, or his own past entries. */
@Serializable
enum class SearchSource(val id: String) {
    @SerialName("memories") Memories("memories"),
    @SerialName("events") Events("events"),
    @SerialName("journal") Journal("journal"),
}

data class SearchHit(val source: SearchSource, val id: Long, val stamp: String, val text: String, val score: Int)

/**
 * Something he wants from the world, with his case for it. [kind] is loose for now: activity,
 * item, place, sense or other. A want without a [justification] isn't considered, so the reply
 * schema and this class both refuse a blank one.
 */
@Serializable
@SerialName("want")
data class Want(val kind: String, val text: String, val justification: String) {
    init {
        require(text.isNotBlank()) { "a want needs text" }
        require(justification.isNotBlank()) { "every want needs a justification: say why it would make your life better or your world larger" }
    }

    /** The justification to follow "because": "being able to ..." rather than "Being able to ...", but "I" stays. */
    val because: String
        get() = justification.trim().trimEnd('.').replaceFirstChar { c -> if (justification.trim().getOrNull(1)?.isLowerCase() == true) c.lowercaseChar() else c }

    companion object {
        private val list = ListSerializer(serializer())

        fun encode(wants: List<Want>): String = Json.encodeToString(list, wants)

        fun decode(text: String): List<Want> = Json.decodeFromString(list, text)
    }
}

data class JournalEntry(
    val id: Long,
    val run: String,
    val tick: Long,
    val day: Int?,
    val time: String?,
    val text: String,
    val wants: List<Want>,
    /** The model that wrote it. */
    val source: String,
) {
    /** "Day 2 21:00", or "t=140" in rooms without a clock. */
    val label: String get() = if (day != null) "Day $day $time" else "t=$tick"

    internal val searchText: String get() = text + " " + wants.joinToString(" ") { "${it.text} ${it.justification}" }

    /** "Day 2 21:00: <entry> Wants: an easel (because ...); ...". */
    fun render(withLabel: Boolean = true): String {
        val wanted = if (wants.isEmpty()) "" else " Wants: " + wants.joinToString("; ") { "${it.text} (because ${it.because})" }
        return (if (withLabel) "$label: " else "") + text + wanted
    }
}

enum class EventKind(val id: String) {
    /** The system state the agent was shown at a decision point. */
    Observation("observation"),

    /** What the agent chose to do. */
    Action("action"),

    /** He wrote in his journal; the entry itself is in the journal, not here. */
    Journal("journal");

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
    private val entries = mutableListOf<JournalEntry>()

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

    @Synchronized
    override fun addJournal(run: String, tick: Long, day: Int?, time: String?, text: String, wants: List<Want>, source: String): Long {
        val id = entries.size + 1L
        entries += JournalEntry(id, run, tick, day, time, text, wants, source)
        return id
    }

    @Synchronized
    override fun journal(run: String) = entries.filter { it.run == run }

    @Synchronized
    override fun search(run: String, source: SearchSource, query: String, limit: Int) = super.search(run, source, query, limit)

    override fun close() {}
}
