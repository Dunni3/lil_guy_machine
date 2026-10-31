package dev.lilguy.agent.art

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.abs

/**
 * One drawing operation on a square grid; [color] is an index into the piece's palette. The drawing
 * spec: a model never writes these, it picks [Strokes] that become them, and [Artwork.draw] draws them.
 */
@Serializable
sealed interface DrawOp {
    val color: Int

    /** A filled rectangle, [w] by [h] cells from its top-left corner. */
    @Serializable
    @SerialName("rect")
    data class Rect(val x: Int, val y: Int, val w: Int, val h: Int, override val color: Int) : DrawOp

    /** A filled disc of radius [r] around (x, y). */
    @Serializable
    @SerialName("circle")
    data class Circle(val x: Int, val y: Int, val r: Int, override val color: Int) : DrawOp

    /** A one-cell line between two points. */
    @Serializable
    @SerialName("line")
    data class Line(val x0: Int, val y0: Int, val x1: Int, val y1: Int, override val color: Int) : DrawOp

    /** Floods the region of one color around (x, y), like a paint bucket. */
    @Serializable
    @SerialName("fill")
    data class Fill(val x: Int, val y: Int, override val color: Int) : DrawOp

    @Serializable
    @SerialName("pixel")
    data class Pixel(val x: Int, val y: Int, override val color: Int) : DrawOp
}

/**
 * A finished picture: [size] by [size] cells, each an index into [palette] (0xRRGGBB), row by row
 * from the top-left. Pure Kotlin, so the TV can draw and save one too ([png] needs no ImageIO).
 */
class Artwork(val size: Int, val palette: List<Int>, private val cells: IntArray) {
    init {
        require(cells.size == size * size) { "an artwork of size $size needs ${size * size} cells" }
    }

    /** The cell's color as 0xRRGGBB. */
    fun rgb(x: Int, y: Int): Int = palette[cells[y * size + x]]

    /** One string per row, one letter per cell (a for palette 0, b for 1, ...): a text model's view of it. */
    fun letters(): List<String> = (0 until size).map { y -> String(CharArray(size) { x -> 'a' + cells[y * size + x] }) }

    /** True when every cell is the same palette color: nothing drawn shows. */
    fun blank(): Boolean = cells.all { it == cells[0] }

    /** The picture as a PNG, each cell [scale] pixels square, so a small grid stays crisp in a viewer. */
    fun png(scale: Int = 8): ByteArray {
        val side = size * scale
        val raw = ByteArrayOutputStream()
        for (py in 0 until side) {
            raw.write(0) // filter: none
            for (px in 0 until side) {
                val c = rgb(px / scale, py / scale)
                raw.write(c shr 16 and 0xff)
                raw.write(c shr 8 and 0xff)
                raw.write(c and 0xff)
            }
        }
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        chunk(out, "IHDR", ByteArrayOutputStream().also { DataOutputStream(it).apply { writeInt(side); writeInt(side); write(byteArrayOf(8, 2, 0, 0, 0)) } }.toByteArray())
        chunk(out, "IDAT", deflate(raw.toByteArray()))
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    companion object {
        /** Draws [ops] in order on a canvas that starts as palette color 0; out-of-range cells are clipped. */
        fun draw(size: Int, palette: List<Int>, ops: List<DrawOp>): Artwork {
            require(palette.isNotEmpty()) { "a palette needs at least one color" }
            val cells = IntArray(size * size)
            fun inside(x: Int, y: Int) = x in 0 until size && y in 0 until size
            fun set(x: Int, y: Int, c: Int) {
                if (inside(x, y)) cells[y * size + x] = c
            }
            for (op in ops) {
                val c = op.color
                require(c in palette.indices) { "color $c is past the palette's ${palette.size} colors" }
                when (op) {
                    is DrawOp.Rect -> for (y in op.y until op.y + op.h) for (x in op.x until op.x + op.w) set(x, y, c)
                    is DrawOp.Circle -> for (y in op.y - op.r..op.y + op.r) for (x in op.x - op.r..op.x + op.r) {
                        val dx = x - op.x
                        val dy = y - op.y
                        if (dx * dx + dy * dy <= op.r * op.r + op.r) set(x, y, c)
                    }
                    is DrawOp.Line -> line(op) { x, y -> set(x, y, c) }
                    is DrawOp.Fill -> if (inside(op.x, op.y)) flood(cells, size, op.x, op.y, c)
                    is DrawOp.Pixel -> set(op.x, op.y, c)
                }
            }
            return Artwork(size, palette, cells)
        }

        private fun line(op: DrawOp.Line, plot: (Int, Int) -> Unit) {
            var x = op.x0
            var y = op.y0
            val dx = abs(op.x1 - x)
            val dy = -abs(op.y1 - y)
            val sx = if (x < op.x1) 1 else -1
            val sy = if (y < op.y1) 1 else -1
            var err = dx + dy
            while (true) {
                plot(x, y)
                if (x == op.x1 && y == op.y1) return
                val e2 = 2 * err
                if (e2 >= dy) { err += dy; x += sx }
                if (e2 <= dx) { err += dx; y += sy }
            }
        }

        private fun flood(cells: IntArray, size: Int, x0: Int, y0: Int, c: Int) {
            val target = cells[y0 * size + x0]
            if (target == c) return
            val stack = ArrayDeque<Int>().apply { add(y0 * size + x0) }
            while (stack.isNotEmpty()) {
                val i = stack.removeLast()
                if (cells[i] != target) continue
                cells[i] = c
                val x = i % size
                if (x > 0) stack.add(i - 1)
                if (x < size - 1) stack.add(i + 1)
                if (i >= size) stack.add(i - size)
                if (i < size * (size - 1)) stack.add(i + size)
            }
        }

        private fun deflate(data: ByteArray): ByteArray {
            val d = Deflater()
            d.setInput(data)
            d.finish()
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!d.finished()) out.write(buf, 0, d.deflate(buf))
            d.end()
            return out.toByteArray()
        }

        private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
            val d = DataOutputStream(out)
            d.writeInt(data.size)
            val head = type.toByteArray(Charsets.US_ASCII)
            d.write(head)
            d.write(data)
            d.writeInt(CRC32().apply { update(head); update(data) }.value.toInt())
        }
    }
}
