package dev.lilguy.agent.shop

import dev.lilguy.agent.Menu
import dev.lilguy.agent.authored.AuthorCall
import dev.lilguy.agent.authored.AuthorHandler
import dev.lilguy.agent.memory.EventKind
import dev.lilguy.agent.memory.MemoryStore
import dev.lilguy.decision.ActionSchema
import dev.lilguy.decision.DecisionFailed
import dev.lilguy.decision.DecisionModel
import dev.lilguy.decision.DecisionRequest
import dev.lilguy.decision.Message
import dev.lilguy.decision.SchemaHints
import dev.lilguy.decision.Usage
import dev.lilguy.sim.AuthoredResult
import dev.lilguy.sim.AuthoredView
import dev.lilguy.sim.BasketOp
import dev.lilguy.sim.BedroomWorld
import dev.lilguy.sim.CatalogEntry
import dev.lilguy.sim.Observation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** One thing he picked on a trip, and why. */
@Serializable
@SerialName("shop_pick")
data class ShopPick(val item: String, val why: String)

@Serializable
@SerialName("shopping")
data class ShoppingReply(
    /** Null when there's nothing he could buy now. */
    val buy: List<ShopPick>?,
    @SerialName("save_for") val saveFor: String?,
    val note: String,
)

/**
 * One shopping trip, for logs and the dev view. [catalog] is what he was shown, a line an item.
 * [choseSaving]: false when the picker had no say in what he's saving for (a picking model, or a
 * failed call), so the sim keeps what it had.
 */
data class Trip(
    val tick: Long,
    val day: Int?,
    val time: String?,
    val shopper: String,
    val catalog: List<String>,
    val buy: List<ShopPick>,
    val saveFor: String?,
    val choseSaving: Boolean,
    val note: String?,
    val raw: String?,
    val usage: Usage,
    val latency: Duration,
    val error: String? = null,
    /** Why the first shopper failed, when [shopper] is the fallback that picked instead. */
    val fellBack: String? = null,
)

/**
 * A shopping trip's picks: when he chooses the `shop` verb he looks through the whole catalog he
 * knows of, food included (what he can afford and what he can't, what's held back and what it waits on), and picks
 * up to the basket's `most` things to buy, each with why, what he's saving for, and a note on what
 * caught his eye. It's the only place he sees what's for sale, and the trip's event is how it
 * reaches his memory.
 *
 * A text model writes it all ([ShoppingReply], items held to the catalog by the schema); a picking
 * model (Jev) picks one thing to buy or nothing, with no reasons and no say in what he saves for.
 * Either way the sim checks every item again at checkout. When [model]'s call fails, [fallback] (the
 * deciding model) picks instead, so a writer that's down doesn't leave him without food.
 * [connect] it to the world first.
 */
class Shopper(private val model: DecisionModel, private val fallback: DecisionModel? = null, private val timeout: Duration = 60.seconds) {
    private var world: BedroomWorld? = null

    val shopper: String get() = model.id

    fun connect(world: BedroomWorld) {
        this.world = world
    }

    /** Never throws for a model failure: the trip says what went wrong and buys nothing. */
    suspend fun shop(call: AuthorCall): Trip {
        val first = shop(model, call)
        if (first.error == null || fallback == null || fallback === model) return first
        val second = shop(fallback, call)
        return second.copy(usage = first.usage + second.usage, latency = first.latency + second.latency, fellBack = first.error)
    }

    private suspend fun shop(model: DecisionModel, call: AuthorCall): Trip {
        val started = TimeSource.Monotonic.markNow()
        val obs = call.obs
        val most = call.verb.ops<BasketOp>().firstOrNull()?.most ?: 1
        val entries = catalog()
        val lines = entries.map { line(it) }
        // What the shop will sell him now and he can afford: not held back, which `shop` already leaves out.
        val buyable = obs.shop.orEmpty().filter { it.affordable && entries.any { e -> e.id == it.id } }.map { it.id }
        var usage = Usage()
        fun trip(buy: List<ShopPick>, saveFor: String?, chose: Boolean, note: String?, raw: String?, error: String? = null) =
            Trip(obs.tick, obs.clock?.day, obs.clock?.time, model.id, lines, buy, saveFor, chose, note, raw, usage, started.elapsedNow(), error)
        return try {
            withTimeout(timeout) {
                if (model.capabilities.closedChoice) {
                    val options = buyable.map { id -> Menu.Option("buy:$id", "Buy " + lines[entries.indexOfFirst { it.id == id }].removePrefix("- ")) } +
                        Menu.Option(NOTHING, "Buy nothing now.")
                    val state = buildJsonObject {
                        put("situation", call.persona)
                        put("money", obs.money ?: 0)
                        putJsonArray("catalog") { lines.forEach { add(it.removePrefix("- ")) } }
                        putJsonArray("owns") { obs.owns.orEmpty().keys.forEach { add(it) } }
                    }
                    val d = Menu.pick(model, PICK_INSTRUCTIONS, state, options, timeout)
                    usage += d.usage
                    val picked = d.value.pick.removePrefix("buy:").takeIf { d.value.pick != NOTHING }
                    trip(listOfNotNull(picked?.let { ShopPick(it, "") }), null, false, null, d.raw)
                } else {
                    val schema = ActionSchema.of(
                        "shopping",
                        ShoppingReply.serializer(),
                        SchemaHints(
                            descriptions = mapOf(
                                "shopping.buy" to "Up to $most things to buy now, most wanted first; empty for nothing.",
                                "shop_pick.why" to "Why you want it, first person, under $WHY_CAP words.",
                                "shopping.save_for" to "The one thing in the catalog you're saving for, or null for nothing.",
                                "shopping.note" to "What you make of the catalog: what caught your eye or what you'd like one day, first person, under $NOTE_CAP words.",
                            ),
                            enums = mapOf("shop_pick.item" to buyable, "shopping.save_for" to entries.map { it.id }),
                        ),
                    ) { null }
                    val user = call.situation.trimEnd() + "\n\n" + "You're at the mail hatch with the shop's catalog. You have $${obs.money ?: 0}. " +
                        "Everything in it you know of, with its price and what it's for:\n" + lines.joinToString("\n") + "\n\n" + ask(most, buyable.isNotEmpty())
                    val request = DecisionRequest(
                        system = call.system.trimEnd() + "\n\n" + INSTRUCTIONS,
                        messages = listOf(Message(Message.Role.User, user)),
                        temperature = TEMPERATURE,
                        timeout = timeout,
                    )
                    val d = model.decide(request, schema)
                    usage += d.usage
                    val picks = d.value.buy.orEmpty().distinctBy { it.item }.take(most).map { it.copy(why = it.why.trim()) }
                    trip(picks, d.value.saveFor, true, d.value.note.trim().ifEmpty { null }, d.raw)
                }
            }
        } catch (e: TimeoutCancellationException) {
            trip(emptyList(), null, false, null, null, "timed out after $timeout")
        } catch (e: CancellationException) {
            throw e
        } catch (e: DecisionFailed) {
            usage += e.usage
            trip(emptyList(), null, false, null, e.attempts.lastOrNull()?.raw, e.message ?: e.toString())
        } catch (e: Exception) {
            trip(emptyList(), null, false, null, null, e.message ?: e.toString())
        }
    }

