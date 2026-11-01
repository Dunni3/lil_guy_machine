package dev.lilguy.desktop

import com.badlogic.gdx.ApplicationListener
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
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
    // LILGUY_MEMORY_ENDPOINT names the memory writer (default: the config's memory_endpoint;
    // "none" for off); LILGUY_WRITER_ENDPOINT the writer of his thoughts and self-model, the same way
    // with the config's writer_endpoint (LILGUY_JOURNAL_ENDPOINT is its old name); LILGUY_RECALL_ENDPOINT
    // the typesafe endpoint that scores recall ("none" for keywords); LILGUY_DAYS the bedroom run length (0 for no end).
    // LILGUY_BEAM sets how many paths Jev keeps walking the menu tree; 0 picks from the flat list.
    // LILGUY_START_ITEMS adds to what he starts with, for screenshots: "novel,folk_record,guitar" or "meal=4".
    val setup = ModelSetup.fromConfig(
        configFile = File(System.getenv("LILGUY_CONFIG") ?: "config/endpoints.yaml"),
        endpoint = System.getenv("LILGUY_ENDPOINT")?.takeIf { it.isNotBlank() },
        env = System::getenv,
        logRoot = File("runs"),
        scenario = System.getenv("LILGUY_SCENARIO")?.takeIf { it.isNotBlank() }?.let(Scenario::parse) ?: Scenario.Bedroom,
        ticksPerMinute = System.getenv("LILGUY_MINUTE_TICKS")?.toIntOrNull() ?: GameClock.DEFAULT_TICKS_PER_MINUTE,
        memoryEndpoint = System.getenv("LILGUY_MEMORY_ENDPOINT")?.takeIf { it.isNotBlank() },
        writerEndpoint = (System.getenv("LILGUY_WRITER_ENDPOINT") ?: System.getenv("LILGUY_JOURNAL_ENDPOINT"))?.takeIf { it.isNotBlank() },
        recallEndpoint = System.getenv("LILGUY_RECALL_ENDPOINT")?.takeIf { it.isNotBlank() },
        beam = System.getenv("LILGUY_BEAM")?.toIntOrNull(),
        days = System.getenv("LILGUY_DAYS")?.toIntOrNull().let { if (it == null) ModelSetup.DEFAULT_DAYS else it.takeIf { d -> d > 0 } },
    ).let { s ->
        val extra = System.getenv("LILGUY_START_ITEMS").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            .associate { it.substringBefore('=') to (it.substringAfter('=', "1").toIntOrNull() ?: 1) }
        if (extra.isEmpty()) s else s.copy(startItems = extra)
    }
    val game = LilGuyGame(setup)
    val shotPath = System.getenv("LILGUY_SCREENSHOT")
    val listener = if (shotPath == null) game else Screenshot(game, shotPath, System.getenv("LILGUY_SCREENSHOT_AT")?.toFloatOrNull() ?: 5f, System.getenv("LILGUY_SCREENSHOT_KEYS").orEmpty())
    Lwjgl3Application(listener, config)
}

/**
 * Dev aid: LILGUY_SCREENSHOT=out.png [LILGUY_SCREENSHOT_AT=seconds] saves one frame, then quits.
 * LILGUY_SCREENSHOT_KEYS presses keys first, a comma-separated list of names as libGDX spells
 * them, `@N` meaning "at second N" (e.g. `Enter,@20,Right,Down`).
 */
private class Screenshot(private val game: ApplicationListener, private val path: String, private val at: Float, keys: String) :
    ApplicationListener by game {
    private var elapsed = 0f
    private val script = ArrayDeque(keys.split(',').map { it.trim() }.filter { it.isNotEmpty() })
    private var waitUntil = 0f

    override fun render() {
        while (script.isNotEmpty() && elapsed >= waitUntil) {
            val next = script.removeFirst()
            if (next.startsWith("@")) {
                waitUntil = next.drop(1).toFloat()
            } else {
                Gdx.input.inputProcessor?.keyDown(Input.Keys.valueOf(next))
            }
        }
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
