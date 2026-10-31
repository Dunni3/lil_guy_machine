package dev.lilguy.agent

import dev.lilguy.sim.BedroomContent

/**
 * Who he is, shared by the decision prompt, the thinker and the art so they can't drift. Three
 * parts: the fixed core from content ([BedroomContent.personality]), true by design and never his
 * to edit; his taste, generated from his affinities so the prompt can't contradict the sim; and his
 * self-model, in his own words, revised each night from his day ([dev.lilguy.agent.memory.SelfModeler]).
 * What he can do is a fact about the world, so it's in the rules ([abilities]), not here.
 */
object Persona {
    /** "eat, sketch, sell your art, sleep and stop and think". */
    fun abilities(content: BedroomContent): String {
        val all = content.verbs.map { it.phrase ?: it.id.replace('_', ' ') }
        return if (all.size <= 1) all.joinToString() else all.dropLast(1).joinToString(", ") + " and " + all.last()
    }

    /**
     * "cozy, calm and warm things, lively ones a little, and loud ones put you off", from his
     * affinities; null when he has none. Only qualities some item in his world has are named.
     */
    fun taste(content: BedroomContent): String? {
        val seen = content.items.flatMap { it.qualities.keys }.toSet()
        val weights = content.personality.affinities.filterKeys { it in seen }
        fun list(qs: List<String>) = if (qs.size <= 1) qs.joinToString() else qs.dropLast(1).joinToString(", ") + " and " + qs.last()
        val likes = weights.filterValues { it >= 1 }.keys.toList()
        val some = weights.filterValues { it > 0 && it < 1 }.keys.toList()
        val dislikes = weights.filterValues { it < 0 }.keys.toList()
        val parts = listOfNotNull(
            likes.takeIf { it.isNotEmpty() }?.let { "${list(it)} things" },
            some.takeIf { it.isNotEmpty() }?.let { "${list(it)} ones a little" },
        )
        val off = dislikes.takeIf { it.isNotEmpty() }?.let { "${list(it)} ones put you off" }
        if (parts.isEmpty() && off == null) return null
        return (if (parts.isEmpty()) "" else "you like ${parts.joinToString(", ")}") + (off?.let { (if (parts.isEmpty()) "" else ", and ") + it }.orEmpty())
    }

    /** What the prompts call his self-model. */
    const val SELF_HEADER = "How you see yourself, in your own words (you revise this as you live):"

    /**
     * The core, his taste, and [self] (his self-model as rendered lines, or its seed) when
     * something revises it; null leaves it out.
     */
    fun text(content: BedroomContent, self: String? = null): String = buildString {
        append(content.personality.core.you)
        taste(content)?.let { append(" As for taste, $it.") }
        if (self != null) append("\n\n$SELF_HEADER\n$self")
    }

    /** The core in the third person, for models that pick from options and only read the choice set. */
    fun short(content: BedroomContent): String = content.personality.core.he
}
