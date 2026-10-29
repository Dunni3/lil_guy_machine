package dev.lilguy.decision

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.TimeSource

/**
 * `/v1/chat/completions` on any OpenAI-compatible server (hosted APIs, vLLM, llama.cpp, Ollama).
 * Typed output comes one of three ways, picked from [capabilities]:
 *
 * - json_schema: `response_format` carries the schema and the server constrains decoding.
 * - tools: one tool per variant of the reply's tagged union, a call required.
 * - text: the schema goes in the system prompt and the reply is parsed out of free text.
 *
 * Every reply is validated against the schema whatever the strategy. A rejected one is sent back
 * with the error, up to [EndpointConfig.maxRetries] times, after which [decide] throws
 * [DecisionFailed] and the caller falls back (the agent loop waits).
 */
class OpenAiCompatBackend(
    private val config: EndpointConfig,
    apiKey: String?,
    override val id: String = "openai_compat:${config.model}",
) : DecisionModel, AutoCloseable {
    private val http = JsonHttp(config.timeout, apiKey)

    override val capabilities = Capabilities(
        jsonSchema = config.structuredOutput == StructuredOutput.JsonSchema,
        toolCalls = config.structuredOutput != StructuredOutput.Text,
    )

    private enum class Strategy { JsonSchema, Tools, Text }

    private val strategy = when {
        capabilities.jsonSchema -> Strategy.JsonSchema
        capabilities.toolCalls -> Strategy.Tools
        else -> Strategy.Text
    }

    private val url = config.baseUrl.trimEnd('/') + "/chat/completions"

    override suspend fun <A> decide(request: DecisionRequest, schema: ActionSchema<A>): Decision<A> {
        val started = TimeSource.Monotonic.markNow()
        val tools = if (strategy == Strategy.Tools) schema.tools ?: error("${schema.name} can't be expressed as tools") else null
        val messages = mutableListOf<JsonObject>()
        messages += message("system", if (strategy == Strategy.Text) request.system + textInstructions(schema) else request.system)
        request.messages.forEach { messages += message(it.role.name.lowercase(), it.content) }

        val attempts = mutableListOf<Attempt>()
        var usage = Usage()
        repeat(config.maxRetries + 1) {
            val response = http.post(url, body(request, schema, messages, tools), attempts, usage)
            usage += usageOf(response)
            val reply = response["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
                ?: throw DecisionFailed("reply has no choices[0].message: ${response.toString().take(300)}", attempts, usage)
            val content = (reply["content"] as? JsonPrimitive)?.contentOrNull
            val call = (reply["tool_calls"] as? JsonArray)?.firstOrNull()?.jsonObject
            val raw = call?.toString() ?: content.orEmpty()
            try {
                val value = if (call != null) {
                    val function = call["function"]!!.jsonObject
                    val name = function["name"]!!.jsonPrimitive.content
                    val args = function["arguments"]?.jsonPrimitive?.content.orEmpty().ifBlank { "{}" }
                    val parsed = try {
                        Json.parseToJsonElement(args).jsonObject
                    } catch (e: SerializationException) {
                        throw ActionSchema.InvalidReply("arguments to $name are not a JSON object")
                    } catch (e: IllegalArgumentException) {
                        throw ActionSchema.InvalidReply("arguments to $name are not a JSON object")
                    }
                    if (tools != null && tools.none { it.name == name }) {
                        throw ActionSchema.InvalidReply("unknown tool \"$name\"; call one of ${tools.joinToString(", ") { it.name }}")
                    }
                    schema.decode(schema.fromToolCall(name, parsed))
                } else {
                    if (content.isNullOrBlank()) throw ActionSchema.InvalidReply("the reply was empty")
                    schema.decode(extractJson(content))
                }
                attempts += Attempt(raw)
                return Decision(value, schema.rationaleOf(value), usage, started.elapsedNow(), raw, attempts.toList())
            } catch (e: ActionSchema.InvalidReply) {
                val error = e.message ?: "invalid reply"
                attempts += Attempt(raw, error)
                messages += correction(reply, call, error)
            }
        }
        throw DecisionFailed(
            "no valid reply after ${attempts.size} tries; last: ${attempts.last().error}",
            attempts.toList(),
            usage,
        )
    }

    private fun <A> body(request: DecisionRequest, schema: ActionSchema<A>, messages: List<JsonObject>, tools: List<ActionSchema.Tool>?) =
        buildJsonObject {
            put("model", config.model)
            put("messages", JsonArray(messages))
            if (config.sendTemperature) put("temperature", config.temperature ?: request.temperature)
            config.maxTokens?.let { put("max_tokens", it) }
            config.reasoningEffort?.let { put("reasoning_effort", it) }
            when (strategy) {
                Strategy.JsonSchema -> put("response_format", buildJsonObject {
                    put("type", "json_schema")
                    put("json_schema", buildJsonObject {
                        put("name", schema.name)
                        put("strict", config.strict)
                        put("schema", schema.jsonSchema)
                    })
                })
                Strategy.Tools -> {
                    put("tools", buildJsonArray {
                        tools!!.forEach { tool ->
                            add(buildJsonObject {
                                put("type", "function")
                                put("function", buildJsonObject {
                                    put("name", tool.name)
                                    tool.description?.let { put("description", it) }
                                    put("parameters", tool.parameters)
                                    if (config.strict) put("strict", true)
                                })
                            })
                        }
                    })
                    put("tool_choice", config.toolChoice)
                }
                Strategy.Text -> Unit
            }
        }

    /** Sends the bad reply back with what was wrong, in the form the strategy expects. */
    private fun correction(reply: JsonObject, call: JsonObject?, error: String): List<JsonObject> {
        if (call != null) {
            val callId = call["id"]?.jsonPrimitive?.contentOrNull ?: "call_0"
            return listOf(
                buildJsonObject {
                    put("role", "assistant")
                    put("content", JsonNull)
                    put("tool_calls", JsonArray(listOf(JsonObject(call + ("id" to JsonPrimitive(callId))))))
                },
                buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", callId)
                    put("content", "Error: $error. Nothing happened. Call one tool again with valid arguments.")
                },
            )
        }
        val content = (reply["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val retry = if (strategy == Strategy.Tools) "Call exactly one of the tools." else "Reply again with one JSON object that fits the schema, and nothing else."
        return listOf(
            message("assistant", content),
            message("user", "That reply was invalid: $error. $retry"),
        )
    }

    private fun <A> textInstructions(schema: ActionSchema<A>) =
        "\n\nReply with one JSON object and nothing else (no code fences), matching this JSON schema:\n${schema.jsonSchema}"

    private fun usageOf(response: JsonObject): Usage {
        val usage = response["usage"] as? JsonObject ?: return Usage()
        return Usage(
            promptTokens = (usage["prompt_tokens"] as? JsonPrimitive)?.intOrNull ?: 0,
            completionTokens = (usage["completion_tokens"] as? JsonPrimitive)?.intOrNull ?: 0,
        )
    }

    override fun close() = http.close()

    companion object {
        private fun message(role: String, content: String): JsonObject = buildJsonObject {
            put("role", role)
            put("content", content)
        }

        /**
         * The JSON object in a free-text reply: code fences stripped, then everything from the
         * first `{` to the last `}`. Anything else is returned as is, to fail in the parser.
         */
        fun extractJson(text: String): String {
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            return if (start >= 0 && end > start) text.substring(start, end + 1) else text.trim()
        }
    }
}
