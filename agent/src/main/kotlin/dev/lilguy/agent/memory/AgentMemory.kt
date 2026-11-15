package dev.lilguy.agent.memory

import dev.lilguy.agent.invent.Drafted
import dev.lilguy.agent.invent.Inventing
import dev.lilguy.agent.invent.InventionHandler
import dev.lilguy.agent.shop.Shopper
import dev.lilguy.agent.shop.ShoppingHandler
import dev.lilguy.agent.shop.Trip
import dev.lilguy.agent.web.Search
import dev.lilguy.agent.web.Searcher

import dev.lilguy.agent.art.ArtStudio
import dev.lilguy.agent.art.Underway
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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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
    /** Every answer to what he asked while thinking, kept or not; called from whichever coroutine wrote it. */
    private val onAnswer: (Answer) -> Unit = {},
    /** Picks what he buys on a shopping trip; without one there are no trips, and he buys everything with `buy`. */
    private val shopper: Shopper? = null,
    /** Every shopping trip, kept or not; called from the agent coroutine. */
    private val onTrip: (Trip) -> Unit = {},
    /** Designs what he invents at the workbench; without one he isn't offered `invent`. */
    private val inventor: Inventing? = null,
    /** Every design, built or not; called from the agent coroutine. */
    private val onDraft: (Drafted) -> Unit = {},
    /** Searches the real web for him (the `search` look); without one he can't. */
    private val searcher: Searcher? = null,
    /** Every web search, found or not; called from the agent coroutine. */
    private val onSearch: (Search) -> Unit = {},
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

    /** Where he was when he had a kept thought, by its event id (not kept across a checkpoint). */
    private val thoughtPlaces = java.util.concurrent.ConcurrentHashMap<Long, String>()

    /** Answers to what he asked while thinking, from kept thinks, oldest first. */
    private val keptAnswers = CopyOnWriteArrayList<Answer>()

    private val tripLog = CopyOnWriteArrayList<Trip>()
    /** Every web search, oldest first; an entry is replaced when his thought and what he did next are known. */
    private val searchLog = CopyOnWriteArrayList<Search>()
    private val revisionLog = CopyOnWriteArrayList<SelfRevision>()

    /** His latest kept thought shown as his plan (`shows_as: plan`), or null. */
    @Volatile var plan: Thought? = null
        private set

    private val authors: Authors = authors()

    private fun authors(): Authors {
        val handlers = mutableListOf<AuthorHandler<*>>()
        if (thinker != null) handlers += ThoughtHandler(thinker, store, run, onThought = { t -> thoughtLog += t; t.asked.forEach(onAnswer) }, onPlan = { t -> plan = t }, onStored = { id, t ->
            keptThoughts += id to t
            (store as? PlacedStore)?.where?.invoke()?.let { thoughtPlaces[id] = it }
        }) { a -> keptAnswers += a }
        if (studio != null) handlers += studio
        if (reader != null) handlers += ReadingHandler(reader, store, run)
        if (shopper != null) handlers += ShoppingHandler(shopper, store, run) { t -> tripLog += t; onTrip(t) }
        if (inventor != null) handlers += InventionHandler(inventor, store, run, onDraft)
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
    val thoughtWriter: String? get() = authors.writer("thought") ?: decidingThinker

    /** The deciding model, once it has said what it thinks as it chose ([thought]). */
    @Volatile private var decidingThinker: String? = null

    /** True when he can ask how his world works ([ask]). */
    val canAsk: Boolean get() = thinker?.oracle != null

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

    /** The latest piece he started, stroke by stroke, and how it ended ([ArtStudio.underway]). Readable from any thread. */
    fun underway(): Underway? = studio?.underway

    /**
     * What a checkpoint keeps of this object: the bookkeeping between observations, the recall
     * ranking, and the thoughts and answers his prompts show. The memories themselves are rows in
     * the store, and the self-model is the latest level-3 memory there.
     */
    @Serializable
    data class State(
        @SerialName("last_news") val lastNews: List<String>,
        val chosen: Intent?,
        @SerialName("chosen_day") val chosenDay: Int?,
        @SerialName("chosen_time") val chosenTime: String?,
        @SerialName("snapshot_day") val snapshotDay: Int?,
        @SerialName("scored_through") val scoredThrough: Long,
        val relevance: Map<String, Double>,
        /** Kept thoughts with their event ids: recall candidates. */
        val thoughts: List<SavedThought>,
        val answers: List<SavedAnswer>,
        val plan: SavedThought?,
        /** His latest web searches, for the viewer and the day's count. */
        val searches: List<Search> = emptyList(),
    )

    @Serializable
    data class SavedThought(val event: Long = 0, val tick: Long, val day: Int?, val time: String?, val thinker: String, val text: String?) {
        fun thought() = Thought(tick, day, time, thinker, text, raw = null, usage = dev.lilguy.decision.Usage(), latency = kotlin.time.Duration.ZERO)

        companion object {
            fun of(t: Thought, event: Long = 0) = SavedThought(event, t.tick, t.day, t.time, t.thinker, t.text)
        }
    }

    @Serializable
    data class SavedAnswer(val question: String, val opened: List<String>, val reply: SavedThought)

    /** True when nothing runs in the background (scoring, a summary, an authored call): its [state] is whole. */
    val quiet: Boolean get() = summaryJob?.isActive != true && scoringJob?.isActive != true && authors.quiet

    /** Only when [quiet], and while the agent loop is held ([dev.lilguy.agent.AgentRuntime.hold]). */
    fun state() = State(
        lastNews = lastNews.toList(),
        chosen = chosen?.intent,
        chosenDay = chosen?.at?.day,
        chosenTime = chosen?.at?.time,
        snapshotDay = snapshotDay,
        scoredThrough = scoredThrough,
        relevance = relevance,
        thoughts = keptThoughts.map { (id, t) -> SavedThought.of(t, id) },
        answers = keptAnswers.map { SavedAnswer(it.question, it.opened, SavedThought.of(it.reply)) },
        plan = plan?.let { SavedThought.of(it) },
        searches = searchLog.toList().takeLast(KEPT_SEARCHES),
    )

    /** Puts back a checkpoint's [State]. Call before the run starts, after [connect]. */
    fun restore(s: State) {
        lastNews = s.lastNews.toSet()
        chosen = s.chosen?.let { Chosen(it, if (s.chosenDay != null && s.chosenTime != null) ClockReading(s.chosenDay, s.chosenTime) else null) }
        snapshotDay = s.snapshotDay
        scoredThrough = s.scoredThrough
        relevance = s.relevance
        keptThoughts.clear()
        thoughtPlaces.clear()
        s.thoughts.forEach { keptThoughts += it.event to it.thought() }
        thoughtLog.clear()
        thoughtLog.addAll(keptThoughts.map { it.second })
        keptAnswers.clear()
        s.answers.forEach { keptAnswers += Answer(it.question, it.opened, it.reply.thought()) }
        plan = s.plan?.thought()
        searchLog.clear()
        searchLog.addAll(s.searches)
        searcher?.counted(s.searches)
    }

    /** Runs authored ops for [world]. Call before the run starts. */
    fun connect(world: BedroomWorld) {
        // Every event is stamped with the place he's in as the sim last published it.
        (store as? PlacedStore)?.where = {
            val layout = world.layout
            if (!layout.outdoors) null else world.snapshot().entities.firstOrNull { it.id == world.agentId }?.place?.let { layout.place(it)?.name }
        }
        authors.connect(world)
        studio?.connect(world)
        thinker?.oracle?.connect(world)
        shopper?.connect(world)
        inventor?.connect(world)
        restVerbs = world.content.verbs.filter { v -> v.effects.any { it is SleepOp } }.map { it.id }.toSet()
        about = world.content.personality.core.he
    }

    /** His latest [n] kept answers, oldest first: what later prompts show of what he found out. Readable from any thread. */
    fun answers(n: Int = SHOWN_ANSWERS): List<Answer> = keptAnswers.toList().takeLast(n)

    /** Every thought so far, failed ones included, oldest first. Readable from any thread. */
    fun thoughts(): List<Thought> = thoughtLog.toList()

    /** Every web search so far, failed and refused ones included, oldest first. Readable from any thread. */
    fun searches(): List<Search> = searchLog.toList()

    /** His latest [n] searches that found something, oldest first: what later prompts show of what he looked up. */
    fun lookedUp(n: Int = SHOWN_ANSWERS): List<Search> = searchLog.filter { it.found }.takeLast(n)

    /** True when he can search the web. */
    val canSearch: Boolean get() = searcher != null

    /** What searches the web for him, or null. */
    val searcherId: String? get() = searcher?.id

    /**
     * He searches the real web for [query]: what comes back goes into the event stream (so his
     * memories and recall have it) and his next prompts. Null when nothing searches ([canSearch]).
     */
    suspend fun searchWeb(query: String, obs: Observation): Search? {
        val s = searcher ?: return null
        val search = s.search(query, obs)
        searchLog += search
        onSearch(search)
        search.remembered()?.let { append(obs, EventKind.Search, it) }
        return search
    }

    /** The searches of the decision at [tick] that don't have it yet get [change]. */
    private fun annotateSearches(tick: Long, change: (Search) -> Search?) {
        searchLog.forEachIndexed { i, s -> if (s.tick == tick) change(s)?.let { searchLog[i] = it } }
    }

    /** Every shopping trip so far, failed ones included, oldest first. Readable from any thread. */
    fun trips(): List<Trip> = tripLog.toList()

    /** Every nightly revision of his self-model so far, changed or not, oldest first. Readable from any thread. */
    fun revisions(): List<SelfRevision> = revisionLog.toList()

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
        annotateSearches(obs.tick) { s -> if (s.then == null) s.copy(then = Describe.action(intent)) else null }
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

    /**
     * What he thought as he chose, said by the deciding model ([thinker]) rather than a think: it
     * goes into the event stream and becomes his [plan], like a kept thought. Returns it, or null
     * for a blank one or the same words as his latest.
     */
    fun thought(obs: Observation, text: String, thinker: String): Thought? {
        val words = text.trim()
        if (words.isNotEmpty()) annotateSearches(obs.tick) { s -> if (s.thought == null) s.copy(thought = words) else null }
        if (words.isEmpty() || words == plan?.text) return null
        decidingThinker = thinker
        val t = Thought(obs.tick, obs.clock?.day, obs.clock?.time, thinker, words, raw = null, usage = dev.lilguy.decision.Usage(), latency = kotlin.time.Duration.ZERO)
        thoughtLog += t
        keptThoughts += append(obs, EventKind.Thought, words) to t
        plan = t
        return t
    }

    /**
     * He tries to remember [query]: the few memories, day memories and earlier thoughts that answer
     * it best, oldest first, as text lines. Jev scores them against it when there's a [scorer];
     * otherwise by keywords. Any of his life, not only what recall would show now.
     */
    suspend fun search(query: String, obs: Observation, n: Int = SEARCH_RESULTS): List<String> {
        val all = (store.memories(run, 2).map { Recollection(Recollection.Kind.Day, it.id, "Day ${it.span.toDay ?: ""}".trimEnd(), it.text, it.importance, orderOf(it.span.toDay, it.span.toTime) ?: it.toId) } +
            store.memories(run, 1).map { Recollection(Recollection.Kind.Memory, it.id, it.span.label, it.text, it.importance, orderOf(it.span.toDay, it.span.toTime) ?: it.toId) } +
            keptThoughts.map { (id, t) -> Recollection(Recollection.Kind.Thought, id, Span(t.day, t.time, t.day, t.time).label, t.text.orEmpty(), null, orderOf(t.day, t.time) ?: id) })
            .sortedBy { it.order }.takeLast(SEARCHED)
        if (all.isEmpty()) return emptyList()
        val scores = scorer?.search(buildJsonObject {
            about?.let { put("who", it) }
            obs.clock?.let { put("time", "day ${it.day}, ${it.time}") }
            put("looking_for", query)
        }, all).orEmpty()
        val picked = if (scores.isNotEmpty()) all.filter { (scores[it.key] ?: 0.0) >= SEARCH_FLOOR }.sortedByDescending { scores[it.key] }.take(n)
        else Keywords.rank(all, query, n, { it.text }, { it.order }).map { it.first }
        return picked.sortedBy { it.order }.map(rule::render)
    }

    /**
     * He asks how his world works, as he would while thinking: the oracle answers from the world's
     * docs and [situation]. A kept answer goes into the event stream and his later prompts. Null
     * when nothing can answer ([canAsk]).
     */
    suspend fun ask(question: String, obs: Observation, situation: String): Answer? {
        val oracle = thinker?.oracle ?: return null
        val started = kotlin.time.TimeSource.Monotonic.markNow()
        val (opened, text, usage) = oracle.answer(question, situation)
        val reply = Thought(obs.tick, obs.clock?.day, obs.clock?.time, oracle.writer, text.getOrNull(), null, usage, started.elapsedNow(), text.exceptionOrNull()?.let { "answer: ${it.message ?: it}" })
        val answer = Answer(question, opened, reply)
        onAnswer(answer)
        reply.text?.let { t ->
            append(obs, EventKind.Answer, "I asked \"$question\": $t")
            keptAnswers += answer
        }
        return answer
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
        revisionLog += revision
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
        placeName(obs)?.let { put("where", it) }
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
        // Thoughts of today and yesterday; older ones live on in the day memories, as the memories do.
        val thoughts = keptThoughts.filter { (_, t) -> !(current != null && t.tick == current.tick && t.text == current.text) && (today == null || (t.day ?: today) >= today - 1) }
        return days.map { Recollection(Recollection.Kind.Day, it.id, "Day ${it.span.toDay ?: ""}".trimEnd(), it.text, it.importance, orderOf(it.span.toDay, it.span.toTime) ?: it.toId) } +
            memories.map { Recollection(Recollection.Kind.Memory, it.id, it.span.label, it.text, it.importance, orderOf(it.span.toDay, it.span.toTime) ?: it.toId, it.places) } +
            thoughts.map { (id, t) ->
                Recollection(Recollection.Kind.Thought, id, Span(t.day, t.time, t.day, t.time).label, t.text.orEmpty(), null, orderOf(t.day, t.time) ?: id, listOfNotNull(thoughtPlaces[id]))
            }
    }

    /** Game minutes since the start of day 1, or null without a clock. */
    private fun orderOf(day: Int?, time: String?): Long? =
        if (day == null || time == null) null else (day - 1L) * GameClock.MINUTES_PER_DAY + GameClock.parseHhmm(time)

    /** The memories and earlier thoughts to show for [obs], as text lines, oldest first. */
    fun recall(obs: Observation): List<String> {
        val candidates = candidates(obs)
        val ranked = relevance
        val picked = if (scorer != null && ranked.isNotEmpty()) rule.selectRanked(candidates, ranked, orderOf(obs.clock?.day, obs.clock?.time), placeName(obs))
        else rule.select(candidates, context(obs))
        lastRecalled = picked.filter { it.kind != Recollection.Kind.Thought }.map { it.id }.toSet()
        lastRecalledThoughts = picked.filter { it.kind == Recollection.Kind.Thought }.map { it.id }.toSet()
        return picked.map(rule::render)
    }

    /** The place he's in, by name, without where in it ("the forest clearing"). */
    private fun placeName(obs: Observation): String? = obs.place?.substringBefore(", by ")

    private fun context(obs: Observation): String = buildList {
        placeName(obs)?.let { add(it) }
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

        /** How many of his latest answers (and, apart, searches) each prompt shows. */
        const val SHOWN_ANSWERS = 2

        /** How many searches a checkpoint keeps: as many as the viewer's log. */
        const val KEPT_SEARCHES = 300

        /** Most recollections one [search] returns, of the latest [SEARCHED] it looks through. */
        const val SEARCH_RESULTS = 5
        const val SEARCHED = 150

        /** Below this score (0 to 1) a recollection doesn't answer the search: "touches the same things". */
        const val SEARCH_FLOOR = 0.5
    }
}
