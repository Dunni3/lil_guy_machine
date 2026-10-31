package dev.lilguy.app

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A small anti-aliased canvas for the hand-drawn textures (faces, crayon grain, the shadow), in place
 * of the workshop's HTML canvas (`tools/workshop/guy.html`), so the same drawing code gives the same
 * pictures. Shapes are given in design units and drawn at [scale], offset by ([ox], [oy]); colours
 * blend in sRGB like a browser canvas. Every stroke is one shape (round caps and joins), composited
 * once, and [clip] masks what is drawn until [unclip].
 */
class CrayonCanvas(val width: Int, val height: Int, private val scale: Float = 1f, private val ox: Float = 0f, private val oy: Float = 0f) {
    /** Straight (not premultiplied) RGBA, row by row from the top. */
    private val px = FloatArray(width * height * 4)
    private var mask: FloatArray? = null

    fun fill(c: Color) {
        for (i in 0 until width * height) {
            px[4 * i] = c.r; px[4 * i + 1] = c.g; px[4 * i + 2] = c.b; px[4 * i + 3] = c.a
        }
    }

    /** Copies [from]'s pixels starting at its pixel ([x0], [y0]), for a crop at the same scale. */
    fun copy(from: CrayonCanvas, x0: Int, y0: Int) {
        for (y in 0 until height) for (x in 0 until width) {
            val sx = (x + x0).coerceIn(0, from.width - 1)
            val sy = (y + y0).coerceIn(0, from.height - 1)
            System.arraycopy(from.px, 4 * (sy * from.width + sx), px, 4 * (y * width + x), 4)
        }
    }

    /** A polyline [pts] (x, y pairs) stroked [lineWidth] wide. */
    fun stroke(pts: FloatArray, lineWidth: Float, c: Color, alpha: Float = 1f) {
        val xs = FloatArray(pts.size / 2) { (pts[2 * it] - ox) * scale }
        val ys = FloatArray(pts.size / 2) { (pts[2 * it + 1] - oy) * scale }
        val half = lineWidth * scale / 2
        val x0 = max(0, floor(xs.min() - half - 1).toInt())
        val x1 = min(width - 1, ceil(xs.max() + half + 1).toInt())
        val y0 = max(0, floor(ys.min() - half - 1).toInt())
        val y1 = min(height - 1, ceil(ys.max() + half + 1).toInt())
        for (y in y0..y1) for (x in x0..x1) {
            val qx = x + 0.5f
            val qy = y + 0.5f
            var d = Float.MAX_VALUE
            if (xs.size == 1) d = hypot(qx - xs[0], qy - ys[0])
            for (k in 0 until xs.size - 1) d = min(d, segmentDistance(qx, qy, xs[k], ys[k], xs[k + 1], ys[k + 1]))
            val cover = (half - d + 0.5f).coerceIn(0f, 1f)
            if (cover > 0f) blend(y * width + x, c, alpha * cover)
        }
    }

    /** A closed polygon [pts] filled (even-odd), anti-aliased horizontally exactly and vertically by sub-rows. */
    fun fillPath(pts: FloatArray, c: Color, alpha: Float = 1f) {
        val cover = coverage(pts)
        for (i in cover.indices) if (cover[i] > 0f) blend(i, c, alpha * cover[i])
    }

    fun clip(pts: FloatArray) { mask = coverage(pts) }

    fun clipRect(x: Float, y: Float, w: Float, h: Float) = clip(floatArrayOf(x, y, x + w, y, x + w, y + h, x, y + h))

    fun unclip() { mask = null }

