package dev.lilguy.sim

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.math.roundToInt

/**
 * One thing a verb does to the world, from its `effects:` list. Each op owns its own content checks
 * ([problems]), why it can't happen now ([lockReason]), what it does ([finish], [minute]) and how
 * the prompt says it ([describe]), so a new mechanic is one class here plus its row in [OpEntry].
 * [BedroomWorld] runs a verb's ops in the order they're listed; the order is also the order of the
 * finish event's parts and of the option's "gives".
 *
 * The world side is [OpWorld], which [BedroomWorld] implements privately. [Bound] is one doing of a
 * verb: the verb, the items its arguments are bound to, and how fresh it was. Where an op names an
 * item it may name an argument slot instead (`enjoy: what`), meaning the item bound to it.
 */
@Serializable(with = OpSerializer::class)
sealed class Op {
    /** Its key in content: `need`, `sell`, ... */
    abstract val key: String

    /** Item ids or affordances it asks him to own, for lock reasons, the shop's "for" and the kind check. */
    open val asksFor: Collection<String> get() = emptyList()

    /** False for an op that puts him to sleep: the awake drains stop while it runs. */
    open val awake: Boolean get() = true

    /** What's wrong with this op in [verb], as messages; empty when it's fine. */
    internal open fun problems(c: BedroomContent, verb: VerbSpec): List<String> = emptyList()

    /** Why the verb can't be done now because of this op, or null. */
    internal open fun lockReason(w: OpWorld, b: Bound): String? = null

    /** Checked as the verb finishes, before anything is applied: why it no longer can, or null. */
    internal open fun gone(w: OpWorld, b: Bound): String? = null

    /** Applies it as the verb finishes; returns the event's parts. */
    internal open fun finish(w: OpWorld, b: Bound): List<String> = emptyList()

    /** Each game minute while the verb runs. */
    internal open fun minute(w: OpWorld, b: Bound) {}

    /** What it gives, for the option list. [forSale]: said of a verb he can't do yet, so only what it makes and uses. */
    internal open fun describe(w: OpWorld, b: Bound, forSale: Boolean): List<String> = emptyList()

    /** Added to how long the verb takes, such as ", however much you sell". */
    internal open fun takes(): String = ""

    companion object {
        /** Every op's key, in the order [OpEntry] lists them. */
        val KEYS: List<String> get() = (0 until OpEntry.serializer().descriptor.elementsCount).map { OpEntry.serializer().descriptor.getElementName(it) }
    }
}

/** Need changes when the verb finishes, scaled by how fresh it was. */
data class NeedOp(val changes: Map<String, Double>) : Op() {
    override val key get() = "need"

    override fun problems(c: BedroomContent, verb: VerbSpec) = needProblems(changes.keys)

    override fun finish(w: OpWorld, b: Bound) = changes.map { (need, d) -> "$need ${signed(w.change(needOf(need), b.paid(need, d), b.verb.id))}" }

    override fun describe(w: OpWorld, b: Bound, forSale: Boolean) = if (forSale) emptyList() else changes.map { (need, d) -> b.shown(need, d) }
}

/** Need changes per game hour while the verb runs, paid each minute. */
data class RateOp(val perHour: Map<String, Double>) : Op() {
    override val key get() = "rate"

    override fun problems(c: BedroomContent, verb: VerbSpec) = needProblems(perHour.keys)

    override fun minute(w: OpWorld, b: Bound) = perHour.forEach { (need, d) -> w.rate(needOf(need), b.paid(need, d), b.verb.id) }

    override fun finish(w: OpWorld, b: Bound) = listOf(perHour.keys.joinToString { "$it ${w.level(needOf(it))}" })

    override fun describe(w: OpWorld, b: Bound, forSale: Boolean) = if (forSale) emptyList() else perHour.map { (need, d) -> b.shown(need, d) + "/h" }
}

/** Uses up items (by id or affordance) when the verb finishes. */
data class ConsumeOp(val items: Map<String, Int>) : Op() {
    override val key get() = "consume"
    override val asksFor get() = items.keys

