package dev.lilguy.agent.memory

import dev.lilguy.decision.Usage
import dev.lilguy.sim.Intent
import dev.lilguy.sim.IntentStatus
import dev.lilguy.sim.Observation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.time.Duration

/** The id of the activity that writes a journal entry. */
const val JOURNAL_ACTIVITY = "journal"

/**
 * One character's long-term memory, as the agent loop sees it: the three layers behind one
 * object, plus his journal.
 *
 * 1. [observed] and [acted] append to the event stream, the only thing recorded as it happens.
 * 2. A [Summarizer], if there is one, turns each [Summarizer.batchSize] new events into a
 *    memory. With a [scope] that runs in the background and never holds up a decision; without
 *    one it runs inline, which is what lockstep runs and tests want.
 * 3. [recall] picks the memories to show at a decision point, by [recall]'s rule.
 *
 * The journal: when he starts the journal activity, a [JournalWriter] (if there is one) writes an
 * entry, in the background with a [scope] as above. The entry is kept only once the activity
 * succeeds; if it fails or is interrupted the entry is thrown away. [recallJournal] picks entries
 * for each decision, with their own budget.
 *
 * Thinking: when he chooses the think intent, a [Thinker] (if there is one) writes one thought,
 * inline, and it goes into the event stream as he thought it.
 */
