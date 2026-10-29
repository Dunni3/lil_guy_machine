package dev.lilguy.agent.memory

import dev.lilguy.sim.Intent
import dev.lilguy.sim.Observation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * One character's long-term memory, as the agent loop sees it: the three layers behind one
 * object.
 *
 * 1. [observed] and [acted] append to the event stream, the only thing recorded as it happens.
 * 2. A [Summarizer], if there is one, turns each [Summarizer.batchSize] new events into a
 *    memory. With a [scope] that runs in the background and never holds up a decision; without
 *    one it runs inline, which is what lockstep runs and tests want.
 * 3. [recall] picks the memories to show at a decision point, by [recall]'s rule.
 */
class AgentMemory(
    private val store: MemoryStore,
    val run: String,
    private val summarizer: Summarizer? = null,
    private val scope: CoroutineScope? = null,
    private val rule: Recall = Recall(),
) {
    private var lastEvents: Set<String> = emptySet()
    private var job: Job? = null

    /** Records the state he was shown. */
    fun observed(obs: Observation) {
        val fresh = obs.recentEvents.filter { it !in lastEvents }
        lastEvents = obs.recentEvents.toSet()
        store.appendEvent(run, obs.tick, obs.clock?.day, obs.clock?.time, EventKind.Observation, Describe.observation(obs, fresh))
    }

    /** Records what he chose, then starts a summary if enough events have piled up. */
    suspend fun acted(obs: Observation, intent: Intent) {
        store.appendEvent(run, obs.tick, obs.clock?.day, obs.clock?.time, EventKind.Action, Describe.intent(intent))
        val s = summarizer ?: return
        if (s.pending() < s.batchSize) return
        if (scope == null) s.catchUp() else if (job?.isActive != true) job = scope.launch { s.catchUp() }
    }

    /** The memories to show for [obs], as text lines, oldest first. */
    fun recall(obs: Observation): List<String> {
        val all = store.memories(run, 1)
        if (all.isEmpty()) return emptyList()
        val context = buildList {
            add(Describe.observation(obs, emptyList()))
            obs.activities.orEmpty().filter { it.locked == null }.forEach { add(it.id) }
            obs.shop.orEmpty().forEach { add(it.id) }
            obs.uses.orEmpty().forEach { add(it.itemId) }
        }.joinToString(" ").replace('_', ' ')
        return rule.select(all, context).map(rule::render)
    }

    /** Waits for a running summary, then summarizes whatever is left. Call at the end of a run. */
    suspend fun flush() {
        job?.join()
        summarizer?.flush()
    }
}
