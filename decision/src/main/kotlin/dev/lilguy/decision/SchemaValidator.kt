package dev.lilguy.decision

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Checks a reply against the subset of JSON schema that [JsonSchemaGenerator] emits. Errors are
 * short sentences meant to be sent back to the model, such as
 * `intent.itemId: "gold_key" is not one of "key", "apple"`.
 *
 * Unknown properties are tolerated (the decoder ignores them); everything else is checked, so a
 * reply that passes here decodes.
 */
object SchemaValidator {
    /** The first problem found, or null if [value] fits [schema]. */
    fun validate(value: JsonElement, schema: JsonObject): String? = check(value, schema, path = "")

    private fun check(value: JsonElement, schema: JsonObject, path: String): String? {
        schema["anyOf"]?.let { return anyOf(value, it.jsonArray.map { b -> b.jsonObject }, path) }
        schema["type"]?.let { type ->
            val types = if (type is JsonArray) type.map { it.jsonPrimitive.content } else listOf(type.jsonPrimitive.content)
            if (types.none { matches(value, it) }) return "${at(path)}expected ${types.joinToString(" or ")}, got ${render(value)}"
        }
        schema["enum"]?.let { allowed ->
            if (allowed.jsonArray.none { it == value }) {
                return "${at(path)}${render(value)} is not one of ${allowed.jsonArray.joinToString(", ") { render(it) }}"
            }
        }
        if (value is JsonPrimitive && !value.isString) {
            val number = value.content.toDoubleOrNull()
            if (number != null) {
                schema["minimum"]?.jsonPrimitive?.contentOrNull?.toDouble()?.let { if (number < it) return "${at(path)}must be at least ${it.toLong()}" }
                schema["maximum"]?.jsonPrimitive?.contentOrNull?.toDouble()?.let { if (number > it) return "${at(path)}must be at most ${it.toLong()}" }
            }
        }
        if (value is JsonObject) {
            val properties = schema["properties"]?.jsonObject ?: JsonObject(emptyMap())
            schema["required"]?.jsonArray?.forEach { name ->
                val n = name.jsonPrimitive.content
                if (n !in value) {
                    // A nullable property may be left out: the Kotlin default (null) fills it in.
                    val prop = properties[n]?.jsonObject
                    if (prop == null || !acceptsNull(prop)) return "${at(path)}missing \"$n\""
                }
            }
            for ((name, sub) in properties) {
                val v = value[name] ?: continue
                check(v, sub.jsonObject, join(path, name))?.let { return it }
            }
        }
        if (value is JsonArray) {
            val items = schema["items"]?.jsonObject ?: return null
            value.forEachIndexed { i, v -> check(v, items, "$path[$i]")?.let { return it } }
        }
        return null
    }

    /** Discriminated unions get a targeted message; anything else reports the first branch's. */
    private fun anyOf(value: JsonElement, branches: List<JsonObject>, path: String): String? {
        if (value is JsonNull && branches.any(::acceptsNull)) return null
        val tagged = branches.mapNotNull { b -> tagOf(b)?.let { it to b } }
        if (tagged.isNotEmpty() && tagged.size == branches.count { !acceptsNull(it) }) {
            val names = tagged.joinToString(", ") { "\"${it.first}\"" }
            if (value !is JsonObject) return "${at(path)}expected an object with \"${JsonSchemaGenerator.DISCRIMINATOR}\" set to one of $names, got ${render(value)}"
            val tag = value[JsonSchemaGenerator.DISCRIMINATOR] ?: return "${at(path)}missing \"${JsonSchemaGenerator.DISCRIMINATOR}\"; expected one of $names"
            val branch = tagged.firstOrNull { JsonPrimitive(it.first) == tag }?.second
                ?: return "${at(path)}unknown ${JsonSchemaGenerator.DISCRIMINATOR} ${render(tag)}; expected one of $names"
            return check(value, branch, path)
        }
        val errors = branches.map { check(value, it, path) }
        return if (errors.any { it == null }) null else errors.first()
    }

    private fun tagOf(branch: JsonObject): String? {
        val enum = branch["properties"]?.jsonObject?.get(JsonSchemaGenerator.DISCRIMINATOR)?.jsonObject?.get("enum")?.jsonArray
        return enum?.singleOrNull()?.jsonPrimitive?.contentOrNull
    }

    private fun acceptsNull(schema: JsonObject): Boolean {
        val type = schema["type"]
        if (type is JsonPrimitive && type.content == "null") return true
        if (type is JsonArray && type.any { it.jsonPrimitive.content == "null" }) return true
        return schema["anyOf"]?.jsonArray?.any { acceptsNull(it.jsonObject) } == true
    }

    private fun matches(value: JsonElement, type: String): Boolean = when (type) {
        "null" -> value is JsonNull
        "object" -> value is JsonObject
        "array" -> value is JsonArray
        "string" -> value is JsonPrimitive && value.isString
        "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
        "integer" -> value is JsonPrimitive && !value.isString && value.longOrNull != null
        "number" -> value is JsonPrimitive && !value.isString && value !is JsonNull && value.content.toDoubleOrNull() != null
        else -> true
    }

    private fun render(value: JsonElement): String {
        val s = value.toString()
        return if (s.length > 60) s.take(57) + "..." else s
    }

    private fun at(path: String) = if (path.isEmpty()) "" else "$path: "

    private fun join(path: String, name: String) = if (path.isEmpty()) name else "$path.$name"
}
