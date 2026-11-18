package dev.lilguy.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InventionTest {
    private val content = BedroomContent.default().nothingWaits().let { it.copy(start = it.start.copy(money = 100, items = it.start.items + mapOf("workbench" to 1, "wood" to 10, "glass" to 3))) }
    private val rules = content.invent!!
    private val lamp = Design(
        name = "pebble lamp",
        use = "viewable",
        qualities = mapOf("calm" to 2.0, "natural" to 1.0),
        place = "floor",
        look = listOf(LookPart("cylinder", 0.3, 0.4, 0.3, color = "#557755", material = "wood"), LookPart("sphere", 0.25, 0.25, 0.25, y = 0.4, color = "#e8d27a", material = "glass")),
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
    fun `a design built at the workbench joins his world, takes its materials and survives a checkpoint`() {
        // Short of glass: refused with what it takes, what he has and where to get it, and nothing is used.
        val short = BedroomWorld.create(content.copy(start = content.start.copy(items = content.start.items - "glass")))
        short.stage("invent", AuthoredResult.Designed(lamp))
        val refused = assertIs<IntentStatus.Failed>(short.finish(Intent.Do("invent"))).reason
        assertTrue("takes 8 wood planks and 2 glass panes; you have no glass panes" in refused && "The shop sells glass panes (3 for $9)" in refused, refused)
        assertEquals(10, short.count("wood"))

        val w = BedroomWorld.create(content)
        w.stage("invent", AuthoredResult.Designed(lamp))
        assertIs<IntentStatus.Succeeded>(w.finish(Intent.Do("invent")))

        val made = w.inventions.single()
        assertEquals("pebble_lamp", made.item.id)
        assertEquals("spot_1", made.item.slot)
        // Its wood (5 for the base, 3 for its qualities' finish) and glass (2 for the shade), at the shop's prices; no money changes hands.
        assertEquals(mapOf("wood" to 8, "glass" to 2), made.bill)
        assertEquals(8 * 2 + 2 * 3, made.cost)
        assertEquals(100, w.ledger.balance)
        assertEquals(2, w.count("wood"))
        assertEquals(1, w.count("glass"))
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
        val w = BedroomWorld.create(content.copy(start = content.start.copy(money = 200, items = content.start.items + mapOf("wood" to 20, "glass" to 6)), invent = rules.copy(perDay = 2)))
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
        assertTrue("needs a material" in refused(lamp.copy(look = listOf(LookPart("box", 0.2, 0.2, 0.2)))))

        // Something to sell: the sim sets what it sells for, its materials plus the wage for a two-hour build.
        val ware = assertIs<InventRules.Checked.Ok>(rules.check(lamp.copy(name = "tin bird", use = "sellable", place = null, qualities = mapOf("pretty" to 2.0)), content, 120)).invention
        assertEquals(7 * 2 + 2 * 3, ware.cost)
        assertEquals(20 + 8, ware.item.sellPrice)
        assertEquals(null, ware.item.price)

        // A failed design fails the verb and costs nothing.
        val w = BedroomWorld.create(content)
        w.stage("invent", AuthoredResult.Designed(lamp.copy(use = "staple")))
        val failed = assertIs<IntentStatus.Failed>(w.finish(Intent.Do("invent")))
        assertTrue("didn't work" in failed.reason, failed.reason)
        assertEquals(100, w.ledger.balance)
        assertTrue(w.inventions.isEmpty())
    }

    @Test
    fun `with the room's spots taken it's built outdoors, and taking it apart gives half its materials and its spot back`() {
        // Things he doesn't own yet keep the room's four spare floor spots.
        val full = content.copy(items = content.items + (1..4).map { ItemSpec("crate_$it", "crate $it", "decor", slot = "spot_$it", blocks = true) })
        val w = BedroomWorld.create(full)
        assertEquals("needs a thing you invented", w.observe(w.agentId).activities!!.first { it.id == "dismantle" }.locked)
        w.stage("invent", AuthoredResult.Designed(lamp))
        assertIs<IntentStatus.Succeeded>(w.finish(Intent.Do("invent")))
        val spot = w.inventions.single().item.slot!!
        assertTrue(spot in full.world!!.slots, "it went to a free spot on the land: $spot")

        val option = w.observe(w.agentId).activities!!.first { it.id == "dismantle" }
        assertEquals("pebble_lamp", option.on)
        assertTrue("back 4 wood planks and 1 glass pane" in option.gives.orEmpty().replace(", ", " and "), option.gives)
        assertIs<IntentStatus.Succeeded>(w.finish(Intent.Do("dismantle", on = "pebble_lamp")))
        assertTrue(w.inventions.isEmpty())
        assertEquals(null, w.content.item("pebble_lamp"))
        assertEquals(2 + 4, w.count("wood"))
        assertEquals(1 + 1, w.count("glass"))
        val outcome = w.observe(w.agentId).recentEvents.joinToString("\n")
        assertTrue("took the pebble lamp apart, and $spot is free now" in outcome, outcome)
        assertEquals(spot, w.inventSpot("floor"))

        w.finish(Intent.Wait(1))
        val restored = BedroomWorld.restore(w.exportState(), full, strict = true).world
        assertTrue(restored.inventions.isEmpty() && restored.content.item("pebble_lamp") == null)
        assertEquals(6, restored.count("wood"))
    }

    @Test
    fun `a record he holds is taken apart for its salvage where he is`() {
        val w = BedroomWorld.create(content)
        val record = lamp.copy(name = "tin song", use = "playable", place = null, qualities = mapOf("calm" to 3.0), look = listOf(LookPart("cylinder", 0.3, 0.02, 0.3, color = "#333333", material = "wood")))
        w.stage("invent", AuthoredResult.Designed(record))
        assertIs<IntentStatus.Succeeded>(w.finish(Intent.Do("invent")))
        val made = w.inventions.single()
        assertEquals(null, made.item.slot)
        assertEquals(mapOf("wood" to 4), made.bill)
        assertEquals(10 - 4, w.count("wood"))

        val option = w.observe(w.agentId).activities!!.first { it.id == "dismantle" }
        assertEquals("tin_song", option.on)
        assertEquals(null, option.locked)
        assertIs<IntentStatus.Succeeded>(w.finish(Intent.Do("dismantle", on = "tin_song")))
        assertEquals(null, w.content.item("tin_song"))
        assertEquals(6 + 2, w.count("wood"))
        val outcome = w.observe(w.agentId).recentEvents.joinToString("\n")
        assertTrue("took the tin song apart" in outcome && "got back 2 wood planks" in outcome, outcome)
    }

    @Test
    fun `materials he doesn't need sell back to the shop for about half`() {
        val w = BedroomWorld.create(content)
        val options = w.observe(w.agentId).activities!!.filter { it.id == "sell_materials" }
        assertEquals(listOf("wood", "glass"), options.map { it.what })
        assertTrue("$10 for the 10 wood planks you hold" in options[0].gives.orEmpty(), options[0].gives)
        assertIs<IntentStatus.Succeeded>(w.finish(Intent.Do("sell_materials", what = "glass")))
        assertEquals(0, w.count("glass"))
        assertEquals(10, w.count("wood"))
        assertEquals(100 + 3 * 2, w.ledger.balance)
    }
}
