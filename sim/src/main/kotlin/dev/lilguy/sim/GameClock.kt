package dev.lilguy.sim

import kotlinx.serialization.Serializable

/**
 * Game time (day and hh:mm) on top of ticks. Rules are written in game minutes and converted
 * here, so [ticksPerMinute] is the one knob that sets how fast a game day passes in ticks: at the
 * default 5, a minute is a quarter of a real second at 20 Hz and a day takes 6 real minutes.
 * Walking stays in tiles per tick, so a smaller scale makes walks cheaper in game minutes.
 *
 * Tick 0 is [startMinute] of day 1.
 */
class GameClock(
    val ticksPerMinute: Int = DEFAULT_TICKS_PER_MINUTE,
    val startMinute: Int = 8 * 60,
) {
    init {
        require(ticksPerMinute >= 1) { "ticksPerMinute must be at least 1" }
        require(startMinute in 0 until MINUTES_PER_DAY) { "startMinute must be within a day" }
    }

    /** Whole game minutes since day 1 00:00. */
    fun minutes(tick: Long): Long = startMinute + tick / ticksPerMinute

    fun day(tick: Long): Int = (minutes(tick) / MINUTES_PER_DAY).toInt() + 1

    fun minuteOfDay(tick: Long): Int = (minutes(tick) % MINUTES_PER_DAY).toInt()

    /** True on the first tick of each game minute. */
    fun isMinuteStart(tick: Long): Boolean = tick % ticksPerMinute == 0L

    fun ticks(minutes: Int): Int = minutes * ticksPerMinute

    fun reading(tick: Long) = ClockReading(day(tick), hhmm(minuteOfDay(tick)))

    /** "day 3, 10:30" */
    fun label(tick: Long): String = "day ${day(tick)}, ${hhmm(minuteOfDay(tick))}"

    /** Ticks until the clock next shows [minuteOfDay]; a full day if it shows it now. */
    fun ticksUntil(tick: Long, minuteOfDay: Int): Long {
        val now = minutes(tick)
        val today = now - now % MINUTES_PER_DAY + minuteOfDay
        val next = if (today > now) today else today + MINUTES_PER_DAY
        return (next - startMinute) * ticksPerMinute - tick
    }

    companion object {
        const val DEFAULT_TICKS_PER_MINUTE = 5
        const val MINUTES_PER_DAY = 24 * 60

        fun hhmm(minuteOfDay: Int): String = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

        /** "07:00" to 420. */
        fun parseHhmm(text: String): Int {
            val (h, m) = text.split(':').map { it.toInt() }
            require(h in 0..23 && m in 0..59) { "not a time of day: $text" }
            return h * 60 + m
        }

        /** "45 min", "1h", "3h 30m". */
        fun duration(minutes: Int): String = when {
            minutes < 60 -> "$minutes min"
            minutes % 60 == 0 -> "${minutes / 60}h"
            else -> "${minutes / 60}h ${minutes % 60}m"
        }
    }
}

@Serializable
data class ClockReading(val day: Int, val time: String)
