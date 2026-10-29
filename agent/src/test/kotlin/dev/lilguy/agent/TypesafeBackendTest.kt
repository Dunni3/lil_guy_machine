package dev.lilguy.agent

import dev.lilguy.decision.DecisionFailed
import dev.lilguy.decision.EndpointConfig
import dev.lilguy.decision.TypesafeBackend
import dev.lilguy.sim.Intent
import dev.lilguy.sim.RoomWorld
import dev.lilguy.sim.Target
import dev.lilguy.sim.World
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The TypeSafe System One backend against a local mock: request shape, decoding, failures. */
class TypesafeBackendTest {
    private fun backend(server: MockChatServer) =
        TypesafeBackend(EndpointConfig(kind = "typesafe", baseUrl = server.root, model = "jev-latest", timeoutSeconds = 5.0), apiKey = "ts-test")

    private val world = RoomWorld.create()
    private val obs = world.observe(world.agentId)
    private val request = PromptBuilder(KEY_DOOR_GOAL).build(obs, emptyList(), closedChoice = true)
    private val schema = replySchema(obs)

    private fun answer(choice: String, p: Double = 0.8) = buildJsonObject {
        put("model", "jev-1.14.0")
        put("answers", buildJsonObject {
            put("next_action", buildJsonObject {
                put("type", "choice")
                put("choice", choice)
                put("confidence", 0.7)
                put("probabilities", buildJsonObject {
                    put(choice, p)
                    put("wait:20", 1 - p)
                })
            })
        })
        put("usage", buildJsonObject { put("input_tokens", 400); put("output_tokens", 10) })
    }.toString()

    @Test
    fun `sends one choice question over the options and decodes the pick`() = runBlocking {
        MockChatServer { _, _ -> 200 to answer("move_to:key") }.use { server ->
            val decision = backend(server).decide(request, schema)
            assertEquals(Intent.MoveTo(Target.Entity("key")), decision.value.intent)
            assertEquals("move_to:key (p 0.80, confidence 0.70, next wait:20 0.20)", decision.rationale)
            assertEquals(400, decision.usage.promptTokens)
            assertEquals(listOf("/v1/systemone"), server.paths.toList())
            assertEquals("Bearer ts-test", server.authorizations.single())

            val body = server.requests.single()
            assertEquals("jev-latest", body["model"]!!.jsonPrimitive.content)
            assertEquals(request.choices!!.state, body["state"])
            val question = body["questions"]!!.jsonObject["next_action"]!!.jsonObject
            assertEquals("choice", question["type"]!!.jsonPrimitive.content)
            val criteria = question["criteria"]!!.jsonObject
            assertEquals(request.choices!!.options.map { it.label }, criteria.keys.toList())
        }
    }

    @Test
    fun `options cover the schema's ids and nothing else`() {
        val choices = request.choices!!
        val labels = choices.options.map { it.label }
        // Nothing held yet: no use options. The door is out of sight at the start.
        assertEquals(listOf("pick_up:apple", "pick_up:book", "pick_up:key", "move_to:apple", "move_to:book", "move_to:key", "wait:20"), labels)
        choices.options.forEach { schema.decode(it.reply) } // every option fits the schema
        assertTrue("localMap" !in choices.state)
        assertEquals("none yet", choices.state["last_action"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a pick outside the options is a failure, not a guess`() = runBlocking {
        MockChatServer { _, _ -> 200 to answer("fly:away") }.use { server ->
            val e = assertFailsWith<DecisionFailed> { backend(server).decide(request, schema) }
            assertTrue("fly:away" in e.message!!)
        }
    }

    @Test
    fun `a request without choices is refused`() = runBlocking {
        MockChatServer { _, _ -> 200 to answer("wait:20") }.use { server ->
            assertFailsWith<IllegalArgumentException> { backend(server).decide(request.copy(choices = null), schema) }
            assertTrue(server.requests.isEmpty())
        }
    }

    @Test
    fun `the agent loop asks for choices and falls back on errors`() {
        MockChatServer { _, _ -> 422 to """{"detail":[{"loc":["body","questions"],"msg":"bad","type":"x"}]}""" }.use { server ->
            val records = mutableListOf<AgentRuntime.DecisionRecord>()
            val (w, runtime) = keyDoorScenario(backend(server), timeout = 30.seconds) { records += it }
            runBlocking { runLockstep(w, runtime, maxTicks = 100, shouldStop = { records.isNotEmpty() }) }
            val record = records.single()
            assertEquals(Intent.Wait(World.TICKS_PER_SECOND), record.intent)
            assertTrue(record.error!!.startsWith("HTTP 422"))
            assertTrue(record.request!!.choices != null)
            val logged = DecisionLog.entryOf("t", "typesafe", record)
            assertEquals(record.request!!.choices!!.options.size, logged.request!!.choices!!.options.size)
            assertNull(logged.thought)
        }
    }
}
