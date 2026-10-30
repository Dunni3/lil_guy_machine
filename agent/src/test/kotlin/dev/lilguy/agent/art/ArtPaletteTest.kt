package dev.lilguy.agent.art

import dev.lilguy.decision.ActionSchema
import dev.lilguy.decision.Capabilities
import dev.lilguy.decision.Decision
import dev.lilguy.decision.DecisionModel
import dev.lilguy.decision.DecisionRequest
import dev.lilguy.decision.Usage
import dev.lilguy.sim.CanvasSpec
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration

/** Replies with [raw] every time, as the drawing model. */
private class CannedArtist(private val raw: String) : DecisionModel {
    override val id = "canned"
    override val capabilities = Capabilities()

    override suspend fun <A> decide(request: DecisionRequest, schema: ActionSchema<A>): Decision<A> =
        Decision(schema.decode(raw), null, Usage(), Duration.ZERO, raw)
}

class ArtPaletteTest {

    /** Gemini 3.5 Flash's five sketches from the first TV run of main (ea493f2), verbatim. */
    private val replies = listOf(
        """{"title":"Tiny Moon","palette":["#0a0e17platform_color_sky_dark","1e293b_cloud_dark","fef9c3_moon_pale","ffffff_white_light"],"tags":["moon","night","sky","stars"],"ops":[{"type":"rect","x":0,"y":11,"w":16,"h":5,"color":1},{"type":"circle","x":4,"y":11,"r":3,"color":1},{"type":"circle","x":12,"y":10,"r":3,"color":1},{"type":"circle","x":8,"y":12,"r":2,"color":1},{"type":"pixel","x":2,"y":3,"color":3},{"type":"pixel","x":13,"y":2,"color":3},{"type":"pixel","x":14,"y":8,"color":3},{"type":"pixel","x":3,"y":8,"color":3},{"type":"pixel","x":11,"y":4,"color":3},{"type":"pixel","x":5,"y":1,"color":3},{"type":"circle","x":8,"y":6,"r":2,"color":2},{"type":"pixel","x":7,"y":5,"color":3},{"type":"pixel","x":8,"y":5,"color":3},{"type":"pixel","x":9,"y":7,"color":1},{"type":"pixel","x":8,"y":7,"color":1}]}""",
        """{"title":"Window to Tomorrow","palette":["#1E1B2E Lisboa frame wall background color 0 fill canvas automatically initialization hex code blue dark slate indigo tone string pattern representation"],"tags":["window","sunrise","horizon","hope"],"ops":[{"type":"rect","x":3,"y":2,"w":10,"h":6,"color":2},{"type":"circle","x":8,"y":5,"r":2,"color":1},{"type":"rect","x":3,"y":8,"w":10,"h":5,"color":3},{"type":"line","x0":7,"y0":8,"x1":9,"y1":8,"color":1},{"type":"line","x0":8,"y0":9,"x1":8,"y1":11,"color":1},{"type":"line","x0":8,"y0":2,"x1":8,"y1":12,"color":0},{"type":"line","x0":3,"y0":7,"x1":12,"y1":7,"color":0},{"type":"rect","x":2,"y":13,"w":12,"h":1,"color":0}]}""",
        """{"title":"Window to the Sun","palette":["#1e1b2e Madrid Wall shadow','#38bdf8 Daylight Sky','#fbbf24 Morning Sun','#34d399 Distant Hills"],"tags":["window","sun","hills","sky","hope"],"ops":[{"type":"rect","x":3,"y":2,"w":10,"h":10,"color":1},{"type":"circle","x":8,"y":5,"r":2,"color":2},{"type":"circle","x":4,"y":11,"r":3,"color":3},{"type":"circle","x":11,"y":11,"r":4,"color":3},{"type":"rect","x":0,"y":12,"w":16,"h":4,"color":0},{"type":"rect","x":0,"y":0,"w":16,"h":2,"color":0},{"type":"rect","x":0,"y":0,"w":3,"h":16,"color":0},{"type":"rect","x":13,"y":0,"w":3,"h":16,"color":0},{"type":"line","x0":8,"y0":2,"x1":8,"y1":11,"color":0},{"type":"line","x0":3,"y0":6,"x1":12,"y1":6,"color":0},{"type":"line","x0":2,"y0":12,"x1":13,"y1":12,"color":2}]}""",
        """{"title":"A Quiet Garden","palette":["#152417嬉#346235嬉#7cc059嬉#f0e2b6"],"tags":["garden嬉bench嬉trees嬉peaceful"],"ops":[{"type":"rect","x":0,"y":10,"w":16,"h":6,"color":1},{"type":"circle","x":2,"y":5,"r":4,"color":1},{"type":"circle","x":13,"y":5,"r":4,"color":1},{"type":"circle","x":8,"y":3,"r":4,"color":1},{"type":"circle","x":2,"y":4,"r":2,"color":2},{"type":"circle","x":13,"y":4,"r":2,"color":2},{"type":"circle","x":8,"y":2,"r":2,"color":2},{"type":"rect","x":0,"y":12,"w":16,"h":4,"color":2},{"type":"line","x0":0,"y0":11,"x1":15,"y1":11,"color":2},{"type":"rect","x":5,"y":9,"w":6,"h":1,"color":3},{"type":"rect","x":5,"y":11,"w":6,"h":1,"color":3},{"type":"line","x0":5,"y0":9,"x1":5,"y1":13,"color":3},{"type":"line","x0":10,"y0":9,"x1":10,"y1":13,"color":3},{"type":"rect","x":6,"y":12,"w":4,"h":1,"color":0},{"type":"line","x0":7,"y0":13,"x1":8,"y1":15,"color":3},{"type":"pixel","x":2,"y":3,"color":3},{"type":"pixel","x":4,"y":5,"color":3},{"type":"pixel","x":11,"y":4,"color":3},{"type":"pixel","x":13,"y":2,"color":3},{"type":"pixel","x":7,"y":1,"color":3},{"type":"pixel","x":9,"y":3,"color":3},{"type":"pixel","x":1,"y":7,"color":3},{"type":"pixel","x":14,"y":7,"color":3}]}""",
        """{"title":"Quiet Garden Sanctuary","palette":["#1b301a塑造#428247#a1d06c#f19bb0"],"tags":["quiet garden","garden","peaceful","trees","flowers"],"ops":[{"type":"rect","x":0,"y":8,"w":16,"h":8,"color":1},{"type":"circle","x":3,"y":5,"r":3,"color":1},{"type":"circle","x":12,"y":5,"r":3,"color":1},{"type":"circle","x":8,"y":2,"r":2,"color":2},{"type":"circle","x":2,"y":4,"r":2,"color":2},{"type":"circle","x":13,"y":4,"r":2,"color":2},{"type":"line","x0":8,"y0":4,"x1":8,"y1":8,"color":2},{"type":"line","x0":8,"y0":8,"x1":6,"y1":11,"color":2},{"type":"rect","x":5,"y":12,"w":4,"h":4,"color":2},{"type":"pixel","x":1,"y":8,"color":3},{"type":"pixel","x":2,"y":9,"color":3},{"type":"pixel","x":3,"y":8,"color":3},{"type":"pixel","x":2,"y":7,"color":3},{"type":"pixel","x":12,"y":8,"color":3},{"type":"pixel","x":13,"y":7,"color":3},{"type":"pixel","x":14,"y":9,"color":3},{"type":"pixel","x":11,"y":9,"color":3},{"type":"pixel","x":2,"y":3,"color":3},{"type":"pixel","x":4,"y":4,"color":3},{"type":"pixel","x":11,"y":3,"color":3},{"type":"pixel","x":13,"y":4,"color":3},{"type":"pixel","x":12,"y":2,"color":3}]}""",
    )

