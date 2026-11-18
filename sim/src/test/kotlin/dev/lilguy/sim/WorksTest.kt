package dev.lilguy.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The compost-to-planter-to-food chain, wear and mending, and code that makes nothing from nothing. */
class WorksTest {
    private fun content(vararg items: Pair<String, Int>) = BedroomContent.default().let { it.copy(start = it.start.copy(money = 100, items = it.start.items + items)) }

    private fun BedroomWorld.finish(intent: Intent): IntentStatus {
        val handle = submit(agentId, intent)
        repeat(40_000) {
            step()
            val s = handle.status
            if (s is IntentStatus.Succeeded || s is IntentStatus.Failed) return s
        }
        error("$intent never ended")
    }

    private fun BedroomWorld.done(intent: Intent) = finish(intent).also { assertIs<IntentStatus.Succeeded>(it, (it as? IntentStatus.Failed)?.reason) }

    private fun BedroomWorld.store(id: String) = exportState().works[id] ?: Store()

    private fun BedroomWorld.open(verb: String, on: String) = observe(agentId).activities!!.any { it.id == verb && it.on == on && it.locked == null }

    @Test
    fun `scraps from a meal become compost, the raised bed beside the bin takes it and grows tomatoes, and the bin wears, jams and is mended`() {
        val w = BedroomWorld.create(content("compost_bin" to 1, "raised_bed" to 1, "food_scrap" to 20, "spare_parts" to 1, "meal" to 1))
        w.done(Intent.Do("eat", what = "meal"))
        assertEquals(21, w.count("food_scrap") + (w.store("compost_bin").inputs["food_scrap"] ?: 0), "eating leaves a scrap")

        // The bin collects his scraps by itself and works through them, and the raised bed within reach takes what it makes;
        // he only takes the tomatoes out, when what he sees says the bed wants him.
        var hours = 0
        var told = false
        while (!w.store("compost_bin").broken && hours < 200) {
            val tending = w.observe(w.agentId).tending.orEmpty()
            if (tending.any { "raised bed" in it && "cherry tomatoes made" in it }) {
                told = true
                w.done(Intent.Do("tend", on = "raised_bed"))
            }
            repeat(3) { w.finish(Intent.Wait(GridWorld.MAX_WAIT)) }
            hours += 2
        }
        assertTrue(told, "he was told the raised bed had tomatoes waiting")
        assertTrue(w.observe(w.agentId).tending.orEmpty().any { "compost bin" in it && "jammed" in it }, "${w.observe(w.agentId).tending}")
        val bin = w.store("compost_bin")
        assertTrue(bin.broken, "the bin jams after enough batches: $bin")
        assertTrue(w.count("cherry_tomatoes") >= 1, "the raised bed grew food from the bin's compost: ${w.exportState().works}")
        val log = w.snapshot().events.joinToString("\n") { it.text }
        assertTrue("the raised bed took 1 compost brick from the compost bin" in log && "jammed and needs repairing" in log, log)
        val entity = w.snapshot().entities.first { it.id.value == "compost_bin" }
        assertEquals("jammed", entity.readout)

        // Jammed, it makes nothing (what it made before can still be taken out) until he mends it with a spare part, which raises his tinkering.
        w.done(Intent.Do("tend", on = "compost_bin"))
        val jammed = w.store("compost_bin")
        repeat(12) { w.finish(Intent.Wait(GridWorld.MAX_WAIT)) }
        assertEquals(jammed.copy(outputs = emptyMap()), w.store("compost_bin").copy(outputs = emptyMap()))
        w.done(Intent.Do("repair", on = "compost_bin"))
        assertTrue(w.store("compost_bin").wear < 100 && w.skill("tinkering") > 0 && w.count("spare_parts") == 0, "${w.store("compost_bin")}")
        assertTrue(w.observe(w.agentId).activities!!.first { it.id == "repair" }.locked != null, "no spare part left")
    }

