package dev.lilguy.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InventionTest {
    private val content = BedroomContent.default().let { it.copy(start = it.start.copy(money = 100, items = it.start.items + ("workbench" to 1))) }
    private val rules = content.invent!!
    private val lamp = Design(
        name = "pebble lamp",
        use = "viewable",
        qualities = mapOf("calm" to 2.0, "natural" to 1.0),
        place = "floor",
        look = listOf(LookPart("cylinder", 0.3, 0.4, 0.3, color = "#557755"), LookPart("sphere", 0.25, 0.25, 0.25, y = 0.4, color = "#e8d27a")),
        why = "the room needs something green that glows",
    )

    private fun BedroomWorld.finish(intent: Intent): IntentStatus {
        val handle = submit(agentId, intent)
        repeat(20_000) {
            step()
            val s = handle.status
            if (s is IntentStatus.Succeeded || s is IntentStatus.Failed) return s
        }
        error("$intent never ended")
    }

    @Test
    fun `a design built at the workbench joins his world, costs its materials and survives a checkpoint`() {
        val w = BedroomWorld.create(content)
        w.stage("invent", AuthoredResult.Designed(lamp))
        assertIs<IntentStatus.Succeeded>(w.finish(Intent.Do("invent")))

        val made = w.inventions.single()
        assertEquals("pebble_lamp", made.item.id)
        assertEquals("spot_1", made.item.slot)
        assertEquals(4 + 5 * 3, made.cost)
        assertEquals(100 - 19, w.ledger.balance)
        assertEquals(1, w.count("pebble_lamp"))
        assertTrue(w.snapshot().entities.any { it.id.value == "pebble_lamp" }, "it stands in the room")
        val outcome = w.observe(w.agentId).recentEvents.joinToString("\n")
        assertTrue("built the pebble lamp" in outcome && "from what you can see here" in outcome, outcome)
        assertEquals("you've built what you can today; a new idea needs a night's sleep", w.observe(w.agentId).activities!!.first { it.id == "invent" }.locked)

        w.finish(Intent.Wait(1))
        val restored = BedroomWorld.restore(w.exportState(), content, strict = true).world
        assertEquals(made, restored.inventions.single())
        assertEquals(1, restored.count("pebble_lamp"))
        assertEquals("spot_1", restored.content.item("pebble_lamp")!!.slot)
    }

    @Test
    fun `a second invention keeps the first`() {
        // Building checks against the content as it is now, not as the run started.
        val w = BedroomWorld.create(content.copy(start = content.start.copy(money = 200), invent = rules.copy(perDay = 2)))
        w.stage("invent", AuthoredResult.Designed(lamp))
        assertIs<IntentStatus.Succeeded>(w.finish(Intent.Do("invent")))
        w.stage("invent", AuthoredResult.Designed(lamp.copy(name = "clay bird", qualities = mapOf("calm" to 1.0))))
        assertIs<IntentStatus.Succeeded>(w.finish(Intent.Do("invent")))
        assertEquals(listOf("pebble_lamp", "clay_bird"), w.inventions.map { it.item.id })
        assertEquals(listOf("spot_1", "spot_2"), w.inventions.map { it.item.slot })
        assertEquals(1, w.count("pebble_lamp"))
    }

    @Test
    fun `a design outside the rules is refused with the reason, and one inside them is priced by the sim`() {
        fun refused(d: Design) = assertIs<InventRules.Checked.Refused>(rules.check(d, content, 120)).problems.joinToString("; ")
        assertTrue("nothing you could build at a workbench is 'staple' yet" in refused(lamp.copy(use = "staple")))
        assertTrue("isn't something a thing can be" in refused(lamp.copy(use = "magic")))
        assertTrue("add up to at most 6" in refused(lamp.copy(qualities = mapOf("calm" to 3.0, "cozy" to 3.0, "warm" to 1.0))))
        assertTrue("'glowing' isn't a quality" in refused(lamp.copy(qualities = mapOf("glowing" to 1.0))))
        assertTrue("needs a place" in refused(lamp.copy(place = null)))
        assertTrue("needs a book" in refused(lamp.copy(use = "readable", place = null)))
        assertTrue("isn't a shape" in refused(lamp.copy(look = listOf(LookPart("teapot", 0.2, 0.2, 0.2)))))
        assertTrue("already a thing called" in refused(lamp.copy(name = "Rug")))

        // Something to sell: the sim sets what it sells for, its materials plus the wage for a two-hour build.
        val ware = assertIs<InventRules.Checked.Ok>(rules.check(lamp.copy(name = "tin bird", use = "sellable", place = null, qualities = mapOf("pretty" to 2.0)), content, 120)).invention
        assertEquals(6 + 2 * 2, ware.cost)
        assertEquals(10 + 8, ware.item.sellPrice)
        assertEquals(null, ware.item.price)

        // A failed design fails the verb and costs nothing.
        val w = BedroomWorld.create(content)
        w.stage("invent", AuthoredResult.Designed(lamp.copy(use = "staple")))
        val failed = assertIs<IntentStatus.Failed>(w.finish(Intent.Do("invent")))
        assertTrue("didn't work" in failed.reason, failed.reason)
        assertEquals(100, w.ledger.balance)
        assertTrue(w.inventions.isEmpty())
    }
}
