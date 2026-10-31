package dev.lilguy.app

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.utils.Disposable
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The room's painted textures, in the guy's hand-drawn look: crayon strokes and pen lines laid down
 * once with [CrayonCanvas] when first asked for, then kept. The floor and walls are drawn with the
 * cheap paper shader, so all their crayon look lives here; part lists name the rest ([texture]).
 *
 * Every picture is painted at one design unit per pixel. The floor and wall tile, so strokes near an
 * edge are drawn again on the far side ([wrapStroke]).
 */
class RoomPaint : Disposable {
    private val textures = HashMap<String, Texture?>()

    /** How long painting has taken so far, for the startup log. */
    var paintMillis = 0L
        private set

    /** The texture called [name], painted the first time; null for a name nothing here paints. */
    fun texture(name: String): Texture? = textures.getOrPut(name) {
        val start = System.nanoTime()
        when (name) {
            FLOOR -> floor()
            WALL -> wall()
            SECTION -> section()
            WHITE -> CrayonCanvas(4, 4).run { fill(Color.WHITE); texture() }
            "rug" -> rug()
            "poster" -> poster()
            "clock" -> clock()
            else -> null
        }.also { paintMillis += (System.nanoTime() - start) / 1_000_000 }
    }

    override fun dispose() {
        textures.values.forEach { it?.dispose() }
        textures.clear()
    }

    /**
     * Floorboards running east-west, [FLOOR_METRES] square: planks of one to two metres, each a
     * slightly different wood, grain in short crayon strokes, broken pen lines between the rows,
     * pen ticks at the joints and the odd knot.
     */
    private fun floor(): Texture {
        val n = FLOOR_PX.toFloat()
        val x = CrayonCanvas(FLOOR_PX, FLOOR_PX)
        val rand = Rng(77)
        val row = n / BOARDS
        x.fill(Color.valueOf(FLOOR_WOOD))
        for (r in 0 until BOARDS) {
            val y0 = r * row
            var at = rand() * n
            var covered = 0f
            while (covered < n) {
                var len = 140 + rand() * 120
                if (n - covered - len < 90) len = n - covered
                val tone = shade(FLOOR_WOOD, (rand() - 0.5f) * 0.07f, (rand() - 0.5f) * 0.06f)
                wrapRect(x, at, y0, len, row, tone, n)
                // A pen tick at the plank's end, and now and then a knot.
                val jx = at + (rand() - 0.5f) * 2
                wrapPen(x, linePts(jx, y0 + 4, jx + (rand() - 0.5f) * 3, y0 + row - 4, rand, 1f, 4), 2.2f, FLOOR_LINE, rand, n, 0.7f)
                if (rand() < 0.3f) {
                    val kx = at + 30 + rand() * (len - 60)
                    val ky = y0 + row * (0.35f + rand() * 0.3f)
                    wrapStroke(x, blobPath(kx, ky, 7 + rand() * 5, 2.5f + rand() * 1.5f, rand, 0.12f, 16), 1.6f, shade(FLOOR_WOOD, -0.16f), 0.55f, n)
                }
                at += len
                covered += len
            }
            // Grain: short strokes along the boards, a shade either side.
            val light = shade(FLOOR_WOOD, 0.06f)
            val dark = shade(FLOOR_WOOD, -0.08f, 0.04f)
            repeat(GRAIN_PER_ROW) {
                val gx = rand() * n
                val gy = y0 + 4 + rand() * (row - 8)
                val len = 16 + rand() * 46
                val tilt = (rand() - 0.5f) * 3
                wrapStroke(x, floatArrayOf(gx, gy, gx + len, gy + tilt), 1.4f + rand() * 1.8f, if (rand() < 0.5f) light else dark, 0.18f + rand() * 0.22f, n)
            }
            // The seam above this row, in broken lengths.
            var sx = rand() * 40
            while (sx < n + 40) {
                val len = 70 + rand() * 150
                val sy = y0 + (rand() - 0.5f) * 1.5f
                wrapPen(x, linePts(sx, sy, sx + len, sy + (rand() - 0.5f) * 2, rand, 1.2f, 8), 2.6f, FLOOR_LINE, rand, n, 0.8f)
                sx += len + 4 + rand() * 12
            }
        }
        return x.texture(repeat = true, mipmaps = true)
    }

