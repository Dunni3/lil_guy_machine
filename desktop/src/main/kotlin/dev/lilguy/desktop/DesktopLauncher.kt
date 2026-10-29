package dev.lilguy.desktop

import com.badlogic.gdx.ApplicationListener
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.graphics.Pixmap
import dev.lilguy.app.LilGuyGame

fun main() {
    val config = Lwjgl3ApplicationConfiguration().apply {
        setTitle("lil guy machine")
        setWindowedMode(1280, 720)
        useVsync(true)
        setForegroundFPS(60)
        setBackBufferConfig(8, 8, 8, 8, 16, 0, 4)
    }
    val game = LilGuyGame()
    val shotPath = System.getenv("LILGUY_SCREENSHOT")
    val listener = if (shotPath == null) game else Screenshot(game, shotPath, System.getenv("LILGUY_SCREENSHOT_AT")?.toFloatOrNull() ?: 5f)
    Lwjgl3Application(listener, config)
}

/** Dev aid: LILGUY_SCREENSHOT=out.png [LILGUY_SCREENSHOT_AT=seconds] saves one frame, then quits. */
private class Screenshot(private val game: ApplicationListener, private val path: String, private val at: Float) :
    ApplicationListener by game {
    private var elapsed = 0f

    override fun render() {
        game.render()
        elapsed += Gdx.graphics.deltaTime
        if (elapsed >= at) {
            val pixmap = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.backBufferWidth, Gdx.graphics.backBufferHeight)
            PixmapIO.writePNG(Gdx.files.absolute(path), pixmap, -1, true)
            pixmap.dispose()
            Gdx.app.exit()
            elapsed = Float.NEGATIVE_INFINITY
        }
    }
}
