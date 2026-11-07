package dev.lilguy.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.security.MessageDigest
import kotlin.system.exitProcess

/**
 * The protocol as JSON Schema (`protocol/schema/protocol.schema.json`), read from the serial
 * descriptors so it can't drift from the types. One `$defs` entry per class, enum and sealed
 * type, named after it, so generated code gets the same names: `make protocol-types` turns it
 * into TypeScript (`protocol/ts/protocol.ts`) with json-schema-to-typescript, as
 * `make server-schema` turns the rows into Pydantic. Every property is required, since [Wire]
 * writes them all; nullable ones may be null.
 */
@OptIn(ExperimentalSerializationApi::class)
object ProtocolSchema {
    private val pretty = Json { prettyPrint = true }

    /** The roots: everything a client reads or sends is reachable from these. */
    private val roots = listOf(ServerMessage.serializer().descriptor, Command.serializer().descriptor, CommandResult.serializer().descriptor, Viewer.serializer().descriptor)

    fun generate(): JsonObject {
        val defs = sortedMapOf<String, JsonObject>()
        roots.forEach { define(it, defs) }
        return buildJsonObject {
            put("\$schema", "https://json-schema.org/draft/2020-12/schema")
            put("title", "Protocol")
            put("description", "The lil guy machine sim host's wire format. Generated from protocol/.../*.kt; don't edit.")
            put("x-protocol-version", PROTOCOL_VERSION)
            put("type", "object")
            putJsonObject("properties") {
                put("server", ref(ServerMessage.serializer().descriptor))
                put("command", ref(Command.serializer().descriptor))
                put("result", ref(CommandResult.serializer().descriptor))
                put("viewer", ref(Viewer.serializer().descriptor))
            }
            putJsonArray("required") { listOf("server", "command", "result", "viewer").forEach { add(JsonPrimitive(it)) } }
            put("additionalProperties", false)
            put("\$defs", JsonObject(defs))
        }
    }

    /** [generate] as the checked-in file's text. */
    fun text(): String = pretty.encodeToString(JsonObject.serializer(), generate()) + "\n"

    fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    /** A type's name: its class name, or for a variant with a wire name ("welcome") that name in CamelCase. */
    private fun nameOf(d: SerialDescriptor): String {
        val n = d.serialName.removeSuffix("?").substringAfterLast('.')
        return n.split('_').joinToString("") { p -> p.replaceFirstChar { it.uppercase() } }
    }

    private fun ref(d: SerialDescriptor): JsonObject = buildJsonObject { put("\$ref", "#/\$defs/${nameOf(d)}") }

    private fun define(d: SerialDescriptor, defs: MutableMap<String, JsonObject>) {
        val name = nameOf(d)
        if (name in defs) return
        when (d.kind) {
            StructureKind.CLASS, StructureKind.OBJECT -> {
                defs[name] = JsonObject(emptyMap()) // placeholder against cycles
                defs[name] = objectSchema(d, discriminator = null, defs)
            }
            SerialKind.ENUM -> defs[name] = buildJsonObject {
                put("title", name)
                put("type", "string")
                put("enum", JsonArray((0 until d.elementsCount).map { JsonPrimitive(d.getElementName(it)) }))
            }
            PolymorphicKind.SEALED -> {
                val holder = d.getElementDescriptor(1)
                val variants = (0 until holder.elementsCount).map { holder.getElementName(it) to holder.getElementDescriptor(it) }
                defs[name] = buildJsonObject {
                    put("title", name)
                    put("oneOf", JsonArray(variants.map { (_, v) -> ref(v) }))
                }
                variants.forEach { (wire, v) ->
                    val vName = nameOf(v)
                    if (vName !in defs) {
                        defs[vName] = JsonObject(emptyMap())
                        defs[vName] = objectSchema(v, discriminator = wire, defs)
                    }
                }
            }
            else -> Unit
        }
    }

    private fun objectSchema(d: SerialDescriptor, discriminator: String?, defs: MutableMap<String, JsonObject>): JsonObject {
        val properties = LinkedHashMap<String, JsonElement>()
        if (discriminator != null) properties["type"] = buildJsonObject { put("type", "string"); put("const", discriminator) }
        for (i in 0 until d.elementsCount) properties[d.getElementName(i)] = schemaOf(d.getElementDescriptor(i), defs)
        return buildJsonObject {
            put("title", nameOf(d))
            put("type", "object")
            put("properties", JsonObject(properties))
            put("required", JsonArray(properties.keys.map(::JsonPrimitive)))
            put("additionalProperties", false)
        }
    }

    private fun schemaOf(d: SerialDescriptor, defs: MutableMap<String, JsonObject>): JsonObject {
        if (d.isInline) return schemaOf(d.getElementDescriptor(0), defs)
        val base: JsonObject = when (val kind = d.kind) {
            is PrimitiveKind -> buildJsonObject {
                put("type", when (kind) {
                    PrimitiveKind.STRING, PrimitiveKind.CHAR -> "string"
                    PrimitiveKind.BOOLEAN -> "boolean"
                    PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> "integer"
                    else -> "number"
                })
            }
            StructureKind.LIST -> buildJsonObject { put("type", "array"); put("items", schemaOf(d.getElementDescriptor(0), defs)) }
            StructureKind.MAP -> buildJsonObject { put("type", "object"); put("additionalProperties", schemaOf(d.getElementDescriptor(1), defs)) }
            StructureKind.CLASS, StructureKind.OBJECT, SerialKind.ENUM, PolymorphicKind.SEALED -> {
                define(d, defs)
                ref(d)
            }
            else -> throw IllegalArgumentException("can't describe ${d.serialName} ($kind) as JSON Schema")
        }
        return if (d.isNullable) buildJsonObject { put("anyOf", JsonArray(listOf(base, buildJsonObject { put("type", "null") }))) } else base
    }
}

/**
 * `write <schema>` writes the schema; `check <schema> <ts>` fails when the schema isn't what the
 * types say, or the TypeScript wasn't generated from it (its header names the schema's SHA-256).
 */
fun main(args: Array<String>) {
    val text = ProtocolSchema.text()
    when (args.firstOrNull()) {
        "write" -> File(args[1]).apply { parentFile?.mkdirs() }.writeText(text)
        "check" -> {
            val schema = File(args[1])
            val ts = File(args[2])
            val problems = buildList {
                if (!schema.isFile || schema.readText() != text) add("${schema.path} is stale")
                val hash = ProtocolSchema.sha256(text)
                if (!ts.isFile || "schema sha256: $hash" !in ts.readText()) add("${ts.path} wasn't generated from the current schema")
            }
            if (problems.isNotEmpty()) {
                System.err.println(problems.joinToString("; ") + ": run `make protocol-types`")
                exitProcess(1)
            }
        }
        else -> {
            System.err.println("usage: write <schema.json> | check <schema.json> <protocol.ts>")
            exitProcess(2)
        }
    }
}
