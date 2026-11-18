package dev.lilguy.sim

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The code behind a verb he wrote: JavaScript with a `finish(world)` (and maybe a `canDo(world)`),
 * run by [ScriptSandbox] against [ScriptWorld]. [says] is what it does, in his words, for his
 * options. [version] counts rewrites.
 */
@Serializable
data class VerbCode(val source: String, val says: String, val version: Int = 1)

/**
 * A verb as the coder writes it at his desk: what it's [called] (its id comes from that), the
 * [phrase] for his list of things he can do, what it [says] it does, how many [minutes] it takes,
 * the thing it takes ([what]: an item id or a use like `playable`, or null), where it's done ([at]:
 * `here`, `what` for at the thing it takes, or an item id), the [code], and [why] he wrote it.
 * Naming one of his own verbs rewrites it.
 */
@Serializable
@SerialName("verb_design")
data class VerbDesign(
    val called: String,
    val phrase: String,
    val says: String,
    val minutes: Int,
    val what: String? = null,
    val at: String = VerbSpec.HERE,
    val code: String,
    val why: String = "",
)

/**
 * What a thing does by itself, as the coder writes it: JavaScript with one or more of the trigger
 * functions ([Triggers.ALL]: `hourly(world, thing)`, `every(world, thing)` every [everyMinutes], and
 * the ones for his arriving, leaving, finishing a verb, sleeping, waking and timers), run while he
 * owns [thing] (its id, or `this` for the thing being invented), and what it [says] it does.
 */
@Serializable
@SerialName("behavior_design")
data class BehaviorDesign(
    val thing: String,
    val says: String,
    val code: String,
    @SerialName("every_minutes") val everyMinutes: Int? = null,
)

/**
 * One go at writing code: new or rewritten [verbs], and [behaviors] for things. At the workbench
 * it's the function of the thing he just invented; at his desk, anything.
 */
@Serializable
@SerialName("code_design")
data class CodeDesign(val verbs: List<VerbDesign> = emptyList(), val behaviors: List<BehaviorDesign> = emptyList(), val why: String = "") {
    val isEmpty get() = verbs.isEmpty() && behaviors.isEmpty()

    /** The same, with `this` (in a verb's `what` or `at`, or a behavior's `thing`) as [id]. */
    fun about(id: String): CodeDesign = copy(
        verbs = verbs.map { v -> v.copy(what = v.what?.let { if (it == THIS) id else it }, at = if (v.at == THIS) id else v.at) },
        behaviors = behaviors.map { if (it.thing == THIS) it.copy(thing = id) else it },
    )

    companion object {
        /** How a design written with a new thing names that thing before it has an id. */
        const val THIS = "this"
    }
}

/**
 * What a thing does by itself: its code ([BehaviorDesign]), as kept with the world. [failures]: runs in
 * a row it broke. [every]: the minutes between its `every` runs, null without one.
 */
@Serializable
data class ThingCode(val source: String, val says: String, val version: Int = 1, val tick: Long = 0, val failures: Int = 0, val every: Int? = null)

/** A verb he wrote, as saved with the world ([BedroomState.coded]): the [verb] and when ([tick]) and [why]. */
@Serializable
data class CodedVerb(val verb: VerbSpec, val why: String = "", val tick: Long = 0)

/** What he wrote that the sim checks: the limits on a verb or a behavior of his own. */
object CodeRules {
    const val MAX_MINUTES = 8 * 60

    /** The names of what a tool model can do besides verbs (agent's `BedroomTools`), which a verb of his can't take. */
    val BUILT_IN = setOf("buy", "wait", "go", "place", "recall", "ask", "search")
    const val MAX_SOURCE = 12_000

