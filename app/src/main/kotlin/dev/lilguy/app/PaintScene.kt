package dev.lilguy.app

import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.utils.Disposable
import dev.lilguy.agent.art.Underway
import kotlin.math.abs

/**
 * The piece he's making, played out as the Painter's strokes come back ([dev.lilguy.agent.art.ArtStudio.underway]):
 * each new stroke's cells go on a few at a time along a brush path, in its crayon, and the brush
 * follows them ([u], [v]); before a stroke in another colour he dips the brush; with nothing new yet
 * he steps back to consider it; when the piece is done, a flourish, then he admires it. The model
 * sets the pace (Jev takes about 7 s a stroke), not the verb's progress, so it plays the same
 * whether the clock runs or waits on the drawing.
 *
 * Pure bookkeeping plus one small texture, the piece's own size (16 or 32 cells), redrawn only
 * as cells go on. [RoomView] decides where it shows and [Blob] acts out [phase].
 */
class PaintScene : Disposable {
    enum class Phase { Ponder, Dip, Stroke, Flourish, Admire }

    var phase = Phase.Ponder
        private set
    /** Seconds into [phase]. */
    var phaseTime = 0f
        private set
    /** The latest word on the piece, null for none. */
    var piece: Underway? = null
        private set
    /** Strokes on the canvas so far. */
    var shown = 0
        private set
    /** Where the brush is on the picture, 0..1 from the top-left, and whether it's off the surface. */
    var u = 0.5f
        private set
    var v = 0.5f
        private set
    var lifted = true
        private set
    /** The brush's colour, 0xRRGGBB. */
    var color = 0
        private set
    /** The picture as far as it has gone on; null with no piece. */
    val texture: Texture? get() = if (piece != null) work?.texture else null

    /** True while strokes that have come back are still going on, or he's in the middle of a dip or the flourish. */
    val finishing: Boolean get() = queue.isNotEmpty() || phase == Phase.Dip || phase == Phase.Stroke || phase == Phase.Flourish

    private var tick: Long? = null
    private var work: Work? = null
    private val queue = ArrayDeque<Underway>()
    private var queued = 0
    private var flourished = false
    /** The stroke going on: its cells in brush order, how many are on, and how long it takes. */
    private var cells = IntArray(0)
    private var target: Underway? = null
    private var on = 0
    private var took = 1f

    /**
     * Advances [delta] seconds with [underway] the latest word from the studio. [hurry] when the
     * verb has ended and the clock waits on this: strokes go on faster.
     */
    fun update(delta: Float, underway: Underway?, hurry: Boolean) {
        if (underway == null || underway.state == Underway.State.Dropped) return reset()
        if (underway.tick != tick) start(underway)
        if (underway.strokes > queued) {
            queue += underway
            queued = underway.strokes
        }
        piece = underway
        phaseTime += delta
        when (phase) {
            Phase.Stroke -> paint(delta, hurry)
            Phase.Dip -> {
                // The brush comes out of the paint in the new colour halfway through.
                val dip = DIP / (if (hurry) HURRY else 1f)
                if (phaseTime >= dip / 2) color = target!!.palette[target!!.crayon ?: 0]
                if (phaseTime >= dip) enter(Phase.Stroke)
            }
            Phase.Flourish -> if (phaseTime >= FLOURISH) enter(Phase.Admire)
            Phase.Ponder, Phase.Admire -> next()
        }
        if (phase == Phase.Ponder && !flourished && queue.isEmpty() && underway.state == Underway.State.Done) {
            flourished = true
            enter(Phase.Flourish)
        }
    }

    /** A new piece: the canvas as it stands when first seen (blank paper, normally). */
    private fun start(underway: Underway) {
        tick = underway.tick
        val art = underway.art
        if (work?.size != art.size) {
            work?.dispose()
            work = Work(art.size)
        }
        val w = work!!
        for (y in 0 until art.size) for (x in 0 until art.size) w.set(x, y, art.rgb(x, y))
        w.upload()
        queue.clear()
        queued = underway.strokes
        shown = underway.strokes
        // Seen first part way through (another page was up), it doesn't flourish late.
        flourished = underway.strokes > 0
        target = null
        color = underway.palette.getOrElse(underway.crayon ?: 1) { underway.palette.first() }
        u = 0.5f
        v = 0.5f
        lifted = true
        enter(Phase.Ponder)
    }

