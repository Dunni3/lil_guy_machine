package dev.lilguy.agent.live

import dev.lilguy.agent.art.Underway

/**
 * How the piece he's making plays out on screen, decided next to the clock so every viewer sees
 * the same thing at the same time. The model's strokes come back at its own pace
 * ([dev.lilguy.agent.art.ArtStudio.underway]); each goes on over a stroke's worth of real seconds
 * (a little more for a bigger stroke, less when strokes are waiting behind it or the clock waits on
 * it), with a dip first when it's in another crayon. With nothing new he ponders; when the piece is
 * done, a flourish, then he admires it.
 *
 * [SimSession] holds the clock while the show is [finishing] after the piece's verb has ended, so a
 * piece is never cut off; renderers play [current] over [phaseSeconds] instead of keeping their own
 * pace. Pure bookkeeping: no pixels.
 */
class ArtShow {
    enum class Phase { Ponder, Dip, Stroke, Flourish, Admire }

    var phase = Phase.Ponder
        private set
    /** Real seconds into [phase], and how long it lasts (0 for one that waits on the model). */
    var phaseTime = 0f
        private set
    var phaseSeconds = 0f
        private set

    /** The picture as far as the screen has got: after the stroke going on now, if one is. Null with no piece. */
    var current: Underway? = null
        private set

    /** The latest word from the studio: [Underway.strokes] there is how far the model has got. */
    var latest: Underway? = null
        private set

    /** [current], or [latest] once the screen has caught up with it (so how the piece ended shows). */
    val presented: Underway? get() {
        val c = current ?: return null
        return latest?.takeIf { it.tick == c.tick && it.strokes == c.strokes } ?: c
    }

    /** Strokes wholly on screen. */
    var shown = 0
        private set

    /** True while strokes that have come back are still going on, or he's in a dip or the flourish. */
    val finishing: Boolean get() = queue.isNotEmpty() || phase == Phase.Dip || phase == Phase.Stroke || phase == Phase.Flourish

    private var tick: Long? = null
    private val queue = ArrayDeque<Underway>()
    private var queued = 0
    private var flourished = false
    private var crayon: Int? = null

    /** Advances [delta] real seconds with [underway] the studio's latest; [hurry] when the clock waits on the show. */
    fun update(delta: Float, underway: Underway?, hurry: Boolean) {
        if (underway == null || underway.state == Underway.State.Dropped) return reset()
        if (underway.tick != tick) start(underway)
        if (underway.strokes > queued) {
            queue += underway
            queued = underway.strokes
        }
        latest = underway
        phaseTime += delta
        when (phase) {
            Phase.Dip -> if (phaseTime >= phaseSeconds) enter(Phase.Stroke, strokeSeconds)
            Phase.Stroke -> if (phaseTime >= phaseSeconds) {
                shown = current!!.strokes
                enter(Phase.Ponder, 0f)
                next(hurry)
            }
            Phase.Flourish -> if (phaseTime >= phaseSeconds) enter(Phase.Admire, 0f)
            Phase.Ponder, Phase.Admire -> next(hurry)
        }
        if (phase == Phase.Ponder && !flourished && queue.isEmpty() && underway.state == Underway.State.Done) {
            flourished = true
            enter(Phase.Flourish, FLOURISH)
        }
    }

    /** The verb that is making the current piece, if the studio said. */
    val verb: String? get() = (latest ?: current)?.verb

    private var strokeSeconds = 0f

    /** A new piece: the canvas as it stands when first seen (blank paper, normally). */
    private fun start(underway: Underway) {
        tick = underway.tick
        current = underway
        queue.clear()
        queued = underway.strokes
        shown = underway.strokes
        // Seen first part way through (the host just started), it doesn't flourish late.
        flourished = underway.strokes > 0
        crayon = underway.crayon
        enter(Phase.Ponder, 0f)
    }

    /** The next stroke that has come back, if any: a dip first when it's in another colour. */
    private fun next(hurry: Boolean) {
        while (queue.isNotEmpty()) {
            val up = queue.removeFirst()
            val was = current!!.art
            val art = up.art
            val changed = if (was.size != art.size) art.size * art.size
            else (0 until art.size * art.size).count { was.rgb(it % art.size, it / art.size) != art.rgb(it % art.size, it / art.size) }
            current = up
            if (changed == 0) {
                shown = up.strokes
                continue
            }
            val speed = (1f + queue.size) * (if (hurry) HURRY else 1f)
            strokeSeconds = (STROKE_BASE + STROKE_PER_CELL * changed).coerceIn(STROKE_MIN, STROKE_MAX) / speed
            if (up.crayon != crayon) {
                crayon = up.crayon
                enter(Phase.Dip, DIP / (if (hurry) HURRY else 1f))
            } else enter(Phase.Stroke, strokeSeconds)
            return
        }
    }

    private fun enter(p: Phase, seconds: Float) {
        phase = p
        phaseTime = 0f
        phaseSeconds = seconds
    }

    private fun reset() {
        current = null
        latest = null
        tick = null
        queue.clear()
        queued = 0
        shown = 0
        enter(Phase.Ponder, 0f)
    }

    companion object {
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
    }
}