    /** [design] as a verb of [c], or why it can't be one. [sandbox] checks the code parses and has a `finish`. */
    fun check(design: VerbDesign, c: BedroomContent, sandbox: ScriptSandbox): Checked {
        val problems = mutableListOf<String>()
        val name = design.called.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        if (name.length !in 2..30) problems += "called must be 2 to 30 letters or digits, like \"water the plant\""
        val existing = c.verbs.firstOrNull { it.id == name }
        if (existing != null && existing.code == null) problems += "'$name' is one of the world's own verbs; call yours something else"
        if (name in BUILT_IN) problems += "'$name' is already something you can do; call yours something else"
        if (name in c.items.map { it.id } || name in c.vocabulary.affords) problems += "'$name' is already the name of a thing or a use; call it something else"
        if (design.phrase.isBlank() || design.phrase.length > 80) problems += "phrase must be a short line, like \"water the plant\""
        if (design.says.isBlank() || design.says.length > 200) problems += "says must say in a line what it does"
        if (design.minutes !in 1..MAX_MINUTES) problems += "minutes must be from 1 to $MAX_MINUTES"
        val tags = c.items.flatMap { it.names }.toSet()
        val what = design.what?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
        if (what != null && what !in tags) problems += "what is '$what', which isn't a thing or a use in your world; use an id from world.catalog() or a use like playable, or null"
        val at = design.at.trim().ifEmpty { VerbSpec.HERE }
        val atOk = when (at) {
            VerbSpec.HERE -> true
            "what" -> what != null && c.withName(what).all { it.slot != null }
            else -> c.item(at)?.slot != null
        }
        if (!atOk) problems += if (at == "what") "at is what, but what isn't a thing that stands somewhere; use here" else "at is '$at', which isn't here, what, or the id of a thing that stands in a spot"
        if (design.code.length > MAX_SOURCE) problems += "the code is longer than $MAX_SOURCE characters"
        else sandbox.check(design.code)?.let { problems += "the code: $it" }
        if (problems.isNotEmpty()) return Checked.Refused(problems)
        val verb = VerbSpec(
            id = name,
            kind = VerbKind.Scripted,
            args = what?.let { mapOf("what" to it) }.orEmpty(),
            at = listOf(at),
            minutes = design.minutes,
            effects = listOf(ScriptOp),
            phrase = design.phrase.trim(),
            code = VerbCode(design.code, design.says.trim(), version = (existing?.code?.version ?: 0) + 1),
        )
        return Checked.Ok(verb, rewrote = existing != null)
    }

    sealed interface Checked {
        data class Ok(val verb: VerbSpec, val rewrote: Boolean) : Checked
        data class Refused(val problems: List<String>) : Checked
    }

    /** What's wrong with [b] as the behavior of a thing of [c] (it must exist), or null. */
    fun problem(b: BehaviorDesign, c: BedroomContent, sandbox: ScriptSandbox): String? = when {
        c.item(b.thing) == null -> "behavior for '${b.thing}': there's no such thing"
        b.says.isBlank() || b.says.length > 200 -> "behavior for '${b.thing}': says must say in a line what it does"
        b.code.length > MAX_SOURCE -> "behavior for '${b.thing}': the code is longer than $MAX_SOURCE characters"
        else -> Triggers.problem(b.code, b.everyMinutes, sandbox)?.let { "behavior for '${b.thing}': $it" }
    }

    /** Every problem in [d] against [c], verb by verb and behavior by behavior; empty when it all goes in. */
    fun problems(d: CodeDesign, c: BedroomContent, sandbox: ScriptSandbox): List<String> {
        if (d.isEmpty) return listOf("it has no verbs and no behaviors")
        var content = c
        val out = mutableListOf<String>()
        d.verbs.forEach { v ->
            when (val r = check(v, content, sandbox)) {
                is Checked.Refused -> out += "verb '${v.called}': " + r.problems.joinToString("; ")
                // Later verbs of the design may take this one's name into account.
                is Checked.Ok -> content = runCatching { content.plusVerb(r.verb) }.getOrElse { e -> out += "verb '${v.called}': ${e.message}"; content }
            }
        }
        d.behaviors.forEach { b -> problem(b, c, sandbox)?.let { out += it } }
        return out
    }
}

/**
 * Runs the verb's own code ([VerbSpec.code]): `canDo(world)`, if it has one, as its lock (a string
 * back is why not; it may only look), and `finish(world)` as it finishes. Code that breaks fails the
 * verb, with the error; what it changed before it broke stays changed.
 */
data object ScriptOp : Op() {
    override val key get() = "script"

    override fun problems(c: BedroomContent, verb: VerbSpec) = listOfNotNull(if (verb.code == null) "script: the verb has no code" else null)

    override fun lockReason(w: OpWorld, b: Bound) = w.scriptLock(b)

    override fun gone(w: OpWorld, b: Bound) = w.runScript(b)

    override fun finish(w: OpWorld, b: Bound) = w.scriptParts(b.verb.id)

    override fun describe(w: OpWorld, b: Bound, forSale: Boolean) = listOfNotNull(b.verb.code?.let { "${it.says} (your own code)" })
}

/**
 * Puts in the verb he wrote: checks the staged [VerbDesign] ([CodeRules.check]) and adds it to his
 * world, or rewrites his verb of that name. The design comes from the verb's
 * `write: { with: verb_code, timing: before }`; one that's refused fails the verb and says why.
 */
data object DefineOp : Op() {
    override val key get() = "define"

    override fun problems(c: BedroomContent, verb: VerbSpec) = listOfNotNull(
        if (verb.effects.none { it is WriteOp && it.with == "verb_code" && it.timing == Timing.Before }) "define: the verb needs a `write: { with: verb_code, timing: before }` to write the code" else null,
    )

    override fun gone(w: OpWorld, b: Bound) = w.defineFailed(b.verb)

    override fun finish(w: OpWorld, b: Bound) = w.define(b.verb)

    override fun describe(w: OpWorld, b: Bound, forSale: Boolean) = listOf(w.defineText())
}
