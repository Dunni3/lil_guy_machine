package dev.lilguy.agent

import dev.lilguy.agent.invent.CannedInventor
import dev.lilguy.agent.memory.AgentMemory
import dev.lilguy.agent.memory.EventKind
import dev.lilguy.agent.memory.InMemoryStore
import dev.lilguy.agent.memory.PlacedStore
import dev.lilguy.sim.AuthoredResult
import dev.lilguy.sim.BedroomContent
import dev.lilguy.sim.BedroomWorld
import dev.lilguy.sim.GridWorld
import dev.lilguy.sim.Intent
import dev.lilguy.sim.IntentStatus
import dev.lilguy.sim.Motion
import dev.lilguy.sim.Target
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The sprinkler of the machines plan: a thing he invents that acts on its own place, shows it, and is remembered there. */
class MachinesTest {
    private val content = BedroomContent.default().let { c ->
        c.copy(start = c.start.copy(money = 100, items = c.start.items + ("workbench" to 1)))
    }

    private fun BedroomWorld.finish(intent: Intent): IntentStatus {
        val handle = submit(agentId, intent)
        repeat(200_000) {
            step()
            val s = handle.status
            if (s is IntentStatus.Succeeded || s is IntentStatus.Failed) return s
        }
        error("$intent never ended")
    }

    private fun BedroomWorld.ok(intent: Intent) = finish(intent).let { assertIs<IntentStatus.Succeeded>(it, "$intent: ${(it as? IntentStatus.Failed)?.reason}\n" + observe(agentId).recentEvents.joinToString("\n")) }

    @Test
    fun `the sprinkler waters the bare patch in its place every half hour, shows it, and he hears of it in the garden`() {
        val w = BedroomWorld.create(content)
        val store = PlacedStore(InMemoryStore())
        val memory = AgentMemory(store, "r").also { it.connect(w) }
        w.stage("invent", AuthoredResult.Designed(CannedInventor.SPRINKLER, code = CannedInventor.SPRINKLER_CODE))
        w.ok(Intent.Do("invent"))
        assertEquals(30, w.behaviors["garden_sprinkler"]?.every, w.observe(w.agentId).recentEvents.joinToString("\n"))
        // It goes out to world.yaml's free spot by the garden bench.
        w.ok(Intent.Do("set_out_the_sprinkler", what = "garden_sprinkler"))
        w.ok(Intent.Do("explore", on = "garden_bench"))
        w.ok(Intent.Do("collect", on = "seedpod_bush"))

        // He goes in for a couple of hours; it keeps watering out there, and he doesn't hear of it.
        w.ok(Intent.MoveTo(Target.Place("bedroom")))
        val until = w.observe(w.agentId).tick + w.clock.ticks(120)
        while (w.observe(w.agentId).tick < until) w.finish(Intent.Wait(GridWorld.MAX_WAIT))
        assertTrue(w.observe(w.agentId).recentEvents.none { "while you were out" in it })
        val sprinkler = w.snapshot().entities.single { it.id.value == "garden_sprinkler" }
        assertEquals("garden", sprinkler.place)
        assertEquals("spraying", sprinkler.variant)
        assertEquals(listOf(Motion("head", "spin", 2.0)), sprinkler.motions)
        assertEquals("watering", sprinkler.readout)

        // Back in the garden, he hears what it did, and the memory of it is filed there.
        w.ok(Intent.MoveTo(Target.Place("garden")))
        val obs = w.observe(w.agentId)
        val heard = obs.news.single { "while you were out" in it }
        assertTrue("your garden sprinkler: the seed-pod bush will grow back 2 hours sooner" in heard && "times" in heard, heard)
        memory.observed(obs)
        val remembered = store.eventsAfter("r", 0, 1000).single { it.kind == EventKind.World && "while you were out" in it.text }
        assertEquals("the garden", remembered.place)
    }
}
