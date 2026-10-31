package dev.lilguy.agent

import dev.lilguy.agent.memory.AgentMemory
import dev.lilguy.agent.memory.Thought
import dev.lilguy.decision.ActionSchema
import dev.lilguy.decision.Attempt
import dev.lilguy.decision.DecisionFailed
import dev.lilguy.decision.DecisionModel
import dev.lilguy.decision.DecisionRequest
import dev.lilguy.decision.Usage
import dev.lilguy.sim.EntityId
import dev.lilguy.sim.Intent
import dev.lilguy.sim.Observation
import dev.lilguy.sim.World
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Drives one character. Runs in its own coroutine and only touches the sim through
 * observe/submit, so a slow model never blocks a frame or a tick.
 *
 * Each decision gets its own schema from [schemaFor], so the ids a model may name are the ones in
 * that observation.
 */
class AgentRuntime(
    private val world: World,
    private val id: EntityId,
    private val model: DecisionModel,
    private val prompts: PromptBuilder,
    private val schemaFor: (Observation) -> ActionSchema<AgentReply> = ::replySchema,
    private val memory: Memory = Memory(),
    /** Long-term memory: records the stream and supplies recalled memories; null for none. */
    private val longTerm: AgentMemory? = null,
    private val fallback: (Throwable) -> Intent = { Intent.Wait(World.TICKS_PER_SECOND) },
    /**
     * Makes him do this verb after this many decisions without it, without asking [model], when it's
     * on offer (not locked); null leaves it to the model. The bedroom's is think ([dev.lilguy.sim.Reflection]).
     */
    private val reflection: Pair<String, Int>? = null,
    private val onDecision: (DecisionRecord) -> Unit = {},
) {
    /** One trip through the loop, for logs, replays and debug views. */
    data class DecisionRecord(
        val tick: Long,
        val intent: Intent,
        val rationale: String?,
        /** The last reply from the model, valid or not; null when it sent none. */
        val raw: String?,
        val latency: Duration,
        /** Set when the model failed and [intent] came from the fallback. */
        val error: String? = null,
        val usage: Usage = Usage(),
        /** Every reply, with the validation error of each rejected one. */
        val attempts: List<Attempt> = emptyList(),
        val observation: Observation? = null,
        val request: DecisionRequest? = null,
        val schema: JsonObject? = null,
        /** The long-term memories put into this decision's prompt. */
        val recalled: List<String> = emptyList(),
        /** The journal entries put into this decision's prompt. */
        val recalledJournal: List<String> = emptyList(),
        /** When [intent]'s verb writes a thought before it starts: what he thought, or why there's no thought. */
        val thought: Thought? = null,
    ) {
        val fellBack: Boolean get() = error != null
    }

    val modelId: String get() = model.id

    /** The latest decision; written by the agent coroutine, readable from any thread. */
    @Volatile var lastDecision: DecisionRecord? = null
        private set

    /** Short-term memory as of the latest decision: the last few intents and how they ended. */
    @Volatile var recent: List<Memory.Entry> = emptyList()
        private set

    /** Decisions since he last did the [reflection] verb. */
    private var sinceReflection = 0

    /** When the in-flight decide() started, or null when the agent isn't waiting on the model. */
    @Volatile var decidingSince: TimeMark? = null
        private set

    suspend fun run() = coroutineScope {
        while (isActive) {
            world.awaitDecisionPoint(id)
            val obs = world.observe(id)
            memory.settle(obs.lastIntent, obs.lastResult)
            longTerm?.observed(obs)
            val recalled = longTerm?.recall(obs).orEmpty()
            val journal = longTerm?.recallJournal(obs).orEmpty()
            // His latest kept thought, shown in every prompt until the next one replaces it.
            val plan = longTerm?.plan?.let { t -> t.text?.let { "${prompts.stampOf(t.tick)}: $it" } }
            val request = prompts.build(obs, memory.recent(), closedChoice = model.capabilities.closedChoice, memories = recalled, journal = journal, thought = plan)
            val schema = schemaFor(obs)
            val started = TimeSource.Monotonic.markNow()
            decidingSince = started
            val base = DecisionRecord(obs.tick, Intent.Wait(0), null, null, Duration.ZERO, observation = obs, request = request, schema = schema.jsonSchema, recalled = recalled, recalledJournal = journal)
            val forced = reflection?.takeIf { (verb, every) -> sinceReflection >= every && obs.activities.orEmpty().any { it.id == verb && it.locked == null } }
            val record = if (forced != null) {
                decidingSince = null
                base.copy(intent = Intent.Do(forced.first), rationale = "Time to ${forced.first} (every ${forced.second} decisions).")
            } else try {
                val decision = withTimeout(request.timeout) { model.decide(request, schema) }
                base.copy(
                    intent = decision.value.intent,
                    rationale = decision.rationale,
                    raw = decision.raw,
                    latency = decision.latency,
                    usage = decision.usage,
                    attempts = decision.attempts,
                )
            } catch (e: TimeoutCancellationException) {
                base.copy(intent = fallback(e), latency = started.elapsedNow(), error = "timed out after ${request.timeout}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: DecisionFailed) {
                base.copy(
                    intent = fallback(e),
                    raw = e.attempts.lastOrNull()?.raw,
                    latency = started.elapsedNow(),
                    error = e.message ?: e.toString(),
                    usage = e.usage,
                    attempts = e.attempts,
                )
            } catch (e: Exception) {
                base.copy(intent = fallback(e), latency = started.elapsedNow(), error = e.message ?: e.toString())
            } finally {
                decidingSince = null
            }
            val brief = prompts.persona?.let { AgentMemory.Brief(it, PromptBuilder.situationOf(request), request.system) }
            // A verb's `before` authored ops (a thought) are more model calls before the sim starts it; he stands still meanwhile, as he does while deciding.
            val thought = if (longTerm != null && brief != null) {
                decidingSince = TimeSource.Monotonic.markNow()
                try {
                    longTerm.before(obs, record.intent, brief).filterIsInstance<Thought>().firstOrNull()
                } finally {
                    decidingSince = null
                }
            } else null
            longTerm?.acted(obs, record.intent, brief)
            if (reflection != null && (record.intent as? Intent.Do)?.activity == reflection.first) sinceReflection = 0 else sinceReflection++
            memory.record(obs.tick, record.intent, thought?.text)
            recent = memory.recent()
            val done = record.copy(thought = thought)
            lastDecision = done
            onDecision(done)
            world.submit(id, record.intent)
        }
    }
}
