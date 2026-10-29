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
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

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
    private val onDecision: (DecisionRecord) -> Unit = {},
) {
    /** One trip through the loop, for logs and debug views. */
    data class DecisionRecord(
        val tick: Long,
        val intent: Intent,
        val rationale: String?,
        val raw: String?,
        val latency: Duration,
        /** Set when the model failed and [intent] came from the fallback. */
        val error: String? = null,
    )

    val modelId: String get() = model.id

    /** The latest decision; written by the agent coroutine, readable from any thread. */
    @Volatile var lastDecision: DecisionRecord? = null
        private set

    /** When the in-flight decide() started, or null when the agent isn't waiting on the model. */
    @Volatile var decidingSince: TimeMark? = null
        private set

    suspend fun run() = coroutineScope {
        while (isActive) {
            world.awaitDecisionPoint(id)
            val obs = world.observe(id)
            val request = prompts.build(obs, memory.recent())
            val started = TimeSource.Monotonic.markNow()
            decidingSince = started
            val record = try {
                val decision = withTimeout(request.timeout) { model.decide(request, schema) }
                DecisionRecord(obs.tick, decision.value, decision.rationale, decision.raw, decision.latency)
            } catch (e: CancellationException) {
                if (!isActive) throw e
                DecisionRecord(obs.tick, fallback(e), null, null, started.elapsedNow(), "timed out after ${request.timeout}")
            } catch (e: Exception) {
                DecisionRecord(obs.tick, fallback(e), null, null, started.elapsedNow(), e.message ?: e.toString())
            } finally {
                decidingSince = null
            }
            memory.record(obs.tick, record.intent)
            lastDecision = record
            onDecision(record)
            world.submit(id, record.intent)
        }
    }
}
