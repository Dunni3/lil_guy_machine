package dev.lilguy.app

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.utils.Disposable
import dev.lilguy.parts.RoomParts
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
     * Wide white-oak boards running east-west, [FLOOR_METRES] square: planks of one and a half to
     * three metres, each a slightly different wood, grain in long faint crayon strokes, soft broken
     * lines between the rows, ticks at the joints and the odd knot.
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
                var len = 190 + rand() * 180
                if (n - covered - len < 120) len = n - covered
                val tone = shade(FLOOR_WOOD, (rand() - 0.5f) * 0.05f, (rand() - 0.5f) * 0.04f)
                wrapRect(x, at, y0, len, row, tone, n)
                // A pen tick at the plank's end, and now and then a knot.
                val jx = at + (rand() - 0.5f) * 2
                wrapPen(x, linePts(jx, y0 + 4, jx + (rand() - 0.5f) * 3, y0 + row - 4, rand, 1f, 4), 2f, FLOOR_LINE, rand, n, 0.55f)
                if (rand() < 0.2f) {
                    val kx = at + 30 + rand() * (len - 60)
                    val ky = y0 + row * (0.35f + rand() * 0.3f)
                    wrapStroke(x, blobPath(kx, ky, 7 + rand() * 5, 2.5f + rand() * 1.5f, rand, 0.12f, 16), 1.6f, shade(FLOOR_WOOD, -0.16f), 0.55f, n)
                }
                at += len
                covered += len
            }
            // Grain: short strokes along the boards, a shade either side.
            val light = shade(FLOOR_WOOD, 0.05f)
            val dark = shade(FLOOR_WOOD, -0.06f, 0.02f)
            repeat(GRAIN_PER_ROW) {
                val gx = rand() * n
                val gy = y0 + 4 + rand() * (row - 8)
                val len = 30 + rand() * 70
                val tilt = (rand() - 0.5f) * 2
                wrapStroke(x, floatArrayOf(gx, gy, gx + len, gy + tilt), 1.4f + rand() * 1.8f, if (rand() < 0.5f) light else dark, 0.14f + rand() * 0.18f, n)
            }
            // The seam above this row, in broken lengths.
            var sx = rand() * 40
            while (sx < n + 40) {
                val len = 70 + rand() * 150
                val sy = y0 + (rand() - 0.5f) * 1.5f
                wrapPen(x, linePts(sx, sy, sx + len, sy + (rand() - 0.5f) * 2, rand, 1.2f, 8), 2.2f, FLOOR_LINE, rand, n, 0.6f)
                sx += len + 4 + rand() * 12
            }
        }
        return x.texture(repeat = true, mipmaps = true)
    }

    /**
     * One stretch of wall, [WALL_METRES] wide and the wall's height tall, bottom to top: a slim
     * baseboard, a clay colour block to [RoomParts.COLOR_BLOCK], and warm limewashed plaster above,
     * both clouded with broad, faint crayon; ink along the top and the baseboard.
     */
    private fun wall(): Texture {
        val w = WALL_PX.toFloat()
        val h = WALL_PX.toFloat()
        val x = CrayonCanvas(WALL_PX, WALL_PX)
        val rand = Rng(31)
        fun yAt(m: Float) = (1 - m / RoomParts.WALL_HEIGHT) * h
        val blockTop = yAt(RoomParts.COLOR_BLOCK)
        val baseTop = yAt(RoomParts.BASEBOARD)

        x.fill(Color.valueOf(PLASTER))
        limewash(x, PLASTER, 0f, blockTop, w, rand)
        x.fillRect(0f, blockTop, w, baseTop - blockTop, Color.valueOf(CLAY))
        limewash(x, CLAY, blockTop, baseTop, w, rand)
        x.fillRect(0f, baseTop, w, h - baseTop, Color.valueOf(BASEBOARD))
        val baseDark = shade(BASEBOARD, -0.06f)
        repeat(120) {
            val px = rand() * w
            val py = baseTop + 2 + rand() * (h - baseTop - 4)
            wrapStroke(x, floatArrayOf(px, py, px + 14 + rand() * 24, py + (rand() - 0.5f) * 1.5f), 1.5f, baseDark, 0.3f, w)
        }

        // The block's edge is a crisp line of its own colour, a shade down; ink only at the top and the baseboard.
        val edge = shade(CLAY, -0.1f)
        var ex = rand() * 30
        while (ex < w + 30) {
            val len = 80 + rand() * 120
            wrapStroke(x, linePts(ex, blockTop + (rand() - 0.5f) * 0.6f, ex + len, blockTop + (rand() - 0.5f) * 0.6f, rand, 0.4f, 8), 2f, edge, 0.6f, w)
            ex += len + 2 + rand() * 6
        }
        val ink = Color.valueOf(WALL_INK)
        for (y in floatArrayOf(1.5f, baseTop)) {
            var sx = rand() * 30
            while (sx < w + 30) {
                val len = 60 + rand() * 110
                wrapPen(x, linePts(sx, y + (rand() - 0.5f), sx + len, y + (rand() - 0.5f), rand, 1f, 8), 2.4f, ink, rand, w, 0.75f)
                sx += len + 3 + rand() * 8
            }
        }
        return x.texture(repeat = true, mipmaps = true)
    }

    /** Limewash between rows [y0] and [y1]: broad, faint clouds a shade either side of [base], then fine crayon over them. */
    private fun limewash(x: CrayonCanvas, base: String, y0: Float, y1: Float, w: Float, rand: Rng) {
        val light = shade(base, 0.04f)
        val dark = shade(base, -0.05f, 0.03f)
        val area = (y1 - y0) * w / (256f * 256f)
        repeat((260 * area).toInt()) {
            val px = rand() * w
            val py = y0 + rand() * (y1 - y0)
            val a = (rand() - 0.5f) * 1.2f
            val len = 20 + rand() * 40
            val pts = floatArrayOf(px, py, px + cos(a) * len, (py + sin(a) * len).coerceIn(y0, y1))
            wrapStroke(x, pts, 10f + rand() * 14, if (rand() < 0.5f) light else dark, 0.07f + rand() * 0.07f, w)
        }
        repeat((700 * area).toInt()) {
            val px = rand() * w
            val py = y0 + rand() * (y1 - y0)
            val a = -1.2f + (rand() - 0.5f) * 0.4f
            val len = 8 + rand() * 16
            val pts = floatArrayOf(px, py, px + cos(a) * len, (py + sin(a) * len).coerceIn(y0, y1))
            wrapStroke(x, pts, 1.5f + rand() * 1.5f, if (rand() < 0.5f) light else dark, 0.18f + rand() * 0.2f, w)
        }
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

    /** An oval wool rug (painted round; the rug's shape stretches it): an oat border, a cream field ringed in rust, and a thin sage ring inside; stitches round the edge. */
    private fun rug(): Texture {
        val x = CrayonCanvas(256, 256)
        val rand = Rng(61)
        val c = 128f
        val rings = listOf(1.6f to RUG_OAT, 0.84f to RUG_RUST, 0.8f to RUG_CREAM, 0.5f to RUG_SAGE, 0.46f to RUG_CREAM)
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
        val stitch = shade(RUG_OAT, -0.18f, 0.03f)
        for (r in floatArrayOf(0.95f)) {
            val steps = (r * 60).toInt().coerceAtLeast(12)
            for (i in 0 until steps) {
                val a0 = i * 2 * PI.toFloat() / steps
                val a1 = a0 + PI.toFloat() / steps
                x.stroke(arcPts(c, c, 124 * r, a0 * DEG_PER_RAD, a1 * DEG_PER_RAD, rand, 0.01f, 4), 2.6f, stitch, 0.75f)
            }
        }
        return x.texture()
    }

    /** A print, 128 x 168 (the poster's shape): a low sun behind three nested arches on cream, a thin oak frame's worth of border. */
    private fun poster(): Texture {
        val x = CrayonCanvas(128, 168)
        val rand = Rng(23)
        x.fill(Color.valueOf(POSTER_BORDER))
        scribble(x, POSTER_BORDER, 0f, 0f, 128f, 168f, rand)
        x.crayonFill(84f, 62f, 18f, 18f, Color.valueOf(POSTER_SUN), rand, 1f)
        // Arches, outermost first, each a band whose inside the next one paints over.
        val cx = 56f
        val base = 140f
        for ((r, col) in listOf(40f to POSTER_RUST, 30f to POSTER_SAND, 20f to POSTER_SAGE, 10f to POSTER_BORDER)) {
            x.clip(archPath(cx, base, r, rand))
            x.fillRect(0f, 0f, 128f, 168f, Color.valueOf(col))
            scribble(x, col, cx - r, base - 2 * r, 2 * r, 2 * r, rand)
            x.unclip()
        }
        x.fillRect(16f, base, 96f, 3f, Color.valueOf(POSTER_RUST))
        return x.texture()
    }

    /** An arch [r] wide each side of [cx], standing on [base]: straight sides up [r], then a half circle. */
    private fun archPath(cx: Float, base: Float, r: Float, rand: Rng): FloatArray {
        val top = arcPts(cx, base - r, r, 180f, 360f, rand, 0.01f, 24)
        return floatArrayOf(cx - r, base) + top + floatArrayOf(cx + r, base)
    }

    /** A clock face for a cylinder's cap: a charcoal rim out to the edge, a warm dial, pen ticks at the quarters and dots between. */
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
            if (i % 3 == 0) x.penStroke(linePts(64 + cos(a) * 37, 64 + sin(a) * 37, 64 + cos(a) * 46, 64 + sin(a) * 46, rand, 0.4f, 3), 3f, ink, rand)
            else x.stroke(floatArrayOf(64 + cos(a) * 43, 64 + sin(a) * 43), 3.4f, ink, 0.7f)
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
        private const val BOARDS = 12
        private const val GRAIN_PER_ROW = 90
        private const val DEG_PER_RAD = (180 / PI).toFloat()

        const val FLOOR_WOOD = "#e7cca9"
        private val FLOOR_LINE = Color.valueOf("#b4977d")
        private const val PLASTER = "#f3e8da"
        private const val CLAY = "#e2c2ac"
        private const val BASEBOARD = "#d4ad95"
        private const val WALL_INK = "#6e5a5a"
        private const val SECTION_COLOR = "#8a7268"
        private const val RUG_OAT = "#e6d5bd"
        private const val RUG_RUST = "#c9785a"
        private const val RUG_CREAM = "#f6eee2"
        private const val RUG_SAGE = "#b3bf9f"
        private const val POSTER_BORDER = "#f8f1e6"
        private const val POSTER_SUN = "#e9b65e"
        private const val POSTER_RUST = "#c9785a"
        private const val POSTER_SAND = "#e5c7a4"
        private const val POSTER_SAGE = "#9fae8f"
        private const val CLOCK_RIM = "#4f4848"
        private const val CLOCK_DIAL = "#faf4ea"
    }
}
