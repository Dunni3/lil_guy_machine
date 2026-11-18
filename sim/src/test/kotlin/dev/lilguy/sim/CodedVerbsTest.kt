package dev.lilguy.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CodedVerbsTest {
    private val content = BedroomContent.default().nothingWaits().let { it.copy(start = it.start.copy(money = 100, items = it.start.items + mapOf("workbench" to 1, "wood" to 30, "metal" to 10, "copper_wire" to 10, "glass" to 10))) }

    private val musicBox = Design(
        name = "music box", use = "viewable", qualities = mapOf("calm" to 1.0, "pretty" to 1.0), place = "floor",
        look = listOf(LookPart("box", 0.3, 0.2, 0.2, color = "#a0522d", material = "wood"), LookPart("cylinder", 0.08, 0.06, 0.08, y = 0.2, color = "#d4af37", material = "metal")),
        why = "something that plays when I wind it",
    )

    /** Winding it stores how wound it is; by itself it plays an hour's tune while wound, and runs down. */
    private val musicBoxCode = CodeDesign(
        verbs = listOf(
            VerbDesign(
                called = "wind the music box", phrase = "wind the music box", says = "winds it for three hours of tunes", minutes = 5,
                what = CodeDesign.THIS, at = CodeDesign.THIS,
                code = """
                    function canDo(world) { return (world.state(world.args.what).turns || 0) >= 3 ? "it's wound all the way" : null; }
                    function finish(world) {
                      world.setState(world.args.what, "turns", 3);
                      world.need("mood", 4);
                      world.say("there we go");
                      return "it's wound";
                    }
                """.trimIndent(),
            ),
        ),
        behaviors = listOf(
            BehaviorDesign(
                CodeDesign.THIS, "plays a tune each hour while it's wound",
                """
                    function hourly(world, thing) {
                      const turns = thing.state.turns || 0;
                      if (turns <= 0) return;
                      world.setState(thing.id, "turns", turns - 1);
                      world.need("stimulation", 2);
                      return "a little tune (" + (turns - 1) + " turns left)";
                    }
                """.trimIndent(),
            ),
        ),
        why = "so it does something",
    )

    private fun BedroomWorld.finish(intent: Intent): IntentStatus {
        val handle = submit(agentId, intent)
        repeat(40_000) {
            step()
            val s = handle.status
            if (s is IntentStatus.Succeeded || s is IntentStatus.Failed) return s
        }
        error("$intent never ended")
    }

    private fun BedroomWorld.events() = observe(agentId).recentEvents.joinToString("\n")

    @Test
    fun `a thing invented with its code has a verb that runs it and a behavior each hour, and both survive a checkpoint`() {
        val w = BedroomWorld.create(content)
        w.stage("invent", AuthoredResult.Designed(musicBox, code = musicBoxCode))
        w.finish(Intent.Do("invent")).let { assertIs<IntentStatus.Succeeded>(it, (it as? IntentStatus.Failed)?.reason) }
        assertTrue("a new verb of your own, wind_the_music_box" in w.events(), w.events())
        assertEquals(setOf("music_box"), w.behaviors.keys)

        val mood = w.observe(w.agentId).needs!!.mood
        w.finish(Intent.Do("wind_the_music_box", what = "music_box")).let { assertIs<IntentStatus.Succeeded>(it, (it as? IntentStatus.Failed)?.reason) }
        assertTrue("it's wound" in w.events() && "you said \"there we go\"" in w.events(), w.events())
        assertTrue(w.observe(w.agentId).needs!!.mood > mood)
        // Its canDo now says no.
        assertEquals("it's wound all the way", w.observe(w.agentId).activities!!.first { it.id == "wind_the_music_box" }.locked)

        // Two hours on, it has played twice by itself.
        w.finish(Intent.Wait(GridWorld.MAX_WAIT))
        w.finish(Intent.Wait(GridWorld.MAX_WAIT))
        assertTrue("your music box: stimulation +2" in w.events(), w.events())

        val restored = BedroomWorld.restore(w.exportState(), content, strict = true).world
        assertEquals(w.coded, restored.coded)
        assertEquals(w.behaviors, restored.behaviors)
        assertNotNull(restored.content.verbs.firstOrNull { it.id == "wind_the_music_box" })
        assertEquals(w.exportState().thingState, restored.exportState().thingState)
    }

    @Test
    fun `code from his desk moves, paints and renames his things and makes new ones, and a rewrite replaces the verb`() {
        val w = BedroomWorld.create(content)
        val spot = w.content.room.spots.floor.first()
        val tidy = VerbDesign(
            called = "rearrange", phrase = "rearrange the room", says = "moves the workbench and paints it", minutes = 30,
            code = """
                function finish(world) {
                  world.move("workbench", "$spot");
                  world.recolor("workbench", "#7aa3c9");
                  world.rename("workbench", "blue bench");
                  const id = world.make({ name: "paper star", use: "viewable", place: "wall", qualities: { pretty: 1 } });
                  world.setState("self", "tidied", true);
                  return "made " + id;
                }
            """.trimIndent(),
        )
        w.stage("devise", AuthoredResult.Coded(CodeDesign(verbs = listOf(tidy))))
        assertIs<IntentStatus.Succeeded>(w.finish(Intent.Do("devise")))
        assertIs<IntentStatus.Succeeded>(w.finish(Intent.Do("rearrange")))
        assertTrue("made paper_star" in w.events(), w.events())
        assertEquals(spot, w.content.item("workbench")!!.slot)
        assertEquals("blue bench", w.content.item("workbench")!!.name)
        assertEquals("#7aa3c9", w.snapshot().entities.first { it.id.value == "workbench" }.color)
        assertEquals(1, w.count("paper_star"))

        // Rewriting it keeps one verb of that name, at version 2.
        w.stage("devise", AuthoredResult.Coded(CodeDesign(verbs = listOf(tidy.copy(says = "does it again", code = "function finish(world) { return 'again'; }")))))
        assertIs<IntentStatus.Succeeded>(w.finish(Intent.Do("devise")))
        assertEquals(2, w.content.verbs.single { it.id == "rearrange" }.code!!.version)

        val restored = BedroomWorld.restore(w.exportState(), content, strict = true).world
        assertEquals(spot, restored.content.item("workbench")!!.slot)
        assertEquals("blue bench", restored.content.item("workbench")!!.name)
        assertEquals(1, restored.count("paper_star"))
    }

    @Test
    fun `code that breaks fails its verb with the error, and the sandbox has no way out`() {
        val sandbox = ScriptSandbox(steps = 200_000)
        fun refused(code: String) = assertIs<CodeRules.Checked.Refused>(CodeRules.check(VerbDesign("try it", "try it", "tries", 10, code = code), content, sandbox)).problems.joinToString()
        assertTrue("syntax error" in refused("function finish(world) { return ; ; ) }"))
        assertTrue("needs a function finish" in refused("function start(world) {}"))

        val w = BedroomWorld.create(content)
        fun write(called: String, code: String) {
            w.stage("devise", AuthoredResult.Coded(CodeDesign(verbs = listOf(VerbDesign(called, called, "tries something", 5, code = code)))))
            assertIs<IntentStatus.Succeeded>(w.finish(Intent.Do("devise")))
        }
        write("break", "function finish(world) {\n  world.need(\"joy\", 5);\n}")
        val broke = assertIs<IntentStatus.Failed>(w.finish(Intent.Do("break"))).reason
        assertTrue("'joy' isn't a need" in broke && "line 2" in broke, broke)

        write("spin", "function finish(world) { while (true) {} }")
        assertTrue("ran too long" in assertIs<IntentStatus.Failed>(w.finish(Intent.Do("spin"))).reason)

        write("escape", "function finish(world) { return java.lang.System.getProperty('user.home'); }")
        assertIs<IntentStatus.Failed>(w.finish(Intent.Do("escape")))
    }

    @Test
    fun `a verb that breaks partway is undone, a look in words makes a plain thing, and fixing broken code doesn't wear thin`() {
        val w = BedroomWorld.create(content)
        fun write(called: String, code: String): String {
            w.stage("devise", AuthoredResult.Coded(CodeDesign(verbs = listOf(VerbDesign(called, called, "tries something", 5, code = code)))))
            assertIs<IntentStatus.Succeeded>(w.finish(Intent.Do("devise")))
            return w.events()
        }
        val money = w.observe(w.agentId).money!!
        write("craft", "function finish(world) { world.spend(25, 'materials'); world.need('mood', 5); world.setState('self', 'crafted', true); world.need('joy', 1); }")
        val broke = assertIs<IntentStatus.Failed>(w.finish(Intent.Do("craft"))).reason
        assertTrue("what it did before that is undone (-\$25 (materials)" in broke, broke)
        assertEquals(money, w.observe(w.agentId).money!!)
        assertEquals(emptyMap(), w.exportState().thingState)

        // Rewrite, still broken, rewrite again: fixing it never pays less.
        repeat(3) {
            assertTrue("done a lot lately" !in write("craft", "function finish(world) { world.need('joy', 1); }").lines().last())
            assertIs<IntentStatus.Failed>(w.finish(Intent.Do("craft")))
        }

        val wood = w.count("wood")
        write("craft", "function finish(world) { world.spend(25, 'materials'); return world.make({ name: 'music box', use: 'playable', look: 'a wooden box of brass gears' }); }")
        assertIs<IntentStatus.Succeeded>(w.finish(Intent.Do("craft")))
        assertTrue("made a music box (plain to look at" in w.events(), w.events())
        assertEquals(1, w.count("music_box"))
        // The music box's materials come from his stock, as at the workbench: a plain thing takes a little wood.
        assertEquals(money - 25, w.observe(w.agentId).money!!)
        assertEquals(wood - InventRules.PLAIN, w.count("wood"))
    }
}
