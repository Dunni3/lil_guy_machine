package dev.lilguy.sim

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.roundToInt

/** One logged value, at the tick it was taken. */
@Serializable
data class Reading(val tick: Long, val value: Double)

/**
 * What his recorders have logged, kept with each thing: thing id to key to its latest readings,
 * oldest first, at most [keeps] a key. A weather station logs temperature, rain, pressure and light
 * each hour; his code logs whatever it likes (`world.record`). Saved in checkpoints.
 */
class Records(private val keeps: Int) {
    private val logs = linkedMapOf<String, LinkedHashMap<String, ArrayDeque<Reading>>>()

    /** Adds [value] under [key] on [thing]; returns how many readings that key now keeps. */
    fun record(thing: String, key: String, tick: Long, value: Double): Int {
        val keys = logs.getOrPut(thing) { linkedMapOf() }
        require(key in keys || keys.size < MAX_KEYS) { "it already logs $MAX_KEYS things (${keys.keys.joinToString()}); log under one of those" }
        val log = keys.getOrPut(key) { ArrayDeque() }
        log.addLast(Reading(tick, value))
        while (log.size > keeps) log.removeFirst()
        return log.size
    }

    fun readings(thing: String, key: String): List<Reading> = logs[thing]?.get(key)?.toList().orEmpty()

    /** Everything [thing] has logged, key to readings. */
    fun of(thing: String): Map<String, List<Reading>> = logs[thing]?.mapValues { it.value.toList() }.orEmpty()

    fun export(): Map<String, Map<String, List<Reading>>> = logs.mapValues { (_, keys) -> keys.mapValues { it.value.toList() } }

    /** Puts back what [saved] holds for the things [keep] says still exist. */
    fun restore(saved: Map<String, Map<String, List<Reading>>>, keep: (String) -> Boolean) {
        logs.clear()
        saved.forEach { (thing, keys) ->
            if (keep(thing)) logs[thing] = LinkedHashMap(keys.mapValues { ArrayDeque(it.value.takeLast(keeps)) })
        }
    }

    companion object {
        /** Keys one thing may log. */
        const val MAX_KEYS = 12
    }
}

/**
 * A weather station's log as he reads it, standing at it: the last day of it in a few short parts
 * ("rain d3 03:00-06:00, 4.2mm", "pressure 1006, falling since d2 14:00", "5° to 14°, now 9°").
 * [stamp] writes a tick as "d3 04:00"; [hourTicks] is a game hour in ticks.
 */
object StationLog {
    /** Readings it sums up: a day of hourly ones. */
    const val SPAN = 24

    /** How far pressure must move over [TREND_HOURS] to count as rising or falling. */
    private const val TREND = 1.5
    private const val TREND_HOURS = 3

    fun summary(logs: Map<String, List<Reading>>, stamp: (Long) -> String, hourTicks: Long): List<String> {
        val temp = logs[TEMPERATURE].orEmpty().takeLast(SPAN)
        val rain = logs[RAIN].orEmpty().takeLast(SPAN)
        val pressure = logs[PRESSURE].orEmpty().takeLast(SPAN)
        val light = logs[LIGHT].orEmpty().takeLast(SPAN)
        val all = listOf(temp, rain, pressure, light).filter { it.isNotEmpty() }
        if (all.isEmpty()) return listOf("the log is empty")
        val first = all.minOf { it.first().tick }
        val hours = all.maxOf { it.size }
        return listOfNotNull(
            "the last ${if (hours >= SPAN) "24h" else "${hours}h"} (since ${stamp(first)})",
            rainText(rain, stamp, hourTicks),
            pressureText(pressure, stamp),
            temp.takeIf { it.isNotEmpty() }?.let { t -> "${deg(t.minOf { it.value })} to ${deg(t.maxOf { it.value })}, now ${deg(t.last().value)}" },
            light.takeIf { l -> l.any { it.value > 0 } }?.let { l -> l.maxBy { it.value }.let { "brightest ${it.value.roundToInt()} at ${stamp(it.tick)}" } },
        )
    }

    /** Each spell of rain, from the hour it began to the reading it was last in. */
    private fun rainText(rain: List<Reading>, stamp: (Long) -> String, hourTicks: Long): String? {
        if (rain.isEmpty()) return null
        val spells = mutableListOf<MutableList<Reading>>()
        var last: Reading? = null
        rain.forEach { r ->
            if (r.value > 0) {
                if (last == null || last!!.value <= 0) spells += mutableListOf<Reading>()
                spells.last() += r
            }
            last = r
        }
        if (spells.isEmpty()) return "no rain"
        return "rain " + spells.joinToString("; ") { s -> "${stamp(s.first().tick - hourTicks)}-${stamp(s.last().tick).substringAfter(' ')}, ${"%.1f".format(s.sumOf { it.value })}mm" }
    }

    /** Where pressure is, which way it's going and since when: the start of the latest run of the same trend. */
    private fun pressureText(p: List<Reading>, stamp: (Long) -> String): String? {
        if (p.isEmpty()) return null
        val now = "pressure ${p.last().value.roundToInt()}"
        if (p.size <= TREND_HOURS) return now
        fun trend(i: Int): Int = (p[i].value - p[i - TREND_HOURS].value).let { if (abs(it) < TREND) 0 else if (it > 0) 1 else -1 }
        val going = trend(p.lastIndex)
        var since = p.lastIndex
        while (since - 1 >= TREND_HOURS && trend(since - 1) == going) since--
        val from = p[since - TREND_HOURS]
        val word = when (going) { 1 -> "rising"; -1 -> "falling"; else -> "steady" }
        return "$now, $word since ${stamp(from.tick)}" + if (going != 0) " (from ${from.value.roundToInt()})" else ""
    }

    private fun deg(v: Double) = "${v.roundToInt()}°"

    const val TEMPERATURE = "temperature"
    const val RAIN = "rain"
    const val PRESSURE = "pressure"
    const val LIGHT = "light"
}
