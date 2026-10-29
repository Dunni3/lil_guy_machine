package dev.lilguy.desktop

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import dev.lilguy.app.LilGuyGame

fun main() {
    val config = Lwjgl3ApplicationConfiguration().apply {
        setTitle("lil guy machine")
        setWindowedMode(1280, 720)
        useVsync(true)
        setForegroundFPS(60)
        setBackBufferConfig(8, 8, 8, 8, 16, 0, 4)
    }
    Lwjgl3Application(LilGuyGame(), config)
}
