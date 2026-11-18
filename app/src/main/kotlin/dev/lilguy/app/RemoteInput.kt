package dev.lilguy.app

import dev.lilguy.agent.live.SimSession

import com.badlogic.gdx.Input.Keys
import com.badlogic.gdx.InputAdapter

/**
 * TV remote (and keyboard) controls. Nothing here moves the guy; the agent is the only driver.
 *
 * - In the room, OK (Enter, Space) opens the in-game menu ([GameMenu]), which has the keys until
 *   BACK closes it: run controls, the shop, his thoughts, memories, self-model and shopping trips.
 * - PLAY/PAUSE: start a ready run, pause, resume, from anywhere.
 * - LEFT/RIGHT: flip to the dev view's pages and back. On them, OK starts, pauses and resumes,
 *   UP/DOWN scroll or step through decisions (History) and pieces (Art); on the Run page DOWN
 *   moves into the settings, LEFT/RIGHT change one, OK twice on Restart or Replay.
 * - BACK twice (menu closed): throw the run away for a fresh one, which waits for a start. Any
 *   other key between the two presses cancels. R on a keyboard restarts at once. Back is caught
 *   by the launcher so it never leaves the app; Home leaves.
 */
class RemoteInput(private val session: () -> SimSession, private val view: DevView, private val menu: GameMenu) : InputAdapter() {
    override fun keyDown(keycode: Int): Boolean {
        val s = session()
        when (keycode) {
            Keys.MEDIA_PLAY_PAUSE -> return true.also { s.toggle() }
            Keys.R -> return true.also { restart(s) }
            Keys.PERIOD -> return true.also { s.step(1) }
        }
        if (menu.open) {
            val press = when (keycode) {
                in BACK -> GameMenu.Press.Back
                in OK -> GameMenu.Press.Ok
                Keys.LEFT -> GameMenu.Press.Left
                Keys.RIGHT -> GameMenu.Press.Right
                Keys.UP -> GameMenu.Press.Up
                Keys.DOWN -> GameMenu.Press.Down
                else -> return true
            }
            menu.press(press, s)
            return true
        }
        val confirming = view.confirmRestart
        val confirmingAction = view.confirmAction
        view.confirmRestart = false
        view.confirmAction = false
        when (keycode) {
            in BACK -> if (confirming) restart(s) else view.confirmRestart = true
            in OK -> when {
                confirming -> Unit
                view.page == DevView.Page.Room -> menu.show(s)
                !view.activate(s, confirmingAction) -> s.toggle()
            }
            Keys.LEFT -> if (!view.adjust(-1, s)) view.flip(-1)
            Keys.RIGHT -> if (!view.adjust(1, s)) view.flip(1)
            Keys.UP -> view.move(-1, s)
            Keys.DOWN -> view.move(1, s)
            else -> return confirming || confirmingAction
        }
        return true
    }

    private fun restart(s: SimSession) {
        s.restart()
        view.reset()
        menu.reset()
    }

    private companion object {
        val BACK = setOf(Keys.BACK, Keys.ESCAPE, Keys.BACKSPACE, Keys.BUTTON_B)
        val OK = setOf(Keys.CENTER, Keys.ENTER, Keys.NUMPAD_ENTER, Keys.SPACE, Keys.BUTTON_A)
    }
}
