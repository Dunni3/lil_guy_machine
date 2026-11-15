package dev.lilguy.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LightTest {
    private fun world(time: String, vararg items: String): BedroomWorld {
        val shipped = BedroomContent.default()
        return BedroomWorld.create(shipped.copy(start = shipped.start.copy(time = time, items = shipped.start.items + items.associateWith { 1 })))
    }

    private fun BedroomWorld.run(intent: Intent): IntentHandle {
        val handle = submit(agentId, intent)
        repeat(20_000) {
            step()
            if (handle.status.let { it is IntentStatus.Succeeded || it is IntentStatus.Failed || it is IntentStatus.Interrupted }) return handle
        }
        error("$intent still ${handle.status}")
    }

    private fun BedroomWorld.locked(verb: String) = observe(agentId).activities!!.first { it.id == verb }.locked

    @Test
    fun `at night it's too dark to read until a lamp lights the bed, and he reads where it's lit`() {
        val day = world("12:00", "novel")
        assertNull(day.locked("read"), "by day the room is lit")
        val night = world("22:00", "novel")
        assertTrue(night.locked("read")!!.startsWith("too dark to read at the bed or the chair"), night.locked("read"))
        assertNull(night.locked("sketch"), "the desk's own lamp lights the desk")
        // The sunroom lamp stands by the bed: the bed is lit, the chair across the room still isn't.
        // (21:30, so the hour below ends before its timer puts it out at midnight.)
        val lamp = world("21:30", "novel", "sunroom_lamp")
        assertNull(lamp.locked("read"))
        assertIs<IntentStatus.Succeeded>(lamp.run(Intent.Do("read", what = "novel")).status)
        val bed = lamp.layout.slotCells.getValue("bed")
        assertTrue(GridWorld.chebyshev(lamp.snapshot().entities.first { it.id == lamp.agentId }.let { Cell(it.x.toInt(), it.y.toInt()) }, bed) <= 1, "he read at the lit bed, not the chair")
        // The field is worked out again only when its inputs change: not through an hour of the same night.
        val computed = lamp.lighting.computed
        repeat(lamp.clock.ticks(60)) { lamp.step(); lamp.lightField() }
        assertEquals(computed, lamp.lighting.computed)
    }

    @Test
    fun `a lamp goes out at its timer's off time, stays on once he switches it, and the switch is saved`() {
        val w = world("23:40", "novel", "sunroom_lamp")
        fun lamp(world: BedroomWorld) = world.snapshot().entities.first { it.id.value == "sunroom_lamp" }
        assertNull(w.locked("read"), "the sunroom lamp is on until midnight")
        while (w.clock.minuteOfDay(w.tick) in 1..23 * 60 + 59) w.step()
        repeat(w.clock.ticks(10)) { w.step() }
        assertTrue(w.locked("read")!!.startsWith("too dark to read"), w.locked("read"))
        assertEquals("off", lamp(w).state)
        assertNull(lamp(w).light, "an unlit lamp gives no light to the renderer")
        assertIs<IntentStatus.Succeeded>(w.run(Intent.Do("switch_on", on = "sunroom_lamp")).status)
        assertNull(w.locked("read"), "switched on, it stays on past its timer")
        assertEquals("the sunroom lamp is already switched on", w.locked("switch_on"))
        val back = BedroomWorld.restore(w.exportState(), w.content, strict = true).world
        assertNull(back.locked("read"), "the switch survives a save")
        assertIs<IntentStatus.Succeeded>(back.run(Intent.Do("lamp_timer", on = "sunroom_lamp")).status)
        assertTrue(back.locked("read")!!.startsWith("too dark to read"), "back on its timer, it's off after midnight")
    }

    @Test
    fun `outdoors at night he sees only what's lit, and remembers what he found by day`() {
        val w = world("17:00")
        assertIs<IntentStatus.Succeeded>(w.run(Intent.Do("explore", on = "clearing_log")).status)
        assertNull(w.observe(w.agentId).light, "by daylight there's nothing to say about the light")
        while (w.clock.minuteOfDay(w.tick) < 22 * 60) w.step()
        val obs = w.observe(w.agentId)
        val glow = obs.nearby.first { it.id == "glowcap_patch" }
        assertTrue(!glow.remembered, "the glowcaps glow, so he sees them")
        assertTrue(obs.nearby.first { it.id == "moss_patch" }.remembered, "the moss is dark, but he found it there")
        val light = assertNotNull(obs.light)
        assertTrue(light.any { it == "glowing: the glowcaps under the log" } && light.any { it.startsWith("too dark to see the rest here; you remember") }, light.toString())
        assertTrue(w.locked("explore")!!.startsWith("too dark to explore"), w.locked("explore"))
        // Somewhere he has never been, in the dark, he sees only what his lantern reaches beside him.
        assertIs<IntentStatus.Succeeded>(w.run(Intent.MoveTo(Target.Place("pond"))).status)
        val pond = w.observe(w.agentId)
        assertTrue(pond.nearby.all { it.distance <= 2 && !it.remembered }, pond.nearby.toString())
        assertTrue(pond.light!!.any { it.startsWith("where you stand: dark") } && pond.light!!.any { it.startsWith("by your lantern: the") }, pond.light.toString())
    }

    @Test
    fun `comfort is what he can see lit from where he stands, with no cap`() {
        val shipped = BedroomContent.default()
        val decor = shipped.items.filter { it.group == "decor" && it.light == null }.associate { it.id to 1 }
        fun target(time: String, vararg more: String): Double {
            val c = shipped.copy(start = shipped.start.copy(time = time, items = shipped.start.items + decor + more.associateWith { 1 }))
            return BedroomWorld.create(c).snapshot().life!!.moodTarget
        }
        val noon = target("12:00")
        val night = target("23:00")
        val lamps = target("23:00", "sunroom_lamp", "string_lights", "amber_floor_lamp")
        assertTrue(noon > 60, "by day he sees the decor round him: $noon")
        assertTrue(night < 55, "in the dark he sees only what his lantern reaches: $night")
        assertTrue(lamps in 51.0..noon, "lamps bring back what they light: $lamps")
        val poster = world("12:00").observe(world("12:00").agentId).shop!!.first { it.id == "poster" }.gives
        assertTrue("house is full" !in poster && "in view (near you, lit)" in poster, poster)
    }
}
