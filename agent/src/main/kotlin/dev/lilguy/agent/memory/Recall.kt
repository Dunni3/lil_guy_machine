package dev.lilguy.agent.memory

/**
 * The rule for which memories come up at a decision: the latest few, plus older ones that share
 * words with what he is looking at now, within a character budget. Shown oldest first. Journal
 * entries follow the same rule with their own numbers ([selectJournal]).
 */
class Recall(
    private val latest: Int = 3,
    private val related: Int = 3,
    private val charBudget: Int = 1200,
    private val latestEntries: Int = 2,
    private val journalBudget: Int = 800,
) {
    fun select(memories: List<StoredMemory>, context: String): List<StoredMemory> {
        if (memories.isEmpty()) return emptyList()
        val recent = memories.takeLast(latest)
        val older = Keywords.rank(memories.dropLast(latest), context, related, { it.text }, { it.id }).map { it.first }
        return withinBudget(recent, older, charBudget, { render(it).length + 2 }, { it.id })
    }

    /**
     * Journal entries by the same rule, with their own budget so the journal and the memories
     * can't crowd each other out: the latest [latestEntries] always (what he last decided he
     * wants), then older ones that share words with [context].
     */
    fun selectJournal(entries: List<JournalEntry>, context: String): List<JournalEntry> {
        if (entries.isEmpty()) return emptyList()
        val recent = entries.takeLast(latestEntries)
        val older = Keywords.rank(entries.dropLast(latestEntries), context, related, { it.text + " " + it.wants.joinToString(" ") { w -> w.text } }, { it.id }).map { it.first }
        return withinBudget(recent, older, journalBudget, { render(it).length + 2 }, { it.id })
    }

    /** "Day 2 08:00-14:30: I sketched ...". */
    fun render(memory: StoredMemory): String = memory.span.label.let { if (it.isEmpty()) memory.text else "$it: ${memory.text}" }

    /** "Day 2 21:00: <entry> Wants: an easel (because ...)". */
    fun render(entry: JournalEntry): String = entry.render()

    /** Newest first while the budget lasts (the newest always fits), then shown in the order they happened. */
    private fun <T> withinBudget(recent: List<T>, older: List<T>, budget: Int, cost: (T) -> Int, id: (T) -> Long): List<T> {
        val chosen = mutableListOf<T>()
        var used = 0
        for (m in (recent.asReversed() + older)) {
            val c = cost(m)
            if (used + c > budget && chosen.isNotEmpty()) continue
            chosen += m
            used += c
        }
        return chosen.sortedBy(id)
    }
}
