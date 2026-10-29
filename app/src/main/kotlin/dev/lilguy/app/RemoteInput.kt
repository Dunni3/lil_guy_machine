package dev.lilguy.app

import com.badlogic.gdx.Input.Keys
import com.badlogic.gdx.InputAdapter

/**
 * TV remote (and keyboard) controls for the dev view. Nothing here moves the guy; the agent is
 * the only driver.
 *
 * - OK (Enter, Space, play/pause): start a ready run, pause, resume.
 * - LEFT/RIGHT: flip pages. UP/DOWN: scroll, or step through decisions on the History page.
 * - BACK twice: throw the run away for a fresh one, which waits for OK. Any other key between
 *   the two presses cancels. R on a keyboard restarts at once. Back is caught by the launcher so
 *   it never leaves the app; Home leaves.
 */
class RemoteInput(private val session: () -> SimSession, private val view: DevView) : InputAdapter() {
    override fun keyDown(keycode: Int): Boolean {
        val s = session()
        val confirming = view.confirmRestart
        view.confirmRestart = false
        when (keycode) {
            Keys.BACK, Keys.ESCAPE, Keys.BACKSPACE, Keys.BUTTON_B -> if (confirming) restart(s) else view.confirmRestart = true
            Keys.R -> restart(s)
            Keys.CENTER, Keys.ENTER, Keys.NUMPAD_ENTER, Keys.SPACE, Keys.BUTTON_A, Keys.MEDIA_PLAY_PAUSE -> if (!confirming) s.toggle()
            Keys.LEFT -> view.flip(-1)
            Keys.RIGHT -> view.flip(1)
            Keys.UP -> view.move(-1, s)
            Keys.DOWN -> view.move(1, s)
            Keys.PERIOD -> s.step(1)
            else -> return confirming
        }
        return true
    }

    private fun restart(s: SimSession) {
        s.restart()
        view.reset()
    }
}
