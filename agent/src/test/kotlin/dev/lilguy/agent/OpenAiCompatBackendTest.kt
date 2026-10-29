package dev.lilguy.agent

import dev.lilguy.decision.DecisionFailed
import dev.lilguy.decision.EndpointConfig
import dev.lilguy.decision.OpenAiCompatBackend
import dev.lilguy.decision.StructuredOutput
import dev.lilguy.sim.Intent
import dev.lilguy.sim.RoomWorld
import dev.lilguy.sim.Target
import dev.lilguy.sim.World
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The OpenAI-compatible backend against a local mock server: request shapes, retries, fallbacks. */
class OpenAiCompatBackendTest {
    private fun backend(server: MockChatServer, strategy: StructuredOutput, key: String? = "test-key") = OpenAiCompatBackend(
        EndpointConfig(baseUrl = server.baseUrl, model = "mock-model", structuredOutput = strategy, timeoutSeconds = 5.0),
        apiKey = key,
    )

    private val world = RoomWorld.create()
    private val obs = world.observe(world.agentId)
    private val request = PromptBuilder(KEY_DOOR_GOAL).build(obs, emptyList())
    private val schema = replySchema(obs)

    private val goodReply = """{"thought":"The key is south.","intent":{"type":"move_to","target":{"type":"entity","id":"key"}}}"""

    // --- request shapes -------------------------------------------------------------------------

    @Test
    fun `json_schema sends the per-request schema as response_format`() = runBlocking {
        MockChatServer.always(MockChatServer.content(goodReply)).use { server ->
            val decision = backend(server, StructuredOutput.JsonSchema).decide(request, schema)
            assertEquals(AgentReply("The key is south.", Intent.MoveTo(Target.Entity("key"))), decision.value)
            assertEquals("The key is south.", decision.rationale)
            assertEquals(100, decision.usage.promptTokens)
            assertEquals(1, decision.attempts.size)

            val body = server.requests.single()
            assertEquals("mock-model", body["model"]!!.jsonPrimitive.content)
            val format = body["response_format"]!!.jsonObject
            assertEquals("json_schema", format["type"]!!.jsonPrimitive.content)
            assertEquals(schema.jsonSchema, format["json_schema"]!!.jsonObject["schema"])
            assertEquals(true, format["json_schema"]!!.jsonObject["strict"]!!.jsonPrimitive.content.toBoolean())
            assertEquals(listOf("system", "user"), body["messages"]!!.jsonArray.map { it.jsonObject["role"]!!.jsonPrimitive.content })
            assertEquals("Bearer test-key", server.authorizations.single())
        }
    }

    @Test
    fun `tools sends one tool per variant and decodes the call`() = runBlocking {
        MockChatServer.always(MockChatServer.toolCall("pick_up", """{"thought":"grab it","itemId":"key"}""")).use { server ->
            val decision = backend(server, StructuredOutput.Tools).decide(request, schema)
            assertEquals(AgentReply("grab it", Intent.PickUp("key")), decision.value)

            val body = server.requests.single()
            val tools = body["tools"]!!.jsonArray.map { it.jsonObject["function"]!!.jsonObject }
            // use is absent: the guy holds nothing yet.
            assertEquals(listOf("move_to", "pick_up", "say", "wait"), tools.map { it["name"]!!.jsonPrimitive.content })
            val params = tools[1]["parameters"]!!.jsonObject["properties"]!!.jsonObject
            assertEquals(listOf("thought", "itemId"), params.keys.toList())
            assertEquals("required", body["tool_choice"]!!.jsonPrimitive.content)
            assertNull(body["response_format"])
        }
    }

    @Test
    fun `text puts the schema in the system prompt and digs the JSON out of prose`() = runBlocking {
        MockChatServer.always(MockChatServer.content("Sure! Here you go:\n```json\n$goodReply\n```")).use { server ->
            val decision = backend(server, StructuredOutput.Text, key = null).decide(request, schema)
            assertEquals(Intent.MoveTo(Target.Entity("key")), decision.value.intent)

            val body = server.requests.single()
            val system = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content
            assertTrue(schema.jsonSchema.toString() in system)
            assertNull(body["response_format"])
            assertNull(body["tools"])
            assertNull(server.authorizations.single())
        }
    }

    // --- retries ---------------------------------------------------------------------------------

