package dev.lilguy.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WorldTest {
    private fun BedroomWorld.run(intent: Intent, maxTicks: Int = 20_000): IntentHandle {
        val handle = submit(agentId, intent)
        repeat(maxTicks) {
            step()
            val s = handle.status
            if (s is IntentStatus.Succeeded || s is IntentStatus.Failed || s is IntentStatus.Interrupted) return handle
        }
        error("$intent still ${handle.status} after $maxTicks ticks")
    }

    private fun BedroomWorld.guyPlace() = snapshot().entities.first { it.id == agentId }.place

    @Test
    fun `he walks out of the front door to the clearing and back, a minute a tile`() {
        val w = BedroomWorld.create()
        assertEquals("bedroom", w.guyPlace())
        val clearing = w.layout.place("clearing")!!.bounds
        val (cx, cy) = clearing.centre
        val start = w.snapshot().life!!.clock
        assertIs<IntentStatus.Succeeded>(w.run(Intent.MoveTo(Target.Tile(cx.toInt(), cy.toInt()))).status)
        assertEquals("clearing", w.guyPlace())
        val minutes = w.clock.minutes(w.tick) - GameClock.parseHhmm(start.time)
        // From his start by the bed, out of the door and along the path: about 25 tiles.
        assertTrue(minutes in 18..40, "the walk took $minutes game minutes")
        val obs = w.observe(w.agentId)
        assertTrue(obs.localMapAt != null && obs.localMap.any { ':' in it }, "outdoors he sees the land round him")
        assertIs<IntentStatus.Succeeded>(w.run(Intent.MoveTo(Target.Entity("bed"))).status)
        assertEquals("bedroom", w.guyPlace())
    }

    @Test
    fun `he sees where he is, where else he could go, and where an activity would take him`() {
        val w = BedroomWorld.create()
        val home = w.observe(w.agentId)
        assertTrue(home.place!!.startsWith("your bedroom"), home.place)
        val garden = home.places!!.first { it.id == "garden" }
        assertTrue(garden.walk in 3..30, "garden is ${garden.walk} min away")
        assertTrue(home.activities!!.filter { it.where != null }.all { it.id == "explore" }, "but exploring is done in his room")
        val went = w.run(Intent.MoveTo(Target.Place("garden")))
        assertIs<IntentStatus.Succeeded>(went.status)
        assertEquals("garden", w.guyPlace())
        val out = w.observe(w.agentId)
        assertTrue(out.recentEvents.last().contains("went to the garden"), out.recentEvents.last())
        val sketch = out.activities!!.first { it.id == "sketch" }
        assertEquals("your bedroom", sketch.where)
        assertTrue(sketch.walk!! > 0)
        assertTrue(out.places!!.first().id == "bedroom" || out.places!!.any { it.id == "bedroom" })
    }

    @Test
    fun `he walks over to a thing anywhere on the land, and is told where he went`() {
        val w = BedroomWorld.create()
        assertIs<IntentStatus.Succeeded>(w.run(Intent.MoveTo(Target.Entity("garden_bench"))).status)
        assertEquals("garden", w.guyPlace())
        val obs = w.observe(w.agentId)
        assertTrue(obs.recentEvents.last().contains("walked over to the garden bench in the garden"), obs.recentEvents.last())
        assertTrue(obs.nearby.first { it.id == "garden_bench" }.inReach)
        assertIs<IntentStatus.Succeeded>(w.run(Intent.MoveTo(Target.Entity("garden_bench"))).status)
        assertTrue(w.observe(w.agentId).recentEvents.last().contains("already by the garden bench"))
        assertTrue("nothing called" in failure(w.run(Intent.MoveTo(Target.Entity("dragon")))))
    }

    @Test
    fun `exploring the clearing finds what grows there, and he collects, keeps and sells a sample`() {
        val w = BedroomWorld.create()
        fun obs() = w.observe(w.agentId)
        assertTrue(obs().activities!!.first { it.id == "collect" }.locked!!.contains("haven't found"))
        assertIs<IntentStatus.Succeeded>(w.run(Intent.Do("explore", on = "clearing_log")).status)
        assertEquals("clearing", w.guyPlace())
        assertTrue(obs().recentEvents.any { "found glowcaps under the log and mossy stones" in it }, obs().recentEvents.toString())
        val patches = obs().activities!!.filter { it.id == "collect" && it.locked == null }.map { it.on }
        assertEquals(listOf("glowcap_patch", "moss_patch"), patches)
        assertIs<IntentStatus.Succeeded>(w.run(Intent.Do("collect", on = "glowcap_patch")).status)
        assertEquals(1, w.count("glowcap"))
        assertTrue("grown back" in failure(w.run(Intent.Do("collect", on = "glowcap_patch"))))
        assertEquals("bare", w.snapshot().entities.first { it.id.value == "glowcap_patch" }.state)
        val money = w.snapshot().life!!.money
        assertIs<IntentStatus.Succeeded>(w.run(Intent.Do("sell_sample", what = "glowcap")).status)
        assertEquals(money + 6, w.snapshot().life!!.money)
        assertEquals(0, w.count("glowcap"))
    }

    @Test
    fun `he builds the lab onto the house, walks into it, and it stays through a checkpoint`() {
        val shipped = BedroomContent.default()
        val content = shipped.copy(start = shipped.start.copy(items = shipped.start.items + ("lab_plan" to 1)))
        val w = BedroomWorld.create(content)
        assertTrue("it needs a room east of your bedroom first" in failure(w.run(Intent.Do("build_room", what = "lab_plan", on = "site_far_east"))))
        val mood = w.snapshot().life!!.moodTarget
        assertIs<IntentStatus.Succeeded>(w.run(Intent.Do("build_room", what = "lab_plan", on = "site_east")).status)
        assertTrue(w.observe(w.agentId).recentEvents.any { "built the lab east of your bedroom" in it })
        val lab = w.layout.place("lab")!!
        assertTrue(w.layout.doors.any { it.inside == "lab" && it.outside == "bedroom" })
        assertTrue(w.snapshot().entities.any { it.id.value == "lab_bench" && it.place == "lab" }, "what comes with the room stands in it")
        assertTrue(w.snapshot().life!!.moodTarget >= mood, "a room he likes is comfort")
        assertIs<IntentStatus.Succeeded>(w.run(Intent.MoveTo(Target.Place("lab"))).status)
        assertEquals("lab", w.guyPlace())
        assertTrue("stands there already" in failure(w.run(Intent.Do("build_room", what = "lab_plan", on = "site_east"))))
        val back = BedroomWorld.restore(w.exportState(), content, strict = true).world
        assertEquals(lab.bounds, back.layout.place("lab")?.bounds)
    }

    @Test
    fun `after the first day he sees what he has done lately and what he never has`() {
        val w = BedroomWorld.create()
        assertEquals(null, w.observe(w.agentId).lately, "on day 1 everything is new")
        assertIs<IntentStatus.Succeeded>(w.run(Intent.Do("sketch")).status)
        // Day 2 by daylight, when exploring isn't too dark to do.
        while (w.clock.day(w.tick) < 2 || w.clock.minuteOfDay(w.tick) < 10 * 60) w.step()
        val lately = w.observe(w.agentId).lately!!
        assertTrue(lately.first() == "in the last 2 days: sketch 1" && lately.any { it.startsWith("never yet: ") && "explore" in it }, lately.toString())
    }

    private fun failure(h: IntentHandle) = assertIs<IntentStatus.Failed>(h.status).reason

    @Test
    fun `a checkpoint from before the lot puts him where he was in the room`() {
        val w = BedroomWorld.create()
        val state = w.exportState()
        val o = w.layout.roomOrigin
        val old = state.copy(version = 1, grid = state.grid.copy(x = state.grid.x - o.x, y = state.grid.y - o.y))
        val back = BedroomWorld.restore(old, strict = true).world
        val guy = back.snapshot().entities.first { it.id == back.agentId }
        assertEquals(state.grid.x to state.grid.y, guy.x to guy.y)
        assertEquals("bedroom", guy.place)
    }
}
