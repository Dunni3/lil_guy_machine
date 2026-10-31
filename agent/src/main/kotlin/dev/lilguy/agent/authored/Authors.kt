package dev.lilguy.agent.authored

import dev.lilguy.sim.AuthoredOp
import dev.lilguy.sim.AuthoredResult
import dev.lilguy.sim.AuthoredView
import dev.lilguy.sim.BedroomContent
import dev.lilguy.sim.BedroomWorld
import dev.lilguy.sim.Intent
import dev.lilguy.sim.IntentStatus
import dev.lilguy.sim.Observation
import dev.lilguy.sim.Timing
import dev.lilguy.sim.VerbSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The code behind an authored op ([AuthoredOp]): content names a handler and sets its parameters;
 * the handler makes the model call and puts the result where the op says. [Authors] runs the
 * lifecycle around it, the same for every handler.
 */
interface AuthorHandler<R : Any> {
    /** The name content uses: `thought`, `draw`. */
    val name: String

    /** The model that writes it. */
    val writer: String

    /** Makes the result. Never throws for a model failure: the result says what went wrong. */
    suspend fun author(call: AuthorCall): R

    /**
     * The verb succeeded: put [result] into [AuthorCall.op]'s destinations. [view] is the sim's record
     * of the op, null if it's gone; [deliver] hands a result to the sim for it.
     */
    fun keep(result: R, call: AuthorCall, ended: Observation, view: AuthoredView?, deliver: (AuthoredResult) -> Unit)

    /** The verb failed or was cut short; [result] is null when the call hadn't finished. */
    fun discard(result: R?, call: AuthorCall) {}
}

/**
 * One authored op's call: the op, its verb, what he chose and when, and what it's written from:
 * who he is ([persona]), his state in the decision prompt's words ([situation]) and that prompt's
 * system message ([system]).
 */
class AuthorCall(
    val op: AuthoredOp,
    val verb: VerbSpec,
    val intent: Intent.Do,
    val obs: Observation,
    val persona: String,
    val situation: String,
    val system: String,
)

/**
 * The handler registry and the one lifecycle every authored op shares:
 * - **Timing.** A `before` op's call runs inline in [before], after the decision and before the
 *   intent is submitted, so he stands still and lockstep runs stay deterministic. A `during` op's
 *   call starts in [acted], when the verb starts, in the background with a [scope] (inline without).
 * - **Keep or throw away.** At the next decision point ([observed]) the result is kept only if the
 *   verb succeeded; otherwise the call is cancelled and the result discarded.
 * - **Hold.** While a `before` call is out, or a `during` call outlasts its verb, [holding] is true
 *   and the app's clock waits: a thought costs the verb's minutes, not the model's seconds too.
 * - **Destinations** are the handler's to write; the sim's part goes through [BedroomWorld.deliver].
 *
 * A verb is offered to models only when every essential authored op ([AuthoredOp.essential]) has a
 * handler here ([offers]); the sim runs its world ops either way.
 */
class Authors(handlers: List<AuthorHandler<*>>, private val scope: CoroutineScope? = null) {
    /** Handlers by name. Each one's results only ever go back to itself, so they're held as handlers of Any. */
    @Suppress("UNCHECKED_CAST")
    private val byName: Map<String, AuthorHandler<Any>> = handlers.associate { it.name to it as AuthorHandler<Any> }

    private class Pending(val handler: AuthorHandler<Any>, val call: AuthorCall) {
        @Volatile var result: Any? = null
        var job: Job? = null

        suspend fun run() {
            result = handler.author(call)
        }

        fun keep(ended: Observation, view: AuthoredView?, deliver: (AuthoredResult) -> Unit) = handler.keep(result!!, call, ended, view, deliver)

        fun discard() = handler.discard(result, call)
    }

    private var content: BedroomContent? = null
    private var deliver: (Int, AuthoredResult) -> Unit = { _, _ -> }
    private val pending = CopyOnWriteArrayList<Pending>()
    /** Kept results still waiting on their call, by handler. */
    private val committing = CopyOnWriteArrayList<Pair<String, Job>>()
    /** Handlers whose `before` call is out right now. */
    private val inline = CopyOnWriteArrayList<String>()

