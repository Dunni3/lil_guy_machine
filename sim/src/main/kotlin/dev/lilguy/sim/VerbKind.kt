package dev.lilguy.sim

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What sort of verb a verb is, and its contract: which [Op]s its `effects` may use and
 * which it must. [BedroomContent] checks every row against its kind the way it checks ids, so a verb
 * he invents later can be held to its kind like an invented item is held to the schema. An op no
 * kind lists is refused for every kind, so a new op has to be allowed somewhere on purpose.
 *
 * [allowed]: op keys ([Op.key]) it may use. [required]: each set is "at least one of these".
 * [affordance]: it must ask for an item by affordance (in `requires`, `args` or a `consume`),
 * not only by id. [routine]: the kind is upkeep, so its rows neither wear thin nor count as new
 * whatever their `routine` flag says. The perceptual kind comes when its ops do.
 */
@Serializable
enum class VerbKind(
    val id: String,
    val meaning: String,
    val allowed: Set<String>,
    val required: List<Set<String>> = emptyList(),
    val affordance: Boolean = false,
    val routine: Boolean = false,
) {
    /** Doing something with an item: eat, read, listen, play a record or the guitar. */
    @SerialName("consumptive")
    Consumptive(
        "consumptive", "does something with an item it asks for by affordance, and changes needs",
        allowed = setOf("need", "rate", "consume", "background", "enjoy", "progress", "practice", "write"),
        required = listOf(setOf("need", "rate", "background", "enjoy")),
        affordance = true,
    ),

    /** Making a new item: sketch, paint. */
    @SerialName("generative")
    Generative(
        "generative", "turns time (and maybe items it uses up) into a new item",
        allowed = setOf("need", "rate", "consume", "produce", "draw", "practice"),
        required = listOf(setOf("produce")),
    ),

    /** Working through a book or practising: raises a skill, and may teach him things. */
    @SerialName("study")
    Study(
        "study", "works through a book or practises, raising a skill",
        allowed = setOf("need", "rate", "progress", "practice", "write"),
        required = listOf(setOf("progress", "practice")),
    ),

    /** Changing the state of an item he has: hanging a piece. */
    @SerialName("transformative")
    Transformative(
        "transformative", "changes the state of an item he has",
        allowed = setOf("need", "rate", "set_state"),
        required = listOf(setOf("set_state")),
    ),

    /** Writing something that persists: a thought, later letters. */
    @SerialName("expressive")
    Expressive(
        "expressive", "he writes something that persists",
        allowed = setOf("need", "rate", "write"),
        required = listOf(setOf("write")),
    ),

    /** Money moves: selling here, buying is the built-in `buy`. Not for inventing. */
    @SerialName("economic")
    Economic(
        "economic", "money moves",
        allowed = setOf("sell"),
        required = listOf(setOf("sell")),
        routine = true,
    ),

    /** Sleep. Not for inventing. */
    @SerialName("rest")
    Rest(
        "rest", "he sleeps",
        allowed = setOf("need", "rate", "sleep"),
        required = listOf(setOf("sleep")),
        routine = true,
    );

    /** What breaks the contract in [a], as messages; empty when it holds. [affordances]: the vocabulary's. */
    fun violations(a: VerbSpec, affordances: Set<String>): List<String> {
        val used = a.effects.map { it.key }.toSet()
        val article = if (id.first() in "aeiou") "an" else "a"
        val asks = a.requires + a.effects.flatMap { it.asksFor } + a.args.values
        return listOfNotNull(if (affordance && asks.none { it in affordances }) "$article $id verb needs an affordance in requires, args or a consume" else null) +
            required.filter { it.none { op -> op in used } }.map { "$article $id verb needs ${it.joinToString(" or ")}" } +
            (used - allowed).map { "$article $id verb can't $it" }
    }
}
