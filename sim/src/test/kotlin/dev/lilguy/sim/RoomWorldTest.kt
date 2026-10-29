package dev.lilguy.sim

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RoomWorldTest {
    // 6x5, door on the east wall with the exit beyond it; the guy starts beside the key.
    private val small = RoomSpec(
        listOf(
            "###### ",
            "#@K..# ",
            "#....DE",
            "#.A..# ",
            "###### ",
        ),
    )

    private fun world() = RoomWorld.create(small)

    private fun RoomWorld.guy() = snapshot().entities.first { it.kind == "guy" }

    private fun RoomWorld.runUntilDone(handle: IntentHandle, maxTicks: Int = 400) {
        repeat(maxTicks) {
            step()
            val s = handle.status
            if (s is IntentStatus.Succeeded || s is IntentStatus.Failed || s is IntentStatus.Interrupted) return
        }
        error("${handle.intent} still ${handle.status} after $maxTicks ticks")
    }

    private fun failure(handle: IntentHandle): String = assertIs<IntentStatus.Failed>(handle.status).reason

    @Test
    fun `parses the MVP room`() {
        val w = RoomWorld.create()
        assertEquals(17, w.map.width)
        assertEquals(16, w.map.height)
        val kinds = w.snapshot().entities.map { it.kind }.toSet()
        assertEquals(setOf("guy", "door", "exit", "key", "apple", "book"), kinds)
    }

    @Test
    fun `submitted intents wait for the next tick`() {
        val w = world()
        val handle = w.submit(w.agentId, Intent.Wait(3))
        assertEquals(IntentStatus.Submitted, handle.status)
        assertFalse(w.awaitingDecision(w.agentId))
        w.step()
        assertEquals(IntentStatus.Executing, handle.status)
        w.step()
        w.step()
        assertEquals(IntentStatus.Succeeded, handle.status)
        assertTrue(w.awaitingDecision(w.agentId))
    }

    @Test
    fun `an agent woken at a decision point sees that tick's observation`() {
        val w = world()
        w.submit(w.agentId, Intent.PickUp("key"))
        var seen: Observation? = null
        runBlocking {
            // Unconfined resumes inside step(), the earliest moment another thread could observe.
            val agent = launch(Dispatchers.Unconfined) {
                w.awaitDecisionPoint(w.agentId)
                seen = w.observe(w.agentId)
            }
            repeat(20) { if (seen == null) w.step() }
            agent.join()
        }
        val obs = assertNotNull(seen)
        assertEquals(IntentStatus.Succeeded, obs.lastResult)
        assertEquals(listOf("key"), obs.self.inventory)
    }

    @Test
    fun `invalid intents fail with a reason on the next tick`() {
        val w = world()
        val cases = mapOf(
            Intent.MoveTo(Target.Tile(0, 0)) to "wall",
            Intent.MoveTo(Target.Tile(40, 2)) to "outside the map",
            Intent.MoveTo(Target.Tile(5, 2)) to "closed",
            Intent.MoveTo(Target.Entity("dragon")) to "nothing called",
            Intent.PickUp("door") to "can't be picked up",
            Intent.PickUp("apple") to "tiles away",
            Intent.Use("key", on = "door") to "aren't holding",
            Intent.Wait(0) to "between",
            Intent.Say("  ") to "needs some text",
        )
        for ((intent, expected) in cases) {
            val handle = w.submit(w.agentId, intent)
            w.step()
            val reason = failure(handle)
            assertTrue(expected in reason, "$intent failed with '$reason', expected '$expected'")
        }
        assertTrue(w.awaitingDecision(w.agentId))
        assertTrue(w.observe(w.agentId).recentEvents.last().contains("failed"))
    }

    @Test
    fun `a new intent interrupts the running one`() {
        val w = world()
        val first = w.submit(w.agentId, Intent.Wait(100))
        w.step()
        assertEquals(IntentStatus.Executing, first.status)
        val second = w.submit(w.agentId, Intent.Say("never mind"))
        w.step()
        assertEquals(IntentStatus.Interrupted, first.status)
        assertEquals(IntentStatus.Executing, second.status)
        assertEquals("never mind", w.snapshot().agents.single().saying)
    }

    @Test
    fun `walls and the locked door block movement`() {
        val w = world()
        val handle = w.submit(w.agentId, Intent.MoveTo(Target.Entity("door")))
        repeat(200) {
            w.step()
            val g = w.guy()
            assertTrue(w.passable(g.x.toInt(), g.y.toInt()), "guy inside a wall or door at (${g.x}, ${g.y})")
        }
        assertEquals(IntentStatus.Succeeded, handle.status)
        // Stopped beside the locked door, not on it.
        val g = w.guy()
        assertEquals(4, g.x.toInt())
        assertFalse(w.snapshot().completed)
    }

    @Test
    fun `key unlocks the door and walking out completes the room`() {
        val w = world()
        val steps = listOf(
            Intent.PickUp("key"),
            Intent.MoveTo(Target.Entity("door")),
            Intent.Use("key", on = "door"),
            Intent.MoveTo(Target.Entity("exit")),
        )
        for (intent in steps) {
            val handle = w.submit(w.agentId, intent)
            w.runUntilDone(handle)
            assertEquals(IntentStatus.Succeeded, handle.status, "$intent")
        }
        val s = w.snapshot()
        assertTrue(s.completed)
        assertEquals("open", s.entities.first { it.kind == "door" }.state)
        assertEquals(listOf("key"), s.agents.single().inventory)
        assertNotNull(s.events.find { "walked out" in it.text })
    }

    @Test
    fun `observation shows nearby entities and a local map`() {
        val w = world()
        val obs = w.observe(w.agentId)
        val key = obs.nearby.first()
        assertEquals("key", key.id)
        assertEquals(Direction.E, key.direction)
        assertEquals(1f, key.distance)
        assertEquals("locked", obs.nearby.first { it.id == "door" }.state)
        assertEquals(9, obs.localMap.size)
        assertEquals('@', obs.localMap[4][4])
        assertEquals('k', obs.localMap[4][5])
        assertEquals('#', obs.localMap[3][4])
    }

    @Test
    fun `snapshots are immutable values`() {
        val w = world()
        val before = w.snapshot()
        w.submit(w.agentId, Intent.MoveTo(Target.Tile(4, 3)))
        repeat(10) { w.step() }
        assertEquals(0L, before.tick)
        assertEquals(1.5f, before.entities.first { it.kind == "guy" }.x)
        assertTrue(w.guy().x > 1.5f)
    }
}
