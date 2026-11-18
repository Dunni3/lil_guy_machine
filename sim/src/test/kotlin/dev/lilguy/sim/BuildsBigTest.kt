package dev.lilguy.sim

import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Big things he builds on the land, recipes and wares of his own, and machines that feed and sell by themselves. */
class BuildsBigTest {
    private fun content(vararg items: Pair<String, Int>) = BedroomContent.default().nothingWaits().let {
        it.copy(start = it.start.copy(time = "09:00", money = 100, items = it.start.items + mapOf("workbench" to 1) + items))
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

    private fun BedroomWorld.events() = observe(agentId).recentEvents.joinToString("\n")

    private fun wood(part: String, w: Double, h: Double, d: Double, x: Double = 0.0, y: Double = 0.0, z: Double = 0.0) =
        LookPart("box", w, h, d, x, y, z, color = "#a87a4f", part = part, material = "wood")

    /** A 5 by 4 cabin with its door on the south side and a window east: a floor, four walls and a roof. */
    private val cabin = Design(
        name = "little cabin",
        use = "viewable",
        qualities = mapOf("cozy" to 2.0),
        place = "land",
        footprint = Footprint(5, 4),
        door = "south",
        windows = listOf("east"),
        look = listOf(
            wood("floor", 5.0, 0.1, 4.0),
            wood("north wall", 5.0, 2.4, 0.15, z = -1.9),
            wood("south wall", 5.0, 2.4, 0.15, z = 1.9),
            wood("west wall", 0.15, 2.4, 4.0, x = -2.4),
            wood("east wall", 0.15, 2.4, 4.0, x = 2.4),
            wood("roof", 5.0, 0.2, 4.0, y = 2.4),
        ),
        why = "somewhere of my own out on the grass",
    )

    @Test
    fun `a cabin he designs goes up on the land, he walks in and puts a thing there, it survives a checkpoint, and taking it apart gives the land back`() {
        val start = content("wood" to 200, "plant" to 1)
        val w = BedroomWorld.create(start)
        w.stage("invent", AuthoredResult.Designed(cabin))
        w.finish(Intent.Do("invent")).let { assertIs<IntentStatus.Succeeded>(it, it.toString()) }
        assertTrue("on the land" in w.events() && "you can go into the little cabin" in w.events(), w.events())

        val place = assertNotNull(w.layout.place("little_cabin"), "the cabin is a place")
        assertEquals(PlaceKind.Building, place.kind)
        val st = assertNotNull(w.content.structure("little_cabin"))
        assertEquals('+', w.layout.rows[st.doorCell!!.y][st.doorCell!!.x], "its door is a doorway")
        assertTrue(w.inventions.single().bill.getValue("wood") > 60, "a cabin takes a lot of wood: ${w.inventions.single().bill}")

        w.finish(Intent.MoveTo(Target.Place("little_cabin"))).let { assertIs<IntentStatus.Succeeded>(it, it.toString()) }
        assertEquals("little_cabin", w.layout.placeOf(w.snapshot().entities.first { it.id == w.agentId }.let { Cell(it.x.toInt(), it.y.toInt()) })?.id)
        val spot = st.spots.first().first
        w.finish(Intent.Put("plant", spot)).let { assertIs<IntentStatus.Succeeded>(it, it.toString()) }
        assertEquals("little_cabin", w.snapshot().entities.first { it.id.value == "plant" }.place)

        val back = BedroomWorld.restore(w.exportState(), start, strict = true).world
        assertNotNull(back.layout.place("little_cabin"), "the cabin stands after a checkpoint")
        assertEquals(spot, back.content.item("plant")!!.slot)

        val locked = assertIs<IntentStatus.Failed>(w.finish(Intent.Do("dismantle", on = "little_cabin"))).reason
        assertTrue("plant stands in the little cabin" in locked, locked)
        w.finish(Intent.Put("plant", "plant")).let { assertIs<IntentStatus.Succeeded>(it, it.toString()) }
        val wood = w.count("wood")
        w.finish(Intent.Do("dismantle", on = "little_cabin")).let { assertIs<IntentStatus.Succeeded>(it, it.toString()) }
        assertNull(w.layout.place("little_cabin"))
        assertTrue(w.count("wood") > wood, "half its wood comes back")
        assertTrue(st.rect.let { r -> (r.y0..r.y1).all { y -> (r.x0..r.x1).all { x -> w.layout.map.isFloor(x, y) } } }, "its ground is open again")
    }

    @Test
    fun `a machine he designs prices its own ware by what it is worth, feeds from his things and sells by itself`() {
        val w = BedroomWorld.create(content("metal" to 60, "wood" to 20, "copper_wire" to 6))
        val lathe = Design(
            name = "bowl lathe",
            use = "viewable",
            place = "floor",
            look = listOf(
                LookPart("box", 0.8, 0.5, 0.5, color = "#7d8a91", part = "bed", material = "metal"),
                LookPart("cylinder", 0.2, 0.3, 0.2, y = 0.5, color = "#b0602e", part = "spindle", material = "metal"),
            ),
            recipes = listOf(RecipeDesign("turning", takes = mapOf("wood" to 2), makes = mapOf("wooden bowl" to 1))),
            products = listOf(ProductDesign("wooden bowl", "a bowl turned from a plank")),
            takeFrom = listOf(Link.YOU),
            sendTo = Link.MARKET,
            why = "to make bowls while I sleep",
        )
        w.stage("invent", AuthoredResult.Designed(lathe))
        w.finish(Intent.Do("invent")).let { assertIs<IntentStatus.Succeeded>(it, it.toString()) }

        val made = w.inventions.single()
        val bowl = assertNotNull(w.content.item("wooden_bowl"), "the bowl is a ware in his world")
        // Two planks ($2 each) and two hours of a machine worth its materials, at 3% of that an hour.
        val earned = maxOf(MachineRules.Earning.LEAST, made.cost * 0.03) * 2
        assertEquals((4 + earned).roundToInt(), bowl.sellPrice)
        assertEquals(setOf(Link(Link.YOU, "bowl_lathe"), Link("bowl_lathe", Link.MARKET)), w.exportState().links.toSet())

        val money = w.ledger.balance
        repeat(w.clock.ticks(4 * 60).toInt()) { w.step() }
        assertTrue(w.ledger.balance > money, "it sold what it made: ${w.events()}")
        assertTrue(w.count("wood") <= 16, "it took its planks from his things")

        // He takes its connections away, and sets it selling again with one verb.
        w.finish(Intent.Do("disconnect", on = "bowl_lathe")).let { assertIs<IntentStatus.Succeeded>(it, it.toString()) }
        assertTrue(w.exportState().links.isEmpty())
        w.finish(Intent.Do("sell_by_itself", what = "bowl_lathe")).let { assertIs<IntentStatus.Succeeded>(it, it.toString()) }
        assertEquals(listOf(Link("bowl_lathe", Link.MARKET)), w.exportState().links)
    }
}
