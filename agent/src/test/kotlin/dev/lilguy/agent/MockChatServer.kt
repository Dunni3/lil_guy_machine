package dev.lilguy.agent

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A local HTTP server for backend tests; every POST path lands in [respond], which gets the
 * request body and its 1-based number and returns (HTTP status, response body).
 */
class MockChatServer(private val respond: (body: JsonObject, n: Int) -> Pair<Int, String>) : AutoCloseable {
    val requests = CopyOnWriteArrayList<JsonObject>()
    val authorizations = CopyOnWriteArrayList<String?>()
    val paths = CopyOnWriteArrayList<String>()

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            paths += exchange.requestURI.path
            val body = Json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
            requests += body
            authorizations += exchange.requestHeaders.getFirst("Authorization")
            val (status, text) = respond(body, requests.size)
            val bytes = text.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}/v1"
    val root: String get() = "http://127.0.0.1:${server.address.port}"

    override fun close() = server.stop(0)

    companion object {
        /** Every request gets the same reply. */
        fun always(reply: String) = MockChatServer { _, _ -> 200 to reply }

        fun content(text: String): String = completion(buildJsonObject {
            put("role", "assistant")
            put("content", text)
        })

        fun toolCall(name: String, arguments: String): String = completion(buildJsonObject {
            put("role", "assistant")
            put("content", null as String?)
            put("tool_calls", buildJsonArray {
                add(buildJsonObject {
                    put("id", "call_1")
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", name)
                        put("arguments", arguments)
                    })
                })
            })
        })

        private fun completion(message: JsonObject): String = buildJsonObject {
            put("id", "chatcmpl-mock")
            put("object", "chat.completion")
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("index", 0)
                    put("message", message)
                    put("finish_reason", "stop")
                })
            })
            put("usage", buildJsonObject {
                put("prompt_tokens", 100)
                put("completion_tokens", 20)
            })
        }.toString()
    }
}
