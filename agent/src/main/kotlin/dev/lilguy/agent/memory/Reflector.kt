package dev.lilguy.agent.memory

import dev.lilguy.decision.ActionSchema
import dev.lilguy.decision.DecisionModel
import dev.lilguy.decision.DecisionRequest
import dev.lilguy.decision.Message
import dev.lilguy.decision.SchemaHints
import dev.lilguy.decision.Usage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

@Serializable
@SerialName("reflection")
data class ReflectionReply(val reflection: String)

/**
 * He steps back. His thoughts of the moment say why he does the next thing; every so often this
 * has a text model read what has happened since he last stepped back (what he did and how it went,
 * what happened around him, what he thought as he went) with who he is, his self-model, his latest
 * day and what he concluded the times before, and write what it adds up to: a pattern, a lesson
 * that would hold another day, how he feels about how it's going, a question he's left with. Each
 * builds on the ones before, so they go somewhere instead of starting over.
 *
 * A reflection is a memory of level [LEVEL] covering the events it read, so where the next one
 * starts and every one so far live in the store. The prompts show the latest few; older ones are
 * recall candidates; the night's self-model revision reads the day's.
 *
 * When: with [threshold], once the significance of the events since the last one adds up to it,
 * never with fewer than [minEvents]; without, every [every] events. Never run past [MAX_EVENTS]
 * events in one (it reads the latest of a longer stretch).
 *
 * [model] must write free text, so a closed-choice model (TypeSafe's Jev) can't be the writer.
 */
