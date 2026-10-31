package dev.lilguy.agent

import dev.lilguy.agent.memory.SelfVersion
import dev.lilguy.agent.memory.StoredMemory
import dev.lilguy.sim.BedroomWorld
import dev.lilguy.sim.Need
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One game hour of a bedroom run: the curves (money, needs) a log should give. */
@Serializable
data class HourSample(
    val tick: Long,
    val day: Int,
    val time: String,
    val money: Int,
    val energy: Double,
    val fullness: Double,
    val mood: Double,
    /** The activity under way, or the intent's type while walking, buying or waiting. */
    val doing: String?,
    val stimulation: Double = 0.0,
)

/** One game day of a bedroom run, for summaries. */
@Serializable
data class DaySummary(
    val day: Int,
    @SerialName("money_end") val moneyEnd: Int,
    val earned: Int,
    val spent: Int,
    @SerialName("mood_min") val moodMin: Double?,
    @SerialName("mood_max") val moodMax: Double?,
    val bought: List<String>,
)

/** Reads summaries off a bedroom world's ledger and need log. */
object BedroomReport {
    fun sample(world: BedroomWorld): HourSample {
        val life = world.snapshot().life!!
        val intent = world.snapshot().agents.firstOrNull()?.intent
        return HourSample(
            tick = world.tick,
            day = life.clock.day,
            time = life.clock.time,
            money = life.money,
            energy = round1(life.energy),
            fullness = round1(life.fullness),
            mood = round1(life.mood),
            doing = life.doing ?: intent?.let { world.describe(it).substringBefore(' ') },
            stimulation = round1(life.stimulation),
        )
    }

    fun days(world: BedroomWorld, hours: List<HourSample>): List<DaySummary> {
        var money = world.content.start.money
        return (1..world.clock.day(world.tick)).map { day ->
            val tx = world.ledger.transactions.filter { it.day == day }
            money = tx.lastOrNull()?.balance ?: money
            val moods = hours.filter { it.day == day }.map { it.mood }
            DaySummary(
                day = day,
                moneyEnd = money,
                earned = tx.filter { it.amount > 0 }.sumOf { it.amount },
                spent = -tx.filter { it.amount < 0 }.sumOf { it.amount },
                moodMin = moods.minOrNull(),
                moodMax = moods.maxOrNull(),
                bought = tx.filter { it.amount < 0 }.map { it.reason.removePrefix("bought ") },
            )
        }
    }

    fun lowest(hours: List<HourSample>): Map<Need, Double> = mapOf(
        Need.Energy to (hours.minOfOrNull { it.energy } ?: 0.0),
        Need.Fullness to (hours.minOfOrNull { it.fullness } ?: 0.0),
        Need.Mood to (hours.minOfOrNull { it.mood } ?: 0.0),
        Need.Stimulation to (hours.minOfOrNull { it.stimulation } ?: 0.0),
    )

    /** Every version of his self-model for the end of a run, oldest first: when, what changed and why, and the model itself. */
    fun selfModels(versions: List<Pair<StoredMemory, SelfVersion>>, seed: String): String = buildString {
        appendLine("self-model: ${versions.size} version${if (versions.size == 1) "" else "s"}")
        appendLine("  seed: $seed")
        versions.forEach { (m, v) ->
            appendLine()
            appendLine("  ${m.span.label.ifEmpty { "version ${m.id}" }} (${m.source})")
            appendLine("    changed: ${v.note}")
            v.self.render().lines().forEach { appendLine("    $it") }
        }
    }.trimEnd()

    private fun round1(v: Double) = Math.round(v * 10) / 10.0
}