    @Test
    fun `an invalid reply is sent back with the error and a valid retry wins`() = runBlocking {
        val bad = """{"thought":"","intent":{"type":"pick_up","itemId":"golden_key"}}"""
        MockChatServer { _, n -> 200 to MockChatServer.content(if (n == 1) bad else goodReply) }.use { server ->
            val decision = backend(server, StructuredOutput.Text).decide(request, schema)
            assertEquals(Intent.MoveTo(Target.Entity("key")), decision.value.intent)
            assertEquals(2, decision.attempts.size)
            assertNotNull(decision.attempts[0].error)
            assertNull(decision.attempts[1].error)
            assertEquals(200, decision.usage.promptTokens)

            val retry = server.requests[1]["messages"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf("system", "user", "assistant", "user"), retry.map { it["role"]!!.jsonPrimitive.content })
            assertEquals(bad, retry[2]["content"]!!.jsonPrimitive.content)
            assertTrue("golden_key" in retry[3]["content"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `a bad tool call is answered with a tool message`() = runBlocking {
        MockChatServer { _, n ->
            200 to if (n == 1) MockChatServer.toolCall("fly", "{}") else MockChatServer.toolCall("wait", """{"thought":"ok","ticks":20}""")
        }.use { server ->
            val decision = backend(server, StructuredOutput.Tools).decide(request, schema)
            assertEquals(Intent.Wait(20), decision.value.intent)
            val retry = server.requests[1]["messages"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf("system", "user", "assistant", "tool"), retry.map { it["role"]!!.jsonPrimitive.content })
            assertEquals("call_1", retry[3]["tool_call_id"]!!.jsonPrimitive.content)
            assertTrue("unknown tool \"fly\"" in retry[3]["content"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `5xx is retried after a backoff, 4xx is not`() = runBlocking {
        MockChatServer { _, n -> if (n == 1) 503 to "{}" else 200 to MockChatServer.content(goodReply) }.use { server ->
            assertEquals(Intent.MoveTo(Target.Entity("key")), backend(server, StructuredOutput.JsonSchema).decide(request, schema).value.intent)
            assertEquals(2, server.requests.size)
        }
        MockChatServer { _, _ -> 401 to """{"error":"bad key"}""" }.use { server ->
            val e = assertFailsWith<DecisionFailed> { backend(server, StructuredOutput.JsonSchema).decide(request, schema) }
            assertTrue(e.message!!.startsWith("HTTP 401"))
            assertEquals(1, server.requests.size)
        }
    }

    // --- invalid output ends in the fallback ---------------------------------------------------

    private data class BadOutput(val name: String, val reply: String, val error: String)

    private val badContent = listOf(
        BadOutput("bad JSON", MockChatServer.content("""{"thought": "hm", "intent": {"type": "move_to""""), "not valid JSON"),
        BadOutput("unknown variant", MockChatServer.content("""{"thought":"","intent":{"type":"fly","to":"door"}}"""), "unknown type \"fly\""),
        BadOutput("unknown id", MockChatServer.content("""{"thought":"","intent":{"type":"pick_up","itemId":"golden_key"}}"""), "\"golden_key\" is not one of"),
    )

    private val badToolCalls = listOf(
        BadOutput("bad JSON", MockChatServer.toolCall("pick_up", """{"thought": "hm", "itemId": """), "are not a JSON object"),
        BadOutput("unknown variant", MockChatServer.toolCall("fly", """{"thought":"","to":"door"}"""), "unknown tool \"fly\""),
        BadOutput("unknown id", MockChatServer.toolCall("pick_up", """{"thought":"","itemId":"golden_key"}"""), "\"golden_key\" is not one of"),
    )

    /**
     * The whole path: the agent loop asks, the server always answers badly, the backend retries
     * twice with the error, gives up, and the loop submits the fallback wait.
     */
    private fun assertFallsBack(strategy: StructuredOutput, bad: BadOutput) {
        MockChatServer.always(bad.reply).use { server ->
            val records = mutableListOf<AgentRuntime.DecisionRecord>()
            val (world, runtime) = keyDoorScenario(backend(server, strategy), timeout = 30.seconds) { records += it }
            runBlocking { runLockstep(world, runtime, maxTicks = 100, shouldStop = { records.isNotEmpty() }) }

            val what = "$strategy / ${bad.name}"
            val record = records.single()
            assertEquals(Intent.Wait(World.TICKS_PER_SECOND), record.intent, what)
            assertTrue(record.fellBack, what)
            assertEquals(3, server.requests.size, "$what: one try and two retries")
            assertEquals(3, record.attempts.size, what)
            record.attempts.forEach { assertTrue(bad.error in it.error.orEmpty(), "$what: '${it.error}' should mention '${bad.error}'") }
            assertTrue(bad.error in record.error.orEmpty(), what)
            // Each retry carries the previous error back to the model.
            val lastMessages = server.requests.last()["messages"]!!.jsonArray.map { it.jsonObject }
            assertEquals(2 + 2 * 2, lastMessages.size, what)
            assertTrue(lastMessages.last().content().contains(bad.error), what)
            assertEquals(3 * 100, record.usage.promptTokens, what)
        }
    }

    private fun JsonObject.content() = this["content"]!!.jsonPrimitive.content

    @Test
    fun `text strategy - invalid output takes the retry path and ends in the fallback`() =
        badContent.forEach { assertFallsBack(StructuredOutput.Text, it) }

    @Test
    fun `json_schema strategy - a server that ignores the schema still ends in the fallback`() =
        badContent.forEach { assertFallsBack(StructuredOutput.JsonSchema, it) }

    @Test
    fun `tools strategy - invalid calls take the retry path and end in the fallback`() =
        badToolCalls.forEach { assertFallsBack(StructuredOutput.Tools, it) }
}
