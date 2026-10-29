package dev.lilguy.agent

import dev.lilguy.decision.ActionSchema
import dev.lilguy.decision.DecisionModel
import dev.lilguy.sim.EntityId
import dev.lilguy.sim.Intent
import dev.lilguy.sim.World
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout

/**
 * Drives one character. Runs in its own coroutine and only touches the sim through
 * observe/submit, so a slow model never blocks a frame or a tick.
 */
class AgentRuntime(
    private val world: World,
    private val id: EntityId,
    private val model: DecisionModel,
    private val schema: ActionSchema<Intent>,
    private val prompts: PromptBuilder,
    private val memory: Memory = Memory(),
    private val fallback: (Throwable) -> Intent = { Intent.Wait(World.TICKS_PER_SECOND) },
) {
    suspend fun run() = coroutineScope {
        while (isActive) {
            world.awaitDecisionPoint(id)
            val obs = world.observe(id)
            val request = prompts.build(obs, memory.recent())
            val intent = try {
                withTimeout(request.timeout) { model.decide(request, schema) }.value
            } catch (e: CancellationException) {
                if (!isActive) throw e
                fallback(e) // timed out
            } catch (e: Exception) {
                fallback(e)
            }
            memory.record(obs.tick, intent)
            world.submit(id, intent)
        }
    }
}
