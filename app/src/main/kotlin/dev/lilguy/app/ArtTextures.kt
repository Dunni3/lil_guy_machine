package dev.lilguy.app

import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.utils.Disposable
import dev.lilguy.agent.art.Artwork
import dev.lilguy.agent.art.Drawing
import kotlin.random.Random

/**
 * One texture per drawn piece of the current run, shared by the 3D room and the dev view's ART
 * page. Built on the render thread the first time a piece is shown, then kept: a piece's picture
 * never changes, so the GPU only sees an upload when he finishes one (16 or 32 cells square,
 * nearest-filtered so the cells stay crisp). The piece under way has one more, redrawn as he works
 * ([partial]). [sync] drops the textures of a finished run.
 */
class ArtTextures : Disposable {
    /** The run these textures belong to. */
    var run = -1
        private set
    private val textures = HashMap<Int, Texture>()
    private var working: Working? = null

    /** Throws away the textures when [session] has moved on to another run. */
    fun sync(session: SimSession) {
        if (session.run == run) return
        dispose()
        run = session.run
    }

    /** Drawn pieces of [session] by piece number. */
    fun drawings(session: SimSession): Map<Int, Drawing> =
        session.memory.studio?.pieces()?.mapNotNull { d -> d.piece?.let { it to d } }?.toMap().orEmpty()

    /** The texture for a kept drawing; null when it has no picture or piece. */
    fun of(d: Drawing): Texture? {
        val piece = d.piece ?: return null
        val art = d.artwork ?: return null
        return textures.getOrPut(piece) { build(art) }
    }

    /**
     * The drawing he's making, [progress] (0..1) of the way through: its cells come in a colour at a
     * time, the commonest (the wash) first, each colour from the top down with a little scatter, on
     * a blank of [blank] (0xRRGGBB). One texture, redrawn in [REVEAL_STEPS] steps; null with no picture.
     */
    fun partial(d: Drawing, progress: Float, blank: Int): Texture? {
        val art = d.artwork ?: return null
        val step = (progress.coerceIn(0f, 1f) * REVEAL_STEPS).toInt()
        val w = working?.takeIf { it.drawing === d } ?: Working(d, art).also { working?.dispose(); working = it }
        if (w.step != step) {
            w.step = step
            val shown = w.order.size * step / REVEAL_STEPS
            for (i in w.order.indices) {
                val cell = w.order[i]
                val x = cell % art.size
                val y = cell / art.size
                w.pixmap.drawPixel(x, y, ((if (i < shown) art.rgb(x, y) else blank) shl 8) or 0xff)
            }
            w.texture.draw(w.pixmap, 0, 0)
        }
        return w.texture
    }

    /** The piece being made: its picture, the order its cells come in, and its texture. */
    private class Working(val drawing: Drawing, art: Artwork) : Disposable {
        var step = -1
        val pixmap = Pixmap(art.size, art.size, Pixmap.Format.RGBA8888)
        val texture = Texture(pixmap).apply { setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest) }
        val order: IntArray

        init {
            val n = art.size * art.size
            val colour = IntArray(n) { art.rgb(it % art.size, it / art.size) }
            val count = colour.toList().groupingBy { it }.eachCount()
            val scatter = Random(drawing.tick)
            val key = FloatArray(n) { it / art.size + scatter.nextFloat() * 3f }
            order = (0 until n).sortedWith(compareByDescending<Int> { count.getValue(colour[it]) }.thenBy { colour[it] }.thenBy { key[it] }).toIntArray()
        }

        override fun dispose() {
            texture.dispose()
            pixmap.dispose()
        }
    }

    private fun build(art: Artwork): Texture {
        val pixmap = Pixmap(art.size, art.size, Pixmap.Format.RGBA8888)
        for (y in 0 until art.size) for (x in 0 until art.size) pixmap.drawPixel(x, y, (art.rgb(x, y) shl 8) or 0xff)
        return Texture(pixmap).also {
            it.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest)
            pixmap.dispose()
        }
    }

    override fun dispose() {
        textures.values.forEach { it.dispose() }
        textures.clear()
        working?.dispose()
        working = null
    }

    private companion object {
        const val REVEAL_STEPS = 24
    }
}
