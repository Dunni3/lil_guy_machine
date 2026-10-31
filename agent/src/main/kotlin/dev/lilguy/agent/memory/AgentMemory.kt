package dev.lilguy.agent.memory

import dev.lilguy.agent.art.ArtStudio
import dev.lilguy.agent.art.Drawing
import dev.lilguy.agent.authored.AuthorHandler
import dev.lilguy.agent.authored.Authors
import dev.lilguy.sim.BedroomWorld
import dev.lilguy.sim.Intent
import dev.lilguy.sim.Observation
import dev.lilguy.sim.VerbSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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
 * Authored ops ([Authors]): the verbs whose result a model writes run through one lifecycle
 * here, each with its handler: a [JournalWriter] for `write: { with: journal }`, a [Thinker] for
 * `write: { with: thought }`, an [ArtStudio] for `draw`. Whichever is null isn't configured, and
 * a verb that needs it isn't offered ([offers]). [recallJournal] picks journal entries for each
 * decision, with their own budget; his latest kept thought is his [plan].
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
    /** Draws his art; the scenario connects it to the world. */
    val studio: ArtStudio? = null,
) {
    /** What authored ops are written from: who he is, his state in the decision prompt's words, and that prompt's system message. */
    class Brief(val persona: String, val situation: String, val system: String)

    private var lastEvents: Set<String> = emptySet()
    @Volatile private var job: Job? = null
    private val thoughtLog = java.util.concurrent.CopyOnWriteArrayList<Thought>()

    /** His latest kept thought shown as his plan (`shows_as: plan`), or null. */
    @Volatile var plan: Thought? = null
        private set

    private val authors: Authors = authors()

    private fun authors(): Authors {
        val handlers = mutableListOf<AuthorHandler<*>>()
        if (journal != null) handlers += JournalHandler(journal, store, run) { s: JournalSession -> lastJournal = s; onJournal(s) }
        if (thinker != null) handlers += ThoughtHandler(thinker, store, run, onThought = { t: Thought -> thoughtLog += t }, onPlan = { t: Thought -> plan = t })
        if (studio != null) handlers += studio
        return Authors(handlers, scope)
    }

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

    /** True when something writes journal entries. */
    val journaling: Boolean get() = authors.has("journal")

    /** True when something writes his thoughts. */
    val thinking: Boolean get() = authors.has("thought")

    /** True when every essential authored op of [verb] has a handler, so models are offered it. */
    fun offers(verb: VerbSpec): Boolean = authors.offers(verb)

    /** True while a `during` call has outlasted its verb: the clock waits for it ([holdingFor] says which). */
    val holding: Boolean get() = authors.holding

    val holdingFor: List<String> get() = authors.holdingFor

    /** True while a piece is being drawn or waiting to be kept. */
    val drawing: Boolean get() = authors.busy("draw")

    /** The drawing for the verb under way, once the model has sent it; not kept yet. Readable from any thread. */
    fun drawingInProgress(): Drawing? = authors.inProgress("draw") as? Drawing

    /** Runs authored ops for [world]. Call before the run starts. */
    fun connect(world: BedroomWorld) {
        authors.connect(world)
        studio?.connect(world)
    }

    /** Every thought so far, failed ones included, oldest first. Readable from any thread. */
    fun thoughts(): List<Thought> = thoughtLog.toList()

    /** Most events one memory covers, or null when nothing summarizes. */
    val batchSize: Int? get() = summarizer?.batchSize

    /** True while a background summary is being written. */
    val summarizing: Boolean get() = job?.isActive == true

    /** True while a journal entry is being written or waiting to be stored. */
    val writingJournal: Boolean get() = authors.busy("journal")

    /** Level-1 memories so far, oldest first. */
    fun memories(): List<StoredMemory> = store.memories(run, 1)

    /** Journal entries so far, oldest first. */
    fun journalEntries(): List<JournalEntry> = store.journal(run)

    fun eventCount(): Int = store.countEventsAfter(run, 0)

    /** Events not yet covered by a memory. */
    fun pending(): Int = summarizer?.pending() ?: eventCount()

    /** Records the state he was shown, and keeps or throws away authored results whose verb has ended. */
    fun observed(obs: Observation) {
        val fresh = obs.recentEvents.filter { it !in lastEvents }
        lastEvents = obs.recentEvents.toSet()
        store.appendEvent(run, obs.tick, obs.clock?.day, obs.clock?.time, EventKind.Observation, Describe.observation(obs, fresh))
        authors.observed(obs)
    }

    /**
     * He chose [intent] at [obs]: runs its verb's `before` authored ops now (a thought) and returns
     * their results. Call before submitting the intent; he stands still meanwhile.
     */
    suspend fun before(obs: Observation, intent: Intent, brief: Brief): List<Any> = authors.before(obs, intent, brief.persona, brief.situation, brief.system)

    /**
     * Records what he chose, then starts its verb's `during` authored ops (with [brief] to write
     * from), words a new commission, and starts a summary if enough events have piled up.
     */
    suspend fun acted(obs: Observation, intent: Intent, brief: Brief? = null) {
        store.appendEvent(run, obs.tick, obs.clock?.day, obs.clock?.time, EventKind.Action, Describe.intent(intent))
        brief?.let { authors.acted(obs, intent, it.persona, it.situation, it.system) }
        studio?.word(obs)
        val s = summarizer ?: return
        if (s.pending() < s.batchSize) return
        if (scope == null) s.catchUp() else if (job?.isActive != true) job = scope.launch { s.catchUp() }
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
    }.joinToString(" ").replace('_', ' ')

    /** Waits for a running summary and authored results being kept, then summarizes whatever is left. Call at the end of a run. */
    suspend fun flush() {
        job?.join()
        authors.flush()
        summarizer?.flush()
    }
}