    /**
     * One stretch of wall, [WALL_METRES] wide and the wall's height tall, bottom to top: a wood
     * baseboard, mint wainscot boards, a cream rail, and cream wallpaper with soft crayon stripes and
     * little blush sprigs; ink along each edge.
     */
    private fun wall(): Texture {
        val w = WALL_PX.toFloat()
        val h = WALL_PX.toFloat()
        val x = CrayonCanvas(WALL_PX, WALL_PX)
        val rand = Rng(31)
        fun yAt(m: Float) = (1 - m / RoomModels.WALL_HEIGHT) * h
        val railTop = yAt(RoomModels.RAIL_TOP)
        val railBottom = yAt(RoomModels.WAINSCOT)
        val baseTop = yAt(RoomModels.BASEBOARD)

        // Wallpaper.
        x.fill(Color.valueOf(WALLPAPER))
        val paperLight = shade(WALLPAPER, 0.03f)
        val paperDark = shade(WALLPAPER, -0.04f, 0.05f)
        repeat(900) {
            val px = rand() * w
            val py = rand() * railTop
            val a = -1.2f + (rand() - 0.5f) * 0.4f
            val len = 8 + rand() * 16
            wrapStroke(x, floatArrayOf(px, py, px + cos(a) * len, py + sin(a) * len), 1.5f + rand() * 1.5f, if (rand() < 0.5f) paperLight else paperDark, 0.25f + rand() * 0.25f, w)
        }
        val stripe = Color.valueOf(WALLPAPER_STRIPE)
        // Each stripe a pair of crayon lines, drawn in lengths that overlap a little.
        for (s in 0 until STRIPES) for (side in floatArrayOf(-5f, 5f)) {
            val cx = (s + 0.5f) * w / STRIPES + side
            var py = -rand() * 20
            while (py < railTop) {
                val len = 30 + rand() * 40
                x.stroke(linePts(cx + (rand() - 0.5f) * 1.5f, py, cx + (rand() - 0.5f) * 1.5f, min(railTop, py + len), rand, 0.8f, 5), 3.2f, stripe, 0.75f)
                py += len - 3
            }
        }
        val sprig = Color.valueOf(SPRIG)
        for (s in 0 until STRIPES) for (k in 0..2) {
            val cx = s * w / STRIPES + (rand() - 0.5f) * 4
            val cy = 18 + k * (railTop - 30) / 3 + (s % 2) * (railTop - 30) / 6
            for (d in 0..2) {
                val a = d * 2.1f + rand()
                wrapStroke(x, floatArrayOf(cx + cos(a) * 3.5f, cy + sin(a) * 3.5f), 3.8f, sprig, 0.8f, w)
            }
        }

        // The rail, wainscot and baseboard.
        x.fillRect(0f, railTop, w, railBottom - railTop, Color.valueOf(RAIL))
        x.fillRect(0f, railBottom, w, baseTop - railBottom, Color.valueOf(WAINSCOT))
        val mintLight = shade(WAINSCOT, 0.05f)
        val mintDark = shade(WAINSCOT, -0.06f, 0.04f)
        repeat(700) {
            val px = rand() * w
            val py = railBottom + rand() * (baseTop - railBottom)
            val len = 10 + rand() * 22
            x.stroke(floatArrayOf(px, py, px + (rand() - 0.5f) * 2, min(baseTop, py + len)), 1.5f + rand() * 1.6f, if (rand() < 0.5f) mintLight else mintDark, 0.25f + rand() * 0.25f)
        }
        val boardLine = shade(WAINSCOT, -0.22f, 0.02f)
        for (b in 0 until WAINSCOT_BOARDS) {
            val bx = b * w / WAINSCOT_BOARDS + (rand() - 0.5f) * 2
            wrapPen(x, linePts(bx, railBottom + 3, bx + (rand() - 0.5f) * 2, baseTop - 2, rand, 1f, 6), 2.2f, boardLine, rand, w, 0.8f)
        }
        x.fillRect(0f, baseTop, w, h - baseTop, Color.valueOf(BASEBOARD))
        val baseDark = shade(BASEBOARD, -0.07f)
        repeat(160) {
            val px = rand() * w
            val py = baseTop + 2 + rand() * (h - baseTop - 4)
            wrapStroke(x, floatArrayOf(px, py, px + 14 + rand() * 24, py + (rand() - 0.5f) * 1.5f), 1.5f, baseDark, 0.3f, w)
        }

        // Ink along the edges: the wall's top, both sides of the rail, the top of the baseboard.
        val ink = Color.valueOf(WALL_INK)
        for (y in floatArrayOf(1.5f, railTop, railBottom, baseTop)) {
            var sx = rand() * 30
            while (sx < w + 30) {
                val len = 60 + rand() * 110
                wrapPen(x, linePts(sx, y + (rand() - 0.5f), sx + len, y + (rand() - 0.5f), rand, 1f, 8), 2.6f, ink, rand, w, 0.85f)
                sx += len + 3 + rand() * 8
            }
        }
        return x.texture(repeat = true, mipmaps = true)
    }

