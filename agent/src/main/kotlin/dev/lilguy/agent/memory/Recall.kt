package dev.lilguy.agent.memory

import kotlin.math.pow

/**
 * Something that could come back to him at a decision: a memory of a stretch ([Kind.Memory]), of a
 * whole day ([Kind.Day]), or a thought he had ([Kind.Thought]). [order] puts them in time order
 * (game minutes at the end, or ids in rooms without a clock).
 */
data class Recollection(
    val kind: Kind,
    val id: Long,
    val label: String,
    val text: String,
    val importance: Double?,
    val order: Long,
) {
    enum class Kind { Memory, Day, Thought }

    /** Unique across kinds: "memory_12", "day_3", "thought_40". */
    val key: String get() = "${kind.name.lowercase()}_$id"

    /** "Day 2 08:00-14:30: I sketched ...", "Day 2 (the whole day): ...", "Day 3 10:00, you thought: "..."". */
    fun render(): String = when (kind) {
        Kind.Memory -> if (label.isEmpty()) text else "$label: $text"
        Kind.Day -> if (label.isEmpty()) text else "$label (the whole day): $text"
        Kind.Thought -> (if (label.isEmpty()) "You thought" else "$label, you thought") + ": \"$text\""
    }
}

/**
 * The rule for which recollections come up at a decision, within a character budget, shown oldest
 * first. Two ways to choose:
 * - [select], by keywords: the latest few memories, plus older memories and thoughts that share
 *   words with what he is looking at now. Used when nothing scores.
 * - [selectRanked], from a model's relevance scores ([MemoryScorer]): relevance plus importance plus
 *   recency, weighted here, and the latest memory always.
 */
class Recall(
    private val latest: Int = 3,
    private val related: Int = 3,
    private val charBudget: Int = 1500,
    /** Most recollections [selectRanked] shows. */
    private val pick: Int = 6,
    private val relevanceWeight: Double = 1.0,
    private val importanceWeight: Double = 0.5,
    private val recencyWeight: Double = 0.5,
    /** Game hours for recency to halve. */
    private val halfLifeHours: Double = 24.0,
) {
    fun select(candidates: List<Recollection>, context: String): List<Recollection> {
        if (candidates.isEmpty()) return emptyList()
        val sorted = candidates.sortedBy { it.order }
        val recent = sorted.filter { it.kind != Recollection.Kind.Thought }.takeLast(latest)
        val older = Keywords.rank(sorted - recent.toSet(), context, related, { it.text }, { it.order }).map { it.first }
        return withinBudget(recent.asReversed() + older)
    }

    /**
     * By [relevance] (0 to 1, by [Recollection.key]; an unscored one counts 0.5), importance and
     * recency at [now] (same units as [Recollection.order]; null ranks recency by position).
     */
    fun selectRanked(candidates: List<Recollection>, relevance: Map<String, Double>, now: Long?): List<Recollection> {
        if (candidates.isEmpty()) return emptyList()
        val sorted = candidates.sortedBy { it.order }
        val newest = sorted.lastOrNull { it.kind != Recollection.Kind.Thought }
        fun recency(r: Recollection, index: Int): Double =
            if (now != null) 0.5.pow((now - r.order).coerceAtLeast(0) / 60.0 / halfLifeHours) else 0.5.pow((sorted.size - 1 - index) / 5.0)
        val ranked = sorted.mapIndexed { i, r ->
            r to relevanceWeight * (relevance[r.key] ?: 0.5) + importanceWeight * (r.importance ?: 0.25) + recencyWeight * recency(r, i)
        }.sortedByDescending { it.second }.map { it.first }
        return withinBudget(listOfNotNull(newest) + (ranked - setOfNotNull(newest)).take(pick - 1))
    }

    fun render(r: Recollection): String = r.render()

    /** In order of preference while the budget lasts (the first always fits), then shown in the order they happened. */
    private fun withinBudget(preferred: List<Recollection>): List<Recollection> {
        val chosen = mutableListOf<Recollection>()
        var used = 0
        for (r in preferred.distinct()) {
            val c = r.render().length + 2
            if (used + c > charBudget && chosen.isNotEmpty()) continue
            chosen += r
            used += c
        }
        return chosen.sortedBy { it.order }
    }
}
