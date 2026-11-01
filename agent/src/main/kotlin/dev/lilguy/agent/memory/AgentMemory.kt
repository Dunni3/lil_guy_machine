package dev.lilguy.agent.memory

import dev.lilguy.agent.art.ArtStudio
import dev.lilguy.agent.art.Drawing
import dev.lilguy.agent.authored.AuthorHandler
import dev.lilguy.agent.authored.Authors
import dev.lilguy.sim.BedroomWorld
import dev.lilguy.sim.ClockReading
import dev.lilguy.sim.GameClock
import dev.lilguy.sim.Intent
import dev.lilguy.sim.IntentStatus
import dev.lilguy.sim.Observation
import dev.lilguy.sim.SleepOp
import dev.lilguy.sim.VerbSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One character's long-term memory, as the agent loop sees it: every layer behind one object.
 *
 * 1. **Events.** [observed] appends how his last intent turned out (from the sim's own words for
 *    what it did), what happened around him since, and once a day when he wakes, how things stand.
 *    Thoughts and finished art are appended by their handlers.
 * 2. **Memories.** A [Summarizer], if there is one, writes stretches of events up as level-1
 *    memories, when their significance adds up (or every few events, without a [scorer]); and when
 *    he goes to sleep, the rest of the day, then one day memory (level 2).
 * 3. **Self-model.** After the day memory, a [SelfModeler] revises how he sees himself (level 3);
 *    [selfModel] is what the prompts show.
 * 4. **Recall.** [recall] picks the memories and earlier thoughts to show at a decision. With a
 *    [scorer], Jev rates every candidate against his situation in the background after each
 *    choice, so the ranking is ready by the next decision (the previous one is used if not);
 *    without, by keywords ([Recall]).
 *
 * With a [scope], everything that calls a model runs in the background and never holds up a
 * decision; without one it runs inline, which is what lockstep runs and tests want.
 *
 * Authored ops ([Authors]): the verbs whose result a model writes run through one lifecycle here,
 * each with its handler: a [Thinker] for `write: { with: thought }`, a [Reader] for
 * `write: { with: reading }`, an [ArtStudio] for `draw`.
 * Whichever is null isn't configured, and a verb that needs it isn't offered ([offers]). His
 * latest kept thought is his [plan]; older ones are recall candidates.
 */
