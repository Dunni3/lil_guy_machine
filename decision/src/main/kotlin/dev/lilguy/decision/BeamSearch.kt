package dev.lilguy.decision

/**
 * Picks from a [ChoiceSet] whose options sit in a tree of [ChoiceSet.Group]s, for a picker that
 * answers Choice questions with probabilities (Jev), by walking down the tree instead of weighing
 * every option at once. At each depth it asks one batch of questions, one per trajectory still at
 * a group, each over that group's children: groups by what's under them, options by their own
 * descriptions. A trajectory's score is the product of its picks' probabilities, and the [width]
 * best go on. When every one ends at an option, a last question over just those options decides.
 *
 * A group with nothing under it is dropped and one with a single child is that child, so no
 * question has one answer.
 */
class BeamSearch(private val width: Int) {
    init {
        require(width >= 1) { "a beam is at least 1 wide" }
    }

    /** One Choice question: what it asks, and its options as label to description. */
    data class Question(val instructions: String, val options: Map<String, String>)

    /** Answers a batch of questions in one call: each question's probabilities by option label. */
    fun interface Ask {
        suspend fun ask(questions: Map<String, Question>): Map<String, Map<String, Double>>
    }

    /** A path down the tree, as labels from the top, and its score. */
    data class Trajectory(val path: List<String>, val p: Double)

    data class Result(
        val option: ChoiceSet.Option,
        /** The beam at the end, best first: what the last question chose among. */
        val finalists: List<Trajectory>,
        /** The last question's probabilities; empty when there was one finalist. */
        val last: Map<String, Double>,
        /** Each depth's answers, by question key. */
        val depths: List<Map<String, Map<String, Double>>>,
    )

    private sealed interface Node {
        val label: String
    }

    private class Leaf(val option: ChoiceSet.Option) : Node {
        override val label get() = option.label
    }

    private class Branch(val group: ChoiceSet.Group?, val children: List<Node>) : Node {
        override val label get() = "group:${group?.id}"
        val key get() = group?.let { "in_${it.id}" } ?: "top"

        fun leaves(): List<Leaf> = children.flatMap { if (it is Branch) it.leaves() else listOf(it as Leaf) }
    }

    private class Path(val node: Node, val trail: List<Node>, val p: Double) {
        fun then(child: Node, q: Double) = Path(child, trail + child, p * q)
        val trajectory get() = Trajectory(trail.map { it.label }, p)
    }

    suspend fun pick(set: ChoiceSet, ask: Ask): Result {
        require(set.options.isNotEmpty()) { "no options to choose from" }
        var beam = listOf(Path(tree(set), emptyList(), 1.0))
        val depths = mutableListOf<Map<String, Map<String, Double>>>()
        while (beam.any { it.node is Branch }) {
            val open = beam.filter { it.node is Branch }
            val questions = open.map { it.node as Branch }.filter { it.children.size > 1 }.associate { b -> b.key to question(set, b) }
            val answers = if (questions.isEmpty()) emptyMap() else ask.ask(questions)
            if (questions.isNotEmpty()) depths += answers
            beam = (beam.filter { it.node is Leaf } + open.flatMap { path ->
                val b = path.node as Branch
                b.children.map { c -> path.then(c, if (b.children.size == 1) 1.0 else answers[b.key]?.get(c.label) ?: 0.0) }
            }).sortedByDescending { it.p }.take(width)
        }
        val finalists = beam.map { (it.node as Leaf).option }
        val last = if (finalists.size == 1) emptyMap() else ask.ask(mapOf(LAST to Question(set.instructions, finalists.associate { it.label to it.description })))[LAST].orEmpty()
        // The beam's order breaks ties, and decides if the last answer is missing.
        val chosen = finalists.maxByOrNull { last[it.label] ?: 0.0 }?.takeIf { last.isNotEmpty() } ?: finalists.first()
        return Result(chosen, beam.map { it.trajectory }, last, depths)
    }

    private fun question(set: ChoiceSet, b: Branch): Question {
        val ask = if (b.group == null) {
            "First, what kind of thing: each option is one thing he could do, or a group of things described by what's in it. Which does he do next?"
        } else {
            "He has settled on this: ${b.group.description}. Which of these does he do?"
        }
        return Question("${set.instructions} $ask", b.children.associate { c -> c.label to describe(c) })
    }

    /** An option's own description; a group's, with what's in it right now. */
    private fun describe(node: Node): String = when (node) {
        is Leaf -> node.option.description
        is Branch -> {
            val labels = node.leaves().map { it.label }
            val shown = labels.take(MAX_LISTED).joinToString(", ") + if (labels.size > MAX_LISTED) " and ${labels.size - MAX_LISTED} more" else ""
            "${node.group!!.description}. Open now: $shown."
        }
    }

    private fun tree(set: ChoiceSet): Branch {
        val ids = set.groups.map { it.id }.toSet()
        val kids = set.groups.groupBy { it.parent?.takeIf { p -> p in ids } }
        val options = set.options.groupBy { it.group?.takeIf { g -> g in ids } }
        fun build(g: ChoiceSet.Group?): Node? {
            val children = kids[g?.id].orEmpty().mapNotNull(::build) + options[g?.id].orEmpty().map(::Leaf)
            return when {
                g == null -> Branch(null, children)
                children.isEmpty() -> null
                children.size == 1 -> children.single()
                else -> Branch(g, children)
            }
        }
        return build(null) as Branch
    }

    companion object {
        /** The key of the question that picks among the finalists. */
        const val LAST = "finalists"

        /** How many of a group's options its description names. */
        private const val MAX_LISTED = 12
    }
}
