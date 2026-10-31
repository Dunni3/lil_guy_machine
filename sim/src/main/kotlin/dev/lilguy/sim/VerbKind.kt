package dev.lilguy.sim

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What sort of verb an activity or use is, and its contract: the fields a row of that kind must
 * set and the ones it can't. [BedroomContent] checks every row against its kind the way it checks
 * ids, so a verb he invents later can be held to its kind's contract like an invented item is held
 * to the schema (research_notes/2026-10-30_1951_world-grammar-consolidated-plan.md, section 2).
 *
 * [must]: each set is "at least one of these". [never]: fields the kind can't set. [routine]: the
 * kind is upkeep, so its rows neither wear thin nor count as new whatever their `routine` flag says.
 * [ready]: false while the engine has nothing for the kind to do; rows of it are refused.
 */
@Serializable
enum class VerbKind(
    val id: String,
    val meaning: String,
    private val must: List<Set<Field>> = emptyList(),
    private val never: Set<Field> = emptySet(),
    val routine: Boolean = false,
    val ready: Boolean = true,
) {
    /** Doing something with an item: eat, read, listen, play a record. */
    @SerialName("consumptive")
    Consumptive(
        "consumptive", "does something with an item it asks for by affordance, and changes needs",
        must = listOf(setOf(Field.Affordance), setOf(Field.Effects, Field.Rates, Field.Background)),
        never = setOf(Field.Produces, Field.Sells, Field.Hangs, Field.Asleep),
    ),

    /** Making a new item: sketch, paint. */
    @SerialName("generative")
    Generative(
        "generative", "turns time (and maybe items it uses up) into a new item",
        must = listOf(setOf(Field.Produces)),
        never = setOf(Field.Sells, Field.Hangs, Field.Background, Field.Asleep),
    ),

    /** Taking information in from an item or place; nothing in the world changes. Step 5. */
    @SerialName("perceptual")
    Perceptual(
        "perceptual", "takes in an item or place and gives back what he notices, changing nothing",
        never = setOf(Field.Consumes, Field.Produces, Field.Sells, Field.Hangs, Field.Background, Field.Asleep),
        ready = false,
    ),

    /** Changing the state of an item he has: hanging a piece. */
    @SerialName("transformative")
    Transformative(
        "transformative", "changes the state of an item he has",
        must = listOf(setOf(Field.Hangs)),
        never = setOf(Field.Produces, Field.Consumes, Field.Sells, Field.Background, Field.Asleep),
    ),

    /** Raising a skill or progress on a book. Step 3. */
    @SerialName("study")
    Study(
        "study", "raises a skill or progress on a book",
        never = setOf(Field.Produces, Field.Sells, Field.Hangs, Field.Background, Field.Asleep),
        ready = false,
    ),

    /** Writing something that persists: the journal (and `think`, which isn't a row). */
    @SerialName("expressive")
    Expressive(
        "expressive", "he writes something that persists",
        never = setOf(Field.Produces, Field.Consumes, Field.Sells, Field.Hangs, Field.Background, Field.Asleep),
    ),

    /** Money moves: selling here, buying is the built-in `buy`. Not for inventing. */
    @SerialName("economic")
    Economic(
        "economic", "money moves",
        must = listOf(setOf(Field.Sells)),
        never = setOf(Field.Produces, Field.Consumes, Field.Hangs, Field.Background, Field.Asleep, Field.Effects, Field.Rates),
        routine = true,
    ),

    /** Sleep. Not for inventing. */
    @SerialName("rest")
    Rest(
        "rest", "he sleeps",
        must = listOf(setOf(Field.Asleep)),
        never = setOf(Field.Produces, Field.Consumes, Field.Sells, Field.Hangs, Field.Background),
        routine = true,
    );

    /** The fields a contract talks about. [Affordance]: it asks for an item by affordance, not by id. */
    enum class Field(val yaml: String) {
        Affordance("an affordance in requires, consumes or item"),
        Consumes("consumes"),
        Produces("produces"),
        Sells("sells"),
        Hangs("hangs"),
        Effects("effects"),
        Rates("rates"),
        Background("background"),
        Asleep("asleep"),
    }

    /** What breaks the contract in [a], as messages; empty when it holds. [affordances]: the vocabulary's. */
    fun violations(a: ActivitySpec, affordances: Set<String>): List<String> {
        if (!ready) return listOf("no activity can be $id yet: the engine has nothing for it to do")
        val set = fieldsSet(a, affordances)
        return must.filter { it.none { f -> f in set } }.map { "a $id verb needs ${it.joinToString(" or ") { f -> f.yaml }}" } +
            (never intersect set).map { "a $id verb can't set ${it.yaml}" }
    }

    companion object {
        private fun fieldsSet(a: ActivitySpec, affordances: Set<String>): Set<Field> = buildSet {
            if ((a.requires + a.consumes.keys + listOfNotNull(a.item)).any { it in affordances }) add(Field.Affordance)
            if (a.consumes.isNotEmpty()) add(Field.Consumes)
            if (a.produces.isNotEmpty()) add(Field.Produces)
            if (a.sells != null) add(Field.Sells)
            if (a.hangs != null) add(Field.Hangs)
            if (a.effects.isNotEmpty()) add(Field.Effects)
            if (a.rates.isNotEmpty()) add(Field.Rates)
            if (a.background != null) add(Field.Background)
            if (a.asleep) add(Field.Asleep)
        }
    }
}
