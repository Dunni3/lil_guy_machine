package dev.lilguy.agent.memory

/**
 * The rule for which memories come up at a decision: the latest few, plus older ones that share
 * words with what he is looking at now, within a character budget. Shown oldest first.
 */
class Recall(
    private val latest: Int = 3,
    private val related: Int = 3,
    private val charBudget: Int = 1200,
) {
    fun select(memories: List<StoredMemory>, context: String): List<StoredMemory> {
        if (memories.isEmpty()) return emptyList()
        val recent = memories.takeLast(latest)
        val query = words(context)
        val older = memories.dropLast(latest)
            .map { it to words(it.text).count { w -> w in query } }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<StoredMemory, Int>> { it.second }.thenByDescending { it.first.id })
            .take(related)
            .map { it.first }
        // Keep the newest first when the budget runs out, then show them in the order they happened.
        val chosen = mutableListOf<StoredMemory>()
        var used = 0
        for (m in (recent.asReversed() + older)) {
            val cost = m.text.length + m.span.label.length + 4
            if (used + cost > charBudget && chosen.isNotEmpty()) continue
            chosen += m
            used += cost
        }
        return chosen.sortedBy { it.id }
    }

    /** "Day 2 08:00-14:30: I sketched ...". */
    fun render(memory: StoredMemory): String = memory.span.label.let { if (it.isEmpty()) memory.text else "$it: ${memory.text}" }

    private fun words(text: String): Set<String> =
        text.lowercase().split(Regex("[^a-z]+")).filter { it.length >= 4 && it !in STOP }.toSet()

    private companion object {
        val STOP = setOf("that", "this", "with", "have", "from", "then", "them", "they", "what", "when", "went", "were", "will", "your", "into", "than", "there", "been", "about", "some", "more", "just", "still", "again", "after", "before", "money", "energy", "fullness", "mood", "event", "did", "saw")
    }
}
