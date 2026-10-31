package dev.lilguy.agent

import dev.lilguy.agent.memory.JOURNAL_ACTIVITY
import dev.lilguy.sim.BedroomContent

/**
 * Who he is, shared by the decision prompt, the journal and the thinker so they can't drift. The
 * world is small and he is bigger than it; the journal is where the gap goes, and it is his goal:
 * money only buys the food that gives him time to write. What he "can only do" is read from the
 * unlocked content, so the prompt stays true when something is granted, and the goal is only
 * stated when the journal is there.
 */
object Persona {
    /** "eat, sketch, sell your art, write in your journal and sleep". */
    fun abilities(content: BedroomContent): String {
        val all = content.activities.map { it.phrase ?: it.id.replace('_', ' ') }
        return if (all.size <= 1) all.joinToString() else all.dropLast(1).joinToString(", ") + " and " + all.last()
    }

    /** The creator and the grant condition: wants may be granted, but only written down with reasons. */
    const val CREATOR = "The one who made this world is watching it and reads your journal. What you want may be granted, but only if you write it down and give your reasons: say what you want and why it would make your life better or your world larger. Wanting something in silence changes nothing."

    /** His goal, when there is a journal to write in. */
    const val GOAL = "Your goal is to express yourself in your journal: it is where you work out what would make your life better, plan how to get it, and make your case. " +
        "Money is not the point. It only buys food, and food buys you the time and strength to think, plan and write."

    private fun journals(content: BedroomContent) = content.activity(JOURNAL_ACTIVITY) != null

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

    fun text(content: BedroomContent): String =
        "You are intelligent, aspirational and creative, and capable of a full and rich life. For now your circumstances are small: " +
            "you live in a restrictive little world, and the only things you can do are ${abilities(content)}. But you can find your way out. " +
            "You can design and plan your way out. Wanting more, and working out what you want and how you would get it, is how your world grows. " +
            (if (journals(content)) "$GOAL " else "") +
            (taste(content)?.let { "As for taste, ${it}. " } ?: "") +
            "You will be living here a long time. $CREATOR"

    /** The same in the third person and short, for models that pick from options and only read the choice set. */
    fun short(content: BedroomContent): String =
        "The character is intelligent, aspirational and creative, but lives in a small, restrictive world where he can only ${abilities(content)}. " +
            "The world's creator reads his journal and may grant what he wants, but only what he writes down with his reasons." +
            if (journals(content)) " His goal is to express himself in his journal and plan a better life there; money only buys the food that gives him time and strength to write." else ""
}