    override fun problems(c: BedroomContent, verb: VerbSpec) = items.keys.mapNotNull { c.unknownName(verb.args[it] ?: it) } +
        items.filterValues { it < 1 }.keys.map { "consumes $it: a count of at least 1" }

    override fun lockReason(w: OpWorld, b: Bound) = items.entries.firstOrNull { (tag, n) -> w.count(b.resolve(tag), b.extra) < n }?.let { "needs ${w.wanted(b.resolve(it.key))}" }

    override fun gone(w: OpWorld, b: Bound) = items.entries.firstOrNull { (tag, n) -> w.count(b.resolve(tag)) < n }?.let { "you no longer have ${w.wanted(b.resolve(it.key))}" }

    override fun finish(w: OpWorld, b: Bound) = items.flatMap { (tag, n) -> w.take(b.resolve(tag), n).map { (id, k) -> "used ${w.quantity(id, k)}" } }

    override fun describe(w: OpWorld, b: Bound, forSale: Boolean) = items.map { (tag, n) ->
        "uses ${if (n == 1) "a" else "$n"} ${w.noun(b.resolve(tag))}" + if (forSale) "" else " (you have ${w.count(b.resolve(tag))})"
    }
}

/** Makes items when the verb finishes. */
data class ProduceOp(val items: Map<String, Int>) : Op() {
    override val key get() = "produce"

    override fun problems(c: BedroomContent, verb: VerbSpec) = items.keys.filter { c.item(it) == null }.map { "produces '$it', which isn't an item" }

    override fun finish(w: OpWorld, b: Bound) = items.map { (id, n) ->
        w.add(id, n)
        "+${w.quantity(id, n)}"
    }

    override fun describe(w: OpWorld, b: Bound, forSale: Boolean) = items.map { (id, n) -> w.quantity(id, n) + (w.item(id).sellPrice?.let { ", sells for $$it" } ?: "") }
}

/** Sells every carried item with this affordance (or id) at its `sellable` price. */
data class SellOp(val what: String) : Op() {
    override val key get() = "sell"

    override fun problems(c: BedroomContent, verb: VerbSpec) = listOfNotNull(c.unknownName(what)) +
        c.withName(what).filter { it.sellPrice == null }.map { "sells ${it.id}, which isn't sellable" }

    override fun lockReason(w: OpWorld, b: Bound): String? {
        if (w.count(what) > 0) return null
        val makers = w.content.verbs.filter { m -> m.effects.any { op -> op is ProduceOp && op.items.keys.any { what in w.item(it).names } } }.map { it.id }
        return "you have no ${w.noun(what)} to sell" + if (makers.isEmpty()) "" else "; ${makers.joinToString(" or ")} makes some"
    }

    override fun finish(w: OpWorld, b: Bound) = listOf(w.sell(what))

    override fun describe(w: OpWorld, b: Bound, forSale: Boolean) = if (forSale) emptyList() else listOf(w.saleText(what))

    override fun takes() = ", however much you sell"
}

/**
 * Puts an item he holds into a [state]. The one state so far is `hung`: his newest held piece
 * answering to [target] goes on the picture rail ([GalleryRules]).
 */
data class SetStateOp(val target: String, val state: String) : Op() {
    override val key get() = "set_state"

    override fun problems(c: BedroomContent, verb: VerbSpec) = listOfNotNull(
        c.unknownName(verb.args[target] ?: target),
        if (state in STATES) null else "set_state: state '$state' isn't one of $STATES",
    )

    override fun lockReason(w: OpWorld, b: Bound) = w.hangLock(b.resolve(target))

    override fun finish(w: OpWorld, b: Bound) = listOf(w.hang(b.resolve(target)))

    override fun describe(w: OpWorld, b: Bound, forSale: Boolean) = if (forSale) emptyList() else w.hangText(b.resolve(target))

    companion object {
        val STATES = listOf("hung")
    }
}

