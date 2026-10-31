package dev.lilguy.agent.art

import kotlin.random.Random

/**
 * The strokes he can add to a piece, one per step: the drawing spec ([DrawOp]) cut into a closed
 * menu, so a model that only picks assembles a picture by choosing which part of it to invoke
 * next, then which crayon. Places are a three by three grid of regions named in words (top
 * left ... bottom right), sizes are small, medium and large relative to the canvas, and bands sit
 * at quarters.
 *
 * A piece is drawn in [Stage]s, each with its own menu, so every pick is between strokes that make
 * sense at that point: the background first (bands and a wash), then the subject's shapes
 * ([Shapes.forNoun]) at any size, then details, all small (more of the subject, scenery shapes,
 * discs and dots), so a detail can't bury the subject under a second big copy, and the painter
 * caps how many more copies of the subject there are ([Stroke.copy]). The subject stage's
 * shapes are drawn in front ([Stroke.front]), so nothing added later hides them. There are no bucket
 * fills, since on a washed canvas one floods everything, and no lines right across the canvas,
 * which cut through whatever is drawn. A menu is about a hundred strokes at most, well under what a
 * Choice question takes, and the descriptions are short because they are most of what a call costs.
 * The order is shuffled by the seed, since a picker leans to early options.
 */
object Strokes {
    /**
     * [front]: the subject stage's shapes, drawn in front of everything that isn't, whenever it was
     * made. [copy]: a detail that is another of the subject's shapes. [shape]: the shape it stamps, if any.
     */
    class Stroke(val label: String, val description: String, val front: Boolean = false, val copy: Boolean = false, val shape: Shapes.Shape? = null, val ops: (color: Int) -> List<DrawOp>)

    enum class Stage(val says: String) {
        Ground("First the background: the sky, the ground, or the whole paper in one color."),
        Subject("Now the subject itself: where its shape goes, and how big."),
        Detail("Now the details, all small: more of the subject, what's around it. The subject stays in front of the rest."),
    }

    private val REGIONS = listOf("top left", "top", "top right", "left", "centre", "right", "bottom left", "bottom", "bottom right")

    /**
     * Which stage stroke [step] (from 0) of [strokes] is in. The background and the subject take a
     * sixth of the strokes each, at least one; without [subject] shapes there's no subject stage.
     */
    fun stage(step: Int, strokes: Int, subject: Boolean): Stage {
        val each = maxOf(1, strokes / 6)
        return when {
            step < each -> Stage.Ground
            subject && step < 2 * each -> Stage.Subject
            else -> Stage.Detail
        }
    }

    /**
     * The menu at [stage] for a [size] by [size] canvas, drawing [subject]'s shapes. [seed] places the
     * dots and orders the menu, so the same step on the same piece offers the same strokes.
     */
    fun menu(size: Int, seed: Long, stage: Stage, subject: List<Shapes.Shape>): List<Stroke> = buildList {
        val s = size
        val quarter = maxOf(1, s / 4)
        val half = s / 2
        val centres = listOf(s / 6, s / 2, s - 1 - s / 6)
        val radii = listOf("small" to maxOf(2, s / 8), "medium" to maxOf(3, s / 5), "large" to maxOf(5, s / 3))
        fun region(r: Int): Pair<Int, Int> = centres[r % 3] to centres[r / 3]
        fun key(r: Int) = REGIONS[r].replace(' ', '_')
        fun one(op: DrawOp): List<DrawOp> = listOf(op)

        // A shape's box sits on its region's centre, kept inside the canvas so it's drawn whole.
        fun shapes(shapes: List<Shapes.Shape>, sizes: List<Pair<String, Int>>, front: Boolean, copy: Boolean) {
            for (shape in shapes) for (r in REGIONS.indices) for ((sizeName, radius) in sizes) {
                val (cx, cy) = region(r)
                val side = minOf(s, 2 * radius + 1)
                val x = (cx - radius).coerceIn(0, s - side)
                val y = (cy - radius).coerceIn(0, s - side)
                add(Stroke("shape:${shape.id}:${key(r)}:$sizeName", "${sizeName.replaceFirstChar { it.uppercase() }} ${shape.name}, ${REGIONS[r]}.", front, copy, shape) { c -> one(DrawOp.Stamp(x, y, side, side, shape.rows, c)) })
            }
        }

        when (stage) {
            Stage.Ground -> {
                // Bands along an edge: a quarter or half of the canvas.
                add(Stroke("band:top:thin", "Band along the top edge, the top quarter of the canvas.") { c -> one(DrawOp.Rect(0, 0, s, quarter, c)) })
                add(Stroke("band:top:deep", "The whole top half of the canvas.") { c -> one(DrawOp.Rect(0, 0, s, half, c)) })
                add(Stroke("band:bottom:thin", "Band along the bottom edge, the bottom quarter of the canvas.") { c -> one(DrawOp.Rect(0, s - quarter, s, quarter, c)) })
                add(Stroke("band:bottom:deep", "The whole bottom half of the canvas.") { c -> one(DrawOp.Rect(0, half, s, s - half, c)) })
                add(Stroke("band:left:thin", "Band down the left edge, the left quarter of the canvas.") { c -> one(DrawOp.Rect(0, 0, quarter, s, c)) })
                add(Stroke("band:left:deep", "The whole left half of the canvas.") { c -> one(DrawOp.Rect(0, 0, half, s, c)) })
                add(Stroke("band:right:thin", "Band down the right edge, the right quarter of the canvas.") { c -> one(DrawOp.Rect(s - quarter, 0, quarter, s, c)) })
                add(Stroke("band:right:deep", "The whole right half of the canvas.") { c -> one(DrawOp.Rect(half, 0, s - half, s, c)) })
                add(Stroke("wash", "The whole canvas in one color.") { c -> one(DrawOp.Rect(0, 0, s, s, c)) })
            }
            Stage.Subject -> shapes(subject, radii, front = true, copy = false)
            Stage.Detail -> {
                shapes(subject, radii.take(1), front = false, copy = true)
                shapes(Shapes.SCENERY.filter { it !in subject }, radii.take(1), front = false, copy = false)
                for (r in REGIONS.indices) {
                    val (cx, cy) = region(r)
                    val radius = radii[0].second
                    add(Stroke("disc:${key(r)}:small", "Small filled circle, ${REGIONS[r]}.") { c -> one(DrawOp.Circle(cx, cy, radius, c)) })
                }

                // A sprinkle of dots in a region (stars, flowers, rain), placed by the seed.
                val third = maxOf(1, s / 3)
                for (r in REGIONS.indices) {
                    val random = Random(seed * 31 + r)
                    val x0 = (r % 3) * third
                    val y0 = (r / 3) * third
                    val cells = List(4) { (x0 + random.nextInt(third)) to (y0 + random.nextInt(third)) }
                    add(Stroke("dots:${key(r)}", "Four scattered dots, ${REGIONS[r]}.") { c -> cells.map { (x, y) -> DrawOp.Pixel(x, y, c) } })
                }
                add(Stroke("frame", "One-cell border all the way round the edge.") { c ->
                    listOf(DrawOp.Rect(0, 0, s, 1, c), DrawOp.Rect(0, s - 1, s, 1, c), DrawOp.Rect(0, 0, 1, s, c), DrawOp.Rect(s - 1, 0, 1, s, c))
                })
            }
        }
    }.shuffled(Random(seed))

    /** The label for crayon [k]. */
    fun colorLabel(k: Int) = "crayon:$k"
}
