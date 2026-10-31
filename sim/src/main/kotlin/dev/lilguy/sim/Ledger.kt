package dev.lilguy.sim

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/** One change of money, with why. [amount] is signed; [balance] is the money after it. */
@Serializable
data class Transaction(
    val tick: Long,
    val day: Int,
    val time: String,
    val amount: Int,
    val balance: Int,
    val reason: String,
)

/**
 * Money, in whole dollars. The balance changes only through [credit] and [debit], and each change
 * is kept as a [Transaction], so the balance is always the start plus the sum of the history.
 * Money never goes below zero: a debit that would is refused.
 */
class Ledger(start: Int, private val clock: GameClock) {
    init {
        require(start >= 0) { "starting money can't be negative" }
    }

    var balance: Int = start
        private set

    private val history = mutableListOf<Transaction>()
    val transactions: List<Transaction> get() = history

    fun credit(tick: Long, amount: Int, reason: String): Transaction {
        require(amount > 0) { "credit must be positive" }
        return record(tick, amount, reason)
    }

    /** Refuses (throws) when [amount] is more than the balance; check [canAfford] first. */
    fun debit(tick: Long, amount: Int, reason: String): Transaction {
        require(amount > 0) { "debit must be positive" }
        require(canAfford(amount)) { "can't spend $$amount with $$balance" }
        return record(tick, -amount, reason)
    }

    fun canAfford(amount: Int) = amount <= balance

    private fun record(tick: Long, amount: Int, reason: String): Transaction {
        balance += amount
        val reading = clock.reading(tick)
        return Transaction(tick, reading.day, reading.time, amount, balance, reason).also { history += it }
    }
}

@Serializable
enum class Need {
    @SerialName("energy") Energy,
    @SerialName("fullness") Fullness,
    @SerialName("mood") Mood,

    /** Low means bored; new things and hobbies feed it, repeating the same thing drains it. */
    @SerialName("stimulation") Stimulation;

    val id: String get() = name.lowercase()
}

/**
 * Needs as the agent sees them: whole numbers from 0 to 100. [stimulation] has a default so
 * content and logs written before it existed still load; it is always written out.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class NeedLevels(
    val energy: Int,
    val fullness: Int,
    val mood: Int,
    @EncodeDefault val stimulation: Int = 60,
) {
    operator fun get(need: Need): Int = when (need) {
        Need.Energy -> energy
        Need.Fullness -> fullness
        Need.Mood -> mood
        Need.Stimulation -> stimulation
    }
}

/**
 * One logged change to a need. Rates (falling 4 an hour while awake, say) are applied every game
 * minute but logged as one change per need and reason per game hour, so a day is a few dozen
 * lines rather than thousands. One-off effects (a meal) are logged as they happen. [delta] is
 * what was actually applied after clamping to 0..100; [value] is the level after it.
 */
@Serializable
data class NeedChange(
    val tick: Long,
    val day: Int,
    val time: String,
    val need: Need,
    val delta: Double,
    val value: Double,
    val reason: String,
)

/**
 * Energy, fullness, mood and stimulation, each kept as a double from 0 to 100 and changed only through
 * [change], which clamps and logs. Call [flush] at each game hour to write the hour's rate changes.
 */
class Needs(start: NeedLevels, private val clock: GameClock) {
    private val values = DoubleArray(Need.entries.size) { start[Need.entries[it]].toDouble() }
    private val history = mutableListOf<NeedChange>()
    /** Rate changes since the last [flush], summed per need and reason. */
    private val pending = LinkedHashMap<Pair<Need, String>, Double>()

    val changes: List<NeedChange> get() = history

    operator fun get(need: Need): Double = values[need.ordinal]

    fun levels() = NeedLevels(level(Need.Energy), level(Need.Fullness), level(Need.Mood), level(Need.Stimulation))

    /** What the agent sees. Rounded down, so "0" means really empty and "100" really full. */
    fun level(need: Need): Int = this[need].toInt()

    /** Applies [delta] now, clamped to 0..100. One-off effects are logged at once; rates wait for [flush]. */
    fun change(tick: Long, need: Need, delta: Double, reason: String, rate: Boolean = false): Double {
        val before = values[need.ordinal]
        val after = (before + delta).coerceIn(0.0, MAX)
        values[need.ordinal] = after
        val applied = after - before
        if (rate) {
            pending.merge(need to reason, applied, Double::plus)
        } else if (applied != 0.0) {
            history += entry(tick, need, applied, reason)
        }
        return applied
    }

    /** Writes the summed rate changes since the last flush, one entry per need and reason. */
    fun flush(tick: Long) {
        for ((key, delta) in pending) {
            if (delta != 0.0) history += entry(tick, key.first, delta, key.second)
        }
        pending.clear()
    }

    private fun entry(tick: Long, need: Need, delta: Double, reason: String): NeedChange {
        val reading = clock.reading(tick)
        return NeedChange(tick, reading.day, reading.time, need, round2(delta), round2(values[need.ordinal]), reason)
    }

    companion object {
        const val MAX = 100.0

        private fun round2(v: Double) = (v * 100).roundToInt() / 100.0
    }
}