/** Starts something that keeps going while he does anything else: the item bound to `what` (a record) [rates] per hour for [minutes]. */
data class BackgroundOp(val name: String, val minutes: Int, val rates: Map<String, Double>) : Op() {
    override val key get() = "background"

    override fun problems(c: BedroomContent, verb: VerbSpec) = needProblems(rates.keys) +
        listOfNotNull(if (minutes < 1) "background $name: at least 1 minute" else null, if ("what" !in verb.args) "background needs a `what` argument: the item it plays" else null)

    override fun finish(w: OpWorld, b: Bound) = listOf(w.play(b.args.getValue("what"), b.args["on"], this, b.fresh))

    override fun describe(w: OpWorld, b: Bound, forSale: Boolean) =
        if (forSale) emptyList() else listOf("$name for ${GameClock.duration(minutes)}: " + rates.entries.joinToString { (need, d) -> b.shown(need, d) + "/h" })
}

/** He's asleep while the verb runs: no awake energy or stimulation drain. */
data object SleepOp : Op() {
    override val key get() = "sleep"
    override val awake get() = false
}

/**
 * Pays the bound item's value to him ([BedroomContent.value]) as mood when the verb finishes, scaled
 * by freshness: liking a record or a book. [what] names the argument slot.
 */
data class EnjoyOp(val what: String) : Op() {
    override val key get() = "enjoy"

    override fun problems(c: BedroomContent, verb: VerbSpec) = if (what in verb.args) emptyList() else listOf("enjoy names '$what', which isn't an argument of this verb")

    /** Null until bound (a locked option, or the shop's "what it's for"). */
    private fun liking(w: OpWorld, b: Bound): Double = b.args[what]?.let { w.content.value(w.item(it)) * w.content.qualityValue.inUse } ?: 0.0

    override fun finish(w: OpWorld, b: Bound): List<String> {
        val d = liking(w, b).takeIf { it != 0.0 } ?: return emptyList()
        val item = b.args.getValue(what)
        return listOf("${if (d > 0) "you like" else "you don't like"} the ${w.item(item).name}: mood ${signed(w.change(Need.Mood, b.paid("mood", d), "liking $item"))}")
    }

    override fun describe(w: OpWorld, b: Bound, forSale: Boolean): List<String> {
        if (forSale) return emptyList()
        val d = liking(w, b).roundToInt().takeIf { it != 0 } ?: return emptyList()
        return listOf((if (d > 0) "you like it: " else "you don't like it: ") + b.shown("mood", d.toDouble()))
    }
}

/**
 * What [Op]s need from the world. [BedroomWorld] implements it privately; nothing else should.
 * [count] and [take] work by id or affordance.
 */
internal interface OpWorld {
    val content: BedroomContent

    fun item(id: String): ItemSpec

    /** How many owned items answer to [tag], plus one more [extra] if given ("what if he bought it"). */
    fun count(tag: String, extra: String? = null): Int

    fun take(tag: String, n: Int): Map<String, Int>

    fun add(id: String, n: Int)

    /** Changes [need] by [d] now; returns what was applied. */
    fun change(need: Need, d: Double, reason: String): Double

    /** One game minute of [perHour]. */
    fun rate(need: Need, perHour: Double, reason: String)

    fun level(need: Need): Int

    /** "book" for an affordance its items share, else the item's name. */
    fun noun(tag: String): String

    /** "an easel ($17)": the cheapest item answering to [tag]. */
    fun wanted(tag: String): String

    /** "3 meals". */
    fun quantity(id: String, n: Int): String

    /** Sells what he holds with [tag]; returns the event text. */
    fun sell(tag: String): String

    /** "$6 for the 2 sketches you hold". */
    fun saleText(tag: String): String

    fun hangLock(tag: String): String?

    fun hang(tag: String): String

    fun hangText(tag: String): List<String>