    fun texture(): Texture {
        val p = Pixmap(width, height, Pixmap.Format.RGBA8888)
        val buf = p.pixels
        for (v in px) buf.put((v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte())
        buf.position(0)
        return Texture(p).also {
            it.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
            p.dispose()
        }
    }

    private fun blend(i: Int, c: Color, a0: Float) {
        val a = a0 * c.a * (mask?.get(i) ?: 1f)
        if (a <= 0f) return
        val o = 4 * i
        val da = px[o + 3]
        val outA = a + da * (1 - a)
        for (k in 0..2) {
            val src = when (k) { 0 -> c.r; 1 -> c.g; else -> c.b }
            px[o + k] = (src * a + px[o + k] * da * (1 - a)) / outA
        }
        px[o + 3] = outA
    }

    private fun coverage(pts: FloatArray): FloatArray {
        val n = pts.size / 2
        val xs = FloatArray(n) { (pts[2 * it] - ox) * scale }
        val ys = FloatArray(n) { (pts[2 * it + 1] - oy) * scale }
        val out = FloatArray(width * height)
        val y0 = max(0, floor(ys.min()).toInt())
        val y1 = min(height - 1, ceil(ys.max()).toInt())
        val hits = FloatArray(n)
        for (y in y0..y1) for (sub in 0 until SUB_ROWS) {
            val sy = y + (sub + 0.5f) / SUB_ROWS
            var count = 0
            for (k in 0 until n) {
                val j = (k + 1) % n
                val ay = ys[k]
                val by = ys[j]
                if ((ay <= sy) != (by <= sy)) hits[count++] = xs[k] + (sy - ay) / (by - ay) * (xs[j] - xs[k])
            }
            hits.sort(0, count)
            var h = 0
            while (h + 1 < count) {
                span(out, y, hits[h], hits[h + 1])
                h += 2
            }
        }
        return out
    }

    /** Adds a sub-row's covered span [a, b) to row [y], with fractional coverage at both ends. */
    private fun span(out: FloatArray, y: Int, a: Float, b: Float) {
        val l = a.coerceIn(0f, width.toFloat())
        val r = b.coerceIn(0f, width.toFloat())
        if (r <= l) return
        val w = 1f / SUB_ROWS
        val li = l.toInt()
        val ri = min(width - 1, r.toInt())
        if (li == ri) { out[y * width + li] += (r - l) * w; return }
        out[y * width + li] += (li + 1 - l) * w
        for (x in li + 1 until ri) out[y * width + x] += w
        if (ri < width) out[y * width + ri] += (r - ri) * w
    }

    private fun segmentDistance(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val len = dx * dx + dy * dy
        val t = if (len > 0f) (((px - ax) * dx + (py - ay) * dy) / len).coerceIn(0f, 1f) else 0f
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    private companion object {
        const val SUB_ROWS = 4
    }
}

/** The workshop's seeded random numbers (mulberry32), so a seed draws the same lumps and strokes as there. */
class Rng(private var seed: Int) {
    operator fun invoke(): Float {
        seed += 0x6D2B79F5
        var t = (seed xor (seed ushr 15)) * (1 or seed)
        t = (t + (t xor (t ushr 7)) * (61 or t)) xor t
        return ((t xor (t ushr 14)).toLong() and 0xffffffffL).toFloat() / 4294967296f
    }
}

/** Smooth noise on 3D points from a few random sine waves, roughly -1..1 (the workshop's noise3). */
class Noise3(seed: Int, waves: Int = 5, freq: Float = 4f) {
    private val dx = FloatArray(waves)
    private val dy = FloatArray(waves)
    private val dz = FloatArray(waves)
    private val f = FloatArray(waves)
    private val p = FloatArray(waves)
    private val a = FloatArray(waves)
    private val total: Float

    init {
        val r = Rng(seed)
        for (i in 0 until waves) {
            val x = r() * 2 - 1
            val y = r() * 2 - 1
            val z = r() * 2 - 1
            val l = sqrt(x * x + y * y + z * z)
            dx[i] = x / l; dy[i] = y / l; dz[i] = z / l
            f[i] = freq * (0.6f + r() * 0.8f) * (1 + i * 0.35f)
            p[i] = r() * PI.toFloat() * 2
            a[i] = 1f / (1 + i * 0.6f)
        }
        total = a.sum()
    }

    operator fun invoke(x: Float, y: Float, z: Float): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * sin((dx[i] * x + dy[i] * y + dz[i] * z) * f[i] + p[i])
        return s / total
    }
}

/** [hex] made lighter by [dl] and more saturated by [ds], in HSL (the workshop's shade). */
fun shade(hex: String, dl: Float, ds: Float = 0f): Color {
    val c = Color.valueOf(hex)
    val mx = maxOf(c.r, c.g, c.b)
    val mn = minOf(c.r, c.g, c.b)
    var l = (mx + mn) / 2
    var s = 0f
    var h = 0f
    if (mx != mn) {
        val d = mx - mn
        s = if (l > 0.5f) d / (2 - mx - mn) else d / (mx + mn)
        h = when (mx) {
            c.r -> (c.g - c.b) / d + (if (c.g < c.b) 6 else 0)
            c.g -> (c.b - c.r) / d + 2
            else -> (c.r - c.g) / d + 4
        } / 6
    }
    s = (s + ds).coerceIn(0f, 1f)
    l = (l + dl).coerceIn(0f, 1f)
    val q = if (l < 0.5f) l * (1 + s) else l + s - l * s
    val p = 2 * l - q
    fun hue(t0: Float): Float {
        val t = if (t0 < 0) t0 + 1 else if (t0 > 1) t0 - 1 else t0
        return when {
            t < 1f / 6 -> p + (q - p) * 6 * t
            t < 0.5f -> q
            t < 2f / 3 -> p + (q - p) * (2f / 3 - t) * 6
            else -> p
        }
    }
    return Color(hue(h + 1f / 3), hue(h), hue(h - 1f / 3), 1f)
}

// --- the workshop's pen shapes, as x, y pairs -------------------------------------------------------

/** A wobbly closed ellipse. */
fun blobPath(cx: Float, cy: Float, rx: Float, ry: Float, rand: Rng, wob: Float = 0.05f, n: Int = 40): FloatArray {
    val p1 = rand() * 6.28f
    val p2 = rand() * 6.28f
    return FloatArray(2 * (n + 1)).also {
        for (i in 0..n) {
            val a = i.toFloat() / n * PI.toFloat() * 2
            val j = 1 + wob * sin(2 * a + p1) + wob * 0.6f * sin(5 * a + p2)
            it[2 * i] = cx + cos(a) * rx * j
            it[2 * i + 1] = cy + sin(a) * ry * j
        }
    }
}

/** A wobbly arc from [a0] to [a1] degrees, overshooting or falling short a little. */
fun arcPts(cx: Float, cy: Float, r: Float, a0: Float, a1: Float, rand: Rng, wob: Float = 0.06f, n: Int = 18): FloatArray {
    val ph = rand() * 6.28f
    val over = (rand() - 0.3f) * 10
    return FloatArray(2 * (n + 1)).also {
        for (i in 0..n) {
            val a = (a0 - over / 2 + (a1 - a0 + over) * i / n) * DEG
            val rr = r * (1 + wob * sin(a * 2.5f + ph))
            it[2 * i] = cx + cos(a) * rr
            it[2 * i + 1] = cy + sin(a) * rr
        }
    }
}

/** A line that bows a little to one side. */
fun linePts(x0: Float, y0: Float, x1: Float, y1: Float, rand: Rng, bow: Float = 3f, n: Int = 10): FloatArray {
    val b = (rand() - 0.5f) * 2 * bow
    val nx = -(y1 - y0)
    val ny = x1 - x0
    val l = hypot(nx, ny).takeIf { it > 0f } ?: 1f
    return FloatArray(2 * (n + 1)).also {
        for (i in 0..n) {
            val t = i.toFloat() / n
            val k = sin(PI.toFloat() * t) * b
            it[2 * i] = x0 + (x1 - x0) * t + nx / l * k
            it[2 * i + 1] = y0 + (y1 - y0) * t + ny / l * k
        }
    }
}

/** An ink stroke gone over twice like a pen: a full line and a fainter, slightly offset one. */
fun CrayonCanvas.penStroke(pts: FloatArray, lineWidth: Float, ink: Color, rand: Rng) {
    for (pass in 0..1) {
        val ox = (rand() - 0.5f) * 3
        val oy = (rand() - 0.5f) * 3
        val jittered = FloatArray(pts.size)
        for (i in 0 until pts.size / 2) {
            jittered[2 * i] = pts[2 * i] + ox + (rand() - 0.5f) * 1.4f
            jittered[2 * i + 1] = pts[2 * i + 1] + oy + (rand() - 0.5f) * 1.4f
        }
        stroke(jittered, if (pass == 1) lineWidth * 0.45f else lineWidth, ink, if (pass == 1) 0.55f else 1f)
    }
}

/** A patch of diagonal crayon strokes inside a wobbly ellipse. */
fun CrayonCanvas.crayonFill(cx: Float, cy: Float, rx: Float, ry: Float, c: Color, rand: Rng, alpha: Float) {
    clip(blobPath(cx, cy, rx, ry, rand, 0.09f))
    var k = -rx * 2
    while (k < rx * 2) {
        val ya = cy + ry + (rand() - 0.5f) * 5
        val yb = cy - ry + (rand() - 0.5f) * 5
        stroke(floatArrayOf(cx + k - ry, ya, cx + k + ry, yb), 4.5f, c, alpha)
        k += 7
    }
    unclip()
}

private const val DEG = (PI / 180).toFloat()
