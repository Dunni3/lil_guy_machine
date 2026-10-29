package dev.lilguy.agent

import dev.lilguy.sim.Intent
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KeyDoorRunTest {
    @Test
    fun `scripted policy solves the room through the agent loop`() {
        val decisions = mutableListOf<AgentRuntime.DecisionRecord>()
        val (world, runtime) = keyDoorScenario(KeyDoorPolicy()) { decisions += it }
        val ticks = runBlocking { runLockstep(world, runtime, maxTicks = 2_000) }

        assertTrue(world.snapshot().completed, "not solved after $ticks ticks")
        val kinds = decisions.map { it.intent::class }
        assertEquals(
            listOf(Intent.Say::class, Intent.MoveTo::class, Intent.PickUp::class, Intent.MoveTo::class, Intent.Use::class, Intent.MoveTo::class),
            kinds,
        )
        assertTrue(decisions.all { it.error == null && it.rationale != null })
        assertEquals(runtime.lastDecision, decisions.last())
    }

    @Test
    fun `lockstep runs are deterministic`() {
        fun run(): Long {
            val (world, runtime) = keyDoorScenario(KeyDoorPolicy())
            return runBlocking { runLockstep(world, runtime, maxTicks = 2_000) }
        }
        assertEquals(run(), run())
    }

    @Test
    fun `the policy only sees the prompt`() {
        val (world, _) = keyDoorScenario(KeyDoorPolicy())
        val request = PromptBuilder(KEY_DOOR_GOAL).build(world.observe(world.agentId), emptyList())
        assertEquals(world.observe(world.agentId), PromptBuilder.observationOf(request))
    }
}
