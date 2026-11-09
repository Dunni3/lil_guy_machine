package dev.lilguy.runlog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The rows as JSON Schema, for the server (`server/schema/rows.schema.json`, written by
 * `make server-schema`, which then generates the Pydantic models in `server/app/rows.py` from it).
 * One definition per table, named after its row class: every column required (nullable ones may
 * be null), plus the `table` it belongs to and the `_rev` sync adds. Each property carries
 * `x-pg-type`, and each table `x-table` and `x-key`, which the server's tests check its migrations
 * against.
 */
object RowSchema {
    private val pretty = Json { prettyPrint = true }

    fun generate(): JsonObject = buildJsonObject {
        put("\$schema", "https://json-schema.org/draft/2020-12/schema")
        put("title", "Row")
        put("description", "One row of the lil guy machine run database, as sync sends it. Generated from runlog/.../Rows.kt; don't edit.")
        putJsonArray("oneOf") { Tables.all.forEach { add(buildJsonObject { put("\$ref", "#/\$defs/${it.rowClass}") }) } }
        putJsonObject("\$defs") { Tables.all.forEach { put(it.rowClass, table(it)) } }
    }

    /** [generate] as the checked-in file's text. */
    fun text(): String = pretty.encodeToString(JsonObject.serializer(), generate()) + "\n"

    private fun table(t: Tables.Table): JsonObject = buildJsonObject {
        put("type", "object")
        put("x-table", t.name)
        put("x-key", JsonArray(t.key.map(::JsonPrimitive)))
        putJsonObject("properties") {
            putJsonObject("table") { put("const", t.name) }
            t.columns.forEach { c -> put(c.name, column(c)) }
            putJsonObject("_rev") { put("type", "integer"); put("minimum", 1); put("x-pg-type", "bigint"); put("x-not-null", true) }
        }
        putJsonArray("required") { (listOf("table") + t.names + "_rev").forEach { add(JsonPrimitive(it)) } }
        put("additionalProperties", false)
    }

    private fun column(c: Tables.Column): JsonObject {
        val base: Map<String, JsonPrimitive> = when (c.type) {
            Tables.Type.Uuid -> mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("uuid"))
            Tables.Type.Text -> mapOf("type" to JsonPrimitive("string"))
            Tables.Type.Int, Tables.Type.Long -> mapOf("type" to JsonPrimitive("integer"))
            Tables.Type.Real -> mapOf("type" to JsonPrimitive("number"))
            Tables.Type.Bool -> mapOf("type" to JsonPrimitive("boolean"))
            Tables.Type.Time -> mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("date-time"))
            Tables.Type.Json, Tables.Type.TextArray -> emptyMap()
        }
        return buildJsonObject {
            when {
                c.type == Tables.Type.TextArray -> {
                    val array = buildJsonObject { put("type", "array"); putJsonObject("items") { put("type", "string") } }
                    if (c.notNull) array.forEach { (k, v) -> put(k, v) }
                    else put("anyOf", JsonArray(listOf(array, buildJsonObject { put("type", "null") })))
                }
                c.type == Tables.Type.Json -> Unit // any JSON value, null included
                c.notNull -> base.forEach { (k, v) -> put(k, v) }
                else -> {
                    put("type", JsonArray(listOf(base.getValue("type"), JsonPrimitive("null"))))
                    base["format"]?.let { put("format", it) }
                }
            }
            put("x-pg-type", c.type.postgres)
            put("x-not-null", c.notNull)
        }
    }
}
