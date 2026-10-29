package dev.lilguy.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GameClockTest {
    private val clock = GameClock(ticksPerMinute = 5, startMinute = 8 * 60)

    @Test
    fun `tick 0 is the start time of day 1`() {
        assertEquals(ClockReading(1, "08:00"), clock.reading(0))
        assertEquals("day 1, 08:00", clock.label(0))
    }

    @Test
    fun `five ticks make a minute and days roll over at midnight`() {
        assertEquals(ClockReading(1, "08:00"), clock.reading(4))
        assertEquals(ClockReading(1, "08:01"), clock.reading(5))
        assertEquals(ClockReading(1, "09:00"), clock.reading(60L * 5))
        val toMidnight = 16L * 60 * 5
        assertEquals(ClockReading(1, "23:59"), clock.reading(toMidnight - 1))
        assertEquals(ClockReading(2, "00:00"), clock.reading(toMidnight))
        assertEquals(ClockReading(15, "08:00"), clock.reading(14L * 24 * 60 * 5))
    }

    @Test
    fun `minute starts and conversions follow the scale`() {
        assertTrue(clock.isMinuteStart(10))
        assertFalse(clock.isMinuteStart(11))
        assertEquals(300, clock.ticks(60))
        val fast = GameClock(ticksPerMinute = 1)
        assertEquals(60, fast.ticks(60))
        assertEquals(ClockReading(1, "09:00"), fast.reading(60))
    }

    @Test
    fun `ticks until a time of day is the next occurrence`() {
        // 08:00 to 07:00 is 23 hours; a full day when it already shows that minute.
        assertEquals(23L * 60 * 5, clock.ticksUntil(0, GameClock.parseHhmm("07:00")))
        assertEquals(24L * 60 * 5, clock.ticksUntil(0, GameClock.parseHhmm("08:00")))
        val at2230 = 14L * 60 * 5 + 30 * 5
        assertEquals(ClockReading(1, "22:30"), clock.reading(at2230))
        assertEquals((8L * 60 + 30) * 5, clock.ticksUntil(at2230, GameClock.parseHhmm("07:00")))
        assertEquals(ClockReading(2, "07:00"), clock.reading(at2230 + clock.ticksUntil(at2230, 420)))
    }

    @Test
    fun `durations read naturally`() {
        assertEquals("45 min", GameClock.duration(45))
        assertEquals("1h", GameClock.duration(60))
        assertEquals("3h 30m", GameClock.duration(210))
        assertEquals(420, GameClock.parseHhmm("07:00"))
    }
}
