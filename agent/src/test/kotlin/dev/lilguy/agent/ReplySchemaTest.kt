package dev.lilguy.agent

import dev.lilguy.decision.ActionSchema
import dev.lilguy.sim.Intent
import dev.lilguy.sim.Observation
import dev.lilguy.sim.RoomWorld
import dev.lilguy.sim.Target
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReplySchemaTest {
    private fun variants(schema: JsonObject) =
        schema["properties"]!!.jsonObject["intent"]!!.jsonObject["anyOf"]!!.jsonArray.map { it.jsonObject }
            .associateBy { it["properties"]!!.jsonObject["type"]!!.jsonObject["enum"]!!.jsonArray.single().jsonPrimitive.content }

    private fun JsonObject.enumAt(vararg path: String): List<String> {
        var node = this
        path.forEach { node = node["properties"]!!.jsonObject[it]!!.jsonObject }
        return node["enum"]!!.jsonArray.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.isString }?.content }
    }

    /** The first observation in which the guy holds the key. */
    private fun holdingKey(): Observation {
        val (world, runtime) = keyDoorScenario(KeyDoorPolicy())
        var obs: Observation? = null
        runBlocking {
            runLockstep(world, runtime, maxTicks = 2_000, shouldStop = { obs != null }) {
                world.observe(world.agentId).takeIf { "key" in it.self.inventory }?.let { obs = it }
            }
        }
        return obs!!
    }

    @Test
    fun `ids are the entities in view and use needs something in hand`() {
        val world = RoomWorld.create()
        val start = world.observe(world.agentId)
        val byTag = variants(replySchema(start).jsonSchema)
        assertEquals(setOf("move_to", "pick_up", "say", "wait"), byTag.keys)
        assertEquals(start.nearby.map { it.id }, byTag.getValue("pick_up").enumAt("itemId"))
        val entity = byTag.getValue("move_to")["properties"]!!.jsonObject["target"]!!.jsonObject["anyOf"]!!.jsonArray
            .map { it.jsonObject }.first { it["properties"]!!.jsonObject.containsKey("id") }
        assertEquals(start.nearby.map { it.id }, entity.enumAt("id"))
        assertTrue("door" !in start.nearby.map { it.id }, "the door starts out of sight")

        val held = holdingKey()
        val heldTags = variants(replySchema(held).jsonSchema)
        assertEquals(listOf("key"), heldTags.getValue("use").enumAt("itemId"))
        assertEquals(held.nearby.map { it.id }, heldTags.getValue("use").enumAt("on"))
        assertTrue("door" in held.nearby.map { it.id })
    }

    @Test
    fun `the schema rejects ids the guy can't see`() {
        val world = RoomWorld.create()
        val schema = replySchema(world.observe(world.agentId))
        val e = assertFailsWith<ActionSchema.InvalidReply> {
            schema.decode("""{"thought":"","intent":{"type":"move_to","target":{"type":"entity","id":"door"}}}""")
        }
        assertTrue("\"door\" is not one of" in e.message!!)
        assertEquals(
            Intent.MoveTo(Target.Tile(14, 7)),
            schema.decode("""{"thought":"","intent":{"type":"move_to","target":{"type":"tile","x":14,"y":7}}}""").intent,
        )
    }

    @Test
    fun `every action example is valid somewhere in the run`() {
        // The examples in the prompt must parse with the same schema the model is held to.
        val loose = ActionSchema.of("reply", AgentReply.serializer())
        ActionExamples.forEach { (intent, _) -> assertEquals(intent, loose.decode(PromptBuilderTestJson.reply(intent)).intent) }
    }

    @Test
    fun `the system prompt lists every action and the reply format`() {
        val system = PromptBuilder.systemPrompt(KEY_DOOR_GOAL)
        listOf("move_to", "pick_up", "use", "say", "wait", "\"thought\"", "\"intent\"", KEY_DOOR_GOAL).forEach {
            assertTrue(it in system, "system prompt is missing $it")
        }
    }

    @Test
    fun `a logged run replays tick for tick without the model`() {
        val log = File.createTempFile("decisions", ".jsonl").apply { deleteOnExit() }
        val original = mutableListOf<AgentRuntime.DecisionRecord>()
        val ticks = DecisionLog(log, run = "test", model = "scripted").use { out ->
            val (world, runtime) = keyDoorScenario(KeyDoorPolicy()) { original += it; out.write(it) }
            runBlocking { runLockstep(world, runtime, maxTicks = 2_000) }
        }
        val entries = DecisionLog.read(log)
        assertEquals(original.size, entries.size)
        assertTrue(entries.all { it.observation != null && it.request?.schema != null && !it.fallback })

        val replayed = mutableListOf<AgentRuntime.DecisionRecord>()
        val (world, runtime) = keyDoorScenario(ReplayDecisionModel(entries)) { replayed += it }
        val replayTicks = runBlocking { runLockstep(world, runtime, maxTicks = 2_000) }
        assertTrue(world.snapshot().completed)
        assertEquals(ticks, replayTicks)
        assertEquals(original.map { it.tick to it.intent }, replayed.map { it.tick to it.intent })
        assertEquals(original.map { it.rationale }, replayed.map { it.rationale })
    }
}

private object PromptBuilderTestJson {
    private val json = kotlinx.serialization.json.Json { encodeDefaults = true; explicitNulls = true }
    fun reply(intent: Intent) = json.encodeToString(AgentReply.serializer(), AgentReply("", intent))
}