class Reflector(
    private val model: DecisionModel,
    private val store: MemoryStore,
    private val run: String,
    /** What is true of him by design, in the second person. */
    private val core: String,
    val threshold: Double? = null,
    val minEvents: Int = 12,
    val every: Int = 30,
    private val timeout: Duration = 60.seconds,
) {
    init {
        require(!model.capabilities.closedChoice) { "reflecting needs a model that writes text; '${model.id}' only picks from options" }
    }

    val writer: String get() = model.id

    private val schema = ActionSchema.of(
        "reflection",
        ReflectionReply.serializer(),
        SchemaHints(descriptions = mapOf("reflection.reflection" to "What you think, stepping back: first person, your own voice, under $WORD_CAP words.")),
    ) { null }

    /** Every reflection so far, oldest first. */
    fun reflections(): List<StoredMemory> = store.memories(run, LEVEL)

    /** True when enough has happened since the last one to step back. */
    fun ready(): Boolean {
        val since = store.eventsAfter(run, store.coveredThrough(run, LEVEL), MAX_EVENTS)
        if (since.size < minEvents) return false
        val t = threshold ?: return since.size >= every
        return since.size >= MAX_EVENTS || since.sumOf { it.significance ?: Summarizer.UNSCORED } >= t
    }

    /**
     * Writes one reflection over the events since the last; [self] is his self-model as the prompts
     * show it. Returns it as a [Thought] (with [Thought.reflection] set; null text and an error when
     * the model failed, and then nothing is stored), or null when there was nothing to reflect on.
     */
    suspend fun reflect(self: String?, tick: Long): Thought? {
        val from = store.coveredThrough(run, LEVEL)
        val all = store.eventsAfter(run, from, 10_000)
        if (all.isEmpty()) return null
        val events = all.takeLast(MAX_EVENTS)
        val last = events.last()
        val started = TimeSource.Monotonic.markNow()
        val earlier = reflections().takeLast(EARLIER)
        val day = store.memories(run, 2).lastOrNull()
        val user = buildString {
            if (self != null) appendLine("How you see yourself:\n$self\n")
            day?.let { appendLine("Your memory of your latest whole day (${it.span.label}):\n${it.text}\n") }
            if (earlier.isNotEmpty()) {
                appendLine("What you concluded the last times you stepped back, oldest first:")
                earlier.forEach { appendLine("- ${it.span.toDay?.let { d -> "Day $d ${it.span.toTime}" } ?: "earlier"}: ${it.text}") }
                appendLine()
            }
            appendLine("What has happened since" + (if (all.size > events.size) " (the latest of it)" else "") + ", oldest first:")
            var at: String? = null
            events.forEach { e ->
                val where = e.place?.takeIf { it != at }?.let { " (in $it)" }.orEmpty()
                at = e.place ?: at
                appendLine("[${e.stamp}]$where ${label(e.kind)}: ${e.text}")
            }
            append("\nStep back and think it over.")
        }
        val request = DecisionRequest(system = system(core), messages = listOf(Message(Message.Role.User, user)), temperature = TEMPERATURE, timeout = timeout)
        var usage = Usage()
        fun failed(error: String) = Thought(tick, last.day, last.time, model.id, null, null, usage, started.elapsedNow(), error, reflection = true)
        val reply = try {
            withTimeout(timeout) { model.decide(request, schema) }.also { usage = it.usage }
        } catch (e: TimeoutCancellationException) {
            return failed("timed out after $timeout")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return failed(e.message ?: e.toString())
        }
        val text = reply.value.reflection.trim().ifEmpty { return failed("the reflection was empty") }
        val span = Span(events.first().day, events.first().time, last.day, last.time)
        val importance = events.mapNotNull { it.significance }.maxOrNull()
        val places = events.mapNotNull { it.place }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
        store.addMemory(run, LEVEL, all.first().id, last.id, span, text, model.id, importance, places)
        return Thought(tick, last.day, last.time, model.id, text, reply.raw, usage, started.elapsedNow(), reflection = true)
    }

    private fun label(kind: EventKind) = when (kind) {
        EventKind.Outcome -> "I did"
        EventKind.World -> "Happened"
        EventKind.Snapshot -> "How things stood"
        EventKind.Thought -> "I thought, in the moment"
        EventKind.Art -> "I made"
        EventKind.Reading -> "I read"
        EventKind.Answer -> "I found out"
        EventKind.Search -> "I looked up"
        EventKind.Shopping -> "I shopped"
        EventKind.Invention -> "I invented"
        EventKind.Letter -> "I wrote"
    }

    companion object {
        /** The memory level reflections are stored at: over events, beside the stretches and days. */
        const val LEVEL = 4

        const val WORD_CAP = 130
        const val TEMPERATURE = 0.7

        /** A starting [threshold]: about three of the summarizer's stretches (`Summarizer.THRESHOLD`), so three or four a day. Not tuned. */
        const val THRESHOLD = 4.5

        /** Most events one reflection reads. */
        const val MAX_EVENTS = 80

        /** Earlier reflections it reads, to build on. */
        const val EARLIER = 4

        fun system(core: String) = """
            |You are the inner voice of a little guy living his life. Who he is, by design:
            |$core
            |
            |From moment to moment he thinks about the next thing to do. Every so often he stops and steps back to think about how his life is going. You're given what has happened since he last did, with the thoughts he had in the moment, and what he concluded the times before.
            |
            |Write what he thinks now, stepping back. Not a recap of what he did, and not his needs or money in numbers ("I was worn out", not "energy 19"): what it all adds up to. Go deep on one or two of these rather than touching all of them:
            |- a pattern: something that keeps happening, or that he keeps doing, and why;
            |- a lesson he can carry to other days, said generally enough to hold again: not what happened, but what it tells him about how his world works or how he works;
            |- how he feels about the way things are going, and what he wants that he isn't getting;
            |- something he's wondering about, a question he's left with, or something he wants to try differently.
            |Build on what he concluded before: take it further, test it against what has happened since, or change his mind. Don't say it again. If his days have been going round in circles, notice that.
            |
            |First person, in his own voice, plain words, under $WORD_CAP words. Say only what the log and his memories bear out.
            |Reply with a JSON object {"reflection": ...}.
        """.trimMargin()
    }
}
