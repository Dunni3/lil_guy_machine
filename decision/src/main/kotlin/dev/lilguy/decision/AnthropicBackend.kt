package dev.lilguy.decision

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.TimeSource

/**
 * Anthropic's Messages API (`/v1/messages`) natively, not through its OpenAI-compatible shim, which
 * ignores `response_format`. The same three strategies as [OpenAiCompatBackend]:
 *
 * - json_schema: `output_config.format` carries the schema and the server constrains decoding.
 * - tools: one tool per variant of the reply's tagged union.
 * - text: the schema goes in the system prompt and the reply is parsed out of free text.
 *
 * What differs from the OpenAI wire: the key goes in `x-api-key`; no temperature is sent (current
 * models reject one); `reasoning_effort` becomes `output_config.effort`; `max_tokens` is required
 * ([DEFAULT_MAX_TOKENS] when unset). Current Opus and Sonnet reject a forced tool call, so
 * `tool_choice` is `auto` unless the endpoint says `any`, and a reply with no call is sent back
 * like any other invalid one. The schemas sent lose the keywords structured outputs can't take
 * ([strictSchema]); replies are still validated against the whole schema here.
 *
 * The whole prompt is cached (top-level `cache_control`), so a turn's later steps and the next
 * decision read its prefix at the cached price. Thinking is left to the model's default (adaptive on current models); the assistant's content
 * goes back verbatim, thinking blocks and all, when a look or a correction continues the turn.
 */
