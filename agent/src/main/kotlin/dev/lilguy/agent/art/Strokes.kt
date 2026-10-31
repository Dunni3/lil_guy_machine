package dev.lilguy.agent.art

import kotlin.random.Random

/**
 * The strokes he can add to a piece, one per step: the drawing spec ([DrawOp]) cut into a closed
 * menu, so a model that only picks assembles a picture by choosing which part of it to invoke
 * next, then which crayon. Places are a three by three grid of regions named in words (top
 * left ... bottom right), sizes are small, medium and large relative to the canvas, and bands and
 * lines sit at quarters. There are about a hundred strokes on any canvas, well under what a Choice
 * question takes, and the descriptions are short because they are most of what a call costs.
 */
object Strokes {
    class Stroke(val label: String, val description: String, val ops: (color: Int) -> List<DrawOp>)

    private val REGIONS = listOf("top left", "top", "top right", "left", "centre", "right", "bottom left", "bottom", "bottom right")

    /**
     * The menu for a [size] by [size] canvas. [seed] places the dots, so the same step on the same
     * piece scatters the same way.
     */
    fun menu(size: Int, seed: Long): List<Stroke> = buildList {
        val s = size
        val quarter = maxOf(1, s / 4)
        val half = s / 2
        val centres = listOf(s / 6, s / 2, s - 1 - s / 6)
        val radii = listOf("small" to maxOf(1, s / 8), "medium" to maxOf(2, s / 5), "large" to maxOf(3, s / 3))
        fun region(r: Int): Pair<Int, Int> = centres[r % 3] to centres[r / 3]
        fun key(r: Int) = REGIONS[r].replace(' ', '_')
        fun one(op: DrawOp): List<DrawOp> = listOf(op)

        // Bands along an edge: a quarter or half of the canvas.
        add(Stroke("band:top:thin", "Band along the top edge, the top quarter of the canvas.") { c -> one(DrawOp.Rect(0, 0, s, quarter, c)) })
        add(Stroke("band:top:deep", "The whole top half of the canvas.") { c -> one(DrawOp.Rect(0, 0, s, half, c)) })
        add(Stroke("band:bottom:thin", "Band along the bottom edge, the bottom quarter of the canvas.") { c -> one(DrawOp.Rect(0, s - quarter, s, quarter, c)) })
        add(Stroke("band:bottom:deep", "The whole bottom half of the canvas.") { c -> one(DrawOp.Rect(0, half, s, s - half, c)) })
        add(Stroke("band:left:thin", "Band down the left edge, the left quarter of the canvas.") { c -> one(DrawOp.Rect(0, 0, quarter, s, c)) })
        add(Stroke("band:left:deep", "The whole left half of the canvas.") { c -> one(DrawOp.Rect(0, 0, half, s, c)) })
        add(Stroke("band:right:thin", "Band down the right edge, the right quarter of the canvas.") { c -> one(DrawOp.Rect(s - quarter, 0, quarter, s, c)) })
        add(Stroke("band:right:deep", "The whole right half of the canvas.") { c -> one(DrawOp.Rect(half, 0, s - half, s, c)) })

        // Filled squares and circles in each region, three sizes.
        for (r in REGIONS.indices) for ((sizeName, radius) in radii) {
            val (cx, cy) = region(r)
            add(Stroke("block:${key(r)}:$sizeName", "${sizeName.replaceFirstChar { it.uppercase() }} filled square, ${REGIONS[r]}.") { c -> one(DrawOp.Rect(cx - radius, cy - radius, 2 * radius + 1, 2 * radius + 1, c)) })
        }
        for (r in REGIONS.indices) for ((sizeName, radius) in radii) {
            val (cx, cy) = region(r)
            add(Stroke("disc:${key(r)}:$sizeName", "${sizeName.replaceFirstChar { it.uppercase() }} filled circle, ${REGIONS[r]}.") { c -> one(DrawOp.Circle(cx, cy, radius, c)) })
        }

        // One-cell lines: across, down, and the two slants.
        for ((where, y) in listOf("top" to quarter, "middle" to half, "bottom" to s - 1 - quarter)) {
            val said = when (where) { "top" -> "a quarter of the way down"; "middle" -> "through the middle"; else -> "a quarter of the way up from the bottom" }
            add(Stroke("line:across:$where", "Thin line right across, $said.") { c -> one(DrawOp.Line(0, y, s - 1, y, c)) })
        }
        for ((where, x) in listOf("left" to quarter, "middle" to half, "right" to s - 1 - quarter)) {
            val said = when (where) { "left" -> "a quarter of the way in from the left"; "middle" -> "through the middle"; else -> "a quarter of the way in from the right" }
            add(Stroke("line:down:$where", "Thin line top to bottom, $said.") { c -> one(DrawOp.Line(x, 0, x, s - 1, c)) })
        }
        add(Stroke("line:slant:down", "Thin slanting line, top left corner to bottom right corner.") { c -> one(DrawOp.Line(0, 0, s - 1, s - 1, c)) })
        add(Stroke("line:slant:up", "Thin slanting line, bottom left corner to top right corner.") { c -> one(DrawOp.Line(0, s - 1, s - 1, 0, c)) })

        // A sprinkle of dots in a region (stars, flowers, rain), placed by the seed.
        val third = maxOf(1, s / 3)
        for (r in REGIONS.indices) {
            val random = Random(seed * 31 + r)
            val x0 = (r % 3) * third
            val y0 = (r / 3) * third
            val cells = List(4) { (x0 + random.nextInt(third)) to (y0 + random.nextInt(third)) }
            add(Stroke("dots:${key(r)}", "Four scattered dots, ${REGIONS[r]}.") { c -> cells.map { (x, y) -> DrawOp.Pixel(x, y, c) } })
        }

        // Bucket fills and a frame.
        for (r in REGIONS.indices) {
            val (cx, cy) = region(r)
            add(Stroke("fill:${key(r)}", "Bucket fill, ${REGIONS[r]}: floods the whole patch of one color there.") { c -> one(DrawOp.Fill(cx, cy, c)) })
        }
        add(Stroke("frame", "One-cell border all the way round the edge.") { c ->
            listOf(DrawOp.Rect(0, 0, s, 1, c), DrawOp.Rect(0, s - 1, s, 1, c), DrawOp.Rect(0, 0, 1, s, c), DrawOp.Rect(s - 1, 0, 1, s, c))
        })
    }

    /** The label for crayon [k]. */
    fun colorLabel(k: Int) = "crayon:$k"
}
