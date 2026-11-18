package dev.lilguy.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Warmth, sound, sun and water, visitors, and what his code gives off (Surroundings.kt). */
class EffectsTest {
    private val shipped = BedroomContent.default()

    /** Always clear, [mean] degrees on average. */
    private fun content(time: String, mean: Double = 12.0, vararg items: String): BedroomContent {
        val w = shipped.weather!!
        val clear = Sky.entries.associate { it.id to mapOf("clear" to 1.0) }
        return shipped.copy(
            start = shipped.start.copy(time = time, money = 100, items = shipped.start.items + items.associateWith { 1 } + mapOf("wood" to 20, "copper_wire" to 6)),
            weather = w.copy(turns = clear, temperature = w.temperature.copy(mean = mean, drift = 0.0)),
        )
    }

    private fun world(time: String, mean: Double = 12.0, vararg items: String) = BedroomWorld.create(content(time, mean, *items))

    private fun BedroomWorld.run(intent: Intent): IntentStatus {
        val handle = submit(agentId, intent)
        repeat(40_000) {
            step()
            val s = handle.status
            if (s is IntentStatus.Succeeded || s is IntentStatus.Failed || s is IntentStatus.Interrupted) return s
        }
        error("$intent still ${handle.status}")
    }

    private fun BedroomWorld.ok(intent: Intent) = run(intent).let { assertIs<IntentStatus.Succeeded>(it, (it as? IntentStatus.Failed)?.reason) }

    private fun BedroomWorld.hours(n: Int) = repeat(clock.ticks(60 * n)) { step() }

    private fun BedroomWorld.senses() = observe(agentId).senses.orEmpty()

    private fun BedroomWorld.locked(verb: String) = observe(agentId).activities!!.first { it.id == verb }.locked

    @Test
    fun `a cold night outdoors lowers mood and energy, and a fire pit keeps it off and lets him explore`() {
        val w = world("22:00", mean = 3.0)
        w.ok(Intent.MoveTo(Target.Place("garden")))
        assertTrue(w.senses().any { it.startsWith("cold here") }, w.senses().toString())
        assertTrue(w.locked("explore")!!.startsWith("too dark to explore"), "dark first, then cold")
        val energy = w.needs[Need.Energy]
        w.hours(1)
        assertTrue(w.needs.changes.any { it.reason == "cold" && it.need == Need.Energy }, "the cold costs energy")
        assertTrue(w.needs[Need.Energy] < energy - 4, "awake and cold: ${w.needs[Need.Energy]} from $energy")

        val fire = world("22:00", 3.0, "fire_pit")
        fire.ok(Intent.Do("explore", on = "garden_bench"))
        val bench = fire.layout.slotCells.getValue("garden_bench")
        assertTrue(fire.warmthField()[bench] > 6 && fire.lightField(lantern = false)[bench] >= 3, "the fire warms and lights the bench")
        assertTrue(fire.senses().none { it.startsWith("cold here") }, fire.senses().toString())
        // Indoors the house keeps most of its warmth, but a cold night chills the bed; the heater warms it.
        val bed = fire.layout.slotCells.getValue("bed")
        assertTrue(fire.warmthField()[bed] < shipped.sleep!!.cold, "a cold night chills the bed: ${fire.warmthField()[bed]}")
        val heater = world("22:00", 3.0, "heater")
        assertTrue(heater.warmthField()[bed] >= shipped.sleep!!.cold, "the heater warms the bed: ${heater.warmthField()[bed]}")
    }

    @Test
    fun `a record in the bedroom is heard faintly in the garden and not in the forest`() {
        val w = world("12:00", 12.0, "record_player", "jazz_record")
        w.ok(Intent.Do("play_record", what = "jazz_record"))
        assertNull(w.observe(w.agentId).senses, "by the player he hears it in full")
        w.ok(Intent.MoveTo(Target.Place("garden")))
        assertTrue(w.senses().any { it.startsWith("you hear the jazz record faintly from here") }, w.senses().toString())
        w.ok(Intent.MoveTo(Target.Place("clearing")))
        assertTrue("you can't hear the jazz record from here" in w.senses(), w.senses().toString())
    }