class AnthropicBackend(
    private val config: EndpointConfig,
    apiKey: String?,
    override val id: String = "anthropic:${config.model}",
) : DecisionModel, AutoCloseable {
    /** Models that take server-side fallbacks: a refusal is re-run on another model inside the call. */
    private val fallbacks = config.model in FALLBACK_MODELS

    private val http = JsonHttp(
        config.timeout,
        apiKey = null,
        extraHeaders = buildMap {
            apiKey?.let { put("x-api-key", it) }
            put("anthropic-version", API_VERSION)
            if (fallbacks) put("anthropic-beta", FALLBACK_BETA)
        },
    )

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

    private val url = config.baseUrl.trimEnd('/') + "/messages"

    override suspend fun <A> decide(request: DecisionRequest, schema: ActionSchema<A>): Decision<A> {
        val started = TimeSource.Monotonic.markNow()
        val own = request.tools?.takeIf { capabilities.toolCalls }
        // A map in the reply can't be put as structured output (it would come back empty), so that schema goes in the prompt.
        val strategy = when {
            own != null -> Strategy.Tools
            configured == Strategy.JsonSchema && hasMaps(schema.jsonSchema) -> Strategy.Text
            else -> configured
        }
        val tools = when {
            own != null -> own.tools.map { ActionSchema.Tool(it.name, it.description, it.parameters) }
            strategy == Strategy.Tools -> schema.tools ?: error("${schema.name} can't be expressed as tools")
            else -> null
        }
        val lookNames = own?.looks.orEmpty().map { it.name }.toSet()
        // The same tools at every step, looks included, so the cached prefix holds while he looks and thinks.
        val offered = if (own != null) tools!! + own.looks.map { ActionSchema.Tool(it.name, it.description, it.parameters) } else tools
        val deferred = if (own != null) deferredOf(own, request.messages, lookNames) else emptyList()
        // System messages inside the conversation join the system prompt; the API has one, up top.
        val system = buildString {
            append(request.system)
            if (strategy == Strategy.Text) append(textInstructions(schema))
            request.messages.filter { it.role == Message.Role.System }.forEach { append("\n\n").append(it.content) }
            if (deferred.isNotEmpty()) append("\n\nMore tools, loaded when you search for them with $SEARCH_TOOL: ${deferred.joinToString(", ")}.")
        }
        val turns = Turns()
        request.messages.filter { it.role != Message.Role.System }.forEach { turns.add(it) }
        if (turns.isEmpty) turns.add("user", listOf(textBlock("Go on.")))

        val attempts = mutableListOf<Attempt>()
        val looked = mutableListOf<Looked>()
        val musings = mutableListOf<String>()
        val optional = own?.ponder != null
        var steps = 0
        var usage = Usage()
        var failures = 0
        var pauses = 0
        fun pondered(): Decision<A> {
            val said = musings.joinToString(" ")
            val value = schema.decode(own!!.ponder!!(said))
            return Decision(value, said.ifEmpty { null }, usage, started.elapsedNow(), said, attempts.toList(), looked.toList(), musings.toList(), pondered = true, steps = steps)
        }
        fun failed(message: String) = DecisionFailed(message, attempts.toList(), usage, looked = looked.toList(), musings = musings.toList(), steps = steps)
        while (failures <= config.maxRetries) {
            val response = http.post(url, body(system, schema, turns, offered, strategy, deferred.toSet()), attempts, usage)
            usage += usageOf(response)
            val content = response["content"] as? JsonArray ?: throw failed("reply has no content: ${response.toString().take(300)}")
            val stop = (response["stop_reason"] as? JsonPrimitive)?.contentOrNull
            if (stop == "pause_turn") {
                // A server tool (the tool search) paused the turn: send it back as it is and the model carries on.
                if (++pauses > MAX_PAUSES) throw failed("the turn paused $pauses times")
                turns.add("assistant", content)
                continue
            }
            if (stop == "refusal") {
                val category = ((response["stop_details"] as? JsonObject)?.get("category") as? JsonPrimitive)?.contentOrNull
                throw failed("the model declined (refusal${category?.let { ": $it" } ?: ""})")
            }
            val blocks = content.map { it.jsonObject }
            val text = blocks.filter { typeOf(it) == "text" }.joinToString("") { (it["text"] as? JsonPrimitive)?.contentOrNull.orEmpty() }
                .trim().takeIf { it.isNotEmpty() }
            val calls = blocks.filter { typeOf(it) == "tool_use" }
            // What goes back as the assistant's turn: its content as sent (thinking blocks too), unless nothing in it can stand alone.
            val replay = if (blocks.any { typeOf(it) != "thinking" && typeOf(it) != "redacted_thinking" }) content else JsonArray(listOf(textBlock("(no reply)")))
            val acting = calls.firstOrNull { nameOf(it) !in lookNames }
            val thinking = optional && calls.isEmpty() && text != null
            if (own != null && acting == null && steps < own.maxSteps && (thinking || calls.isNotEmpty())) {
                // A step without acting: looks are answered, text is his thinking; both go back and he goes on.
                steps++
                text?.let { musings += it }
                turns.add("assistant", replay)
                val results = calls.map { c ->
                    val name = nameOf(c)!!
                    val args = c["input"] as? JsonObject ?: JsonObject(emptyMap())
                    val result = try {
                        own.looks.first { it.name == name }.answer(args)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        "That didn't work: ${e.message ?: e}"
                    }
                    looked += Looked(name, args, result)
                    toolResult(idOf(c), result)
                }
                if (steps >= own.maxSteps && optional) return pondered()
                turns.add("user", results.ifEmpty { listOf(textBlock(OpenAiCompatBackend.GO_ON)) })
                continue
            }
            // His steps are spent and he's still only thinking or looking: with ponder, that's how the turn ends.
            if (optional && acting == null && (thinking || calls.isNotEmpty())) {
                text?.let { musings += it }
                return pondered()
            }
            // An action among the calls is the decision; looks beside it are dropped.
            val call = acting ?: calls.firstOrNull()
            val raw = call?.toString() ?: text.orEmpty()
            try {
                val value = if (call != null) {
                    val name = nameOf(call) ?: throw ActionSchema.InvalidReply("a tool call with no name")
                    var parsed = call["input"] as? JsonObject ?: throw ActionSchema.InvalidReply("arguments to $name are not a JSON object")
                    if (offered != null && offered.none { it.name == name }) {
                        throw ActionSchema.InvalidReply("unknown tool \"$name\"; call one of ${offered.joinToString(", ") { it.name }}")
                    }
                    if (name in lookNames) throw ActionSchema.InvalidReply("no more looking this turn; call one of ${tools!!.joinToString(", ") { it.name }}")
                    // What he wrote is his thought when the call leaves it out: this reply's text, else his latest.
                    val arg = own?.thoughtArg
                    val said = text ?: musings.lastOrNull()
                    if (arg != null && said != null && (parsed[arg] as? JsonPrimitive)?.contentOrNull.isNullOrBlank()) parsed = JsonObject(parsed + (arg to JsonPrimitive(said)))
                    text?.let { musings += it }
                    schema.decode(own?.reply(name, parsed) ?: schema.fromToolCall(name, parsed))
                } else {
                    if (text == null) throw ActionSchema.InvalidReply(if (stop == "max_tokens") "the reply was cut off at max_tokens" else "the reply was empty")
                    schema.decode(OpenAiCompatBackend.extractJson(text))
                }
                attempts += Attempt(raw)
                return Decision(value, schema.rationaleOf(value), usage, started.elapsedNow(), raw, attempts.toList(), looked.toList(), musings.toList(), steps = steps)
            } catch (e: ActionSchema.InvalidReply) {
                val error = e.message ?: "invalid reply"
                attempts += Attempt(raw, error)
                turns.add("assistant", replay)
                turns.add("user", correction(calls, call, error, strategy))
                failures++
            }
        }
        throw failed("no valid reply after ${attempts.size} tries; last: ${attempts.last().error}")
    }

    /**
     * With [EndpointConfig.toolSearch], the acting tools past [EndpointConfig.loadedTools] that go by
     * name only: all but those called most recently in [messages]. Looks always stay loaded.
     */
    private fun deferredOf(own: ToolSet, messages: List<Message>, lookNames: Set<String>): List<String> {
        if (!config.toolSearch || own.tools.size <= config.loadedTools) return emptyList()
        val acting = own.tools.map { it.name }
        val recent = messages.mapNotNull { it.call?.name }.asReversed().distinct().filter { it in acting }
        val loaded = recent.take(config.loadedTools).toMutableSet()
        // The API needs one tool loaded; with no looks and no history, that's the first.
        if (loaded.isEmpty() && lookNames.isEmpty()) loaded += acting.first()
        return acting.filter { it !in loaded }
    }

    private fun <A> body(system: String, schema: ActionSchema<A>, turns: Turns, tools: List<ActionSchema.Tool>?, strategy: Strategy, deferred: Set<String> = emptySet()) =
        buildJsonObject {
            put("model", config.model)
            put("max_tokens", config.maxTokens ?: DEFAULT_MAX_TOKENS)
            put("system", system)
            put("messages", turns.toJson())
            // Caches the prompt up to the last turn, so a turn's later steps and the next decision read its prefix.
            put("cache_control", buildJsonObject { put("type", "ephemeral") })
            val output = buildJsonObject {
                config.reasoningEffort?.let { put("effort", it) }
                if (strategy == Strategy.JsonSchema) put("format", buildJsonObject {
                    put("type", "json_schema")
                    put("schema", strictSchema(schema.jsonSchema))
                })
            }
            if (output.isNotEmpty()) put("output_config", output)
            if (fallbacks) put("fallbacks", "default")
            if (strategy == Strategy.Tools) {
                // The API takes at most MAX_STRICT_TOOLS strict tools a request; past that none are strict (replies are validated here anyway).
                // Deferred tools are never strict, so they don't count toward the limit.
                val strictAllowed = config.strict && tools!!.count { it.name !in deferred } <= MAX_STRICT_TOOLS
                put("tools", buildJsonArray {
                    tools!!.forEach { tool ->
                        add(buildJsonObject {
                            put("name", tool.name)
                            tool.description?.let { put("description", it) }
                            val strict = strictAllowed && tool.name !in deferred && !hasMaps(tool.parameters)
                            put("input_schema", if (strict) strictSchema(tool.parameters) else tool.parameters)
                            if (strict) put("strict", true)
                            if (tool.name in deferred) put("defer_loading", true)
                        })
                    }
                    if (deferred.isNotEmpty()) add(buildJsonObject {
                        put("type", SEARCH_TOOL_TYPE)
                        put("name", SEARCH_TOOL)
                    })
                })
                put("tool_choice", buildJsonObject { put("type", if (config.toolChoice == "any") "any" else "auto") })
            }
        }

    /** What was wrong, as the user's next turn: a result for every call made (the API needs one each), or a note after text. */
    private fun correction(calls: List<JsonObject>, call: JsonObject?, error: String, strategy: Strategy): List<JsonObject> {
        if (call != null) {
            return calls.map { c ->
                if (c === call) toolResult(idOf(c), "Error: $error. Nothing happened. Call one tool again with valid arguments.", isError = true)
                else toolResult(idOf(c), "Not run.")
            }
        }
        val retry = if (strategy == Strategy.Tools) "Call exactly one of the tools." else "Reply again with one JSON object that fits the schema, and nothing else."
        return listOf(textBlock("That reply was invalid: $error. $retry"))
    }

    private fun <A> textInstructions(schema: ActionSchema<A>) =
        "\n\nReply with one JSON object and nothing else (no code fences), matching this JSON schema:\n${schema.jsonSchema}"

    override fun close() = http.close()

    /** The conversation in the API's form, consecutive turns of one role merged into one. */
    private class Turns {
        private val turns = mutableListOf<Pair<String, MutableList<JsonElement>>>()

        val isEmpty get() = turns.isEmpty()

        fun add(role: String, blocks: List<JsonElement>) {
            val last = turns.lastOrNull()
            if (last != null && last.first == role) last.second += blocks else turns += role to blocks.toMutableList()
        }

        /** [m] as blocks: a call as a `tool_use` after any text, its result as a `tool_result` from the user. */
        fun add(m: Message) {
            val call = m.call
            when {
                m.role == Message.Role.Tool && call != null -> add("user", listOf(toolResult(call.id, m.content)))
                m.role == Message.Role.Tool -> add("user", listOf(textBlock(m.content)))
                m.role == Message.Role.Assistant -> add("assistant", buildList {
                    if (m.content.isNotBlank()) add(textBlock(m.content))
                    if (call != null) add(buildJsonObject {
                        put("type", "tool_use")
                        put("id", call.id)
                        put("name", call.name)
                        put("input", call.arguments)
                    })
                    if (isEmpty()) add(textBlock("(no reply)"))
                })
                else -> add("user", listOf(textBlock(m.content)))
            }
        }

        fun toJson() = JsonArray(turns.map { (role, blocks) -> buildJsonObject { put("role", role); put("content", JsonArray(blocks)) } })
    }

    companion object {
        const val API_VERSION = "2026-10-21"
        const val DEFAULT_MAX_TOKENS = 16_000
        /** Strict tools the API takes in one request. */
        const val MAX_STRICT_TOOLS = 20
        /** The API's tool search, by BM25 over the deferred tools' names and descriptions. */
        private const val SEARCH_TOOL_TYPE = "tool_search_tool_bm25_20251119"
        private const val SEARCH_TOOL = "tool_search_tool_bm25"
        /** Times a turn may pause on a server tool before it counts as failed. */
        private const val MAX_PAUSES = 3
        private const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
        private val FALLBACK_MODELS = setOf("claude-fable-5-2", "claude-fable-5-1", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5")

        /** Keywords structured outputs and strict tools reject; [SchemaValidator] still checks them on the reply. */
        private val UNSUPPORTED = setOf(
            "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf",
            "minLength", "maxLength", "maxItems", "uniqueItems", "minProperties", "maxProperties",
        )

        /** Keys whose values are maps of name to schema, not schemas themselves. */
        private val SCHEMA_MAPS = setOf("properties", "\$defs", "definitions", "patternProperties")

        /** Keys whose values are data, not schemas. */
        private val DATA = setOf("enum", "const", "default", "examples", "required")

        /**
         * [schema] with what structured outputs can't take removed: numeric and length bounds and
         * `minItems` above 1; every object gets `additionalProperties: false`. Not for schemas with
         * maps in them ([hasMaps]).
         */
        fun strictSchema(schema: JsonElement): JsonElement {
            if (schema !is JsonObject) return if (schema is JsonArray) JsonArray(schema.map { strictSchema(it) }) else schema
            val out = LinkedHashMap<String, JsonElement>()
            for ((k, v) in schema) {
                when {
                    k in UNSUPPORTED -> Unit
                    k == "minItems" -> if (((v as? JsonPrimitive)?.intOrNull ?: 0) <= 1) out[k] = v
                    k == "additionalProperties" -> Unit // false, or [hasMaps] kept this schema away
                    k in DATA -> out[k] = v
                    k in SCHEMA_MAPS && v is JsonObject -> out[k] = JsonObject(v.mapValues { strictSchema(it.value) })
                    else -> out[k] = strictSchema(v)
                }
            }
            val type = schema["type"]
            val isObject = "properties" in schema || (type as? JsonPrimitive)?.contentOrNull == "object" ||
                (type as? JsonArray)?.any { (it as? JsonPrimitive)?.contentOrNull == "object" } == true
            if (isObject) out["additionalProperties"] = JsonPrimitive(false)
            // The API won't take an enum under a type list (a nullable enum), so that becomes anyOf the enum and null.
            val types = (type as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            if (types != null && ("enum" in out || "const" in out) && "null" in types && types.size == 2) {
                val branch = LinkedHashMap(out)
                branch["type"] = JsonPrimitive(types.first { it != "null" })
                (branch["enum"] as? JsonArray)?.let { values -> branch["enum"] = JsonArray(values.filter { it !is JsonNull }) }
                val top = buildJsonObject {
                    out["description"]?.let { put("description", it) }
                    put("anyOf", JsonArray(listOf(JsonObject(branch - "description"), buildJsonObject { put("type", "null") })))
                }
                return top
            }
            return JsonObject(out)
        }

        /** Whether [schema] has an object open to any keys (a map), which structured outputs can't express. */
        fun hasMaps(schema: JsonElement): Boolean = when (schema) {
            is JsonObject -> schema.any { (k, v) ->
                when {
                    k == "additionalProperties" -> v !is JsonPrimitive || v.contentOrNull != "false"
                    k in DATA -> false
                    k in SCHEMA_MAPS && v is JsonObject -> v.values.any { hasMaps(it) }
                    else -> hasMaps(v)
                }
            }
            is JsonArray -> schema.any { hasMaps(it) }
            else -> false
        }

        /** Prompt tokens count cache writes and reads too, the reads as [Usage.cachedTokens]; output tokens include thinking. */
        internal fun usageOf(response: JsonObject): Usage {
            val usage = response["usage"] as? JsonObject ?: return Usage()
            fun n(key: String) = (usage[key] as? JsonPrimitive)?.intOrNull ?: 0
            return Usage(
                promptTokens = n("input_tokens") + n("cache_creation_input_tokens") + n("cache_read_input_tokens"),
                completionTokens = n("output_tokens"),
                cachedTokens = n("cache_read_input_tokens"),
            )
        }

        private fun typeOf(block: JsonObject) = (block["type"] as? JsonPrimitive)?.contentOrNull
        private fun nameOf(block: JsonObject) = (block["name"] as? JsonPrimitive)?.contentOrNull
        private fun idOf(block: JsonObject) = block["id"]?.jsonPrimitive?.contentOrNull ?: "toolu_0"

        private fun textBlock(text: String) = buildJsonObject {
            put("type", "text")
            put("text", text.ifBlank { "(empty)" })
        }

        private fun toolResult(id: String, content: String, isError: Boolean = false) = buildJsonObject {
            put("type", "tool_result")
            put("tool_use_id", id)
            put("content", content.ifBlank { "(empty)" })
            if (isError) put("is_error", true)
        }
    }
}