    @Test
    fun `the inspector's view of a machine says what it makes, what's in it, how worn it is and that he can tend it`() {
        val w = BedroomWorld.create(content("compost_bin" to 1, "raised_bed" to 1, "food_scrap" to 3))
        fun bin() = w.snapshot().life!!.gadgets.first { it.id == "compost_bin" }
        assertTrue(bin().uses.any { it.verb == "tend" && it.locked == null }, "${bin().uses}")
        // An empty raised bed with nothing of his to put in still shows tend, locked with why, and says where its compost comes from.
        w.finish(Intent.Wait(GridWorld.MAX_WAIT))
        val bed = w.snapshot().life!!.gadgets.first { it.id == "raised_bed" }
        assertTrue(bed.uses.any { it.verb == "tend" && it.locked?.contains("compost brick") == true }, "${bed.uses}")
        assertTrue(bed.state.any { it.first == "working" && "which the compost bin beside it makes" in it.second }, "${bed.state}")
        // The bin collected his scraps by itself meanwhile.
        assertEquals(0, w.count("food_scrap"))
        val g = bin()
        assertTrue(g.about.any { "food scrap" in it && "compost brick" in it }, "${g.about}")
        assertEquals(listOf("3 food scraps"), g.holds.map { it.what })
        assertTrue(g.state.any { it.first == "wear" }, "${g.state}")
    }

    @Test
    fun `his code can't print money, and takes out what a machine made only from beside it`() {
        val w = BedroomWorld.create(content("compost_bin" to 1))
        fun write(called: String, code: String) {
            w.stage("devise", AuthoredResult.Coded(CodeDesign(verbs = listOf(VerbDesign(called, called, "tries something", 5, code = code)))))
            w.done(Intent.Do("devise"))
        }
        val money = w.observe(w.agentId).money
        write("print_money", "function finish(world) { world.earn(1000, 'printing'); }")
        val printed = assertIs<IntentStatus.Failed>(w.finish(Intent.Do("print_money"))).reason
        assertTrue("money doesn't come from nothing" in printed, printed)
        assertEquals(money, w.observe(w.agentId).money)

        // From the desk the bin is out of reach.
        write("empty_bin", "function finish(world) { return 'took ' + world.transfer('compost_bin', 'self', 'compost_brick', 1); }")
        val far = assertIs<IntentStatus.Failed>(w.finish(Intent.Do("empty_bin"))).reason
        assertTrue("too far apart" in far, far)
    }

    @Test
    fun `a ware fetches less the more of it he sells, by his code or at the hatch alike, and recovers over days`() {
        val w = BedroomWorld.create(content("clay_pot" to 4))
        fun write(called: String, code: String) {
            w.stage("devise", AuthoredResult.Coded(CodeDesign(verbs = listOf(VerbDesign(called, called, "tries something", 5, code = code)))))
            w.done(Intent.Do("devise"))
        }
        fun events() = w.observe(w.agentId).recentEvents.joinToString("\n")
        write("sell_a_pot", "function finish(world) { return 'got ' + world.sell('clay_pot', 1); }")
        write("pot_price", "function finish(world) { const p = world.price('clay_pot'); return 'now ' + p.now + ' usual ' + p.usual; }")

        val money = w.observe(w.agentId).money!!
        w.done(Intent.Do("sell_a_pot"))
        assertTrue("got 20" in events(), events())
        // The hatch carries on from where his code left the price: $19, $18, $17 (6% off each).
        w.done(Intent.Do("sell_wares", what = "clay_pot"))
        assertTrue("sold 3 clay pots for \$54" in events() && "a clay pot fetches \$16 now, not \$20" in events(), events())
        assertEquals(money + 74, w.observe(w.agentId).money)
        w.done(Intent.Do("pot_price"))
        assertTrue("now 16 usual 20" in events(), events())

        // Saved with the world, and half the gap back a day: by midnight two days on, $19.
        assertEquals(w.exportState().market, BedroomWorld.restore(w.exportState(), BedroomContent.default(), strict = true).world.exportState().market)
        val day = w.observe(w.agentId).clock!!.day
        while (w.observe(w.agentId).clock!!.day < day + 2) w.finish(Intent.Wait(GridWorld.MAX_WAIT))
        w.done(Intent.Do("pot_price"))
        assertTrue("now 19 usual 20" in events(), events())
    }
}
