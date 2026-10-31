package dev.lilguy.agent.art

import dev.lilguy.decision.ActionSchema
import dev.lilguy.decision.Capabilities
import dev.lilguy.decision.Decision
import dev.lilguy.decision.DecisionModel
import dev.lilguy.decision.DecisionRequest
import dev.lilguy.decision.TypesafeBackend
import dev.lilguy.decision.Usage
import dev.lilguy.sim.CanvasSpec
import dev.lilguy.sim.CommissionView
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

/** Picks from the choice set the way Jev does: [choose] gets each call's labels and returns one. */
private class Picker(private val choose: (List<String>) -> String) : DecisionModel {
    override val id = "picker"
    override val capabilities = Capabilities(closedChoice = true)
    val menus = mutableListOf<List<String>>()

    override suspend fun <A> decide(request: DecisionRequest, schema: ActionSchema<A>): Decision<A> {
        val choices = assertNotNull(request.choices, "a picking model needs choices")
        val labels = choices.options.map { it.label }
        assertEquals(labels.toSet().size, labels.size, "labels repeat")
        assertTrue(labels.size <= TypesafeBackend.MAX_OPTIONS, "${labels.size} options is more than a Choice question takes")
        menus += labels
        val label = choose(labels)
        val option = choices.options.first { it.label == label }
        return Decision(schema.decode(option.reply), null, Usage(promptTokens = 10), Duration.ZERO, option.label)
    }
}

/** Writes `{"pick": ...}` the way a text model would: the first label the schema allows. */
private class Writer : DecisionModel {
    override val id = "writer"
    override val capabilities = Capabilities(jsonSchema = true)
    val prompts = mutableListOf<String>()

    override suspend fun <A> decide(request: DecisionRequest, schema: ActionSchema<A>): Decision<A> {
        prompts += request.messages.last().content
        val first = schema.jsonSchema["properties"]!!.jsonObject["pick"]!!.jsonObject["enum"]!!.jsonArray.first().jsonPrimitive.content
        val raw = """{"pick": "$first"}"""
        return Decision(schema.decode(raw), null, Usage(), Duration.ZERO, raw)
    }
}

class PainterTest {
    private val sketch = CanvasSpec(16, 4, 2)
    private val moon = CommissionView(1, "a tiny moon", "Could you draw me a tiny moon? It's for my son.", 12, "d2 09:00")

    @Test
    fun `a sketch is assembled pick by pick, and the subject's words become its title and tags`() = runBlocking<Unit> {
        val script = ArrayDeque(listOf("request", "box:night", "band:bottom:thin", "crayon:1", "disc:top_right:small", "crayon:2"))
        val picker = Picker { script.removeFirst() }
        val d = Painter(picker).draw("who he is", "how things stand", "sketch", sketch, moon, Painter.Subjects.DEFAULT, 500, 1, "10:00")

        val art = assertNotNull(d.artwork, d.error)
        assertEquals("Tiny Moon", d.title)
        assertEquals(listOf("tiny", "moon"), d.tags)
        assertEquals(6, d.calls)
        assertEquals(2, d.ops)
        assertEquals(Usage(promptTokens = 60), d.usage)
        assertEquals("request box:night band:bottom:thin crayon:1 disc:top_right:small crayon:2", d.raw)
        val rows = art.letters()
        // The band is the bottom quarter in pale yellow (b); the small white disc (c) sits in the top right.
        assertEquals(List(4) { "b".repeat(16) }, rows.takeLast(4))
        assertEquals('c', rows[2][13])
        assertEquals('a', rows[0][0])
        // The menus: the subject offered the request then eight of his own; strokes then crayons (paper last)
        // for each stroke, and the stroke just made isn't offered again.
        assertEquals(listOf("request") + (0 until 8).map { "own:$it" }, picker.menus[0])
        assertTrue(picker.menus[1].all { it.startsWith("box:") })
        assertTrue(picker.menus[2].none { it.startsWith("crayon:") })
        assertEquals(listOf("crayon:1", "crayon:2", "crayon:3", "crayon:0"), picker.menus[3])
        assertTrue("band:bottom:thin" !in picker.menus[4] && "band:bottom:thin" in picker.menus[2])
    }

    @Test
    fun `a painting's menus fit a Choice question, and a text model is offered the same options`() = runBlocking<Unit> {
        val painting = CanvasSpec(32, 16, 12)
        val eager = Picker { it.first() }
        val d = Painter(eager).draw("", "", "painting", painting, null, Painter.Subjects.DEFAULT, 7, null, null)
        assertNotNull(d.artwork, d.error)
        assertEquals(1 + 2 * 12, d.calls) // no palette call: one box fits sixteen colors
        assertEquals(12, d.ops)
        assertNull(d.error)

        val writer = Writer()
        val w = Painter(writer).draw("", "", "sketch", sketch, moon, Painter.Subjects.DEFAULT, 500, 1, "10:00")
        assertNotNull(w.artwork, w.error)
        assertEquals("Tiny Moon", w.title)
        assertTrue(writer.prompts.first().contains("- request: What the customer asked for"), writer.prompts.first())
        assertTrue(writer.prompts[2].contains("- disc:centre:small: Small filled circle, centre."), writer.prompts[2])
        assertTrue(writer.prompts[3].contains("- crayon:1: pale yellow"), writer.prompts[3])
    }
}
