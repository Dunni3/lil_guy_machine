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
import kotlinx.coroutines.CancellationException

/**
 * `/v1/chat/completions` on any OpenAI-compatible server (hosted APIs, vLLM, llama.cpp, Ollama).
 * Typed output comes one of three ways, picked from [capabilities]:
 *
 * - json_schema: `response_format` carries the schema and the server constrains decoding.
 * - tools: one tool per variant of the reply's tagged union, a call required.
 * - text: the schema goes in the system prompt and the reply is parsed out of free text.
 *
 * A request that carries [DecisionRequest.tools] gets those tools instead, whatever the strategy,
 * and the reply a call stands for goes through the schema the same way.
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

    private val configured = when {
        capabilities.jsonSchema -> Strategy.JsonSchema
        capabilities.toolCalls -> Strategy.Tools
        else -> Strategy.Text
    }

    private val url = config.baseUrl.trimEnd('/') + "/chat/completions"

    override suspend fun <A> decide(request: DecisionRequest, schema: ActionSchema<A>): Decision<A> {
        val started = TimeSource.Monotonic.markNow()
        val own = request.tools?.takeIf { capabilities.toolCalls }
        val strategy = if (own != null) Strategy.Tools else configured
        val tools = when {
            own != null -> own.tools.map { ActionSchema.Tool(it.name, it.description, it.parameters) }
            strategy == Strategy.Tools -> schema.tools ?: error("${schema.name} can't be expressed as tools")
            else -> null
        }
        val messages = mutableListOf<JsonObject>()
        messages += message("system", if (strategy == Strategy.Text) request.system + textInstructions(schema) else request.system)
        request.messages.forEach { messages += message(it) }

        val attempts = mutableListOf<Attempt>()
        val looked = mutableListOf<Looked>()
        val lookNames = own?.looks.orEmpty().map { it.name }.toSet()
        var usage = Usage()
        var failures = 0
        while (failures <= config.maxRetries) {
            // While looks are left, they're offered beside the tools that act; then only those.
            val looking = own != null && own.looks.isNotEmpty() && looked.size < own.maxLooks
            val offered = if (looking) tools!! + own!!.looks.map { ActionSchema.Tool(it.name, it.description, it.parameters) } else tools
            val response = http.post(url, body(request, schema, messages, offered, strategy), attempts, usage)
            usage += usageOf(response)
            val reply = response["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
                ?: throw DecisionFailed("reply has no choices[0].message: ${response.toString().take(300)}", attempts, usage, looked = looked.toList())
            val content = (reply["content"] as? JsonPrimitive)?.contentOrNull
            val calls = (reply["tool_calls"] as? JsonArray)?.map { it.jsonObject }.orEmpty()
            if (own != null && calls.isNotEmpty() && calls.all { nameOf(it) in lookNames }) {
                // Only looks: answer each, put the answers back, and ask again. They don't count as tries, and are kept in [Decision.looks], not attempts.
                val ided = calls.mapIndexed { i, c -> if (c["id"] is JsonPrimitive) c else JsonObject(c + ("id" to JsonPrimitive("look_${looked.size}_$i"))) }
                messages += buildJsonObject {
                    put("role", "assistant")
                    if (content.isNullOrEmpty()) put("content", JsonNull) else put("content", content)
                    put("tool_calls", JsonArray(ided))
                }
                for (c in ided) {
                    val name = nameOf(c)!!
                    val args = argumentsOf(c) ?: JsonObject(emptyMap())
                    val result = if (looked.size >= own.maxLooks) "Nothing: no more looking this turn. Call one of the tools that act." else try {
                        own.looks.first { it.name == name }.answer(args)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        "That didn't work: ${e.message ?: e}"
                    }
                    looked += Looked(name, args, result)
                    messages += buildJsonObject {
                        put("role", "tool")
                        put("tool_call_id", c["id"]!!.jsonPrimitive.content)
                        put("content", result)
                    }
                }
                continue
            }
            // An action among the calls is the decision; looks beside it are dropped.
            val call = calls.firstOrNull { nameOf(it) !in lookNames } ?: calls.firstOrNull()
            val raw = call?.toString() ?: content.orEmpty()
            try {
                val value = if (call != null) {
                    val name = nameOf(call) ?: throw ActionSchema.InvalidReply("a tool call with no name")
                    val parsed = argumentsOf(call) ?: throw ActionSchema.InvalidReply("arguments to $name are not a JSON object")
                    if (offered != null && offered.none { it.name == name }) {
                        throw ActionSchema.InvalidReply("unknown tool \"$name\"; call one of ${offered.joinToString(", ") { it.name }}")
                    }
                    if (name in lookNames) throw ActionSchema.InvalidReply("no more looking this turn; call one of ${tools!!.joinToString(", ") { it.name }}")
                    schema.decode(own?.reply(name, parsed) ?: schema.fromToolCall(name, parsed))
                } else {
                    if (content.isNullOrBlank()) throw ActionSchema.InvalidReply("the reply was empty")
                    schema.decode(extractJson(content))
                }
                attempts += Attempt(raw)
                return Decision(value, schema.rationaleOf(value), usage, started.elapsedNow(), raw, attempts.toList(), looked.toList())
            } catch (e: ActionSchema.InvalidReply) {
                val error = e.message ?: "invalid reply"
                attempts += Attempt(raw, error)
                messages += correction(reply, call, error, strategy)
                failures++
            }
        }
        throw DecisionFailed(
            "no valid reply after ${attempts.size} tries; last: ${attempts.last().error}",
            attempts.toList(),
            usage,
            looked = looked.toList(),
        )
    }

    private fun nameOf(call: JsonObject): String? = (call["function"] as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull

    /** The call's arguments as an object, or null when they aren't one. */
    private fun argumentsOf(call: JsonObject): JsonObject? {
        val args = ((call["function"] as? JsonObject)?.get("arguments") as? JsonPrimitive)?.contentOrNull.orEmpty().ifBlank { "{}" }
        return try {
            Json.parseToJsonElement(args) as? JsonObject
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun <A> body(request: DecisionRequest, schema: ActionSchema<A>, messages: List<JsonObject>, tools: List<ActionSchema.Tool>?, strategy: Strategy) =
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
    private fun correction(reply: JsonObject, call: JsonObject?, error: String, strategy: Strategy): List<JsonObject> {
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

    override fun close() = http.close()

    companion object {
        /** The call's usage; Gemini leaves its thinking out of completion_tokens but bills it as output, and total_tokens has it. */
        internal fun usageOf(response: JsonObject): Usage {
            val usage = response["usage"] as? JsonObject ?: return Usage()
            val prompt = (usage["prompt_tokens"] as? JsonPrimitive)?.intOrNull ?: 0
            val completion = (usage["completion_tokens"] as? JsonPrimitive)?.intOrNull ?: 0
            val total = (usage["total_tokens"] as? JsonPrimitive)?.intOrNull ?: 0
            return Usage(promptTokens = prompt, completionTokens = maxOf(completion, total - prompt))
        }

        private fun message(role: String, content: String): JsonObject = buildJsonObject {
            put("role", role)
            put("content", content)
        }

        /** [m] in the wire form: a tool call as the assistant's `tool_calls`, its result as a `tool` message. */
        private fun message(m: Message): JsonObject {
            val call = m.call ?: return message(m.role.name.lowercase(), m.content)
            return when (m.role) {
                Message.Role.Assistant -> buildJsonObject {
                    put("role", "assistant")
                    if (m.content.isEmpty()) put("content", JsonNull) else put("content", m.content)
                    put("tool_calls", buildJsonArray {
                        add(buildJsonObject {
                            put("id", call.id)
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", call.name)
                                put("arguments", call.arguments.toString())
                            })
                        })
                    })
                }
                Message.Role.Tool -> buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", call.id)
                    put("content", m.content)
                }
                else -> message(m.role.name.lowercase(), m.content)
            }
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