    /** The cut wall's top and ends: a plum-brown, scribbled. */
    private fun section(): Texture {
        val n = SECTION_PX.toFloat()
        val x = CrayonCanvas(SECTION_PX, SECTION_PX)
        val rand = Rng(5)
        x.fill(Color.valueOf(SECTION_COLOR))
        val light = shade(SECTION_COLOR, 0.06f)
        val dark = shade(SECTION_COLOR, -0.05f)
        repeat(260) {
            val px = rand() * n
            val py = rand() * n
            val a = -0.7f + (rand() - 0.5f) * 0.5f
            val len = 6 + rand() * 12
            wrapStroke(x, floatArrayOf(px, py, px + cos(a) * len, py + sin(a) * len), 1.5f + rand(), if (rand() < 0.5f) light else dark, 0.4f, n)
        }
        return x.texture(repeat = true, mipmaps = true)
    }

    /** An oval rag rug (painted round; the rug's shape stretches it): rose, a cream ring, rose again, stitched rings between. */
    private fun rug(): Texture {
        val x = CrayonCanvas(256, 256)
        val rand = Rng(61)
        val c = 128f
        val rings = listOf(1.6f to RUG_ROSE, 0.8f to RUG_CREAM, 0.54f to RUG_ROSE, 0.22f to RUG_CREAM)
        for ((r, col) in rings) {
            x.clip(blobPath(c, c, 124 * r, 124 * r, rand, 0.02f, 48))
            x.fillRect(0f, 0f, 256f, 256f, Color.valueOf(col))
            val light = shade(col, 0.05f)
            val dark = shade(col, -0.07f, 0.04f)
            var k = -260f
            while (k < 260f) {
                x.stroke(floatArrayOf(c + k - 80, 256f, c + k + 80, 0f), 3f + rand() * 2, if (rand() < 0.5f) light else dark, 0.3f + rand() * 0.25f)
                k += 6f
            }
            x.unclip()
        }
        val stitch = shade(RUG_ROSE, -0.2f, 0.05f)
        for (r in floatArrayOf(0.96f, 0.8f, 0.54f, 0.22f)) {
            val steps = (r * 60).toInt().coerceAtLeast(12)
            for (i in 0 until steps) {
                val a0 = i * 2 * PI.toFloat() / steps
                val a1 = a0 + PI.toFloat() / steps
                x.stroke(arcPts(c, c, 124 * r, a0 * DEG_PER_RAD, a1 * DEG_PER_RAD, rand, 0.01f, 4), 2.6f, stitch, 0.75f)
            }
        }
        return x.texture()
    }

