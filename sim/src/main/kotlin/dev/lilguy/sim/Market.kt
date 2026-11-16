package dev.lilguy.sim

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * How much the market wants each kind of ware (rules.yaml `market`). Each one sold lowers what the
 * next fetches by [drop] of its going price, never below [floor] of its usual price, and the going
 * price climbs back toward the usual one by [recoverPerDay] of the gap a game day. Flooding one ware
 * pays less and less, so a second kiln earns less than the first and a new kind of ware pays.
 */
@Serializable
data class MarketRules(
    val drop: Double = 0.06,
    @SerialName("recover_per_day") val recoverPerDay: Double = 0.5,
    val floor: Double = 0.25,
) {
    internal fun problems(): List<String> = listOfNotNull(
        "market: drop is above 0 and below 1".takeIf { drop <= 0 || drop >= 1 },
        "market: recover_per_day is above 0 and at most 1".takeIf { recoverPerDay <= 0 || recoverPerDay > 1 },
        "market: floor is above 0 and at most 1".takeIf { floor <= 0 || floor > 1 },
    )
}

/**
 * The going price of each kind of ware: kind to its demand (1 is its usual price) and the tick that
 * was set. Every path that sells a ware goes through [sell], so the `sell_wares` verb, `world.sell` in
 * his code and anything his machines sell share it. Art and samples aren't wares and aren't in it.
 */
class Market {
    private val demand = linkedMapOf<String, BedroomState.Wear>()

    /** Wares: the `ware` affordance, or the ware group (what he invents to sell is in it). */
    fun covers(spec: ItemSpec): Boolean = WARE in spec.affords || spec.group == WARE

    /** The share of its usual price a kind fetches now, 1 when it hasn't been sold lately. */
    fun demand(rules: MarketRules, id: String, tick: Long, ticksPerDay: Long): Double {
        val d = demand[id] ?: return 1.0
        val days = (tick - d.tick).coerceAtLeast(0).toDouble() / ticksPerDay
        return 1 - (1 - d.level) * (1 - rules.recoverPerDay).pow(days)
    }

    /** What one sells for now, from its usual [price]. */
    fun price(rules: MarketRules, id: String, price: Int, tick: Long, ticksPerDay: Long): Int =
        (price * demand(rules, id, tick, ticksPerDay)).roundToInt()

    /** What [n] of a kind would fetch sold now, one after another, each for less than the last. */
    fun quote(rules: MarketRules, id: String, price: Int, n: Int, tick: Long, ticksPerDay: Long): Int =
        run(rules, demand(rules, id, tick, ticksPerDay), price, n).first

    /** Sells [n] of a kind as [quote] prices them, and lowers its going price; the dollars they fetched. */
    fun sell(rules: MarketRules, id: String, price: Int, n: Int, tick: Long, ticksPerDay: Long): Int {
        val (total, d) = run(rules, demand(rules, id, tick, ticksPerDay), price, n)
        demand[id] = BedroomState.Wear(d, tick)
        return total
    }

    private fun run(rules: MarketRules, start: Double, price: Int, n: Int): Pair<Int, Double> {
        var d = start
        var total = 0
        repeat(n) {
            total += (price * d).roundToInt()
            d = maxOf(rules.floor, d * (1 - rules.drop))
        }
        return total to d
    }

    /** The going prices that are below usual now, kind to share, for a save and for what he's shown. */
    fun low(rules: MarketRules, tick: Long, ticksPerDay: Long): Map<String, Double> =
        demand.keys.associateWith { demand(rules, it, tick, ticksPerDay) }.filterValues { it < 0.995 }

    fun export(rules: MarketRules?, tick: Long, ticksPerDay: Long): Map<String, BedroomState.Wear> =
        if (rules == null) demand.toMap() else demand.filterKeys { demand(rules, it, tick, ticksPerDay) < 0.995 }

    fun load(saved: Map<String, BedroomState.Wear>) {
        demand.clear()
        demand.putAll(saved)
    }

    companion object {
        const val WARE = "ware"
    }
}
