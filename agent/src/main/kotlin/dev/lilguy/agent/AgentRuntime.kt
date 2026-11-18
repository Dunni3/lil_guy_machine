package dev.lilguy.agent

import dev.lilguy.agent.memory.AgentMemory
import dev.lilguy.agent.memory.Thought
import dev.lilguy.decision.ActionSchema
import dev.lilguy.decision.Attempt
import dev.lilguy.decision.DecisionFailed
import dev.lilguy.decision.DecisionModel
import dev.lilguy.decision.DecisionRequest
import dev.lilguy.decision.Looked
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicInteger
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
        /** The long-term memories and earlier thoughts put into this decision's prompt. */
        val recalled: List<String> = emptyList(),
        /**
         * When [intent]'s verb writes a thought before it starts: what he thought, or why there's no
         * thought. For a model that calls tools, what it said he thought as it chose.
         */
        val thought: Thought? = null,
        /** What a model that calls tools looked at before it chose ([dev.lilguy.decision.ToolSet.looks]). */
        val looks: List<Looked> = emptyList(),
        /** What it wrote along the way without acting, in order. */
        val musings: List<String> = emptyList(),
        /** The turn ran out of steps with no action, so he sat and thought ([dev.lilguy.decision.ToolSet.ponder]). */
        val pondered: Boolean = false,
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

    /** What a checkpoint keeps of the loop: his short-term memory and the count toward the next reflection. */
    @Serializable
    data class State(val recent: List<Memory.Entry>, @SerialName("since_reflection") val sinceReflection: Int)

    /** [WAITING] while the loop waits for a decision point and nothing else; [EXPORTING] while another thread reads its state. */
    private val phase = AtomicInteger(BUSY)

    /**
     * Holds the loop at its wait for a decision point, so another thread (the sim thread) can read
     * its [state] and the world's at the same moment: a loop woken meanwhile waits for [release].
     * False when the loop is busy deciding; try again later. Cheap enough to call every tick.
     */
    fun hold(): Boolean = phase.compareAndSet(WAITING, EXPORTING)

    fun release() = phase.set(WAITING)

    /** What a checkpoint keeps of the loop. Only while [hold]ing it, or before [run]. */
    fun state() = State(memory.recent(), sinceReflection)

    /** Puts back a checkpoint's [State]. Call before [run]. */
    fun restore(s: State) {
        memory.restore(s.recent)
        recent = memory.recent()
        sinceReflection = s.sinceReflection
    }

    /** When the in-flight decide() started, or null when the agent isn't waiting on the model. */
    @Volatile var decidingSince: TimeMark? = null
        private set

    suspend fun run() = coroutineScope {
        while (isActive) {
            phase.set(WAITING)
            world.awaitDecisionPoint(id)
            while (!phase.compareAndSet(WAITING, BUSY)) kotlinx.coroutines.yield()
            val obs = world.observe(id)
            memory.settle(obs.lastIntent, obs.lastResult, obs.outcome)
            longTerm?.observed(obs)
            val recalled = longTerm?.recall(obs).orEmpty()
            // His latest kept thought, shown in every prompt until the next one replaces it.
            val plan = longTerm?.plan?.let { t -> t.text?.let { "${prompts.stampOf(t.tick)}: $it" } }
            // His latest answers from asking how his world works, and what he last looked up on the web.
            val found = (longTerm?.answers().orEmpty().map { a -> a.tick to "I asked \"${a.question}\": ${a.reply.text}" } +
                longTerm?.lookedUp().orEmpty().mapNotNull { s -> s.remembered()?.let { s.tick to it } })
                .sortedBy { it.first }.map { (tick, text) -> "${prompts.stampOf(tick)}: $text" }
            // What he concluded the latest times he stepped back.
            val reflections = longTerm?.reflections().orEmpty().map { m -> "${m.span.toDay?.let { d -> "Day $d ${m.span.toTime}" } ?: "Earlier"}: ${m.text}" }
            val caps = model.capabilities
            val request = prompts.build(obs, memory.recent(), closedChoice = caps.closedChoice, tools = caps.toolCalls && !caps.closedChoice, memories = recalled, thought = plan, found = found, reflections = reflections)
            val schema = schemaFor(obs)
            val started = TimeSource.Monotonic.markNow()
            decidingSince = started
            val base = DecisionRecord(obs.tick, Intent.Wait(0), null, null, Duration.ZERO, observation = obs, request = request, schema = schema.jsonSchema, recalled = recalled)
            val forced = reflection?.takeIf { (verb, every) -> sinceReflection >= every && obs.activities.orEmpty().any { it.id == verb && it.locked == null } }
            val record = if (forced != null) {
                decidingSince = null
                base.copy(intent = Intent.Do(forced.first), rationale = "Time to ${forced.first} (every ${forced.second} decisions).")
            } else try {
                val decision = withTimeout(request.timeout) { model.decide(request, schema) }
                // A turn that ended in thought is its verb (think), or a wait when that can't be done now (he just did).
                val thinks = (decision.value.intent as? Intent.Do)?.takeIf { decision.pondered }
                val intent = if (thinks != null && obs.activities.orEmpty().none { it.id == thinks.activity && it.locked == null }) fallback(IllegalStateException("can't ${thinks.activity} now")) else decision.value.intent
                base.copy(
                    intent = intent,
                    rationale = decision.rationale,
                    raw = decision.raw,
                    latency = decision.latency,
                    usage = decision.usage,
                    attempts = decision.attempts,
                    looks = decision.looks,
                    musings = decision.musings,
                    pondered = decision.pondered,
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
                    looks = e.looked,
                    musings = e.musings,
                )
            } catch (e: Exception) {
                base.copy(intent = fallback(e), latency = started.elapsedNow(), error = e.message ?: e.toString())
            } finally {
                decidingSince = null
            }
            // A model that calls tools says what he thinks as he chooses: that's his thought of the moment.
            val spoken = if (request.tools != null && record.error == null && forced == null) record.rationale?.let { longTerm?.thought(obs, it, model.id) } else null
            val brief = prompts.persona?.let { AgentMemory.Brief(it(), PromptBuilder.situationOf(request), request.system) }
            // A verb's `before` authored ops (a thought) are more model calls before the sim starts it; he stands still meanwhile, as he does while deciding.
            // A turn that ended in thought has its thought already: what he wrote.
            val thought = if (longTerm != null && brief != null && !record.pondered) {
                decidingSince = TimeSource.Monotonic.markNow()
                try {
                    longTerm.before(obs, record.intent, brief).filterIsInstance<Thought>().firstOrNull()
                } finally {
                    decidingSince = null
                }
            } else null
            longTerm?.acted(obs, record.intent, brief)
            if (reflection != null && (record.intent as? Intent.Do)?.activity == reflection.first) sinceReflection = 0 else sinceReflection++
            memory.record(obs.tick, record.intent, thought?.text, record.rationale)
            recent = memory.recent()
            val done = record.copy(thought = thought ?: spoken)
            lastDecision = done
            onDecision(done)
            world.submit(id, record.intent)
        }
    }

    private companion object {
        const val BUSY = 0
        const val WAITING = 1
        const val EXPORTING = 2
    }
}
