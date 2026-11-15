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
 * Ops whose result a model writes are [AuthoredOp]s (Authored.kt).
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

    override fun describe(w: OpWorld, b: Bound, forSale: Boolean) = items.map { (id, n) -> w.quantity(id, n) + (w.priceOf(id)?.let { ", sells for $$it" } ?: "") }
}

/** Sells every carried item with this affordance (or id) at its `sellable` price. */
data class SellOp(val what: String) : Op() {
    override val key get() = "sell"

    /** What it sells: an id or affordance, or an argument slot naming the item bound to it (`sell: what`). */
    override fun problems(c: BedroomContent, verb: VerbSpec): List<String> {
        val name = verb.args[what] ?: what
        return listOfNotNull(c.unknownName(name)) + c.withName(name).filter { it.sellPrice == null }.map { "sells ${it.id}, which isn't sellable" }
    }

    override fun lockReason(w: OpWorld, b: Bound): String? {
        val what = b.resolve(what)
        if (w.count(what) > 0) return null
        val makers = w.content.verbs.filter { m -> w.knows(m.id) && m.effects.any { op -> op is ProduceOp && op.items.keys.any { what in w.item(it).names } } }.map { it.id }
        return "you have no ${w.noun(what)} to sell" + if (makers.isEmpty()) "" else "; ${makers.joinToString(" or ")} makes some"
    }

    override fun finish(w: OpWorld, b: Bound) = listOf(w.sell(b.resolve(what)))

    override fun describe(w: OpWorld, b: Bound, forSale: Boolean) = if (forSale) emptyList() else listOf(w.saleText(b.resolve(what)))

    override fun takes() = ", however much you sell"
}

/**
 * A shopping trip's checkout: buys what he picked on the trip, up to [most] items, in order, each
 * checked as a `buy` is, so what he can't have or afford is turned away and named. What he picked
 * comes from the trip's `write: { with: shopping, timing: before }` (the agent layer stages it
 * through [BedroomWorld.stage]), as does what he's saving for, which the trip's option shows.
 */
data class BasketOp(val most: Int) : Op() {
    override val key get() = "basket"

    override fun problems(c: BedroomContent, verb: VerbSpec) = listOfNotNull(
        if (most < 1) "basket: most must be at least 1" else null,
        if (verb.effects.none { it is WriteOp && it.with == "shopping" && it.timing == Timing.Before }) "basket: the verb needs a `write: { with: shopping, timing: before }` to pick what goes in it" else null,
    )

    override fun lockReason(w: OpWorld, b: Bound) = w.tripLock()

    override fun gone(w: OpWorld, b: Bound) = w.tripFailed(b.verb.id)

    override fun finish(w: OpWorld, b: Bound) = w.checkout(b.verb.id, most)

    override fun describe(w: OpWorld, b: Bound, forSale: Boolean) = if (forSale) emptyList() else listOf(w.tripText())
}

/**
 * Builds the thing he designed: checks the design against the invent rules ([InventRules.check]),
 * charges its materials, adds it to the content of his world and gives him the first one. The
 * design comes from the verb's `write: { with: invention, timing: before }` (the agent layer
 * stages it through [BedroomWorld.stage]); a design that's refused fails the verb and says why.
 */
data object BuildOp : Op() {
    override val key get() = "build"

    override fun problems(c: BedroomContent, verb: VerbSpec) = listOfNotNull(
        if (c.invent == null) "build: the rules have no `invent`" else null,
        if (verb.minutes == null) "build: the verb needs `minutes`" else null,
        if (verb.effects.none { it is WriteOp && it.with == "invention" && it.timing == Timing.Before }) "build: the verb needs a `write: { with: invention, timing: before }` to design what's built" else null,
    )

    override fun lockReason(w: OpWorld, b: Bound) = w.buildLock()

    override fun gone(w: OpWorld, b: Bound) = w.buildFailed(b.verb)