    /** A crayon landscape, 128 x 168 (the poster's shape): sky, sun, two hills, a cloud, a cream border. */
    private fun poster(): Texture {
        val x = CrayonCanvas(128, 168)
        val rand = Rng(23)
        val ink = Color.valueOf(WALL_INK)
        x.fill(Color.valueOf(POSTER_BORDER))
        x.clip(floatArrayOf(10f, 10f, 118f, 10f, 118f, 158f, 10f, 158f))
        x.fillRect(0f, 0f, 128f, 168f, Color.valueOf(POSTER_SKY))
        scribble(x, POSTER_SKY, 0f, 0f, 128f, 168f, rand)
        x.unclip()
        x.crayonFill(84f, 50f, 20f, 20f, Color.valueOf(POSTER_SUN), rand, 1f)
        x.penStroke(blobPath(84f, 50f, 20f, 20f, rand, 0.04f), 2.2f, ink, rand)
        for (i in 0 until 8) {
            val a = i * PI.toFloat() / 4 + 0.2f
            x.penStroke(linePts(84 + cos(a) * 26, 50 + sin(a) * 26, 84 + cos(a) * 33, 50 + sin(a) * 33, rand, 0.5f, 3), 2f, ink, rand)
        }
        x.fillPath(blobPath(40f, 46f, 16f, 7f, rand, 0.1f), Color.valueOf(POSTER_BORDER))
        for ((hill, col) in listOf(floatArrayOf(10f, 158f, 10f, 116f, 40f, 98f, 76f, 112f, 100f, 158f) to POSTER_HILL, floatArrayOf(44f, 158f, 70f, 120f, 96f, 106f, 118f, 114f, 118f, 158f) to POSTER_HILL_LIGHT)) {
            x.clip(hill)
            x.fillRect(0f, 0f, 128f, 168f, Color.valueOf(col))
            scribble(x, col, 0f, 90f, 128f, 78f, rand)
            x.unclip()
            x.penStroke(hill.copyOfRange(2, hill.size - 2), 2.2f, ink, rand)
        }
        return x.texture()
    }

    /** A clock face for a cylinder's cap: a wood rim out to the edge, a cream dial with twelve pen ticks. */
    private fun clock(): Texture {
        val x = CrayonCanvas(128, 128)
        val rand = Rng(12)
        val ink = Color.valueOf(WALL_INK)
        x.fill(Color.valueOf(CLOCK_RIM))
        scribble(x, CLOCK_RIM, 0f, 0f, 128f, 128f, rand)
        val dial = blobPath(64f, 64f, 52f, 52f, rand, 0.015f)
        x.clip(dial)
        x.fillRect(0f, 0f, 128f, 128f, Color.valueOf(CLOCK_DIAL))
        scribble(x, CLOCK_DIAL, 0f, 0f, 128f, 128f, rand)
        x.unclip()
        x.penStroke(dial, 2.4f, ink, rand)
        for (i in 0 until 12) {
            val a = i * PI.toFloat() / 6
            val r0 = if (i % 3 == 0) 36f else 41f
            x.penStroke(linePts(64 + cos(a) * r0, 64 + sin(a) * r0, 64 + cos(a) * 47, 64 + sin(a) * 47, rand, 0.4f, 3), if (i % 3 == 0) 3.2f else 2.2f, ink, rand)
        }
        return x.texture()
    }

    /** Diagonal crayon strokes a shade either side of [base] over a rectangle. */
    private fun scribble(x: CrayonCanvas, base: String, x0: Float, y0: Float, w: Float, h: Float, rand: Rng) {
        val light = shade(base, 0.05f)
        val dark = shade(base, -0.06f, 0.04f)
        var k = -h
        while (k < w) {
            x.stroke(floatArrayOf(x0 + k, y0 + h, x0 + k + h * 0.6f, y0), 2.5f + rand() * 1.5f, if (rand() < 0.5f) light else dark, 0.3f + rand() * 0.25f)
            k += 4.5f
        }
    }

