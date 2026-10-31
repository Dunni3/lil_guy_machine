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

/** Writes `{"pick": ...}` the way a text model would: a label the schema allows, the first on its first call, then each next one along. */
private class Writer : DecisionModel {
    override val id = "writer"
    override val capabilities = Capabilities(jsonSchema = true)
    val prompts = mutableListOf<String>()

    override suspend fun <A> decide(request: DecisionRequest, schema: ActionSchema<A>): Decision<A> {
        val labels = schema.jsonSchema["properties"]!!.jsonObject["pick"]!!.jsonObject["enum"]!!.jsonArray
        val label = labels[prompts.size % labels.size].jsonPrimitive.content
        prompts += request.messages.last().content
        val raw = """{"pick": "$label"}"""
        return Decision(schema.decode(raw), null, Usage(), Duration.ZERO, raw)
    }
}

class PainterTest {
    private val sketch = CanvasSpec(16, 4, 2)
    private val moon = CommissionView(1, "a tiny moon", "Could you draw me a tiny moon? It's for my son.", 12, "d2 09:00")

    @Test
    fun `a sketch is assembled pick by pick, and the subject's words become its title and tags`() = runBlocking<Unit> {
        val script = ArrayDeque(listOf("request", "box:night", "band:bottom:thin", "crayon:1", "shape:moon:top_right:small", "crayon:2"))
        val picker = Picker { script.removeFirst() }
        val d = Painter(picker).draw("who he is", "how things stand", "sketch", sketch, moon, Painter.Subjects.DEFAULT, 500, 1, "10:00")

        val art = assertNotNull(d.artwork, d.error)
        assertEquals("Tiny Moon", d.title)
        assertEquals(listOf("tiny", "moon"), d.tags)
        assertEquals(6, d.calls)
        assertEquals(2, d.ops)
        assertEquals(Usage(promptTokens = 60), d.usage)
        assertEquals("request box:night band:bottom:thin crayon:1 shape:moon:top_right:small crayon:2", d.raw)
        val rows = art.letters()
        // The band is the bottom quarter in pale yellow (b); the small white moon (c) is all in the top right.
        assertEquals(List(4) { "b".repeat(16) }, rows.takeLast(4))
        val moonCells = rows.flatMapIndexed { y, r -> r.indices.filter { r[it] == 'c' }.map { it to y } }
        assertTrue(moonCells.isNotEmpty() && moonCells.all { (x, y) -> x >= 11 && y <= 4 }, rows.joinToString("\n"))
        // The menus: the subject offered the request then eight of his own; the background's strokes,
        // then the crayons it would show in (not the paper, on the paper); then the subject stage
        // offers only the moon's shapes.
        assertEquals(listOf("request") + (0 until 8).map { "own:$it" }, picker.menus[0])
        assertTrue(picker.menus[1].all { it.startsWith("box:") })
        assertTrue("wash" in picker.menus[2] && picker.menus[2].none { it.startsWith("crayon:") || it.startsWith("shape:") })
        assertEquals(setOf("crayon:1", "crayon:2", "crayon:3"), picker.menus[3].toSet())
        assertTrue(picker.menus[4].isNotEmpty() && picker.menus[4].all { it.startsWith("shape:moon:") }, picker.menus[4].toString())
    }

    @Test
    fun `a painting's menus fit a Choice question, and a text model is offered the same options`() = runBlocking<Unit> {
        val painting = CanvasSpec(32, 16, 12)
        val eager = Picker { it.first() }
        val d = Painter(eager).draw("", "", "painting", painting, null, Painter.Subjects.DEFAULT, 7, null, null)
        assertNotNull(d.artwork, d.error)
        assertEquals(1 + 2 * 12, d.calls) // no palette call: one box fits sixteen colors
        assertTrue(d.ops >= 12)
        assertNull(d.error)

        // A subject of his own isn't one he has drawn before.
        val few = Painter.Subjects(listOf("wild", "quiet"), listOf("mountain"))
        val again = Picker { it.first() }
        Painter(again).draw("", "", "sketch", sketch, null, few, 9, null, null, drawn = listOf("Wild Mountain"))
        assertEquals(listOf("own:0"), again.menus[0])
        assertTrue(again.menus.drop(1).any { menu -> menu.any { it.startsWith("shape:mountain:") } })

        val writer = Writer()
        val w = Painter(writer).draw("", "", "sketch", sketch, moon, Painter.Subjects.DEFAULT, 500, 1, "10:00")
        assertNotNull(w.artwork, w.error)
        assertEquals("Tiny Moon", w.title)
        assertTrue(writer.prompts.first().contains("- request: What the customer asked for"), writer.prompts.first())
        assertTrue(writer.prompts[2].contains("- wash: The whole canvas in one color."), writer.prompts[2])
        assertTrue(writer.prompts[3].contains("Options, each as `label: what it means`:\n- crayon:"), writer.prompts[3])
    }
}