    override fun finish(w: OpWorld, b: Bound) = w.build(b.verb)

    override fun describe(w: OpWorld, b: Bound, forSale: Boolean) = listOf(w.buildText())
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

/**
 * Listens to whatever another verb's `background` op named [background] is playing, so the verb takes
 * no item of its own. It's locked unless that background is playing with time left for the whole verb,
 * and pays the playing item's value to him as mood when it finishes, as [EnjoyOp] does.
 */
data class HearOp(val background: String) : Op() {
    override val key get() = "hear"

    override fun problems(c: BedroomContent, verb: VerbSpec) =
        if (starters(c).isEmpty()) listOf("hear: no verb starts a background called '$background'") else emptyList()

    /** The verbs whose `background` op starts it. */
    private fun starters(c: BedroomContent) = c.verbs.filter { v -> v.ops<BackgroundOp>().any { it.name == background } }

    override fun lockReason(w: OpWorld, b: Bound): String? {
        val start = starters(w.content).filter { w.knows(it.id) }.joinToString(" or ") { it.id }
        val (item, left) = w.playing(background) ?: return "needs $background playing; $start puts some on"
        val minutes = b.verb.minutes ?: return null
        return if (left < minutes) "the ${w.item(item).name} won't last the ${GameClock.duration(minutes)} ${b.verb.id} takes; $start starts it again" else null
    }

    private fun liking(w: OpWorld, item: String) = w.value(w.item(item)) * w.content.qualityValue.inUse