    /** The next stroke that has come back, if any: a dip first when it's in another colour. */
    private fun next() {
        val w = work ?: return
        while (queue.isNotEmpty()) {
            val up = queue.removeFirst()
            val art = up.art
            val changed = (0 until art.size * art.size).filter { w.rgb[it] != art.rgb(it % art.size, it / art.size) }
            if (changed.isEmpty()) {
                shown = up.strokes
                continue
            }
            target = up
            cells = path(changed, art.size)
            on = 0
            took = (STROKE_BASE + STROKE_PER_CELL * cells.size).coerceIn(STROKE_MIN, STROKE_MAX)
            val next = up.palette[up.crayon ?: 0]
            if (next != color) enter(Phase.Dip) else enter(Phase.Stroke)
            return
        }
    }

    /** Puts cells on along the path; strokes waiting behind this one hurry it along. */
    private fun paint(delta: Float, hurry: Boolean) {
        val w = work!!
        val up = target!!
        val speed = (1f + queue.size) * (if (hurry) HURRY else 1f)
        val due = (cells.size * (phaseTime * speed / took)).toInt().coerceAtMost(cells.size)
        if (due > on) {
            for (i in on until due) {
                val c = cells[i]
                w.set(c % up.art.size, c / up.art.size, up.art.rgb(c % up.art.size, c / up.art.size))
            }
            on = due
            w.upload()
        }
        val c = cells[on.coerceAtMost(cells.size - 1)]
        val tu = (c % up.art.size + 0.5f) / up.art.size
        val tv = (c / up.art.size + 0.5f) / up.art.size
        // A jump of more than a couple of cells lifts the brush across.
        lifted = abs(tu - u) + abs(tv - v) > LIFT_JUMP / up.art.size
        val k = (delta * FOLLOW).coerceAtMost(1f)
        u += (tu - u) * k
        v += (tv - v) * k
        if (on >= cells.size) {
            shown = up.strokes
            target = null
            lifted = true
            enter(Phase.Ponder)
            next()
        }
    }

    private fun enter(p: Phase) {
        phase = p
        phaseTime = 0f
    }

    private fun reset() {
        piece = null
        tick = null
        queue.clear()
        target = null
        enter(Phase.Ponder)
    }

    /**
     * [cells] in the order a brush would cover them: across the stroke's longer side a column (or
     * row) at a time, up and down alternately, so a fill goes on in back-and-forth sweeps and a line
     * from one end to the other.
     */
    private fun path(cells: List<Int>, size: Int): IntArray {
        val xs = cells.map { it % size }
        val ys = cells.map { it / size }
        val wide = xs.max() - xs.min() >= ys.max() - ys.min()
        return cells.sortedWith(compareBy<Int> { if (wide) it % size else it / size }.thenBy { c ->
            val along = if (wide) c % size else c / size
            val across = if (wide) c / size else c % size
            if (along % 2 == 0) across else -across
        }).toIntArray()
    }

    /** The canvas: its cells' colours and their texture. */
    private class Work(val size: Int) : Disposable {
        val rgb = IntArray(size * size)
        val pixmap = Pixmap(size, size, Pixmap.Format.RGBA8888)
        val texture = Texture(pixmap).apply { setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest) }

        fun set(x: Int, y: Int, color: Int) {
            rgb[y * size + x] = color
            pixmap.drawPixel(x, y, (color shl 8) or 0xff)
        }

        fun upload() = texture.draw(pixmap, 0, 0)

        override fun dispose() {
            texture.dispose()
            pixmap.dispose()
        }
    }

    override fun dispose() {
        work?.dispose()
        work = null
    }

    private companion object {
        /** A stroke takes this long plus a little a cell, within these bounds (seconds), before hurrying. */
        const val STROKE_BASE = 0.6f
        const val STROKE_PER_CELL = 0.02f
        const val STROKE_MIN = 0.9f
        const val STROKE_MAX = 3.2f
        /** Seconds to load the brush with a new colour, and to throw his arms up at the end. */
        const val DIP = 0.9f
        const val FLOURISH = 1.8f
        /** How much faster everything goes once the clock waits on it. */
        const val HURRY = 2f
        /** How fast the brush catches up with the cell going on (per second), and the jump (cells) that lifts it. */
        const val FOLLOW = 18f
        const val LIFT_JUMP = 2.5f
    }
}