    /** What a trip looks through: everything in the catalog he knows of and doesn't already have (food and supplies always). */
    private fun catalog(): List<CatalogEntry> =
        world?.snapshot()?.life?.catalog.orEmpty().filter { it.taughtBy == null && (it.stackable || it.owned == 0) }

    /** "- easel, $17, you can afford it: unlocks paint (...)". */
    private fun line(e: CatalogEntry): String =
        "- ${e.id} (${e.name}), $${e.price}, " + (if (e.affordable) "you can afford it" else "more than you have") + ": ${e.gives}"

    companion object {
        const val WHY_CAP = 25
        const val NOTE_CAP = 50
        const val TEMPERATURE = 0.7
        private const val NOTHING = "nothing"

        private fun ask(most: Int, any: Boolean) = if (any) {
            "What do you buy now, if anything (up to $most things)? What are you saving for, if anything?"
        } else {
            "You can't buy anything here right now. What are you saving for, if anything?"
        }

        val INSTRUCTIONS = """
            |Right now you are not choosing an action: you are going through the shop's catalog. Money is scarce and food comes first, so think about what you have, what you'd use, and what would make your life better over the coming days. Buying nothing is fine. What you can't afford yet you can save for.
            |
            |For this reply only, ignore the reply format above: reply with a JSON object {"buy": [{"item": ..., "why": ...}], "save_for": ..., "note": ...} and nothing else.
        """.trimMargin()

        private const val PICK_INSTRUCTIONS = "The character in `state` is going through the shop's catalog. Money is scarce and food comes first. " +
            "Which one thing does he buy now, if any? Choose what he'd use and what would make his life better over the coming days; buying nothing is fine."

        /** "the easel ($17): so I can paint". */
        internal fun picked(p: ShopPick, catalog: List<String>): String {
            val line = catalog.firstOrNull { it.startsWith("- ${p.item} (") }
            val name = line?.substringAfter(" (")?.substringBefore(")") ?: p.item.replace('_', ' ')
            val price = line?.substringAfter("), ")?.substringBefore(",")
            return "the $name" + (price?.let { " ($it)" } ?: "") + (p.why.trimEnd('.').takeIf { it.isNotEmpty() }?.let { ": $it" } ?: "")
        }
    }
}

/**
 * `write: { with: shopping, timing: before }`: a [Shopper] picks what goes in the basket before he
 * walks to the hatch, and the picks are staged for the sim's checkout ([staged]). Kept (the trip
 * succeeded), the trip goes into the event stream as a [EventKind.Shopping] event in his words:
 * what he picked and why, what he's saving for, his note. [onTrip] gets every trip as it's
 * written, kept or not.
 */
class ShoppingHandler(
    private val shopper: Shopper,
    private val store: MemoryStore,
    private val run: String,
    private val onTrip: (Trip) -> Unit = {},
) : AuthorHandler<Trip> {
    override val name = "shopping"
    override val writer: String get() = shopper.shopper

    override suspend fun author(call: AuthorCall) = shopper.shop(call).also(onTrip)

    override fun staged(result: Trip) = AuthoredResult.Basket(result.buy.map { it.item }, result.saveFor, result.choseSaving, failed = result.error)

    override fun keep(result: Trip, call: AuthorCall, ended: Observation, view: AuthoredView?, deliver: (AuthoredResult) -> Unit) {
        if (result.error != null) return
        val text = listOfNotNull(
            if (result.buy.isEmpty()) "I went through the shop's catalog and bought nothing." else "I went through the shop's catalog and picked " + result.buy.joinToString("; ") { Shopper.picked(it, result.catalog) } + ".",
            result.saveFor?.let { "I'm saving for ${Shopper.picked(ShopPick(it, ""), result.catalog)}." },
            result.note,
        ).joinToString(" ")
        if ("events" in call.op.into) store.appendEvent(run, result.tick, result.day, result.time, EventKind.Shopping, text)
        deliver(AuthoredResult.Written(text))
    }
}