class AgentMemory(
    private val store: MemoryStore,
    val run: String,
    private val summarizer: Summarizer? = null,
    private val scope: CoroutineScope? = null,
    private val rule: Recall = Recall(),
    private val journal: JournalWriter? = null,
    /** Every finished journal session, kept or not; called from whichever coroutine finished it. */
    private val onJournal: (JournalSession) -> Unit = {},
    private val thinker: Thinker? = null,
) {
    /** What [acted] needs to write an entry: who he is, and his state in the decision prompt's words. */
    class JournalBrief(val persona: String, val situation: String)

    /** A journal session under way: started when he chose the activity, kept when it succeeds. */
    private class Pending(val intent: Intent, val tick: Long, val day: Int?, val time: String?) {
        @Volatile var result: JournalSession? = null
        var job: Job? = null
    }

    private var lastEvents: Set<String> = emptySet()
    @Volatile private var job: Job? = null
    @Volatile private var pending: Pending? = null
    @Volatile private var committing: Job? = null

    /** Ids of the memories the latest [recall] picked. Readable from any thread. */
    @Volatile var lastRecalled: Set<Long> = emptySet()
        private set

    /** Ids of the journal entries the latest [recallJournal] picked. */
    @Volatile var lastRecalledJournal: Set<Long> = emptySet()
        private set

    /** The latest finished journal session, kept or not. */
    @Volatile var lastJournal: JournalSession? = null
        private set

    /** The model that writes memories, or null when nothing summarizes. */
    val writer: String? get() = summarizer?.writer

    /** The model that writes the journal, or null when journaling writes nothing. */
    val journalWriter: String? get() = journal?.writer

    /** True when the journal activity produces an entry. */
    val journaling: Boolean get() = journal != null

    /** True when the think intent produces a thought. */
    val thinking: Boolean get() = thinker != null

    private val thoughtLog = java.util.concurrent.CopyOnWriteArrayList<Thought>()

    /** Every thought so far, failed ones included, oldest first. Readable from any thread. */
    fun thoughts(): List<Thought> = thoughtLog.toList()

    /** Most events one memory covers, or null when nothing summarizes. */
    val batchSize: Int? get() = summarizer?.batchSize

    /** True while a background summary is being written. */
    val summarizing: Boolean get() = job?.isActive == true

    /** True while a journal entry is being written or waiting to be stored. */
    val writingJournal: Boolean get() = pending?.job?.isActive == true || committing?.isActive == true

    /** Level-1 memories so far, oldest first. */
    fun memories(): List<StoredMemory> = store.memories(run, 1)

    /** Journal entries so far, oldest first. */
    fun journalEntries(): List<JournalEntry> = store.journal(run)

    fun eventCount(): Int = store.countEventsAfter(run, 0)

    /** Events not yet covered by a memory. */
    fun pending(): Int = summarizer?.pending() ?: eventCount()

    /** Records the state he was shown, and settles a journal session whose activity has ended. */
    fun observed(obs: Observation) {
        val fresh = obs.recentEvents.filter { it !in lastEvents }
        lastEvents = obs.recentEvents.toSet()
        store.appendEvent(run, obs.tick, obs.clock?.day, obs.clock?.time, EventKind.Observation, Describe.observation(obs, fresh))
        settleJournal(obs)
    }

    /**
     * Records what he chose, then starts a summary if enough events have piled up, and a journal
     * entry if he chose to write and can. [brief] is what the entry is written from.
     */
    suspend fun acted(obs: Observation, intent: Intent, brief: JournalBrief? = null) {
        store.appendEvent(run, obs.tick, obs.clock?.day, obs.clock?.time, EventKind.Action, Describe.intent(intent))
        startJournal(obs, intent, brief)
        val s = summarizer ?: return
        if (s.pending() < s.batchSize) return
        if (scope == null) s.catchUp() else if (job?.isActive != true) job = scope.launch { s.catchUp() }
    }

    /**
     * He chose to think at [obs]: one call to the thinker with the decision prompt's [system] and
     * [situation], and the thought goes into the event stream. Null when nothing thinks.
     */
    suspend fun think(obs: Observation, system: String, situation: String): Thought? {
        val t = thinker ?: return null
        val thought = t.think(system, situation, obs.tick, obs.clock?.day, obs.clock?.time)
        thought.text?.let { store.appendEvent(run, obs.tick, obs.clock?.day, obs.clock?.time, EventKind.Thought, it) }
        thoughtLog += thought
        return thought
    }

    /** The memories to show for [obs], as text lines, oldest first. */
    fun recall(obs: Observation): List<String> {
        val all = store.memories(run, 1)
        if (all.isEmpty()) {
            lastRecalled = emptySet()
            return emptyList()
        }
        val picked = rule.select(all, context(obs))
        lastRecalled = picked.map { it.id }.toSet()
        return picked.map(rule::render)
    }

    /** The journal entries to show for [obs], as text lines with their wants, oldest first. */
    fun recallJournal(obs: Observation): List<String> {
        val all = store.journal(run)
        if (all.isEmpty()) {
            lastRecalledJournal = emptySet()
            return emptyList()
        }
        val picked = rule.selectJournal(all, context(obs))
        lastRecalledJournal = picked.map { it.id }.toSet()
        return picked.map(rule::render)
    }

    private fun context(obs: Observation): String = buildList {
        add(Describe.observation(obs, emptyList()))
        obs.activities.orEmpty().filter { it.locked == null }.forEach { add(it.id) }
        obs.shop.orEmpty().forEach { add(it.id) }
        obs.uses.orEmpty().forEach { add(it.itemId) }
    }.joinToString(" ").replace('_', ' ')

    /** Waits for a running summary and a journal entry being stored, then summarizes whatever is left. Call at the end of a run. */
    suspend fun flush() {
        job?.join()
        committing?.join()
        summarizer?.flush()
    }

    // --- journal ---------------------------------------------------------------------------------

    private suspend fun startJournal(obs: Observation, intent: Intent, brief: JournalBrief?) {
        val writer = journal ?: return
        if (intent != Intent.Do(JOURNAL_ACTIVITY) || brief == null) return
        // Refused at once (too tired, too hungry): nothing to write about.
        if (obs.activities.orEmpty().any { it.id == JOURNAL_ACTIVITY && it.locked != null }) return
        pending?.job?.cancel()
        val p = Pending(intent, obs.tick, obs.clock?.day, obs.clock?.time)
        pending = p
        val write = suspend { p.result = writer.write(brief.persona, brief.situation, p.tick, p.day, p.time) }
        if (scope == null) write() else p.job = scope.launch { write() }
    }

    /** At the next decision point after he chose to write: keep the entry if the activity succeeded. */
    private fun settleJournal(obs: Observation) {
        val p = pending ?: return
        if (obs.tick <= p.tick) return
        pending = null
        val succeeded = obs.lastIntent == p.intent && obs.lastResult == IntentStatus.Succeeded
        val done = p.result
        when {
            !succeeded && done != null -> finish(done.discarded())
            !succeeded -> {
                p.job?.cancel()
                finish(JournalSession(p.tick, p.day, p.time, journal!!.writer, emptyList(), null, emptyList(), Usage(), Duration.ZERO, "the activity ended before the entry was written", JournalSession.Status.Discarded))
            }
            done != null -> commit(done, obs)
            else -> committing = scope?.launch {
                p.job?.join()
                p.result?.let { commit(it, obs) }
            }
        }
    }

    private fun JournalSession.discarded() = if (status == JournalSession.Status.Written) copy(status = JournalSession.Status.Discarded) else this

    private fun commit(session: JournalSession, ended: Observation) {
        val entry = session.entry ?: return finish(session)
        store.addJournal(run, session.tick, session.day, session.time, entry, session.wants, session.writer)
        val wants = if (session.wants.isEmpty()) "" else " and wrote down ${session.wants.size} want${if (session.wants.size == 1) "" else "s"}"
        store.appendEvent(run, ended.tick, ended.clock?.day, ended.clock?.time, EventKind.Journal, "wrote in my journal$wants")
        finish(session.copy(status = JournalSession.Status.Stored))
    }

    private fun finish(session: JournalSession) {
        lastJournal = session
        onJournal(session)
    }
}