    /** A stroke on a canvas that tiles every [tile] units both ways: drawn again wherever it crosses an edge. */
    private fun wrapStroke(x: CrayonCanvas, pts: FloatArray, width: Float, c: Color, alpha: Float, tile: Float) {
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0 until pts.size / 2) {
            minX = min(minX, pts[2 * i]); maxX = max(maxX, pts[2 * i])
            minY = min(minY, pts[2 * i + 1]); maxY = max(maxY, pts[2 * i + 1])
        }
        val pad = width
        for (dx in floatArrayOf(-tile, 0f, tile)) for (dy in floatArrayOf(-tile, 0f, tile)) {
            if (maxX + dx + pad < 0 || minX + dx - pad > x.width || maxY + dy + pad < 0 || minY + dy - pad > x.height) continue
            val moved = FloatArray(pts.size) { pts[it] + if (it % 2 == 0) dx else dy }
            x.stroke(moved, width, c, alpha)
        }
    }

    /** [wrapStroke] gone over twice like a pen (as [penStroke]). */
    private fun wrapPen(x: CrayonCanvas, pts: FloatArray, width: Float, c: Color, rand: Rng, tile: Float, alpha: Float = 1f) {
        for (pass in 0..1) {
            val ox = (rand() - 0.5f) * 2
            val oy = (rand() - 0.5f) * 2
            val moved = FloatArray(pts.size) { pts[it] + (if (it % 2 == 0) ox else oy) + (rand() - 0.5f) * 1f }
            wrapStroke(x, moved, if (pass == 1) width * 0.45f else width, c, if (pass == 1) alpha * 0.55f else alpha, tile)
        }
    }

    /** A rectangle on a canvas that wraps every [tile] units across. */
    private fun wrapRect(x: CrayonCanvas, x0: Float, y0: Float, w: Float, h: Float, c: Color, tile: Float) {
        val start = x0.mod(tile)
        x.fillRect(start, y0, w, h, c)
        if (start + w > tile) x.fillRect(start - tile, y0, w, h, c)
    }

    companion object {
        const val FLOOR = "floor"
        const val WALL = "wall"
        const val SECTION = "section"
        const val WHITE = "white"
        /** The floor texture covers this many metres each way, and the wall's this many across. */
        const val FLOOR_METRES = 4f
        const val WALL_METRES = 2f
        /** Section texture: one repeat per this many metres. */
        const val SECTION_METRES = 0.5f
        private const val FLOOR_PX = 512
        private const val WALL_PX = 256
        private const val SECTION_PX = 64
        private const val BOARDS = 16
        private const val GRAIN_PER_ROW = 110
        private const val STRIPES = 4
        private const val WAINSCOT_BOARDS = 8
        private const val DEG_PER_RAD = (180 / PI).toFloat()

        const val FLOOR_WOOD = "#ebbf95"
        private val FLOOR_LINE = Color.valueOf("#a9785e")
        const val WALLPAPER = "#fcebcf"
        private const val WALLPAPER_STRIPE = "#f3cfa9"
        private const val SPRIG = "#f4b9bf"
        private const val RAIL = "#fff6e8"
        private const val WAINSCOT = "#b4dcc6"
        private const val BASEBOARD = "#d29d7b"
        private const val WALL_INK = "#6e5a66"
        private const val SECTION_COLOR = "#7d6573"
        private const val RUG_ROSE = "#f2aebb"
        private const val RUG_CREAM = "#fde8c4"
        private const val POSTER_BORDER = "#fffaf0"
        private const val POSTER_SKY = "#a9cdf0"
        private const val POSTER_SUN = "#ffd86a"
        private const val POSTER_HILL = "#8fcf8c"
        private const val POSTER_HILL_LIGHT = "#b5e2a0"
        private const val CLOCK_RIM = "#c48d68"
        private const val CLOCK_DIAL = "#fff8ec"
    }
}
