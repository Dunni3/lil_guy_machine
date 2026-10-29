package dev.lilguy.app

import dev.lilguy.agent.KeyDoorPolicy
import dev.lilguy.agent.Scenario
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class SimSessionTest {
    private fun session(block: (SimSession) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val s = SimSession(scope, ModelSetup({ KeyDoorPolicy(0.milliseconds) }, Scenario.KeyDoor))
        try { block(s) } finally { s.dispose(); scope.cancel() }
    }

    @Test
    fun `step back and forward move the view one tick each`() = session { s ->
        s.paused = true
        s.step(5)
        assertEquals(5L, s.current.tick)

        s.step(-1)
        assertEquals(4L, s.current.tick)
        s.step(-1)
        assertEquals(3L, s.current.tick)
        s.step(1)
        assertEquals(4L, s.current.tick)
        s.step(1)
        s.step(1)
        assertEquals(6L, s.current.tick, "forward past the live tick advances the world")
    }

    @Test
    fun `stepping back stops at the start of the episode`() = session { s ->
        s.paused = true
        s.step(3)
        s.step(-10)
        assertEquals(0L, s.current.tick)
    }

    @Test
    fun `resuming returns to the live tick`() = session { s ->
        s.paused = true
        s.step(4)
        s.step(-3)
        s.paused = false
        assertEquals(4L, s.current.tick)
    }

    @Test
    fun `scrubbing back shows the decision as it was at that tick`() = session { s ->
        s.paused = true
        // Tick until the agent has made its first decision, giving it a moment between ticks.
        var guard = 0
        while (s.decision == null && guard++ < 200) { s.step(1); Thread.sleep(5) }
        val liveTick = s.current.tick
        val decidedAt = checkNotNull(s.decision).tick
        s.step(-liveTick.toInt())
        assertEquals(0L, s.current.tick)
        assertEquals(null, s.decision, "no decision existed at tick 0")
        s.step(liveTick.toInt())
        assertEquals(decidedAt, checkNotNull(s.decision).tick)
    }
}
