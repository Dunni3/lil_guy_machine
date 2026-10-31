package dev.lilguy.decision

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Per-request extras the Kotlin type can't carry. Keys are `"<serialName>"` for a class and
 * `"<serialName>.<property>"` for one of its properties, for example `"pick_up.itemId"`.
 */
data class SchemaHints(
    val descriptions: Map<String, String> = emptyMap(),
    /** Allowed string values. An empty list makes a non-null property, and so its object, impossible. */
    val enums: Map<String, List<String>> = emptyMap(),
    val ranges: Map<String, IntRange> = emptyMap(),
    /** Sealed variants to leave out, by serial name: for a variant with no field an enum could empty, such as `think`. */
    val omit: Set<String> = emptySet(),
    /** Editor schemas only: the keys a map property may have. */
    val mapKeys: Map<String, List<String>> = emptyMap(),
    /** Editor schemas only: classes, by serial name, whose objects set exactly one property. */
    val exactlyOne: Set<String> = emptySet(),
)

/**
 * JSON schema from a serializer's descriptor, in the subset hosted providers accept for
 * constrained decoding (OpenAI "strict" and the like): no `$ref` or `oneOf`, every object closed
 * with `additionalProperties: false` and every property required (nullable ones may be null).
 * Sealed types become `anyOf` over their variants, each with a one-value `type` enum, which is
 * how kotlinx.serialization's class discriminator appears on the wire.
 *
 * Objects that can't be satisfied (a required id with no allowed values, say) are dropped from
 * their `anyOf`, so a variant with nothing to act on never reaches the model.
 *
 * With `strict = false` it writes a schema for editors instead (the bedroom's content files): only
 * properties without a default are required, and maps are allowed.
 */
@OptIn(ExperimentalSerializationApi::class)
object JsonSchemaGenerator {
    const val DISCRIMINATOR = "type"

    fun generate(descriptor: SerialDescriptor, hints: SchemaHints = SchemaHints(), strict: Boolean = true, key: String? = null): JsonObject =
        Gen(hints, strict).schemaOf(descriptor, key, nullable = false)
            ?: throw IllegalArgumentException("no value of ${descriptor.serialName} satisfies the hints")

    /** The subclasses of a sealed descriptor, by serial name. */
    fun variantsOf(sealed: SerialDescriptor): List<Pair<String, SerialDescriptor>> {
        val holder = sealed.getElementDescriptor(1)
        return (0 until holder.elementsCount).map { holder.getElementName(it) to holder.getElementDescriptor(it) }
    }

    private class Gen(val hints: SchemaHints, val strict: Boolean) {
        /** Null when no value fits. [key] is the hint key of the property this schema sits under. */
        fun schemaOf(d: SerialDescriptor, key: String?, nullable: Boolean): JsonObject? {
            if (d.isInline) return schemaOf(d.getElementDescriptor(0), key, nullable || d.isNullable)
            val isNullable = nullable || d.isNullable
            val description = key?.let { hints.descriptions[it] }
            return when (val kind = d.kind) {
                is PrimitiveKind -> primitive(kind, key, isNullable, description)
                SerialKind.ENUM -> {
                    val values = (0 until d.elementsCount).map { d.getElementName(it) }
                    stringSchema(values, isNullable, description)
                }
                StructureKind.CLASS, StructureKind.OBJECT -> objectSchema(d, discriminator = null)?.let { orNull(it, isNullable, description) }
                    ?: nullOnly(isNullable)
                StructureKind.LIST -> {
                    val items = schemaOf(d.getElementDescriptor(0), key = null, nullable = false) ?: return nullOnly(isNullable)
                    orNull(buildJsonObject { put("type", "array"); put("items", items) }, isNullable, description)
                }
                StructureKind.MAP -> {
                    require(!strict) { "a strict schema can't have a map (${d.serialName})" }
                    val values = schemaOf(d.getElementDescriptor(1), key = null, nullable = false) ?: return nullOnly(isNullable)
                    val schema = buildJsonObject {
                        put("type", "object")
                        key?.let { hints.mapKeys[it] }?.let { keys -> put("propertyNames", buildJsonObject { put("enum", JsonArray(keys.map(::JsonPrimitive))) }) }
                        put("additionalProperties", values)
                    }
                    orNull(schema, isNullable, description)
                }
                PolymorphicKind.SEALED -> {
                    val variants = variantsOf(d).filter { it.first !in hints.omit }.mapNotNull { (name, sub) -> objectSchema(sub, discriminator = name) }
                    if (variants.isEmpty()) return nullOnly(isNullable)
                    val all = if (isNullable) variants + NULL else variants
                    buildJsonObject {
                        description?.let { put("description", it) }
                        put("anyOf", JsonArray(all))
                    }
                }
                else -> throw IllegalArgumentException("can't describe ${d.serialName} ($kind) as a JSON schema")
            }
        }

