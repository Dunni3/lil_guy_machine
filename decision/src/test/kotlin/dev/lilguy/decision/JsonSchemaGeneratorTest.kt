package dev.lilguy.decision

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonSchemaGeneratorTest {
    @Serializable
    sealed interface Act {
        @Serializable @SerialName("go") data class Go(val to: Spot) : Act
        @Serializable @SerialName("grab") data class Grab(val thing: String) : Act
        @Serializable @SerialName("give") data class Give(val thing: String, val to: String? = null) : Act
        @Serializable @SerialName("rest") data class Rest(val ticks: Int) : Act
        @Serializable @SerialName("stop") data object Stop : Act
    }

    @Serializable
    sealed interface Spot {
        @Serializable @SerialName("cell") data class Cell(val x: Int, val y: Int) : Spot
        @Serializable @SerialName("thing") data class Thing(val id: String) : Spot
    }

    @Serializable @SerialName("reply")
    data class Reply(val thought: String = "", val act: Act)

    private val hints = SchemaHints(
        descriptions = mapOf("grab" to "Pick something up.", "reply.thought" to "Think first."),
        enums = mapOf("grab.thing" to listOf("key", "apple"), "give.thing" to listOf("apple"), "give.to" to listOf("bob"), "thing.id" to listOf("key")),
        ranges = mapOf("rest.ticks" to 1..200),
    )

    private fun schema(h: SchemaHints = hints) = JsonSchemaGenerator.generate(Reply.serializer().descriptor, h)

    private fun variants(s: JsonObject) = s.prop("act")["anyOf"]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.prop(name: String) = this["properties"]!!.jsonObject[name]!!.jsonObject

    private fun tagOf(variant: JsonObject) = variant.prop("type")["enum"]!!.jsonArray.single().jsonPrimitive.content

    // kotlinx.serialization orders sealed subclasses by class name.
    @Test
    fun `root is a closed object with thought before the tagged union`() {
        val s = schema()
        assertEquals("object", s["type"]!!.jsonPrimitive.content)
        assertEquals(listOf("thought", "act"), s["properties"]!!.jsonObject.keys.toList())
        assertEquals(listOf("thought", "act"), s["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(JsonPrimitive(false), s["additionalProperties"])
        assertEquals("Think first.", s.prop("thought")["description"]!!.jsonPrimitive.content)
        assertEquals(listOf("give", "go", "grab", "rest", "stop"), variants(s).map(::tagOf))
    }

    @Test
    fun `stays inside the strict subset`() {
        fun walk(e: JsonElement, inSchema: Boolean = true) {
            if (e is JsonObject) {
                assertTrue(listOf("\$ref", "oneOf", "allOf", "\$defs", "definitions").none { it in e }, "unsupported keyword in $e")
                if (e["type"] == JsonPrimitive("object")) {
                    assertEquals(JsonPrimitive(false), e["additionalProperties"], "open object: $e")
                    assertEquals(e["properties"]!!.jsonObject.keys, e["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
                }
                e.values.forEach { walk(it, inSchema) }
            } else if (e is JsonArray) e.forEach { walk(it, inSchema) }
        }
        walk(schema())
    }

    @Test
    fun `hints become enums ranges and descriptions`() {
        val byTag = variants(schema()).associateBy(::tagOf)
        val grab = byTag.getValue("grab")
        assertEquals("Pick something up.", grab["description"]!!.jsonPrimitive.content)
        assertEquals(listOf("key", "apple"), grab.prop("thing")["enum"]!!.jsonArray.map { it.jsonPrimitive.content })
        val rest = byTag.getValue("rest").prop("ticks")
        assertEquals("integer", rest["type"]!!.jsonPrimitive.content)
        assertEquals(1, rest["minimum"]!!.jsonPrimitive.content.toInt())
        assertEquals(200, rest["maximum"]!!.jsonPrimitive.content.toInt())
        val target = byTag.getValue("go").prop("to")["anyOf"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("cell", "thing"), target.map(::tagOf))
    }

    @Test
    fun `nullable properties are required but may be null`() {
        val to = variants(schema()).first { tagOf(it) == "give" }.prop("to")
        assertEquals(JsonArray(listOf(JsonPrimitive("string"), JsonPrimitive("null"))), to["type"])
        assertEquals(listOf(JsonPrimitive("bob"), JsonNull), to["enum"]!!.jsonArray.toList())
    }

    @Test
    fun `a variant with no allowed id is dropped, and so is a target branch`() {
        val s = schema(hints.copy(enums = hints.enums + mapOf("grab.thing" to emptyList(), "thing.id" to emptyList())))
        val tags = variants(s).map(::tagOf)
        assertTrue("grab" !in tags)
        val go = variants(s).first { tagOf(it) == "go" }
        assertEquals(listOf("cell"), go.prop("to")["anyOf"]!!.jsonArray.map { tagOf(it.jsonObject) })
    }

    @Test
    fun `an empty enum on a nullable property leaves only null`() {
        val s = schema(hints.copy(enums = hints.enums + ("give.to" to emptyList())))
        val to = variants(s).first { tagOf(it) == "give" }.prop("to")
        assertEquals(JsonPrimitive("null"), to["type"])
    }

    @Test
    fun `nothing satisfiable is an error`() {
        @Serializable data class Only(val id: String)
        assertFailsWith<IllegalArgumentException> {
            JsonSchemaGenerator.generate(Only.serializer().descriptor, SchemaHints(enums = mapOf("${Only.serializer().descriptor.serialName}.id" to emptyList())))
        }
    }

    // --- validation and decoding --------------------------------------------------------------

    private val action = ActionSchema.of("reply", Reply.serializer(), hints) { it.thought }

    private fun error(raw: String): String = assertFailsWith<ActionSchema.InvalidReply> { action.decode(raw) }.message!!

    @Test
    fun `valid replies decode, discriminator anywhere, nullable may be omitted`() {
        assertEquals(Reply("hm", Act.Grab("key")), action.decode("""{"thought":"hm","act":{"thing":"key","type":"grab"}}"""))
        assertEquals(Act.Give("apple"), action.decode("""{"thought":"","act":{"type":"give","thing":"apple"}}""").act)
        assertEquals(Act.Give("apple", "bob"), action.decode("""{"thought":"","act":{"type":"give","thing":"apple","to":"bob"}}""").act)
        assertEquals(Act.Stop, action.decode("""{"thought":"","act":{"type":"stop"}}""").act)
        assertEquals("hm", action.rationaleOf(action.decode("""{"thought":"hm","act":{"type":"stop"}}""")))
        assertNull(action.rationaleOf(action.decode("""{"thought":" ","act":{"type":"stop"}}""")))
    }

    @Test
    fun `invalid replies explain themselves`() {
        assertTrue(error("""{"thought": "x", "act": """).startsWith("not valid JSON"))
        assertEquals("""act: unknown type "fly"; expected one of "give", "go", "grab", "rest", "stop"""", error("""{"thought":"","act":{"type":"fly"}}"""))
        assertEquals("""act: missing "type"; expected one of "give", "go", "grab", "rest", "stop"""", error("""{"thought":"","act":{"thing":"key"}}"""))
        assertEquals("""act.thing: "gold" is not one of "key", "apple"""", error("""{"thought":"","act":{"type":"grab","thing":"gold"}}"""))
        assertEquals("""act.to.id: "door" is not one of "key"""", error("""{"thought":"","act":{"type":"go","to":{"type":"thing","id":"door"}}}"""))
        assertEquals("act.ticks: must be at most 200", error("""{"thought":"","act":{"type":"rest","ticks":500}}"""))
        assertEquals("act.ticks: expected integer, got 1.5", error("""{"thought":"","act":{"type":"rest","ticks":1.5}}"""))
        assertEquals("""act: missing "thing"""", error("""{"thought":"","act":{"type":"grab"}}"""))
        assertEquals("""missing "act"""", error("""{"thought":""}"""))
    }

    @Test
    fun `tools are the union's variants with thought folded in`() {
        val tools = assertNotNull(action.tools)
        assertEquals(listOf("give", "go", "grab", "rest", "stop"), tools.map { it.name })
        val grab = tools.first { it.name == "grab" }
        assertEquals("Pick something up.", grab.description)
        assertEquals(listOf("thought", "thing"), grab.parameters["properties"]!!.jsonObject.keys.toList())
        val rebuilt = action.fromToolCall("grab", buildJsonObject { put("thought", "mine"); put("thing", "apple") })
        assertEquals(Reply("mine", Act.Grab("apple")), action.decode(rebuilt))
        assertEquals(
            Json.parseToJsonElement("""{"thought":"mine","act":{"thing":"apple","type":"grab"}}"""),
            rebuilt,
        )
    }

    @Test
    fun `an empty schema accepts anything that decodes`() {
        val loose = ActionSchema("act", Act.serializer(), JsonObject(emptyMap()))
        assertEquals(Act.Rest(3), loose.decode("""{"type":"rest","ticks":3}"""))
        assertNull(loose.tools)
    }
}