    /** Starts [bg] playing [item] on [on]; returns the event text. */
    fun play(item: String, on: String?, bg: BackgroundOp, fresh: Double): String
}

/**
 * One doing of [verb]: [args] is the item id bound to each argument slot (empty before binding),
 * [fresh] how fresh the habit was when it started (1 for a new or routine one). [extra]: for lock
 * reasons, as if he also owned that item.
 */
internal class Bound(val verb: VerbSpec, val args: Map<String, String>, val fresh: Double, val extra: String? = null) {
    /** The item bound to [name] when it's an argument slot, otherwise [name] itself (an id or affordance); unbound, the slot's type. */
    fun resolve(name: String): String = args[name] ?: verb.args[name] ?: name

    /** [d] as paid at [fresh]: gains in mood and stimulation shrink with wear; costs and other needs don't. */
    fun paid(need: String, d: Double): Double = if (d > 0 && (need == Need.Mood.id || need == Need.Stimulation.id)) d * fresh else d

    /** "mood +2", rounded, as paid now. */
    fun shown(need: String, d: Double) = "$need ${signed(paid(need, d).roundToInt().toDouble())}"
}

/** "+3", "-1.5". */
internal fun signed(v: Double): String {
    val text = if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(v)
    return if (v >= 0) "+$text" else text
}

private fun needProblems(names: Collection<String>): List<String> = names.mapNotNull { runCatching { needOf(it) }.exceptionOrNull()?.message }

/**
 * How an op is written in content: a one-key map, `- need: { mood: 2 }`. Exactly one field is set;
 * [OpSerializer] turns it into the [Op]. Its descriptor is what the content schema shows.
 */
@Serializable
@SerialName("op")
internal data class OpEntry(
    val need: Map<String, Double>? = null,
    val rate: Map<String, Double>? = null,
    val consume: Map<String, Int>? = null,
    val produce: Map<String, Int>? = null,
    val sell: String? = null,
    @SerialName("set_state") val setState: SetState? = null,
    val background: Background? = null,
    val sleep: NoParams? = null,
    val enjoy: String? = null,
) {
    @Serializable
    @SerialName("set_state")
    data class SetState(val target: String, val state: String)

    @Serializable
    @SerialName("background")
    data class Background(val name: String, val minutes: Int, val rates: Map<String, Double>)

    /** An op that takes nothing: `- sleep: {}`. */
    @Serializable
    @SerialName("no_params")
    class NoParams

    fun toOp(): Op {
        val set = listOfNotNull(
            need?.let(::NeedOp), rate?.let(::RateOp), consume?.let(::ConsumeOp), produce?.let(::ProduceOp), sell?.let(::SellOp),
            setState?.let { SetStateOp(it.target, it.state) }, background?.let { BackgroundOp(it.name, it.minutes, it.rates) },
            sleep?.let { SleepOp }, enjoy?.let(::EnjoyOp),
        )
        require(set.size == 1) { "an effect is one op, such as `- need: { mood: 2 }`; this one has ${if (set.isEmpty()) "none" else set.joinToString { it.key }}" }
        return set.single()
    }

    companion object {
        fun of(op: Op): OpEntry = when (op) {
            is NeedOp -> OpEntry(need = op.changes)
            is RateOp -> OpEntry(rate = op.perHour)
            is ConsumeOp -> OpEntry(consume = op.items)
            is ProduceOp -> OpEntry(produce = op.items)
            is SellOp -> OpEntry(sell = op.what)
            is SetStateOp -> OpEntry(setState = SetState(op.target, op.state))
            is BackgroundOp -> OpEntry(background = Background(op.name, op.minutes, op.rates))
            SleepOp -> OpEntry(sleep = NoParams())
            is EnjoyOp -> OpEntry(enjoy = op.what)
        }
    }
}

internal object OpSerializer : KSerializer<Op> {
    override val descriptor: SerialDescriptor = OpEntry.serializer().descriptor

    override fun deserialize(decoder: Decoder): Op = decoder.decodeSerializableValue(OpEntry.serializer()).toOp()

    override fun serialize(encoder: Encoder, value: Op) = encoder.encodeSerializableValue(OpEntry.serializer(), OpEntry.of(value))
}
