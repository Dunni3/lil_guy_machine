package dev.lilguy.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WeatherTest {
    private val rules = BedroomContent.default().weather!!

    @Test
    fun `the weather is the seed's, every sky comes, and pressure falls before each rain`() {
        val hours = 0L until 30 * 24
        val a = Weather(rules, seed = 7)
        val b = Weather(rules, seed = 7)
        // Asked in another order, the same seed still gives the same month.
        hours.reversed().forEach { b.sky(it) }
        assertEquals(hours.map(a::sky), hours.map(b::sky))
        assertNotEquals(hours.map(a::sky), hours.map(Weather(rules, seed = 8)::sky))
        assertEquals(Sky.entries.toSet(), hours.map(a::sky).toSet())

        val lead = rules.pressure.leadHours
        // Rain after a dry spell (not a shower just after the last).
        val onsets = hours.filter { h -> h > 2 * lead && a.sky(h) == Sky.Rain && (1..2 * lead).none { a.sky(h - it) == Sky.Rain } }
        assertTrue(onsets.size >= 8, "rain about every few days: ${onsets.size} spells")
        onsets.forEach { h -> assertTrue(a.pressure(h) < a.pressure(h - lead - 1) - 3, "pressure falls in the hours before the rain at hour $h") }
    }

    @Test
    fun `a station logs its spot each hour, he reads it there, and the log survives a checkpoint`() {
        val content = BedroomContent.default().let { c -> c.copy(start = c.start.copy(items = c.start.items + ("weather_station" to 1))) }
        val w = BedroomWorld.create(content)
        repeat(2) { w.finish(Intent.Wait(GridWorld.MAX_WAIT)) }
        w.finish(Intent.Do("check_station")).let { assertIs<IntentStatus.Succeeded>(it, (it as? IntentStatus.Failed)?.reason) }
        val read = w.observe(w.agentId).recentEvents.last { "station's log" in it }
        assertTrue("pressure" in read && "°" in read, read)
        assertEquals("garden", w.snapshot().entities.first { it.id == w.agentId }.place)

        val saved = w.exportState().records
        assertEquals(setOf(StationLog.TEMPERATURE, StationLog.RAIN, StationLog.PRESSURE, StationLog.LIGHT), saved.getValue("weather_station").keys)
        assertEquals(saved, BedroomWorld.restore(w.exportState(), content, strict = true).world.exportState().records)
    }

    private fun BedroomWorld.finish(intent: Intent): IntentStatus {
        val handle = submit(agentId, intent)
        repeat(60_000) {
            step()
            val s = handle.status
            if (s is IntentStatus.Succeeded || s is IntentStatus.Failed) return s
        }
        error("$intent never ended")
    }
}
