package dev.lilguy.decision

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A Kotlin type as the model sees it: the JSON schema sent with the request and the parser for
 * the reply. Build one per request with [of] when the allowed values depend on what the
 * character can see.
 *
 * [rationale] pulls the model's reasoning (a "thought" field) out of a parsed reply.
 */
class ActionSchema<A>(
    val name: String,
    val serializer: KSerializer<A>,
    val jsonSchema: JsonObject,
    private val rationale: (A) -> String? = { null },
    private val json: Json = DefaultJson,
) {
    /** One tool per variant of the reply's tagged union, for backends that only do tool calling. */
    data class Tool(val name: String, val description: String?, val parameters: JsonObject)

    /**
     * Parses and checks a raw reply: JSON syntax, then the schema (so ids outside the per-request
     * enums are caught here, not by the sim), then decoding. Throws [InvalidReply] with a message
     * that can be sent back to the model.
     */
    fun decode(raw: String): A {
        val element = try {
            json.parseToJsonElement(raw)
        } catch (e: SerializationException) {
            throw InvalidReply("not valid JSON: ${e.message?.lineSequence()?.first()}")
        }
        return decode(element)
    }

    fun decode(element: JsonElement): A {
        SchemaValidator.validate(element, jsonSchema)?.let { throw InvalidReply(it) }
        return try {
            json.decodeFromJsonElement(serializer, element)
        } catch (e: SerializationException) {
            throw InvalidReply(e.message?.lineSequence()?.first() ?: "doesn't match the schema")
        } catch (e: IllegalArgumentException) {
            throw InvalidReply(e.message?.lineSequence()?.first() ?: "doesn't match the schema")
        }
    }

    fun rationaleOf(value: A): String? = rationale(value)?.takeIf { it.isNotBlank() }

    /**
     * The schema as tools: the variants of the one tagged-union property of the root object, each
     * with the root's other properties (such as "thought") folded in first. Null when the schema
     * has no such property.
     */
    val tools: List<Tool>? by lazy { toolsOf(jsonSchema) }

    /** Rebuilds the reply a tool call stands for, in the shape [decode] expects. */
    fun fromToolCall(tool: String, arguments: JsonObject): JsonObject {
        val field = unionField(jsonSchema) ?: throw IllegalStateException("$name has no tagged union to call as tools")
        val rootKeys = jsonSchema["properties"]!!.jsonObject.keys - field
        val inner = arguments.filterKeys { it !in rootKeys } + (JsonSchemaGenerator.DISCRIMINATOR to JsonPrimitive(tool))
        return JsonObject(arguments.filterKeys { it in rootKeys } + (field to JsonObject(inner)))
    }

    class InvalidReply(message: String) : Exception(message)

    companion object {
        val DefaultJson = Json { ignoreUnknownKeys = true }

        fun <A> of(
            name: String,
            serializer: KSerializer<A>,
            hints: SchemaHints = SchemaHints(),
            rationale: (A) -> String? = { null },
        ) = ActionSchema(name, serializer, JsonSchemaGenerator.generate(serializer.descriptor, hints), rationale)

        /** The root property whose schema is an anyOf of tagged objects. */
        private fun unionField(root: JsonObject): String? {
            val props = root["properties"]?.jsonObject ?: return null
            return props.entries.singleOrNull { (_, s) ->
                val branches = s.jsonObject["anyOf"]?.jsonArray ?: return@singleOrNull false
                branches.all { b -> b.jsonObject["properties"]?.jsonObject?.containsKey(JsonSchemaGenerator.DISCRIMINATOR) == true }
            }?.key
        }

        private fun toolsOf(root: JsonObject): List<Tool>? {
            val field = unionField(root) ?: return null
            val props = root["properties"]!!.jsonObject
            val shared = props.filterKeys { it != field }
            return props.getValue(field).jsonObject["anyOf"]!!.jsonArray.map { b ->
                val branch = b.jsonObject
                val own = branch["properties"]!!.jsonObject.filterKeys { it != JsonSchemaGenerator.DISCRIMINATOR }
                val all = shared + own
                val tag = branch["properties"]!!.jsonObject.getValue(JsonSchemaGenerator.DISCRIMINATOR).jsonObject["enum"]!!.jsonArray[0].jsonPrimitive.content
                Tool(
                    name = tag,
                    description = branch["description"]?.jsonPrimitive?.content,
                    parameters = JsonObject(
                        mapOf(
                            "type" to JsonPrimitive("object"),
                            "properties" to JsonObject(all),
                            "required" to JsonArray(all.keys.map { JsonPrimitive(it) }),
                            "additionalProperties" to JsonPrimitive(false),
                        ),
                    ),
                )
            }
        }
    }
}
