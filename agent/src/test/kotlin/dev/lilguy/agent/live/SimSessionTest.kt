package dev.lilguy.agent.live

import dev.lilguy.agent.KeyDoorPolicy
import dev.lilguy.agent.LifeRecorder
import dev.lilguy.agent.Scenario
import dev.lilguy.agent.live.SimSession.RunState
import dev.lilguy.runlog.jdbc.JdbcSqlDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class SimSessionTest {
    private fun session(setup: ModelSetup = ModelSetup({ KeyDoorPolicy(0.milliseconds) }, Scenario.KeyDoor), block: (SimSession) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val s = SimSession(scope, setup)
        try { block(s) } finally { s.dispose(); scope.cancel() }
    }

    /** Ticks by hand while paused, giving the agent a moment between ticks, until [done] or [limit] ticks. */
    private fun SimSession.stepUntil(limit: Int = 400, done: () -> Boolean) {
        var n = 0
        while (!done() && n++ < limit) { step(1); Thread.sleep(3) }
    }

    @Test
    fun `a new run waits for start`() = session { s ->
        assertEquals(RunState.Ready, s.state)
        repeat(20) { s.update(0.1f) }
        Thread.sleep(50)
        assertEquals(0L, s.current.tick)
        assertNull(s.decision, "the agent doesn't run before start")
        s.step(5)
        assertEquals(0L, s.current.tick, "stepping only works while paused")
    }

    @Test
    fun `start runs, pause stops the clock, toggle resumes`() = session { s ->
        s.toggle()
        assertEquals(RunState.Running, s.state)
        s.update(0.2f)
        val ticked = s.current.tick
        assertTrue(ticked > 0)
        s.toggle()
        assertEquals(RunState.Paused, s.state)
        s.update(0.2f)
        assertEquals(ticked, s.current.tick)
        s.step(2)
        assertEquals(ticked + 2, s.current.tick)
        s.toggle()
        assertEquals(RunState.Running, s.state)
    }

    @Test
    fun `restart is a blank run that waits for start`() = session { s ->
        s.start()
        s.pause()
        s.stepUntil { s.history.size >= 2 }
        assertTrue(s.history.isNotEmpty())
        s.restart()
        assertEquals(2, s.run)
        assertEquals(RunState.Ready, s.state)
        assertEquals(0L, s.current.tick)
        assertTrue(s.history.isEmpty())
        assertNull(s.decision)
        assertEquals(0, s.memory.eventCount())
    }

    @Test
    fun `each decision is kept with the world it was made in`() = session { s ->
        s.start()
        s.pause()
        s.stepUntil { s.history.size >= 3 }
        assertTrue(s.history.size >= 3)
        s.history.forEach { assertEquals(it.decision.tick, it.snapshot.tick) }
        // An outcome per finished intent (the latest is still running), plus what happened around him.
        assertTrue(s.memory.eventCount() in s.history.size - 1..2 * s.history.size, "${s.memory.eventCount()} events for ${s.history.size} decisions")
    }

    @Test
    fun `a solved room ends the run and no new one starts`() = session { s ->
        s.start()
        s.pause()
        s.stepUntil(limit = 2000) { s.current.completed }
        assertEquals(RunState.Ended, s.state)
        val endTick = s.current.tick
        s.toggle()
        repeat(20) { s.update(0.25f) }
        assertEquals(RunState.Ended, s.state, "OK doesn't restart an ended run")
        assertEquals(endTick, s.current.tick)
        assertEquals(1, s.run)
    }

    @Test
    fun `a bedroom run ends after its days`() = session(ModelSetup({ Scenario.Bedroom.scriptedPolicy() }, Scenario.Bedroom, ticksPerMinute = 1, days = 1)) { s ->
        s.start()
        s.pause()
        s.step(2000)
        assertEquals(RunState.Ended, s.state)
        assertEquals(24L * 60, s.current.tick)
        assertEquals("lived 1 day", s.endedBecause)

        s.configure(s.settings.copy(days = 2))
        assertEquals(RunState.Paused, s.state, "more days reopen an ended run")
        s.step(2000)
        assertEquals(RunState.Ended, s.state)
        assertEquals(2L * 24 * 60, s.current.tick)
        assertEquals("lived 2 days", s.endedBecause)
    }

    @Test
    fun `a run left mid-day is there to go back to, and a host restart carries it on`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = JdbcSqlDriver.store(null)
        val setup = ModelSetup({ Scenario.Bedroom.scriptedPolicy() }, Scenario.Bedroom, ticksPerMinute = 1, days = 3)
        val s = SimSession(scope, setup, RunDatabase(store, sync = null, platform = "test", host = null))
        val first = s.runId
        s.start()
        s.pause()
        // A few decisions in, all on day 1 (no morning checkpoint yet).
        s.stepUntil { s.history.size >= 8 }
        val left = s.current.tick
        assertTrue(left in 1 until 16L * 60, "still day 1: $left")

        s.restart()
        assertTrue(s.runId != first)
        assertTrue(s.resume(first), s.resumeError ?: "")
        assertEquals(first, s.runId)
        val back = s.current.tick
        // From the rolling save: at most its gap plus the thing he was partway through.
        assertTrue(back > 0 && left - back < 3 * LifeRecorder.AUTOSAVE_MINUTES + 60, "carried on near where it was left ($left), not from the start: $back")

        // The host goes down with it running; the next one carries it on and starts it again.
        s.start()
        s.dispose()
        val again = SimSession(scope, setup, RunDatabase(store, sync = null, platform = "test", host = null, resume = "latest"))
        try {
            assertEquals(first, again.runId, "latest is the run played most recently, not the newest one")
            assertTrue(again.wasRunning)
        } finally {
            again.dispose()
            scope.cancel()
            store.close()
        }
    }
}
