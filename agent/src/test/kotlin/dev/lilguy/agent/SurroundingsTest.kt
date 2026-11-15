package dev.lilguy.agent

import dev.lilguy.sim.BedroomContent
import dev.lilguy.sim.BedroomWorld
import dev.lilguy.sim.GameClock
import dev.lilguy.sim.Intent
import dev.lilguy.sim.Target
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SurroundingsTest {
    @Test
    fun `a tool model sees what's round him, can look around and walk over to a thing`() = runBlocking {
        val content = BedroomContent.default()
        val world = BedroomWorld.create(content)
        val obs = BedroomPrompt.deciding(world.observe(world.agentId), content)
        val tpm = GameClock.DEFAULT_TICKS_PER_MINUTE

        val here = BedroomTools.view(obs).here!!
        assertTrue(here.any { it.startsWith("bed: ") }, here.toString())

        val looks = BedroomTools.looks(recall = null, ask = null, look = { Surroundings.look(obs, content) })
        val tools = BedroomTools.of(obs, tpm, looks)
        val walk = tools.tools.first { it.name == "walk_to" }
        val ids = walk.parameters["properties"]!!.jsonObject["to"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("bed" in ids && "desk" in ids, ids.toString())
        val seen = tools.looks.first { it.name == BedroomTools.LOOK_AROUND }.answer(JsonObject(emptyMap()))
        assertTrue("You're in your bedroom" in seen, seen)
        assertTrue("- desk:" in seen && "garden:" in seen && "min walk" in seen, seen)

        assertEquals("walk_to" to "bed", BedroomTools.call(Intent.MoveTo(Target.Entity("bed")), tpm).let { (n, a) -> n to a["to"]!!.jsonPrimitive.content })
    }
}
