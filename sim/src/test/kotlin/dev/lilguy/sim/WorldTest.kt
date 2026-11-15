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
        assertTrue(home.activities!!.none { it.where != null }, "everything he can do is in his room")
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
