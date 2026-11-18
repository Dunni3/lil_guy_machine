package dev.lilguy.agent

import dev.lilguy.agent.memory.WorldDocs
import dev.lilguy.sim.BedroomContent
import dev.lilguy.sim.BedroomWorld
import dev.lilguy.sim.GameClock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import kotlin.test.Test
import kotlin.test.assertTrue

class DocsLookTest {
    @Test
    fun `he searches and reads the world's docs, and the guide waits for what a book teaches`() = runBlocking {
        val content = BedroomContent.default()
        val world = BedroomWorld.create(content)
        val obs = BedroomPrompt.deciding(world.observe(world.agentId), content)
        val looks = BedroomTools.looks(recall = null, ask = null, docs = { WorldDocs.of(world.knownContent()) })
        val tools = BedroomTools.of(obs, GameClock.DEFAULT_TICKS_PER_MINUTE, looks)
        val search = tools.looks.first { it.name == BedroomTools.SEARCH_DOCS }
        val read = tools.looks.first { it.name == BedroomTools.READ_DOCS }

        val found = search.answer(buildJsonObject { put("query", "sleeping in the cold") })
        assertTrue("guide:night" in found && "rules:sleep" in found, found)
        val contents = search.answer(JsonObject(emptyMap()))
        assertTrue("- verb:sketch:" in contents && "- guide:fun:" in contents, contents)
        // Painting is taught by the drawing book: neither its verb nor its guide section is there yet.
        assertTrue("verb:paint" !in contents && "guide:painting" !in contents, contents)

        val opened = read.answer(buildJsonObject { putJsonArray("ids") { add("guide:fun"); add("guide:nope") } })
        assertTrue("## guide:fun:" in opened && "familiar" in opened, opened)
        assertTrue("no section \"guide:nope\"" in opened, opened)

        // Once he knows painting, its section is in the docs.
        val painting = WorldDocs.of(content.known(content.taught)).map { it.id }
        assertTrue("guide:painting" in painting && "verb:paint" in painting, painting.toString())
        assertTrue(BedroomPrompt.system(content, GameClock.DEFAULT_TICKS_PER_MINUTE, tools = true, looks = setOf(BedroomTools.SEARCH_DOCS)).contains("`search_docs`"))
        Unit
    }
}