    @Test
    fun `a planter grows by the window and not in the corner`() {
        val w = world("06:00", 12.0, "planter", "watering_can")
        w.ok(Intent.Do("water_plant", on = "planter"))
        w.hours(14)
        assertTrue(w.locked("harvest")!!.contains("0 of 10 sunny hours; too little sun reaches it here"), w.locked("harvest"))
        w.ok(Intent.Put("planter", "spot_2"))
        w.ok(Intent.Do("water_plant", on = "planter"))
        w.hours(24)
        assertNull(w.locked("harvest"), "a day by the window")
        w.ok(Intent.Do("harvest", on = "planter"))
        assertEquals(3, w.count("cherry_tomatoes"))
    }

    @Test
    fun `moths come to the porch lantern at night and birds to the feeder by day, which the station logs`() {
        val w = world("20:00", 12.0, "moth_lamp", "bird_feeder", "weather_station")
        assertTrue(w.locked("watch")!!.startsWith("no moths at the porch lantern now; they come at night"), w.locked("watch"))
        w.hours(1)
        val moths = w.visits().single { it.visitor.id == "moths" }
        assertEquals("moth_lamp", moths.lure.id)
        assertTrue(w.observe(w.agentId).recentEvents.any { "moths came to the porch lantern" in it.toString() }, "the coming is an event")
        w.ok(Intent.Do("watch", on = "moth_lamp"))
        assertEquals(1, w.count("silver_moth"))
        assertTrue(w.visits().none { it.visitor.id == "moths" }, "they keep away a while after")
        while (w.clock.minuteOfDay(w.tick) != 11 * 60) w.step()
        assertTrue(w.visits().any { it.visitor.id == "birds" }, "birds by day")
        w.hours(1)
        val log = w.exportState().records.getValue("weather_station")
        assertTrue((log["birds"] ?: emptyList()).any { it.value > 0 }, "the station by the feeder logs the birds: $log")
        assertTrue("moths" !in log, "the porch is out of the station's reach")
    }

    @Test
    fun `his pinecone lamp's hourly code gives off real light now, and it survives a checkpoint`() {
        val c = content("15:00", 12.0, "workbench")
        val w = BedroomWorld.create(c)
        // His live invention (run 01a1e006), its code ported from `world.need("mood", 1)` to emitting light at night.
        val lamp = Design(name = "pinecone ceramic lamp", use = "viewable", qualities = mapOf("cozy" to 2.0, "warm" to 2.0, "natural" to 2.0), place = "floor", why = "a warm glowing lamp")
        val code = CodeDesign(
            behaviors = listOf(
                BehaviorDesign(
                    CodeDesign.THIS, "glows softly at night",
                    """
                        function hourly(world, thing) {
                          world.emit(thing.id, "light", world.time().night ? 3 : 0, 3);
                          return "light here " + world.field(thing.id, "light");
                        }
                    """.trimIndent(),
                ),
            ),
        )
        w.stage("invent", AuthoredResult.Designed(lamp, code = code))
        w.ok(Intent.Do("invent"))
        val id = w.behaviors.keys.single()
        while (w.clock.minuteOfDay(w.tick) != 22 * 60 + 5) w.step()
        val at = w.layout.slotCells.getValue(w.content.item(id)!!.slot!!)
        assertTrue(w.lightField(lantern = false)[at] >= 3, "its code lights its spot: ${w.lightField(lantern = false)[at]}")
        val restored = BedroomWorld.restore(w.exportState(), c, strict = true).world
        assertEquals(w.lightField(lantern = false)[at], restored.lightField(lantern = false)[at])
        assertNotNull(w.exportState().surroundings.emitted[id])
    }
}
