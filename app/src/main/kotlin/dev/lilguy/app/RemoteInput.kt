package dev.lilguy.app

import com.badlogic.gdx.Input.Keys
import com.badlogic.gdx.InputAdapter

/**
 * TV remote (and keyboard) controls for the dev view. Nothing here moves the guy; the agent is
 * the only driver. Back is caught by the launcher so it toggles detail instead of leaving the
 * app; Home leaves.
 */
class RemoteInput(private val session: () -> SimSession, private val view: DevView) : InputAdapter() {
    override fun keyDown(keycode: Int): Boolean {
        val s = session()
        when (keycode) {
            Keys.CENTER, Keys.ENTER, Keys.NUMPAD_ENTER, Keys.SPACE, Keys.BUTTON_A, Keys.MEDIA_PLAY_PAUSE -> s.paused = !s.paused
            Keys.BACK, Keys.ESCAPE, Keys.BACKSPACE, Keys.BUTTON_B -> view.detail = !view.detail
            Keys.UP -> view.scroll--
            Keys.DOWN -> view.scroll++
            Keys.RIGHT -> s.step(1)
            Keys.LEFT -> s.step(-1)
            Keys.R -> s.reset()
            else -> return false
        }
        return true
    }
}
