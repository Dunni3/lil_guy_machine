package dev.lilguy.sim

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * An op whose result a model writes: a thought, a drawing. Content says that it
 * happens, where its result goes ([into]) and its parameters; a handler in the agent layer
 * (agent/.../authored/) says how. The sim never calls a model or waits for one. When the verb
 * succeeds it records the op as [AuthoredView] with an id, and the agent layer hands the result
 * back through [BedroomWorld.deliver]. A failed or cut-short verb records nothing, and the agent
 * layer throws its result away.
 *
 * [handler] is one of a closed list ([HANDLERS]), each with the destinations it can write to.
 * [timing]: when the call runs ([Timing]).
 */
sealed interface AuthoredOp {
    val handler: String
    val into: List<String>
    val timing: Timing

    /** False when the verb still makes sense without its handler (the piece is made either way): it's offered anyway. */
    val essential: Boolean

    companion object {
        /** Every handler, with the destinations it can write to. */
        val HANDLERS: Map<String, Set<String>> = linkedMapOf(
            "thought" to setOf("events", "short_term"),
            "draw" to setOf("pieces", "events"),
        )

        /** Where results can go: his event stream, short-term memory, and the sim's art pieces. */
        val DESTINATIONS = listOf("events", "short_term", "pieces")

        /** How a short-term result can show in later prompts. */
        val SHOWS_AS = listOf("plan")
    }
}

/** When an authored op's call runs. */
@Serializable
enum class Timing {
    /** After the decision and before the intent is submitted; he stands still meanwhile (a thought). */
    @SerialName("before") Before,

    /** From when the verb starts, in the background while game time passes; the clock waits for it if it outlasts the verb. */
    @SerialName("during") During,
}

/**
 * A model writes something: [with] names the handler (`thought`), [into] where it goes.
 * [showsAs]: `plan` puts a short-term result in every later prompt as his plan.
 */
data class WriteOp(val with: String, override val into: List<String>, override val timing: Timing = Timing.During, val showsAs: String? = null) : Op(), AuthoredOp {
    override val key get() = "write"
    override val handler get() = with
    override val essential get() = true

    override fun problems(c: BedroomContent, verb: VerbSpec): List<String> {
        val can = AuthoredOp.HANDLERS[with]?.takeIf { with != "draw" } ?: return listOf("write: no handler '$with'; use ${AuthoredOp.HANDLERS.keys.filter { it != "draw" }.joinToString(" or ")}")
        return listOfNotNull(
            if (into.isEmpty()) "write: `into` names no destination" else null,
            (into - can).takeIf { it.isNotEmpty() }?.let { "write with $with can't go into $it; it can go into ${can.joinToString()}" },
            showsAs?.let { if (it !in AuthoredOp.SHOWS_AS || "short_term" !in into) "write: shows_as ${AuthoredOp.SHOWS_AS} needs short_term in `into`" else null },
        )
    }

    override fun finish(w: OpWorld, b: Bound): List<String> {
        w.await(this, b, piece = null)
        return emptyList()
    }
}

/**
 * The drawing model draws [what] (an item id or an argument), which the verb produces and which
 * affords `drawable`: the canvas comes from there. The piece is made either way; a drawing gives it
 * a title and tags.
 */
data class DrawOp(val what: String) : Op(), AuthoredOp {
    override val key get() = "draw"
    override val handler get() = "draw"
    override val into get() = listOf("pieces", "events")
    override val timing get() = Timing.During
    override val essential get() = false

    override fun problems(c: BedroomContent, verb: VerbSpec): List<String> {
        val id = verb.args[what] ?: what
        return listOfNotNull(
            if (c.item(id)?.canvas == null) "draw: '$id' isn't a drawable item" else null,
            if (verb.effects.indexOfFirst { it is ProduceOp && id in it.items }.let { it < 0 || it > verb.effects.indexOf(this) }) "draw: the verb doesn't produce '$id' before it draws it" else null,
        )
    }

    override fun finish(w: OpWorld, b: Bound): List<String> {
        w.await(this, b, piece = w.newestPiece(b.resolve(what)))
        return emptyList()
    }
}

/**
 * An authored op whose verb succeeded, waiting for its result: [id] is what [BedroomWorld.deliver]
 * takes. [piece]: for a drawing, the piece it is for. [tick]: when the verb finished.
 */
data class AuthoredView(val id: Int, val verb: String, val handler: String, val tick: Long, val piece: Int? = null, val delivered: Boolean = false)

/** What the agent layer hands back for an authored op ([BedroomWorld.deliver]). */
sealed interface AuthoredResult {
    /** A drawing: its [title] and the [tags] the drawing model claimed for it. */
    data class Drawn(val title: String, val tags: List<String>) : AuthoredResult

    /** Text that went to the agent's own stores (a thought); the sim only marks it delivered. */
    data class Written(val text: String) : AuthoredResult
}
