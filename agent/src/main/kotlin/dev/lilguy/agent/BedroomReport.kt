package dev.lilguy.agent

import dev.lilguy.agent.memory.JournalEntry
import dev.lilguy.agent.memory.Want
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

    /** A want and every entry it appeared in, by when it was first written. */
    data class WantSeen(val want: Want, val first: String, val again: List<String>)

    /**
     * Every want in [entries], oldest first, with repeats folded in: the same kind and text
     * (ignoring case and punctuation) counts as asking again. The latest justification is kept,
     * since a repeated want is often a better-argued one.
     */
    fun wants(entries: List<JournalEntry>): List<WantSeen> {
        val seen = LinkedHashMap<String, WantSeen>()
        entries.forEach { e ->
            e.wants.forEach { w ->
                val key = w.kind + ":" + w.text.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
                seen[key] = seen[key]?.let { it.copy(want = w, again = it.again + e.label) } ?: WantSeen(w, e.label, emptyList())
            }
        }
        return seen.values.toList()
    }

    /** The journal as text for the end of a run: each entry, then the wants collected from all of them. */
    fun journal(entries: List<JournalEntry>): String = buildString {
        if (entries.isEmpty()) return "journal: no entries"
        appendLine("journal: ${entries.size} entr${if (entries.size == 1) "y" else "ies"}")
        entries.forEach { e ->
            appendLine("  ${e.label} (${e.source})")
            e.text.lines().forEach { appendLine("    $it") }
            e.wants.forEach { appendLine("    want [${it.kind}] ${it.text}: ${it.justification}") }
        }
        val wants = wants(entries)
        appendLine("wants: ${wants.size}")
        wants.forEach { w ->
            val again = if (w.again.isEmpty()) "" else "; again ${w.again.joinToString()}"
            appendLine("  [${w.want.kind}] ${w.want.text} (first ${w.first}$again)")
            appendLine("      because ${w.want.because}")
        }
    }.trimEnd()

    private fun round1(v: Double) = Math.round(v * 10) / 10.0
}