    @Test
    fun `palettes packed into one string keep all their colors, and a palette too short for its ops fails`() = runBlocking<Unit> {
        val drawings = replies.map { ArtWriter(CannedArtist(it)).draw("", "", "sketch", CanvasSpec(16, 4, 40), null, 0, 1, "09:00") }

        // Piece 1 sent four entries; pieces 3-5 packed all four colors into one string.
        val palettes = mapOf(
            0 to listOf(0x0a0e17, 0x1e293b, 0xfef9c3, 0xffffff),
            2 to listOf(0x1e1b2e, 0x38bdf8, 0xfbbf24, 0x34d399),
            3 to listOf(0x152417, 0x346235, 0x7cc059, 0xf0e2b6),
            4 to listOf(0x1b301a, 0x428247, 0xa1d06c, 0xf19bb0),
        )
        palettes.forEach { (i, palette) ->
            val art = assertNotNull(drawings[i].artwork, "piece ${i + 1}: ${drawings[i].error}")
            assertEquals(palette, art.palette)
            assertFalse(art.blank(), "piece ${i + 1} is one solid color")
        }
        // Piece 2 really has one color and draws with four: a failed drawing, not a solid canvas.
        assertNull(drawings[1].artwork)
        assertNotNull(drawings[1].error)
    }
}
