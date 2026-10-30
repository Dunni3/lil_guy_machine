package dev.lilguy.app

import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.utils.Disposable
import dev.lilguy.agent.art.Artwork
import dev.lilguy.agent.art.Drawing

/**
 * One texture per drawn piece of the current run, shared by the 3D room and the dev view's ART
 * page. Built on the render thread the first time a piece is shown, then kept: a piece's picture
 * never changes, so the GPU only sees an upload when he finishes one (16 or 32 cells square,
 * nearest-filtered so the cells stay crisp). [sync] drops the textures of a finished run.
 */
class ArtTextures : Disposable {
    /** The run these textures belong to. */
    var run = -1
        private set
    private val textures = HashMap<Int, Texture>()

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
    }
}
