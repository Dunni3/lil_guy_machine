package dev.lilguy.agent.memory

import dev.lilguy.decision.ActionSchema
import dev.lilguy.decision.DecisionModel
import dev.lilguy.decision.DecisionRequest
import dev.lilguy.decision.Message
import dev.lilguy.decision.SchemaHints
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@Serializable
@SerialName("memory")
data class MemoryReply(val memory: String)

/**
 * The second and third layers: reads a stretch of the event stream, has a language model write it
 * up as a level-1 memory, and stores that; and when he goes to sleep, turns the day's level-1
 * memories into one day memory (level 2, [summarizeDay]). Each job starts where the last one
 * stopped, so everything is covered once. A failed job stores nothing and is retried from the same
 * place next time.
 *
 * When a stretch is written up: with [threshold], once the significance of its events adds up to it
 * (eventful stretches make memories often, quiet ones rarely), never with fewer than [minEvents];
 * without, every [batchSize] events. A stretch never runs past [batchSize] events or across a day
 * change, and [flush] writes up whatever is left. Each memory is written with the one before it in
 * view, so they read as one story.
 *
 * [model] must write free text, so a closed-choice model (TypeSafe's Jev) can't be the writer.
 */
class Summarizer(
    private val model: DecisionModel,
    private val store: MemoryStore,
    private val run: String,
    /** Most events one memory covers; without [threshold], also how many make one. */
    val batchSize: Int = 20,
    /** With [threshold]: fewest events one memory covers. */
    val minEvents: Int = 4,
    /** The significance (0 to 1 an event) a stretch adds up to before it's written up; null for fixed batches. */
    val threshold: Double? = null,
    private val timeout: Duration = 60.seconds,
    private val onFailure: (Throwable) -> Unit = {},
) {
    init {
        require(!model.capabilities.closedChoice) { "the summarizer needs a model that writes text; '${model.id}' only picks from options" }
        require(minEvents in 1..batchSize) { "minEvents must be from 1 to batchSize" }
    }

    private val schema = ActionSchema.of(
        "memory",
        MemoryReply.serializer(),
        SchemaHints(descriptions = mapOf("memory.memory" to "The memory, in the first person.")),
    ) { null }

    /** The writing model's id. */
    val writer: String get() = model.id

    /** Events not yet covered by a level-1 memory. */
    fun pending(): Int = store.countEventsAfter(run, store.coveredThrough(run, 1))

    /** The significance the uncovered events add up to so far (unscored ones count [UNSCORED]). */
    fun pendingSignificance(): Double =
        store.eventsAfter(run, store.coveredThrough(run, 1), batchSize).sumOf { it.significance ?: UNSCORED }

    /** True when the next stretch is ready to be written up. */
    fun ready(): Boolean = nextBatch(force = false) != null

    /**
     * The next stretch to write up, or null when it isn't ready ([force] writes up whatever is
     * there). It stops at the first event of a later day.
     */
    private fun nextBatch(force: Boolean): List<MemoryEvent>? {
        val events = store.eventsAfter(run, store.coveredThrough(run, 1), batchSize + 1)
        if (events.isEmpty()) return null
        val stretch = events.takeWhile { it.day == events.first().day }.take(batchSize)
        // More follows it: the next day has begun, or the stretch is as long as one gets.
        val closed = stretch.size < events.size
        threshold?.let { t ->
            var sum = 0.0
            stretch.forEachIndexed { i, e ->
                sum += e.significance ?: UNSCORED
                if (i + 1 >= minEvents && sum >= t) return stretch.take(i + 1)
            }
        }
        if (threshold == null && stretch.size >= batchSize) return stretch
        return if (closed || force) stretch else null
    }

    /** Writes one memory from the next stretch. Returns null if nothing was ready or the model failed. */
    suspend fun summarizeOnce(force: Boolean = false): StoredMemory? {
        val events = nextBatch(force) ?: return null
        val previous = store.memories(run, 1).lastOrNull()
        val text = write(SYSTEM, buildString {
            previous?.let { appendLine("Your memory of just before this, for continuity (don't repeat it):\n${it.text}\n") }
            appendLine("Log:")
            events.forEach { appendLine("[${it.stamp}] ${verb(it.kind)}: ${it.text}") }
            append("\nWrite the memory.")
        }) ?: return null
        val first = events.first()
        val last = events.last()
        val span = Span(first.day, first.time, last.day, last.time)
        val importance = events.mapNotNull { it.significance }.maxOrNull()
        val id = store.addMemory(run, 1, first.id, last.id, span, text, model.id, importance)
        return StoredMemory(id, run, 1, first.id, last.id, span, text, model.id, importance)
    }

    /** Writes up stretches while they're ready, until one isn't or a job fails. */
    suspend fun catchUp() {
        while (summarizeOnce() != null) Unit
    }

    /** Writes up everything left, a short last stretch included: at bedtime and at the end of a run. */
    suspend fun flush() {
        while (pending() > 0) summarizeOnce(force = true) ?: return
    }

    /**
     * Turns the level-1 memories since the last day memory into one (level 2), when he goes to
     * sleep on [day]. Skipped when there's nothing new, or a day memory already ended on [day] (a
     * second sleep the same day). Returns null then, or if the model failed.
     */
    suspend fun summarizeDay(day: Int?): StoredMemory? {
        val days = store.memories(run, 2)
        if (day != null && days.lastOrNull()?.span?.toDay == day) return null
        val memories = store.memories(run, 1).filter { it.id > (days.lastOrNull()?.toId ?: 0L) }
        if (memories.isEmpty()) return null
        val text = write(DAY_SYSTEM, buildString {
            days.lastOrNull()?.let { appendLine("Your memory of the day before, for continuity (don't repeat it):\n${it.text}\n") }
            appendLine("Your memories of this day, oldest first:")
            memories.forEach { appendLine("- ${it.span.label.ifEmpty { "earlier" }}: ${it.text}") }
            append("\nWrite the memory of this day.")
        }) ?: return null
        val span = Span(memories.first().span.fromDay, memories.first().span.fromTime, memories.last().span.toDay, memories.last().span.toTime)
        val importance = memories.mapNotNull { it.importance }.maxOrNull()
        val id = store.addMemory(run, 2, memories.first().id, memories.last().id, span, text, model.id, importance)
        return StoredMemory(id, run, 2, memories.first().id, memories.last().id, span, text, model.id, importance)
    }

    private suspend fun write(system: String, user: String): String? {
        val request = DecisionRequest(system = system, messages = listOf(Message(Message.Role.User, user)), temperature = 0.3, timeout = timeout)
        val text = try {
            withTimeout(timeout) { model.decide(request, schema) }.value.memory.trim()
        } catch (e: CancellationException) {
            if (e is kotlinx.coroutines.TimeoutCancellationException) { onFailure(e); return null }
            throw e
        } catch (e: Exception) {
            onFailure(e)
            return null
        }
        return text.ifEmpty { null }
    }

    private fun verb(kind: EventKind) = when (kind) {
        EventKind.Outcome -> "I did"
        EventKind.World -> "Happened"
        EventKind.Snapshot -> "How things stood"
        EventKind.Thought -> "I thought"
        EventKind.Art -> "I made"
        EventKind.Reading -> "I read"
        EventKind.Answer -> "I found out"
    }

    companion object {
        /** What an event not scored yet counts toward [threshold]: a little, so a quiet stretch still adds up. */
        const val UNSCORED = 0.15

        /**
         * A starting [threshold]: routine events score near 0 and notable ones about 0.5, so a quiet
         * stretch runs to [batchSize] and a day makes a handful of memories. Not tuned.
         */
        const val THRESHOLD = 1.5

        val SYSTEM = """
            |You write the memories of a little guy who lives alone in a bedroom. You are given a log of a stretch of his life, oldest first: "I did" lines are what he did and how each turned out (what it gave and cost, or why it failed), "Happened" lines are things that happened around him, "How things stood" is his money, needs and belongings when he woke, "I thought" lines are what he thought when he stopped to think, "I made" lines are art he finished.
            |Write one memory of this stretch, in the first person, as he would remember it: what he did, what changed (money, needs, things he got), what worked or didn't, and anything he should keep in mind next time.
            |Say only what the log shows. Keep it to two to four sentences and do not repeat the log line by line.
            |Reply with a JSON object {"memory": ...}.
        """.trimMargin()

        val DAY_SYSTEM = """
            |You write the memories of a little guy who lives alone in a bedroom. You are given his memories of one day, oldest first, each covering a stretch of it.
            |Write his memory of the whole day, in the first person, as he would remember it a week later: what the day was about, what changed for him, what he learned, and what he was working toward by the end of it.
            |Say only what the memories show. Keep it to three to five sentences.
            |Reply with a JSON object {"memory": ...}.
        """.trimMargin()
    }
}
