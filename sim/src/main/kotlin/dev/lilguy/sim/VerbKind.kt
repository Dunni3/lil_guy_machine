package dev.lilguy.sim

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What sort of verb an activity or use is, and its contract: which [Op]s its `effects` may use and
 * which it must. [BedroomContent] checks every row against its kind the way it checks ids, so a verb
 * he invents later can be held to its kind like an invented item is held to the schema. An op no
 * kind lists is refused for every kind, so a new op has to be allowed somewhere on purpose.
 *
 * [allowed]: op keys ([Op.key]) it may use. [required]: each set is "at least one of these".
 * [affordance]: it must ask for an item by affordance (in `requires`, a `consume` or a use's `item`),
 * not only by id. [routine]: the kind is upkeep, so its rows neither wear thin nor count as new
 * whatever their `routine` flag says. Perceptual and study kinds come when their ops do.
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
    /** Doing something with an item: eat, read, listen, play a record. */
    @SerialName("consumptive")
    Consumptive(
        "consumptive", "does something with an item it asks for by affordance, and changes needs",
        allowed = setOf("need", "rate", "consume", "background", "enjoy"),
        required = listOf(setOf("need", "rate", "background", "enjoy")),
        affordance = true,
    ),

    /** Making a new item: sketch, paint. */
    @SerialName("generative")
    Generative(
        "generative", "turns time (and maybe items it uses up) into a new item",
        allowed = setOf("need", "rate", "consume", "produce"),
        required = listOf(setOf("produce")),
    ),

    /** Changing the state of an item he has: hanging a piece. */
    @SerialName("transformative")
    Transformative(
        "transformative", "changes the state of an item he has",
        allowed = setOf("need", "rate", "set_state"),
        required = listOf(setOf("set_state")),
    ),

    /** Writing something that persists: the journal (and `think`, which isn't a row). */
    @SerialName("expressive")
    Expressive(
        "expressive", "he writes something that persists",
        allowed = setOf("need", "rate"),
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
    fun violations(a: ActivitySpec, affordances: Set<String>): List<String> {
        val used = a.effects.map { it.key }.toSet()
        val article = if (id.first() in "aeiou") "an" else "a"
        val asks = a.requires + a.effects.flatMap { it.asksFor } + listOfNotNull(a.item)
        return listOfNotNull(if (affordance && asks.none { it in affordances }) "$article $id verb needs an affordance in requires, a consume or item" else null) +
            required.filter { it.none { op -> op in used } }.map { "$article $id verb needs ${it.joinToString(" or ")}" } +
            (used - allowed).map { "$article $id verb can't $it" }
    }
}