    /** Runs authored ops for [world]: its content says which verbs have them, and results go back to it. */
    fun connect(world: BedroomWorld) {
        content = world.content
        deliver = world::deliver
    }

    fun has(handler: String): Boolean = handler in byName

    fun writer(handler: String): String? = byName[handler]?.writer

    /** True when every essential authored op of [verb] has a handler. */
    fun offers(verb: VerbSpec): Boolean = verb.authored.all { !it.essential || has(it.handler) }

    /** True while a call for [handler] is running or waiting to be kept. */
    fun busy(handler: String): Boolean = pending.any { it.handler.name == handler && it.job?.isActive == true } || committing.any { it.first == handler && it.second.isActive }

    /** The result of the latest [handler] call not yet kept or thrown away, once the model has sent it. Readable from any thread. */
    fun inProgress(handler: String): Any? = pending.lastOrNull { it.handler.name == handler }?.result

    /** True while a `before` call is out or a `during` call has outlasted its verb: the clock should wait for it. */
    val holding: Boolean get() = inline.isNotEmpty() || committing.any { it.second.isActive }

    /** The handlers [holding] is waiting on. */
    val holdingFor: List<String> get() = (inline + committing.filter { it.second.isActive }.map { it.first }).distinct()

    /**
     * He chose [intent] at [obs]: runs its verb's `before` ops now and returns their results, or none
     * when the intent isn't a verb with any or the verb is locked.
     */
    suspend fun before(obs: Observation, intent: Intent, persona: String, situation: String, system: String): List<Any> =
        start(obs, intent, persona, situation, system, Timing.Before).mapNotNull { p ->
            inline += p.handler.name
            try {
                p.run()
            } finally {
                inline.remove(p.handler.name)
            }
            p.result
        }

    /** He chose [intent] at [obs]: starts its verb's `during` ops. */
    suspend fun acted(obs: Observation, intent: Intent, persona: String, situation: String, system: String) {
        start(obs, intent, persona, situation, system, Timing.During).forEach { p ->
            if (scope == null) p.run() else p.job = scope.launch { p.run() }
        }
    }

    private fun start(obs: Observation, intent: Intent, persona: String, situation: String, system: String, timing: Timing): List<Pending> {
        val verb = (intent as? Intent.Do)?.let { content?.verb(it.activity) } ?: return emptyList()
        // Refused at once (too tired, no record): there's nothing to write about.
        if (obs.activities.orEmpty().none { it.id == verb.id && it.locked == null }) return emptyList()
        return verb.authored.filter { it.timing == timing }.mapNotNull { op ->
            byName[op.handler]?.let { h -> Pending(h, AuthorCall(op, verb, intent, obs, persona, situation, system)) }
        }.onEach { pending += it }
    }

    /** At a decision point: keeps or throws away the results of verbs that have ended. */
    fun observed(obs: Observation) {
        pending.filter { obs.tick > it.call.obs.tick }.forEach { p ->
            pending -= p
            val succeeded = obs.lastIntent == p.call.intent && obs.lastResult == IntentStatus.Succeeded
            if (!succeeded) {
                p.job?.cancel()
                p.discard()
                return@forEach
            }
            val view = obs.authored.lastOrNull { it.verb == p.call.verb.id && it.handler == p.call.op.handler && it.tick > p.call.obs.tick && !it.delivered }
            val give: (AuthoredResult) -> Unit = { r -> if (view != null) deliver(view.id, r) }
            if (p.result != null) p.keep(obs, view, give)
            else scope?.launch {
                p.job?.join()
                if (p.result != null) p.keep(obs, view, give)
            }?.let { job ->
                val entry = p.handler.name to job
                committing += entry
                job.invokeOnCompletion { committing -= entry }
            }
        }
    }

    /** Waits for results still being kept. Call at the end of a run. */
    suspend fun flush() {
        committing.toList().forEach { it.second.join() }
    }
}
