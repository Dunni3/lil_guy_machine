package dev.lilguy.sim

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What a machine is (items.yaml `machine`, or an invention's `runs`): the [runs] it makes things by
 * (rules.yaml `machines.recipes`), how much it [holds] of each thing it takes and of what it makes,
 * and the [wear] a batch adds. Null fields take the rules'.
 * (`docs/decisions/2026-11-15-machines-are-fed-and-wear-out-instead-of-uncapped.md`)
 */
@Serializable
data class MachineSpec(
    val runs: List<String> = emptyList(),
    val holds: Int? = null,
    val wear: Double? = null,
)

/**
 * One way of making something: a batch [takes] its inputs and, after [hours] of working, [makes] its
 * outputs. [needs]: effects at the machine's tile while it works (`light: 3`); below them it waits.
 * [says]: what it's doing, for its readout ("composting").
 */
@Serializable
data class Recipe(
    val id: String,
    val says: String,
    val takes: Map<String, Int>,
    val makes: Map<String, Int>,
    val hours: Double,
    val needs: Map<String, Double> = emptyMap(),
)

/**
 * How machines work (rules.yaml `machines`). [reach]: tiles (in the same place) over which one machine
 * takes what it needs from what another has made. Wear runs 0 to 100: a batch adds the machine's
 * `wear` or [wearPerBatch], a run of a thing's code that changes something adds [wearPerRun]; above
 * [slowAt] it works slower, up to half speed at 100, where it breaks and stops until it's repaired.
 */
@Serializable
data class MachineRules(
    val recipes: List<Recipe> = emptyList(),
    val holds: Int = 6,
    val reach: Int = 3,
    @SerialName("wear_per_batch") val wearPerBatch: Double = 10.0,
    @SerialName("wear_per_run") val wearPerRun: Double = 1.0,
    @SerialName("slow_at") val slowAt: Double = 50.0,
    val repair: RepairRules = RepairRules(),
) {
    /**
     * What a repair takes off: [restores] plus [perLevel] a level of [skill]. Below [wornAt] there's
     * nothing to mend. What it uses and how long it takes is the verb's (verbs.yaml `repair`).
     */
    @Serializable
    data class RepairRules(
        val restores: Double = 50.0,
        @SerialName("per_level") val perLevel: Double = 25.0,
        val skill: String? = null,
        @SerialName("worn_at") val wornAt: Double = 25.0,
    )

    fun recipe(id: String): Recipe? = recipes.firstOrNull { it.id == id }

    internal fun problems(c: BedroomContent): List<String> {
        val p = mutableListOf<String>()
        if (holds < 1 || reach < 0 || wearPerBatch < 0 || wearPerRun < 0 || slowAt !in 0.0..BROKEN) p += "machines: holds at least 1, reach and wear not negative, slow_at 0 to 100"
        if (repair.restores <= 0 || repair.perLevel < 0 || repair.wornAt !in 0.0..BROKEN) p += "machines.repair: restores positive, per_level not negative, worn_at 0 to 100"
        repair.skill?.let { if (it !in c.vocabulary.skills) p += "machines.repair: skill '$it' isn't in vocabulary.skills" }
        if (recipes.map { it.id }.toSet().size != recipes.size) p += "machines: recipe ids must be unique"
        recipes.forEach { r ->
            if (r.hours <= 0 || r.takes.isEmpty() || r.makes.isEmpty()) p += "recipe ${r.id}: positive hours, and something it takes and makes"
            (r.takes + r.makes).forEach { (id, n) ->
                if (c.item(id) == null) p += "recipe ${r.id} names '$id', which isn't an item"
                else if (c.item(id)!!.slot != null || c.item(id)!!.carried) p += "recipe ${r.id}: '$id' stands in a slot or is carried art; a machine takes and makes things he keeps"
                if (n < 1) p += "recipe ${r.id}: counts are at least 1"
            }
            r.needs.forEach { (k, v) -> if (EffectKind.of(k) == null || v <= 0) p += "recipe ${r.id} needs '$k' $v: an effect (${EffectKind.entries.joinToString { it.id }}) above 0" }
        }
        c.items.forEach { item ->
            item.machine?.runs?.forEach { if (recipe(it) == null) p += "item ${item.id} runs '$it', which isn't a recipe in rules.yaml machines" }
            item.machine?.let { m -> if (item.slot == null) p += "item ${item.id} is a machine, so it needs a slot to stand in" else if ((m.holds ?: 1) < 1 || (m.wear ?: 0.0) < 0) p += "item ${item.id}: machine holds at least 1, wear not negative" }
        }
        return p
    }

    companion object {
        const val BROKEN = 100.0
    }
}

/** What one machine has in it: [inputs] put in, [outputs] made, its [wear], and how far into a batch of [recipe] it is ([progress], game minutes of work). */
@Serializable
data class Store(
    val inputs: Map<String, Int> = emptyMap(),
    val outputs: Map<String, Int> = emptyMap(),
    val wear: Double = 0.0,
    val progress: Double = 0.0,
    val recipe: String? = null,
    /** What it last told him it was stuck on ([Works.Stuck]), so each is told once. */
    val told: String? = null,
) {
    val broken: Boolean get() = wear >= MachineRules.BROKEN
    val isEmpty: Boolean get() = inputs.isEmpty() && outputs.isEmpty() && wear == 0.0 && progress == 0.0 && told == null
}

/** What the [Works] needs of the world. [BedroomWorld] is the one host. */
interface WorksHost {
    val content: BedroomContent
    val layout: Layout
    fun count(id: String): Int
    /** The tile [id] stands on, or null when it stands nowhere (carried, or not his). */
    fun standing(id: String): Cell?
    fun hisCell(): Cell
    /** Effect [kind] at [cell], as a verb's needs read it (no lantern). */
    fun effect(kind: EffectKind, cell: Cell): Double
    /** Things with code: they wear with use too, and can be repaired. */
    val coded: Set<String>
    /** Puts [n] of [id] in his things, or takes them; [take] returns how many it took. */
    fun add(id: String, n: Int)
    fun take(id: String, n: Int): Int
    fun skill(name: String): Double
    fun quantity(id: String, n: Int): String
    /** An event in [place]: told now when he's there, else when he next walks in. */
    fun tell(text: String, place: String?)
    fun changed()
}

/**
 * His machines over time: each game minute, every machine he owns that stands somewhere takes what it
 * needs from what its neighbours made, works a batch while it has the inputs, room for the outputs and
 * the effects it needs, wears with each batch, slows as it wears and breaks at 100. He fills and
 * empties them ([tend]) and mends them ([repair]); his code moves things in and out ([transfer]) and
 * wears the things it runs on ([wore]). What happens is told in the machine's place.
 */
class Works {
    private val stores = linkedMapOf<String, Store>()

    fun export(): Map<String, Store> = LinkedHashMap(stores)

    fun restore(s: Map<String, Store>, keep: (String) -> Boolean) {
        stores.clear()
        s.forEach { (id, st) -> if (keep(id)) stores[id] = st }
    }

    fun store(id: String): Store = stores[id] ?: Store()

    private fun set(id: String, s: Store) {
        if (s.isEmpty) stores.remove(id) else stores[id] = s
    }

    /** Ids of the machines he owns, recipe machines first. */
    private fun machines(h: WorksHost): List<String> =
        h.content.items.filter { it.machine != null && h.count(it.id) > 0 }.map { it.id } + h.coded.filter { h.count(it) > 0 && h.content.item(it)?.machine == null }

    fun isMachine(h: WorksHost, id: String): Boolean = h.content.item(id)?.machine != null || id in h.coded

    private fun rules(h: WorksHost) = h.content.machines ?: MachineRules()

    private fun recipes(h: WorksHost, id: String): List<Recipe> = h.content.item(id)?.machine?.runs.orEmpty().mapNotNull { rules(h).recipe(it) }

    private fun holds(h: WorksHost, id: String) = h.content.item(id)?.machine?.holds ?: rules(h).holds

    private fun name(h: WorksHost, id: String) = h.content.item(id)?.name ?: id

    private fun placeOf(h: WorksHost, id: String) = h.standing(id)?.let { h.layout.placeOf(it)?.id }

    /** Everything each of [id]'s recipes takes, in order. */
    private fun takes(h: WorksHost, id: String): List<String> = recipes(h, id).flatMap { it.takes.keys }.distinct()

    /** How much slower it works at its wear: 1 up to [MachineRules.slowAt], 2 at 100. */
    private fun slowness(h: WorksHost, wear: Double): Double {
        val r = rules(h)
        return 1.0 + max(0.0, (wear - r.slowAt) / (MachineRules.BROKEN - r.slowAt))
    }

    /** Why it isn't working now, or null when it is. */
    enum class Stuck(val text: String) { Broken("jammed"), Full("full"), Empty("empty"), Dark("waiting") }

    // --- each minute -------------------------------------------------------------------------------

    /** One game minute of his machines. [minute]: game minutes since day 1 began. */
    fun minute(h: WorksHost, minute: Long) {
        val ids = machines(h).filter { h.content.item(it)?.machine != null }
        if (ids.isEmpty()) return
        if (minute % FEED_EVERY == 0L) ids.forEach { feed(h, it, ids) }
        ids.forEach { work(h, it) }
    }

    /** Takes what [id] needs from what machines within reach of it have made. */
    private fun feed(h: WorksHost, id: String, all: List<String>) {
        val at = h.standing(id) ?: return
        var s = store(id)
        val room = holds(h, id)
        val wants = takes(h, id).filter { (s.inputs[it] ?: 0) < room }
        if (wants.isEmpty()) return
        val place = h.layout.placeOf(at)
        val reach = rules(h).reach
        for (other in all) {
            if (other == id) continue
            val c = h.standing(other) ?: continue
            if (h.layout.placeOf(c) != place || max(abs(c.x - at.x), abs(c.y - at.y)) > reach) continue
            var o = store(other)
            val moved = mutableListOf<String>()
            for (item in wants) {
                val n = min(o.outputs[item] ?: 0, room - (s.inputs[item] ?: 0))
                if (n <= 0) continue
                o = o.copy(outputs = o.outputs.minusCount(item, n), told = o.told.takeIf { it != Stuck.Full.text })
                s = s.copy(inputs = s.inputs.plusCount(item, n))
                moved += h.quantity(item, n)
            }
            if (moved.isEmpty()) continue
            set(other, o)
            set(id, s)
            h.tell("the ${name(h, id)} took ${moved.joinToString(" and ")} from the ${name(h, other)}", placeOf(h, id))
            h.changed()
        }
    }

    /** A minute of work on [id]'s batch. */
    private fun work(h: WorksHost, id: String) {
        val at = h.standing(id) ?: return
        val s = store(id)
        if (s.broken) return stuck(h, id, s, Stuck.Broken)
        val recipe = s.recipe?.let { r -> recipes(h, id).firstOrNull { it.id == r } }?.takeIf { has(s, it) }
            ?: recipes(h, id).firstOrNull { has(s, it) }
            ?: return stuck(h, id, s.copy(recipe = null, progress = 0.0), Stuck.Empty)
        val room = holds(h, id)
        if (recipe.makes.any { (item, n) -> (s.outputs[item] ?: 0) + n > room }) return stuck(h, id, s, Stuck.Full)
        if (recipe.needs.any { (k, v) -> h.effect(EffectKind.of(k)!!, at) < v }) return stuck(h, id, s.copy(recipe = recipe.id), Stuck.Dark)
        val progress = (if (s.recipe == recipe.id) s.progress else 0.0) + 1.0 / slowness(h, s.wear)
        if (progress < recipe.hours * 60) {
            val was = s.copy(recipe = recipe.id, progress = progress, told = null)
            set(id, was)
            // Its readout moves in steps of 10%.
            if (percent(recipe, s) != percent(recipe, was) || s.told != null || s.recipe != recipe.id) h.changed()
            return
        }
        // A batch is done: what it took is used up, what it made waits in it, and it wears.
        val r = rules(h)
        val wear = min(MachineRules.BROKEN, s.wear + (h.content.item(id)?.machine?.wear ?: r.wearPerBatch))
        set(id, s.copy(inputs = recipe.takes.entries.fold(s.inputs) { m, (item, n) -> m.minusCount(item, n) }, outputs = recipe.makes.entries.fold(s.outputs) { m, (item, n) -> m.plusCount(item, n) }, wear = wear, progress = 0.0, recipe = null, told = null))
        val made = recipe.makes.entries.joinToString(" and ") { (item, n) -> h.quantity(item, n) }
        h.tell("the ${name(h, id)} made $made" + wearNote(h, s.wear, wear), placeOf(h, id))
        h.changed()
    }

    private fun has(s: Store, r: Recipe) = r.takes.all { (item, n) -> (s.inputs[item] ?: 0) >= n }

    private fun percent(r: Recipe, s: Store) = (s.progress / (r.hours * 60) * 10).toInt() * 10

    /** "; it's wearing, and slower now" or "; it jammed and needs repairing", when [was] to [now] crossed a line. */
    private fun wearNote(h: WorksHost, was: Double, now: Double): String = when {
        now >= MachineRules.BROKEN && was < MachineRules.BROKEN -> "; then it jammed and needs repairing"
        now > rules(h).slowAt && was <= rules(h).slowAt -> "; it's wearing, and works slower now"
        else -> ""
    }

    /** It can't work: told once, until it works again. Waiting for light or for something to take isn't news. */
    private fun stuck(h: WorksHost, id: String, s: Store, why: Stuck) {
        if (s.told == why.text) return set(id, s)
        set(id, s.copy(told = why.text))
        h.changed()
        when (why) {
            Stuck.Full -> h.tell("the ${name(h, id)} is full: what it made is waiting to be taken out", placeOf(h, id))
            Stuck.Broken -> {}
            Stuck.Empty, Stuck.Dark -> {}
        }
    }

    // --- wear from code ----------------------------------------------------------------------------

    /** A run of [id]'s code changed something: it wears. Past 100 its code stops until it's repaired. */
    fun wore(h: WorksHost, id: String) {
        val s = store(id)
        if (s.broken) return
        val now = min(MachineRules.BROKEN, s.wear + rules(h).wearPerRun)
        set(id, s.copy(wear = now))
        if (now >= MachineRules.BROKEN) {
            h.tell("your ${name(h, id)} wore out and stopped; it needs repairing before it does anything again", placeOf(h, id))
            h.changed()
        } else if (now > rules(h).slowAt && s.wear <= rules(h).slowAt) h.changed()
    }

    fun broken(id: String): Boolean = store(id).broken

    // --- what he does at a machine -----------------------------------------------------------------

    /** Why tending [id] would do nothing now, or null. */
    fun tendLock(h: WorksHost, id: String): String? {
        val takes = takes(h, id)
        if (takes.isEmpty()) return "the ${name(h, id)} takes nothing and makes nothing to take out"
        val s = store(id)
        if (s.outputs.isNotEmpty() || loadable(h, id).isNotEmpty()) return null
        return "nothing to take out of the ${name(h, id)}, and you have nothing it takes (" + takes.joinToString(" or ") { name(h, it) } + ")" +
            if (takes.all { (s.inputs[it] ?: 0) >= holds(h, id) }) "; it's full of what it takes" else ""
    }

    /** What of his would go in, item to count. */
    private fun loadable(h: WorksHost, id: String): Map<String, Int> {
        val s = store(id)
        return takes(h, id).associateWith { min(h.count(it), holds(h, id) - (s.inputs[it] ?: 0)) }.filterValues { it > 0 }
    }

    /** "put in 3 food scraps; take out 1 compost brick", for the option. */
    fun tendText(h: WorksHost, id: String): String {
        val s = store(id)
        val put = loadable(h, id).entries.joinToString(" and ") { (item, n) -> h.quantity(item, n) }
        val out = s.outputs.entries.joinToString(" and ") { (item, n) -> h.quantity(item, n) }
        return listOfNotNull(put.takeIf { it.isNotEmpty() }?.let { "put in $it" }, out.takeIf { it.isNotEmpty() }?.let { "take out $it" }).joinToString("; ")
            .ifEmpty { "fill and empty the ${name(h, id)}" } + status(h, id)?.let { " (it's $it)" }.orEmpty()
    }

    /** Fills [id] with what he has that it takes, and takes out what it made; the event's parts. */
    fun tend(h: WorksHost, id: String): List<String> {
        var s = store(id)
        val parts = mutableListOf<String>()
        loadable(h, id).forEach { (item, n) ->
            val k = h.take(item, n)
            if (k > 0) {
                s = s.copy(inputs = s.inputs.plusCount(item, k))
                parts += "put ${h.quantity(item, k)} in the ${name(h, id)}"
            }
        }
        if (s.outputs.isNotEmpty()) {
            s.outputs.forEach { (item, n) -> h.add(item, n) }
            parts += "took out " + s.outputs.entries.joinToString(" and ") { (item, n) -> h.quantity(item, n) }
            s = s.copy(outputs = emptyMap())
        }
        set(id, s.copy(told = s.told.takeIf { it == Stuck.Broken.text }))
        h.changed()
        return parts + listOfNotNull(status(h, id)?.let { "it's $it" })
    }

    /** "jammed", "worn (wear 60)", or null for a machine in good order. */
    fun status(h: WorksHost, id: String): String? {
        val s = store(id)
        return when {
            s.broken -> "jammed"
            s.wear > rules(h).slowAt -> "worn (wear ${s.wear.roundToInt()})"
            else -> null
        }
    }

    /** Why [id] doesn't need repairing now, or null. */
    fun repairLock(h: WorksHost, id: String): String? {
        val s = store(id)
        val at = rules(h).repair.wornAt
        return if (s.wear < at) "the ${name(h, id)} doesn't need mending" + (if (s.wear > 0) " (wear ${s.wear.roundToInt()}; mend it from $at)" else "") else null
    }

    /** What a repair would take off now. */
    private fun restores(h: WorksHost): Double = rules(h).repair.let { r -> r.restores + r.perLevel * (r.skill?.let(h::skill) ?: 0.0) }

    fun repairText(h: WorksHost, id: String): String {
        val s = store(id)
        val after = max(0.0, s.wear - restores(h))
        return "mend the ${name(h, id)}: wear ${s.wear.roundToInt()} to ${after.roundToInt()}" + if (s.broken) ", and it works again" else ""
    }

    /** Mends [id]; the event's parts. */
    fun repair(h: WorksHost, id: String): List<String> {
        val s = store(id)
        val after = max(0.0, s.wear - restores(h))
        set(id, s.copy(wear = after, told = null))
        h.changed()
        return listOf("mended the ${name(h, id)}: wear ${s.wear.roundToInt()} to ${after.roundToInt()}" + if (s.broken) "; it works again" else "")
    }

    // --- what his code sees and does ----------------------------------------------------------------

    /** `world.machine(id)`: what it runs, holds and has made, its wear, and how it's doing. */
    fun view(h: WorksHost, id: String): Map<String, Any?> {
        val s = store(id)
        val recipe = s.recipe?.let { rules(h).recipe(it) }
        return mapOf(
            "runs" to recipes(h, id).map { r -> mapOf("id" to r.id, "takes" to r.takes, "makes" to r.makes, "hours" to r.hours, "needs" to r.needs) },
            "holds" to holds(h, id),
            "in" to s.inputs, "out" to s.outputs,
            "wear" to (s.wear * 10).roundToInt() / 10.0, "broken" to s.broken,
            "making" to recipe?.id, "done" to (recipe?.let { (s.progress / (it.hours * 60) * 100).roundToInt() } ?: 0),
            "stuck" to s.told,
        )
    }

    /**
     * `world.transfer(from, to, item, n)`: moves [n] of [item] from [from] to [to], each a machine of his
     * or `self` (him). Out of a machine comes what it made, or else what was put in; into one goes only
     * what it takes, up to what it holds. A machine and him, or two machines, must be within reach of
     * each other, and the code's thing ([self]) must be one of them or within reach. Returns how many moved.
     */
    fun transfer(h: WorksHost, from: String, to: String, item: String, n: Int, self: String?, did: MutableList<String>): Int {
        if (n !in 1..MOST_MOVED) throw ScriptProblem("move 1 to $MOST_MOVED at a time")
        if (from == to) throw ScriptProblem("from and to are the same")
        val spec = h.content.item(item) ?: throw ScriptProblem("there's no kind of thing '$item'")
        fun machine(id: String): Cell {
            val s = h.content.item(id) ?: throw ScriptProblem("there's no thing '$id'")
            if (h.count(id) == 0) throw ScriptProblem("you don't own a ${s.name}")
            if (!isMachine(h, id)) throw ScriptProblem("the ${s.name} isn't a machine: it takes and makes nothing")
            return h.standing(id) ?: throw ScriptProblem("the ${s.name} isn't standing anywhere")
        }
        val a = if (from == Reach.SELF) h.hisCell() else machine(from)
        val b = if (to == Reach.SELF) h.hisCell() else machine(to)
        val reach = rules(h).reach
        fun near(x: Cell, y: Cell) = h.layout.placeOf(x) == h.layout.placeOf(y) && max(abs(x.x - y.x), abs(x.y - y.y)) <= reach
        if (!near(a, b)) throw ScriptProblem("${who(h, from)} and ${who(h, to)} are too far apart: things only pass between what's within $reach tiles, in the same place")
        self?.let { me -> h.standing(me)?.let { c -> if (me != from && me != to && !near(c, a)) throw ScriptProblem("your ${name(h, me)} is too far from ${who(h, from)} to move anything") } }
        val have = if (from == Reach.SELF) h.count(item) else store(from).let { (it.outputs[item] ?: 0) + (it.inputs[item] ?: 0) }
        if (have == 0) throw ScriptProblem("${who(h, from)} has no ${spec.name}")
        var k = min(n, have)
        if (to != Reach.SELF) {
            if (item !in takes(h, to)) throw ScriptProblem("the ${name(h, to)} doesn't take ${spec.name}" + takes(h, to).takeIf { it.isNotEmpty() }?.let { "; it takes ${it.joinToString { name(h, it) }}" }.orEmpty())
            k = min(k, holds(h, to) - (store(to).inputs[item] ?: 0))
            if (k <= 0) throw ScriptProblem("the ${name(h, to)} is full of ${spec.name}")
        }
        if (from == Reach.SELF) h.take(item, k) else {
            val s = store(from)
            val out = min(k, s.outputs[item] ?: 0)
            set(from, s.copy(outputs = s.outputs.minusCount(item, out), inputs = s.inputs.minusCount(item, k - out), told = null))
        }
        if (to == Reach.SELF) h.add(item, k) else store(to).let { set(to, it.copy(inputs = it.inputs.plusCount(item, k), told = null)) }
        did += "moved ${h.quantity(item, k)} from ${who(h, from)} to ${who(h, to)}"
        h.changed()
        return k
    }

    private fun who(h: WorksHost, id: String) = if (id == Reach.SELF) "you" else "the ${name(h, id)}"

    // --- how it shows ------------------------------------------------------------------------------

    /** Its readout: what it's doing and how far, or why not ("composting 40%", "full", "jammed"). Null for a thing without recipes in good order. */
    fun readout(h: WorksHost, id: String): String? {
        val s = store(id)
        if (s.broken) return Stuck.Broken.text
        val worn = if (s.wear > rules(h).slowAt) " · worn" else ""
        if (recipes(h, id).isEmpty()) return worn.removePrefix(" · ").ifEmpty { null }
        val recipe = s.recipe?.let { rules(h).recipe(it) }
        val text = when {
            s.told == Stuck.Full.text -> "full"
            s.told == Stuck.Dark.text && recipe != null -> "${recipe.says}: dark"
            recipe != null -> "${recipe.says} ${percent(recipe, s)}%"
            s.outputs.isNotEmpty() -> "ready"
            else -> "empty"
        }
        return (text + worn).take(Machines.READOUT)
    }

    /** The entity's state, for what he sees: "jammed", "full" or "worn"; null otherwise. */
    fun state(h: WorksHost, id: String): String? {
        val s = store(id)
        return when {
            s.broken -> "jammed"
            s.told == Stuck.Full.text -> "full"
            s.wear > rules(h).slowAt -> "worn"
            else -> null
        }
    }

    companion object {
        /** Minutes between machines taking from their neighbours. */
        const val FEED_EVERY = 10
        const val MOST_MOVED = 20
        const val MACHINE = "machine"

        private fun Map<String, Int>.plusCount(item: String, n: Int) = LinkedHashMap(this).also { it[item] = (it[item] ?: 0) + n }
        private fun Map<String, Int>.minusCount(item: String, n: Int) = LinkedHashMap(this).also { m ->
            val left = (m[item] ?: 0) - n
            if (left > 0) m[item] = left else m.remove(item)
        }
    }
}

/** Fills the machine bound to [on] with what he has that it takes, and takes out what it made. */
data class TendOp(val on: String) : Op() {
    override val key get() = "tend"

    override fun problems(c: BedroomContent, verb: VerbSpec) = listOfNotNull(if (verb.args[on] == null) "tend's machine must be an argument slot (`on`)" else null)

    override fun lockReason(w: OpWorld, b: Bound) = b.args[on]?.let(w::tendLock)

    override fun finish(w: OpWorld, b: Bound) = w.tend(b.resolve(on))

    override fun describe(w: OpWorld, b: Bound, forSale: Boolean) = if (forSale) emptyList() else listOfNotNull(b.args[on]?.let(w::tendText))
}

/** Mends the machine bound to [on]: takes wear off it (rules.yaml `machines.repair`), and a broken one works again. */
data class RepairOp(val on: String) : Op() {
    override val key get() = "repair"

    override fun problems(c: BedroomContent, verb: VerbSpec) = listOfNotNull(
        if (verb.args[on] == null) "repair's machine must be an argument slot (`on`)" else null,
        if (c.machines == null) "repairs, but rules.yaml has no `machines`" else null,
    )

    override fun lockReason(w: OpWorld, b: Bound) = b.args[on]?.let(w::repairLock)

    override fun gone(w: OpWorld, b: Bound) = w.repairLock(b.resolve(on))

    override fun finish(w: OpWorld, b: Bound) = w.repair(b.resolve(on))

    override fun describe(w: OpWorld, b: Bound, forSale: Boolean) = if (forSale) emptyList() else listOfNotNull(b.args[on]?.let(w::repairText))
}