        private fun objectSchema(d: SerialDescriptor, discriminator: String?): JsonObject? {
            val properties = LinkedHashMap<String, JsonElement>()
            val required = mutableListOf<String>()
            if (discriminator != null) properties[DISCRIMINATOR] = stringSchema(listOf(discriminator), nullable = false, description = null)!!.also { required += DISCRIMINATOR }
            for (i in 0 until d.elementsCount) {
                val name = d.getElementName(i)
                val child = d.getElementDescriptor(i)
                properties[name] = schemaOf(child, "${d.serialName}.$name", nullable = false) ?: return null
                if (strict || !d.isElementOptional(i)) required += name
            }
            return buildJsonObject {
                put("type", "object")
                hints.descriptions[d.serialName]?.let { put("description", it) }
                put("properties", JsonObject(properties))
                if (strict || required.isNotEmpty()) put("required", JsonArray(required.map(::JsonPrimitive)))
                if (d.serialName in hints.exactlyOne) {
                    put("minProperties", 1)
                    put("maxProperties", 1)
                }
                put("additionalProperties", false)
            }
        }

        private fun primitive(kind: PrimitiveKind, key: String?, nullable: Boolean, description: String?): JsonObject? {
            if (kind == PrimitiveKind.STRING || kind == PrimitiveKind.CHAR) {
                return stringSchema(key?.let { hints.enums[it] }, nullable, description)
            }
            val type = when (kind) {
                PrimitiveKind.BOOLEAN -> "boolean"
                PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> "integer"
                else -> "number"
            }
            val range = key?.let { hints.ranges[it] }
            return buildJsonObject {
                put("type", typeOf(type, nullable))
                description?.let { put("description", it) }
                range?.let {
                    put("minimum", it.first)
                    put("maximum", it.last)
                }
            }
        }

        /** Null when [values] is empty and null isn't allowed either. */
        private fun stringSchema(values: List<String>?, nullable: Boolean, description: String?): JsonObject? {
            if (values != null && values.isEmpty()) return nullOnly(nullable)
            return buildJsonObject {
                put("type", typeOf("string", nullable))
                description?.let { put("description", it) }
                if (values != null) put("enum", JsonArray(values.map(::JsonPrimitive) + if (nullable) listOf(JsonNull) else emptyList()))
            }
        }

        private fun typeOf(type: String, nullable: Boolean): JsonElement =
            if (nullable) JsonArray(listOf(JsonPrimitive(type), JsonPrimitive("null"))) else JsonPrimitive(type)

        private fun orNull(schema: JsonObject, nullable: Boolean, description: String?): JsonObject {
            val described = if (description != null && "description" !in schema) JsonObject(schema + ("description" to JsonPrimitive(description))) else schema
            return if (nullable) buildJsonObject { put("anyOf", JsonArray(listOf(described, NULL))) } else described
        }

        private fun nullOnly(nullable: Boolean): JsonObject? = if (nullable) NULL else null
    }

    private val NULL = buildJsonObject { put("type", "null") }
}
