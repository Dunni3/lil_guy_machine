package dev.lilguy.desktop

import com.badlogic.gdx.ApplicationListener
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.graphics.Pixmap
import dev.lilguy.app.LilGuyGame
import dev.lilguy.app.ModelSetup
import dev.lilguy.agent.Scenario
import dev.lilguy.sim.GameClock
import java.io.File

fun main() {
    val config = Lwjgl3ApplicationConfiguration().apply {
        setTitle("lil guy machine")
        setWindowedMode(1280, 720)
        useVsync(true)
        setForegroundFPS(60)
        setBackBufferConfig(8, 8, 8, 8, 16, 0, 4)
    }
    // LILGUY_ENDPOINT picks an endpoint from the config (LILGUY_CONFIG, default
    // config/endpoints.yaml); without it the config's default agent endpoint is used, and
    // without a config the scripted policy. Keys come from this process's environment.
    // LILGUY_SCENARIO is bedroom (default) or key-door; LILGUY_MINUTE_TICKS the clock scale.
    val setup = ModelSetup.fromConfig(
        configFile = File(System.getenv("LILGUY_CONFIG") ?: "config/endpoints.yaml"),
        endpoint = System.getenv("LILGUY_ENDPOINT")?.takeIf { it.isNotBlank() },
        env = System::getenv,
        logRoot = File("runs"),
        scenario = System.getenv("LILGUY_SCENARIO")?.takeIf { it.isNotBlank() }?.let(Scenario::parse) ?: Scenario.Bedroom,
        ticksPerMinute = System.getenv("LILGUY_MINUTE_TICKS")?.toIntOrNull() ?: GameClock.DEFAULT_TICKS_PER_MINUTE,
    )
    val game = LilGuyGame(setup)
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
