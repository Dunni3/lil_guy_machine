package dev.lilguy.decision

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * A Kotlin type as the model sees it: the JSON schema sent with the request and the decoder for
 * the reply. Schema generation from the serializer lands with milestone 3; until then callers
 * pass the schema in.
 */
class ActionSchema<A>(
    val name: String,
    val serializer: KSerializer<A>,
    val jsonSchema: JsonObject,
    private val json: Json = DefaultJson,
) {
    fun decode(raw: String): A = json.decodeFromString(serializer, raw)

    companion object {
        val DefaultJson = Json { ignoreUnknownKeys = true }
    }
}
