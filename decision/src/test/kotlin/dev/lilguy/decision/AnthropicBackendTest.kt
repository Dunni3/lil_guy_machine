package dev.lilguy.decision

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals

class AnthropicBackendTest {
    @Serializable
    data class Pick(val n: Int)

    @Test
    fun `a tool set whose strict grammar is too large is sent again unstrict, and stays unstrict`() {
        val strictness = mutableListOf<Boolean>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val strict = "\"strict\":true" in ex.requestBody.readAllBytes().decodeToString()
            strictness += strict
            val (status, body) = if (strict) {
                400 to """{"type":"error","error":{"type":"invalid_request_error","message":"The compiled grammar is too large, which would cause performance issues. Simplify your tool schemas or reduce the number of strict tools."}}"""
            } else {
                200 to """{"content":[{"type":"tool_use","id":"toolu_1","name":"pick","input":{"n":3}}],"stop_reason":"tool_use","usage":{"input_tokens":10,"output_tokens":2}}"""
            }
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
            ex.close()
        }
        server.start()
        try {
            val config = EndpointConfig(kind = "anthropic", baseUrl = "http://127.0.0.1:${server.address.port}/v1", model = "m", structuredOutput = StructuredOutput.Tools)
            val params = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { putJsonObject("n") { put("type", "integer") } }
            }
            val tools = ToolSet(listOf(ToolSet.Tool("pick", "pick a number", params) { it }))
            val schema = ActionSchema("pick", Pick.serializer(), JsonObject(emptyMap()))
            val request = DecisionRequest(system = "pick", messages = emptyList(), tools = tools)
            AnthropicBackend(config, "k").use { model ->
                assertEquals(Pick(3), runBlocking { model.decide(request, schema) }.value)
                assertEquals(Pick(3), runBlocking { model.decide(request, schema) }.value)
            }
            assertEquals(listOf(true, false, false), strictness)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a call with no tools goes through`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            ex.requestBody.readAllBytes()
            val bytes = """{"content":[{"type":"text","text":"{\"n\":3}"}],"stop_reason":"end_turn","usage":{"input_tokens":10,"output_tokens":2}}""".toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
            ex.close()
        }
        server.start()
        try {
            val config = EndpointConfig(kind = "anthropic", baseUrl = "http://127.0.0.1:${server.address.port}/v1", model = "m", structuredOutput = StructuredOutput.JsonSchema)
            val schema = ActionSchema("pick", Pick.serializer(), JsonObject(emptyMap()))
            AnthropicBackend(config, "k").use { model ->
                assertEquals(Pick(3), runBlocking { model.decide(DecisionRequest(system = "pick", messages = emptyList()), schema) }.value)
            }
        } finally {
            server.stop(0)
        }
    }
}