class AgentMemory(
    private val store: MemoryStore,
    val run: String,
    private val summarizer: Summarizer? = null,
    private val scope: CoroutineScope? = null,
    private val rule: Recall = Recall(),
    /** Scores recall and event significance; null for keywords and fixed batches. */
    private val scorer: MemoryScorer? = null,
    private val selfModeler: SelfModeler? = null,
    /** Every nightly revision, changed or not; called from whichever coroutine made it. */
    private val onSelf: (SelfRevision) -> Unit = {},
    private val thinker: Thinker? = null,
    /** Draws his art; the scenario connects it to the world. */
    val studio: ArtStudio? = null,
    /** Writes what stuck with him from each chapter; reading works without it. */
    private val reader: Reader? = null,
    /** Writes the question when he asks how his world works; with [oracle], which answers it. Without both, ask isn't offered. */
    private val questioner: Questioner? = null,
    private val oracle: Oracle? = null,
    /** Every answer, kept or not; called from whichever coroutine wrote it. */
    private val onAnswer: (Answer) -> Unit = {},
) {
    /** What authored ops are written from: who he is, his state in the decision prompt's words, and that prompt's system message. */
    class Brief(val persona: String, val situation: String, val system: String)

    /** What he chose and when: the outcome event is written once the sim says how it ended. */
    private class Chosen(val intent: Intent, val at: ClockReading?)

    private var lastNews: Set<String> = emptySet()
    private var chosen: Chosen? = null
    private var snapshotDay: Int? = null
    /** Verbs that put him to sleep: going to sleep ends his day; waking is when the day's snapshot is taken. */
    private var restVerbs: Set<String> = emptySet()
    /** Who he is in the third person, for the scorer's state. */
    private var about: String? = null

    @Volatile private var summaryJob: Job? = null
    @Volatile private var scoringJob: Job? = null
    private val summaries = Mutex()
    /** The highest event id already sent for significance. */
    @Volatile private var scoredThrough = 0L
    /** The latest relevance ranking, by [Recollection.key]. */
    @Volatile private var relevance: Map<String, Double> = emptyMap()

    private val thoughtLog = CopyOnWriteArrayList<Thought>()
    /** Kept thoughts, with their event ids: recall candidates. */
    private val keptThoughts = CopyOnWriteArrayList<Pair<Long, Thought>>()

    /** Kept answers (`shows_as: answer`), oldest first. */
    private val keptAnswers = CopyOnWriteArrayList<Answer>()

    /** His latest kept thought shown as his plan (`shows_as: plan`), or null. */
    @Volatile var plan: Thought? = null
        private set

    private val authors: Authors = authors()

    private fun authors(): Authors {
        val handlers = mutableListOf<AuthorHandler<*>>()
        if (thinker != null) handlers += ThoughtHandler(thinker, store, run, onThought = { t -> thoughtLog += t }, onPlan = { t -> plan = t }, onStored = { id, t -> keptThoughts += id to t })
        if (studio != null) handlers += studio
        if (reader != null) handlers += ReadingHandler(reader, store, run)
        if (questioner != null && oracle != null) handlers += AnswerHandler(questioner, oracle, store, run, onAnswer) { a -> keptAnswers += a }
        return Authors(handlers, scope)
    }

    /** Ids of the memories (any level) the latest [recall] picked. Readable from any thread. */
    @Volatile var lastRecalled: Set<Long> = emptySet()
        private set

    /** Event ids of the earlier thoughts the latest [recall] picked. */
    @Volatile var lastRecalledThoughts: Set<Long> = emptySet()
        private set

    /** The latest nightly revision of his self-model, changed or not. */
    @Volatile var lastRevision: SelfRevision? = null
        private set

    /** The model that writes memories, or null when nothing summarizes. */
    val writer: String? get() = summarizer?.writer

    /** The model that writes his thoughts, or null. */
    val thoughtWriter: String? get() = authors.writer("thought")

    /** The model that revises his self-model, or null. */
    val selfWriter: String? get() = selfModeler?.writer

    /** The model that scores recall and significance, or null for keywords. */
    val scorerId: String? get() = scorer?.id

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
        oracle?.connect(world)
        restVerbs = world.content.verbs.filter { v -> v.effects.any { it is SleepOp } }.map { it.id }.toSet()
        about = world.content.personality.core.he
    }

    /** His latest [n] kept answers, oldest first: what later prompts show of what he found out. Readable from any thread. */
    fun answers(n: Int = SHOWN_ANSWERS): List<Answer> = keptAnswers.toList().takeLast(n)

    /** Every thought so far, failed ones included, oldest first. Readable from any thread. */
    fun thoughts(): List<Thought> = thoughtLog.toList()

    /** Most events one memory covers, or null when nothing summarizes. */
    val batchSize: Int? get() = summarizer?.batchSize

    /** True while a memory, day memory or self-model is being written in the background. */
    val summarizing: Boolean get() = summaryJob?.isActive == true

    /** Level-1 memories so far, oldest first. */
    fun memories(): List<StoredMemory> = store.memories(run, 1)

    /** Day memories (level 2) so far, oldest first. */
    fun days(): List<StoredMemory> = store.memories(run, 2)

    /** His current self-model, or null before the first revision (or with no [selfModeler]). */
    fun selfModel(): SelfVersion? = selfModeler?.current()

    /** Every version of his self-model, oldest first, with the memory row it's stored in. */
    fun selfVersions(): List<Pair<StoredMemory, SelfVersion>> = selfModeler?.versions().orEmpty()

    /** How he sees himself, as the prompts show it: the current self-model, or the seed before the first; null when nothing revises it. */
    fun selfText(): String? = selfModeler?.let { m -> m.current()?.self?.render() ?: m.seed }

    fun eventCount(): Int = store.countEventsAfter(run, 0)

    /** Events not yet covered by a memory. */
    fun pending(): Int = summarizer?.pending() ?: eventCount()

    /** The significance the uncovered events add up to, and what they must reach; null without a threshold. */
    fun pendingSignificance(): Pair<Double, Double>? = summarizer?.threshold?.let { summarizer.pendingSignificance() to it }

    /**
     * Records how his last intent turned out, what happened since, and (when he has just woken) how
     * things stand; then keeps or throws away authored results whose verb has ended.
     */
    fun observed(obs: Observation) {
        val c = chosen
        val status = obs.lastResult
        if (c != null && obs.lastIntent == c.intent && (status is IntentStatus.Failed || status == IntentStatus.Succeeded || status == IntentStatus.Interrupted)) {
            val minutes = Describe.minutesBetween(c.at?.day, c.at?.time, obs.clock?.day, obs.clock?.time)
            append(obs, EventKind.Outcome, Describe.outcome(c.intent, status, obs.outcome, minutes))
            chosen = null
        }
        obs.news.filter { it !in lastNews }.forEach { append(obs, EventKind.World, it.substringAfter(": ")) }
        lastNews = obs.news.toSet()
        val day = obs.clock?.day
        val woke = (obs.lastIntent as? Intent.Do)?.activity in restVerbs
        if (day != null && day != snapshotDay && (snapshotDay == null || woke)) {
            append(obs, EventKind.Snapshot, Describe.state(obs))
            snapshotDay = day
        }
        authors.observed(obs)
    }

    private fun append(obs: Observation, kind: EventKind, text: String) =
        store.appendEvent(run, obs.tick, obs.clock?.day, obs.clock?.time, kind, text)

    /**
     * He chose [intent] at [obs]: runs its verb's `before` authored ops now (a thought) and returns
     * their results. Call before submitting the intent; he stands still meanwhile.
     */
    suspend fun before(obs: Observation, intent: Intent, brief: Brief): List<Any> = authors.before(obs, intent, brief.persona, brief.situation, brief.system)

    /**
     * Records what he chose, then starts its verb's `during` authored ops (with [brief] to write
     * from), words a new commission, scores the new events and ranks recall for the next decision,
     * and writes memories: a stretch if one is ready, or the whole day and his self-model if he is
     * going to sleep.
     */
    suspend fun acted(obs: Observation, intent: Intent, brief: Brief? = null) {
        chosen = Chosen(intent, obs.clock)
        brief?.let { authors.acted(obs, intent, it.persona, it.situation, it.system) }
        studio?.word(obs)
        if (scorer != null) {
            if (scope == null) score(scorer, obs, intent)
            else if (scoringJob?.isActive != true) scoringJob = scope.launch { score(scorer, obs, intent) }
        }
        val s = summarizer ?: return
        val bedtime = (intent as? Intent.Do)?.activity in restVerbs
        if (!bedtime && !s.ready()) return
        val work: suspend () -> Unit = { summaries.withLock { if (bedtime) night(s, obs.clock?.day) else s.catchUp() } }
        when {
            scope == null -> work()
            bedtime -> summaryJob.let { before -> summaryJob = scope.launch { before?.join(); work() } }
            summaryJob?.isActive != true -> summaryJob = scope.launch { work() }
        }
    }

    /** He's going to sleep on [day]: the rest of the day becomes memories, then a day memory, then his self-model is revised. */
    private suspend fun night(s: Summarizer, day: Int?) {
        s.flush()
        val memory = s.summarizeDay(day) ?: return
        val m = selfModeler ?: return
        val covered = store.memories(run, 1).filter { it.id in memory.fromId..memory.toId }
        val from = covered.minOfOrNull { it.fromId } ?: return
        val to = covered.maxOf { it.toId }
        val thoughts = store.eventsAfter(run, from - 1, (to - from + 1).toInt()).filter { it.kind == EventKind.Thought }.map { it.text }
        val revision = m.revise(memory, thoughts)
        lastRevision = revision
        onSelf(revision)
    }

    /** Scores the events since the last time, then ranks every recall candidate against his situation and what he just chose. */
    private suspend fun score(scorer: MemoryScorer, obs: Observation, intent: Intent) {
        val state = state(obs, intent)
        val fresh = store.eventsAfter(run, scoredThrough, SCORE_BATCH).filter { it.significance == null }
        if (fresh.isNotEmpty()) {
            scoredThrough = fresh.last().id
            val routine = fresh.filter { it.kind == EventKind.Snapshot }
            routine.forEach { store.setSignificance(run, it.id, 0.0) }
            scorer.significance(state, fresh - routine.toSet()).forEach { (id, v) -> store.setSignificance(run, id, v) }
        }
        val candidates = candidates(obs)
        if (candidates.isNotEmpty()) scorer.relevance(state, candidates).takeIf { it.isNotEmpty() }?.let { relevance = it }
    }

    /** His situation for the scorer: roughly what the decision prompt shows, without the option lists. */
    private fun state(obs: Observation, intent: Intent): JsonObject = buildJsonObject {
        about?.let { put("who", it) }
        obs.clock?.let { put("time", "day ${it.day}, ${it.time}") }
        obs.money?.let { put("money", it) }
        obs.needs?.let { n ->
            putJsonObject("needs") {
                put("energy", n.energy)
                put("fullness", n.fullness)
                put("mood", n.mood)
                put("stimulation", n.stimulation)
            }
        }
        obs.holding?.takeIf { it.isNotEmpty() }?.let { h -> putJsonArray("holding") { h.forEach { add(it) } } }
        obs.owns?.let { o -> putJsonObject("owns") { o.forEach { (id, n) -> put(id, n) } } }
        put("just_chose", Describe.action(intent))
        plan?.text?.let { put("latest_thought", it) }
        if (obs.recentEvents.isNotEmpty()) putJsonArray("recent_events") { obs.recentEvents.forEach { add(it) } }
    }

    /**
     * What could come back to him: every day memory, the level-1 memories of today and yesterday
     * and any not yet in a day memory (older days are their day memory), and his kept thoughts
     * except the latest, which is shown as his plan anyway.
     */
    private fun candidates(obs: Observation): List<Recollection> {
        val today = obs.clock?.day
        val days = store.memories(run, 2)
        val inDays = days.lastOrNull()?.toId ?: 0L
        val memories = store.memories(run, 1).filter { it.id > inDays || (today != null && (it.span.toDay ?: 0) >= today - 1) }
        val current = plan
        val thoughts = keptThoughts.filter { (_, t) -> !(current != null && t.tick == current.tick && t.text == current.text) }
        return days.map { Recollection(Recollection.Kind.Day, it.id, "Day ${it.span.toDay ?: ""}".trimEnd(), it.text, it.importance, orderOf(it.span.toDay, it.span.toTime) ?: it.toId) } +
            memories.map { Recollection(Recollection.Kind.Memory, it.id, it.span.label, it.text, it.importance, orderOf(it.span.toDay, it.span.toTime) ?: it.toId) } +
            thoughts.map { (id, t) ->
                Recollection(Recollection.Kind.Thought, id, Span(t.day, t.time, t.day, t.time).label, t.text.orEmpty(), null, orderOf(t.day, t.time) ?: id)
            }
    }

    /** Game minutes since the start of day 1, or null without a clock. */
    private fun orderOf(day: Int?, time: String?): Long? =
        if (day == null || time == null) null else (day - 1L) * GameClock.MINUTES_PER_DAY + GameClock.parseHhmm(time)

    /** The memories and earlier thoughts to show for [obs], as text lines, oldest first. */
    fun recall(obs: Observation): List<String> {
        val candidates = candidates(obs)
        val ranked = relevance
        val picked = if (scorer != null && ranked.isNotEmpty()) rule.selectRanked(candidates, ranked, orderOf(obs.clock?.day, obs.clock?.time))
        else rule.select(candidates, context(obs))
        lastRecalled = picked.filter { it.kind != Recollection.Kind.Thought }.map { it.id }.toSet()
        lastRecalledThoughts = picked.filter { it.kind == Recollection.Kind.Thought }.map { it.id }.toSet()
        return picked.map(rule::render)
    }

    private fun context(obs: Observation): String = buildList {
        add(Describe.state(obs))
        obs.activities.orEmpty().filter { it.locked == null }.forEach { add(it.id) }
        obs.shop.orEmpty().forEach { add(it.id) }
        plan?.text?.let(::add)
    }.joinToString(" ").replace('_', ' ')

    /** Waits for background scoring, summaries and authored results being kept, then summarizes whatever is left. Call at the end of a run. */
    suspend fun flush() {
        scoringJob?.join()
        summaryJob?.join()
        authors.flush()
        summaries.withLock { summarizer?.flush() }
    }

    private companion object {
        /** Most events scored for significance in one request. */
        const val SCORE_BATCH = 200

        /** How many of his latest answers each prompt shows. */
        const val SHOWN_ANSWERS = 2
    }
}