    override fun finish(w: OpWorld, b: Bound): List<String> {
        val item = w.playing(background)?.first ?: return listOf("the $background had stopped")
        val d = liking(w, item)
        if (d == 0.0) return listOf("listened to the ${w.item(item).name}")
        return listOf("${if (d > 0) "you like" else "you don't like"} the ${w.item(item).name}: mood ${signed(w.change(Need.Mood, b.paid("mood", d), "liking $item"))}")
    }

    override fun describe(w: OpWorld, b: Bound, forSale: Boolean): List<String> {
        if (forSale) return listOf("listens to whatever is playing")
        val item = w.playing(background)?.first ?: return emptyList()
        val d = liking(w, item).roundToInt()
        return listOf("the ${w.item(item).name} that's playing" + when {
            d > 0 -> ": you like it: " + b.shown("mood", d.toDouble())
            d < 0 -> ": you don't like it: " + b.shown("mood", d.toDouble())
            else -> ""
        })
    }
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
    private fun liking(w: OpWorld, b: Bound): Double = b.args[what]?.let { w.value(w.item(it)) * w.content.qualityValue.inUse } ?: 0.0

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
 * Reads the next chapter of the book bound to [book] (an argument slot), when the item is a book
 * ([BookSpec]), which raises the book's skills; reading a finished book again rereads it, with no
 * progress and no skill. Finishing the last chapter teaches him what the book teaches.
 */
data class ProgressOp(val book: String) : Op() {
    override val key get() = "progress"

    override fun problems(c: BedroomContent, verb: VerbSpec) = if (book in verb.args) emptyList() else listOf("progress names '$book', which isn't an argument of this verb")

    override fun finish(w: OpWorld, b: Bound) = b.args[book]?.let { w.readChapter(it) }.orEmpty()

    override fun describe(w: OpWorld, b: Bound, forSale: Boolean): List<String> {
        val id = b.args[book] ?: return emptyList()
        val spec = w.content.book(id) ?: return emptyList()
        val read = w.chaptersRead(id)
        if (read >= spec.chapters.size) return listOf("rereads \"${spec.title}\" (you've finished it)")
        val next = read + 1
        return listOfNotNull(
            "chapter $next of ${spec.chapters.size} of \"${spec.title}\"",
            spec.skill.entries.joinToString { (skill, d) -> "$skill skill ${signed(d)}" }.ifEmpty { null },
            w.teaching(id)?.takeIf { next == spec.chapters.size }?.let { "finishing it teaches you $it" },
        )
    }
}

/** Raises his skills by [gains] when the verb finishes. The verb's mood gains grow with these skills ([SkillRules]). */
data class PracticeOp(val gains: Map<String, Double>) : Op() {
    override val key get() = "practice"

    override fun problems(c: BedroomContent, verb: VerbSpec) = gains.keys.filter { it !in c.vocabulary.skills }.map { "practice: '$it' isn't in vocabulary.skills" } +
        gains.filterValues { it <= 0 }.keys.map { "practice: $it needs a positive gain" }

    override fun finish(w: OpWorld, b: Bound) = gains.map { (skill, d) -> w.practise(skill, d) }

    override fun describe(w: OpWorld, b: Bound, forSale: Boolean) = if (forSale) emptyList() else gains.map { (skill, d) -> "$skill skill ${signed(d)}" }
}

/**
 * What [Op]s need from the world. [BedroomWorld] implements it privately; nothing else should.
 * [count] and [take] work by id or affordance.
 */
internal interface OpWorld {
    val content: BedroomContent

    fun item(id: String): ItemSpec

    /** What [item] is worth to him: [BedroomContent.value], with what his skills add to his affinities. */
    fun value(item: ItemSpec): Double

    /** What one [id] would sell for if he made it now, with his skill; null when it isn't sellable. */
    fun priceOf(id: String): Int?

    /** False for a verb or item a book teaches that he hasn't learned. */
    fun knows(id: String): Boolean

    fun chaptersRead(book: String): Int

    /** Reads the next chapter of [book] ([ProgressOp]); returns the event's parts. */
    fun readChapter(book: String): List<String>

    /** "to paint, and about the easel and canvas": what finishing [book] would teach him that he doesn't know; null for nothing. */
    fun teaching(book: String): String?

    /** Raises [skill] by [d]; returns the event text. */
    fun practise(skill: String, d: Double): String

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

    /** Buys what was staged for [verb]'s trip, up to [most]; returns the event's parts. */
    fun checkout(verb: String, most: Int): List<String>

    /** What a trip would find: how much of the catalog he can afford, and what he's saving for. */
    fun tripText(): String

    /** Why a trip is pointless now (nothing left he doesn't have), or null. */
    fun tripLock(): String?

    /** Why [verb]'s trip came to nothing (its picks couldn't be made), or null; the trip then fails. */
    fun tripFailed(verb: String): String?

    /** Why he can't start building now (today's invention is made, or no money for materials), or null. */
    fun buildLock(): String?

    /** As [verb] finishes: why the staged design can't be built (none came, it's refused, he can't pay), or null. */
    fun buildFailed(verb: VerbSpec): String?

    /** Builds [verb]'s staged design; returns the event's parts. */
    fun build(verb: VerbSpec): List<String>

    /** "design and build one new thing: materials from $4". */
    fun buildText(): String

    fun hangLock(tag: String): String?

    fun hang(tag: String): String

    fun hangText(tag: String): List<String>

    /** Records authored [op] of the verb that just succeeded as waiting for its result ([AuthoredView]). */
    fun await(op: AuthoredOp, b: Bound, piece: Int?)

    /** The id of his newest piece of [item], if any. */
    fun newestPiece(item: String): Int?

    /** Starts [bg] playing [item] on [on]; returns the event text. */
    fun play(item: String, on: String?, bg: BackgroundOp, fresh: Double): String

    /** The item the background called [name] is playing and its whole minutes left; null when it isn't playing. */
    fun playing(name: String): Pair<String, Int>?

    /** Looks round where [lookout] stands: the patches there he hadn't found become known; what he found, for the event. */
    fun survey(lookout: String): String

    /** What exploring at [lookout] would show, for the option: whether he has looked round there before. */
    fun surveyText(lookout: String): String

    /** Why [patch] has nothing to collect now (it hasn't grown back), or null. */
    fun bare(patch: String): String?

    /** Why [plan]'s room can't be built (or moved) onto [site] now, or null. */
    fun roomLock(plan: String, site: String): String?

    /** Builds (or moves) [plan]'s room onto [site]; the event's parts. */
    fun placeRoom(plan: String, site: String): List<String>

    /** Takes what [patch] yields; the event's parts. */
    fun gather(patch: String): List<String>
}

/**
 * One doing of [verb]: [args] is the item id bound to each argument slot (empty before binding),
 * [fresh] how fresh the habit was when it started (1 for a new or routine one). [extra]: for lock
 * reasons, as if he also owned that item.
 */
internal class Bound(val verb: VerbSpec, val args: Map<String, String>, val fresh: Double, val extra: String? = null, val skilled: Double = 1.0) {
    /** The item bound to [name] when it's an argument slot, otherwise [name] itself (an id or affordance); unbound, the slot's type. */
    fun resolve(name: String): String = args[name] ?: verb.args[name] ?: name

    /**
     * [d] as paid at [fresh] and [skilled]: gains in mood and stimulation shrink with wear, and mood gains grow with skill;
     * costs and other needs don't. Skill doesn't make a thing less boring, so stimulation isn't scaled by it.
     */
    fun paid(need: String, d: Double): Double = when {
        d <= 0 -> d
        need == Need.Mood.id -> d * fresh * skilled
        need == Need.Stimulation.id -> d * fresh
        else -> d
    }

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
 * Looks round the place the item bound to [on] stands in (a lookout): every patch there that he
 * hadn't found becomes known to him, so collecting from it is offered and his map says what grows
 * there. Exploring again finds nothing new, but it's still a walk outdoors.
 */
data class SurveyOp(val on: String) : Op() {
    override val key get() = "survey"

    override fun problems(c: BedroomContent, verb: VerbSpec) = listOfNotNull(
        if (verb.args[on] == null && c.item(on) == null) "surveys '$on', which isn't an argument or an item" else null,
        if (c.world == null) "surveys, but there's no land (world.yaml)" else null,
    )

    override fun finish(w: OpWorld, b: Bound) = listOf(w.survey(b.resolve(on)))

    override fun describe(w: OpWorld, b: Bound, forSale: Boolean) = if (forSale) emptyList() else listOf(w.surveyText(b.resolve(on)))
}

/** Takes what the patch bound to [on] yields (world.yaml `finds`); it's bare until it regrows. */
data class GatherOp(val on: String) : Op() {
    override val key get() = "gather"

    override fun problems(c: BedroomContent, verb: VerbSpec) = listOfNotNull(
        if (verb.args[on] == null && c.item(on) == null) "gathers from '$on', which isn't an argument or an item" else null,
        if (c.world == null) "gathers, but there's no land (world.yaml)" else null,
    )

    override fun lockReason(w: OpWorld, b: Bound) = w.bare(b.resolve(on))

    override fun gone(w: OpWorld, b: Bound) = w.bare(b.resolve(on))

    override fun finish(w: OpWorld, b: Bound) = w.gather(b.resolve(on))

    override fun describe(w: OpWorld, b: Bound, forSale: Boolean) = listOfNotNull(w.content.world?.find(b.resolve(on))?.let { f -> "+1 ${w.item(f.yields).name}" })
}

/** Builds the room whose plan is bound to [room] on the site bound to [site], or moves it there when it stands elsewhere. */
data class PlaceRoomOp(val room: String, val site: String) : Op() {
    override val key get() = "place_room"

    override fun problems(c: BedroomContent, verb: VerbSpec) = listOfNotNull(
        if (verb.args[room] == null) "place_room's room must be an argument slot (`what`)" else null,
        if (verb.args[site] == null) "place_room's site must be an argument slot (`on`)" else null,
        if (c.world?.rooms.isNullOrEmpty()) "places rooms, but world.yaml has none" else null,
    )

    override fun lockReason(w: OpWorld, b: Bound) = w.roomLock(b.resolve(room), b.resolve(site))

    override fun gone(w: OpWorld, b: Bound) = w.roomLock(b.resolve(room), b.resolve(site))

    override fun finish(w: OpWorld, b: Bound) = w.placeRoom(b.resolve(room), b.resolve(site))

    override fun describe(w: OpWorld, b: Bound, forSale: Boolean) =
        listOfNotNull(w.content.world?.roomFor(b.resolve(room))?.let { r -> "${r.name} on ${w.content.world?.site(b.resolve(site))?.name ?: b.resolve(site)}" })
}

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
    val basket: Basket? = null,
    @SerialName("set_state") val setState: SetState? = null,
    val background: Background? = null,
    val sleep: NoParams? = null,
    val enjoy: String? = null,
    val hear: String? = null,
    val progress: Progress? = null,
    val practice: Map<String, Double>? = null,
    val write: Write? = null,
    val draw: String? = null,
    val build: NoParams? = null,
    val survey: String? = null,
    val gather: String? = null,
    @SerialName("place_room") val placeRoom: PlaceRoom? = null,
) {
    @Serializable
    @SerialName("place_room")
    data class PlaceRoom(val room: String, val site: String)

    @Serializable
    @SerialName("write")
    data class Write(val with: String, val into: List<String>, val timing: Timing = Timing.During, @SerialName("shows_as") val showsAs: String? = null, val optional: Boolean = false)

    @Serializable
    @SerialName("basket")
    data class Basket(val most: Int)

    @Serializable
    @SerialName("progress")
    data class Progress(val book: String)

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
            need?.let(::NeedOp), rate?.let(::RateOp), consume?.let(::ConsumeOp), produce?.let(::ProduceOp), sell?.let(::SellOp), basket?.let { BasketOp(it.most) },
            setState?.let { SetStateOp(it.target, it.state) }, background?.let { BackgroundOp(it.name, it.minutes, it.rates) },
            sleep?.let { SleepOp }, enjoy?.let(::EnjoyOp), hear?.let(::HearOp), progress?.let { ProgressOp(it.book) }, practice?.let(::PracticeOp),
            write?.let { WriteOp(it.with, it.into, it.timing, it.showsAs, it.optional) }, draw?.let(::DrawOp), build?.let { BuildOp },
            survey?.let(::SurveyOp), gather?.let(::GatherOp), placeRoom?.let { PlaceRoomOp(it.room, it.site) },
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
            is BasketOp -> OpEntry(basket = Basket(op.most))
            is SetStateOp -> OpEntry(setState = SetState(op.target, op.state))
            is BackgroundOp -> OpEntry(background = Background(op.name, op.minutes, op.rates))
            SleepOp -> OpEntry(sleep = NoParams())
            is EnjoyOp -> OpEntry(enjoy = op.what)
            is HearOp -> OpEntry(hear = op.background)
            is ProgressOp -> OpEntry(progress = Progress(op.book))
            is PracticeOp -> OpEntry(practice = op.gains)
            is WriteOp -> OpEntry(write = Write(op.with, op.into, op.timing, op.showsAs, op.optional))
            is DrawOp -> OpEntry(draw = op.what)
            BuildOp -> OpEntry(build = NoParams())
            is SurveyOp -> OpEntry(survey = op.on)
            is GatherOp -> OpEntry(gather = op.on)
            is PlaceRoomOp -> OpEntry(placeRoom = PlaceRoom(op.room, op.site))
        }
    }
}

internal object OpSerializer : KSerializer<Op> {
    override val descriptor: SerialDescriptor = OpEntry.serializer().descriptor

    override fun deserialize(decoder: Decoder): Op = decoder.decodeSerializableValue(OpEntry.serializer()).toOp()

    override fun serialize(encoder: Encoder, value: Op) = encoder.encodeSerializableValue(OpEntry.serializer(), OpEntry.of(value))
}
