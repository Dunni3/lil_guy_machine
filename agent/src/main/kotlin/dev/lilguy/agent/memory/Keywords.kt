package dev.lilguy.agent.memory

/**
 * Plain keyword matching, shared by [Recall] and [MemoryStore.search]: words of four letters or
 * more, minus common ones, and a score of how many a text shares with the query. No embeddings
 * yet; a run's worth of text is small.
 */
object Keywords {
    fun of(text: String): Set<String> =
        text.lowercase().split(Regex("[^a-z]+")).filter { it.length >= 4 && it !in STOP }.toSet()

    /** How many of [query]'s words [text] has. */
    fun score(text: String, query: Set<String>): Int = of(text).count { it in query }

    /** The [limit] best of [items] for [query]: most shared words, newest (highest [id]) first on ties; none that share nothing. */
    fun <T> rank(items: List<T>, query: String, limit: Int, text: (T) -> String, id: (T) -> Long): List<Pair<T, Int>> {
        val words = of(query)
        if (words.isEmpty()) return emptyList()
        return items.map { it to score(text(it), words) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<T, Int>> { it.second }.thenByDescending { id(it.first) })
            .take(limit)
    }

    private val STOP = setOf("that", "this", "with", "have", "from", "then", "them", "they", "what", "when", "went", "were", "will", "your", "into", "than", "there", "been", "about", "some", "more", "just", "still", "again", "after", "before", "money", "energy", "fullness", "mood", "event", "did", "saw")
}
