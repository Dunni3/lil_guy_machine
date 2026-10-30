package dev.lilguy.agent.memory

import dev.lilguy.decision.ActionSchema
import dev.lilguy.decision.DecisionFailed
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

/** One step of a journal session: look something up in his past, or write the entry. */
@Serializable
sealed interface JournalStep {
    @Serializable
    @SerialName("search")
    data class Search(val source: SearchSource, val query: String) : JournalStep

    @Serializable
    @SerialName("write")
    data class Write(val entry: String, val wants: List<Want>) : JournalStep
}

@Serializable
@SerialName("journal")
data class JournalReply(val step: JournalStep)

/** One model call of a session: what it sent back and, for a search, what was found. */
data class JournalCall(val raw: String, val step: JournalStep, val results: List<SearchHit> = emptyList())

/**
 * One time he sat down to write, for logs and the dev view. [entry] is null when the model failed
 * ([error]); [status] says whether it was kept.
 */
data class JournalSession(
    val tick: Long,
    val day: Int?,
    val time: String?,
    val writer: String,
    val calls: List<JournalCall>,
    val entry: String?,
    val wants: List<Want>,
    val usage: Usage,
    val latency: Duration,
    val error: String? = null,
    val status: Status = Status.Written,
) {
    enum class Status {
        /** Written, waiting for the activity to finish. */
        Written,

        /** The activity succeeded and the entry is in the journal. */
        Stored,

        /** The activity failed or was interrupted, so the entry was thrown away. */
        Discarded,

        /** The model gave no entry. */
        Failed,
    }

    val searches: Int get() = calls.count { it.step is JournalStep.Search }
}

/**
 * The journal's second LLM call, beside [Summarizer]: when he sits down to write, a text model
 * gets his persona and his situation, may search his past (memories, events, earlier entries) up
 * to [maxSearches] times, then must write the entry and the wants he argues for. Each step is a
 * schema-constrained reply (`search` or `write`), so it works with every backend, without native
 * tool calls; search results come back as the next user message. Writing is all it does:
 * [AgentMemory] decides when, and keeps the entry only if the activity succeeds.
 *
 * [model] must write free text, so a closed-choice model (TypeSafe's Jev) can't be the writer.
 */
class JournalWriter(
    private val model: DecisionModel,
    private val store: MemoryStore,
    private val run: String,
    val maxSearches: Int = 3,
    /** Per model call, retries included. */
    private val timeout: Duration = 90.seconds,
    private val hitsPerSearch: Int = 5,
) {
    init {
        require(!model.capabilities.closedChoice) { "the journal needs a model that writes text; '${model.id}' only picks from options" }
    }

    val writer: String get() = model.id

    private fun schema(searchesLeft: Int) = ActionSchema.of(
        "journal",
        JournalReply.serializer(),
        SchemaHints(
            descriptions = mapOf(
                "journal.step" to "Search your past, or write the entry.",
                "search" to "Look something up in your past before writing.",
                "search.source" to "memories: what you remember; events: the raw log of what you saw and did; journal: your earlier entries.",
                "search.query" to "A few keywords.",
                "write" to "Write the entry and what you want.",
                "write.entry" to "The journal entry, first person, under $WORD_CAP words.",
                "write.wants" to "What you want from the world, each with your case for it. Can be empty.",
                "want.kind" to "What sort of thing it is.",
                "want.text" to "What you want, specifically.",
                "want.justification" to "Why: what it would change for you or your world, what it would cost or need, how it fits your plans. Required.",
            ),
            enums = buildMap {
                put("want.kind", WANT_KINDS)
                // No searches left: an empty enum drops the search variant, so the model must write.
                if (searchesLeft <= 0) put("search.query", emptyList())
            },
        ),
    )

    /**
     * Writes one entry. [persona] is who he is; [situation] is his state in the words of the
     * decision prompt. Never throws for a model failure: the session says what went wrong.
     */
    suspend fun write(persona: String, situation: String, tick: Long, day: Int?, time: String?): JournalSession {
        val started = TimeSource.Monotonic.markNow()
        val messages = mutableListOf(Message(Message.Role.User, situation.trimEnd() + "\n\n" + startNote()))
        val calls = mutableListOf<JournalCall>()
        var usage = Usage()
        fun session(entry: String?, wants: List<Want>, error: String? = null) = JournalSession(
            tick, day, time, model.id, calls.toList(), entry, wants, usage, started.elapsedNow(), error,
            if (entry == null) JournalSession.Status.Failed else JournalSession.Status.Written,
        )
        try {
            while (true) {
                val left = maxSearches - calls.count { it.step is JournalStep.Search }
                val request = DecisionRequest(system = system(persona), messages = messages.toList(), temperature = TEMPERATURE, timeout = timeout)
                val decision = withTimeout(timeout) { model.decide(request, schema(left)) }
                usage += decision.usage
                when (val step = decision.value.step) {
                    is JournalStep.Write -> {
                        calls += JournalCall(decision.raw, step)
                        val text = step.entry.trim()
                        return if (text.isEmpty()) session(null, emptyList(), "the entry was empty") else session(text, step.wants)
                    }
                    is JournalStep.Search -> {
                        // A model that ignores the schema and searches anyway is cut off here.
                        if (left <= 0) return session(null, emptyList(), "searched again after the last search")
                        val hits = store.search(run, step.source, step.query, hitsPerSearch)
                        calls += JournalCall(decision.raw, step, hits)
                        messages += Message(Message.Role.Assistant, decision.raw)
                        messages += Message(Message.Role.User, results(step, hits, left - 1))
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            return session(null, emptyList(), "timed out after $timeout")
        } catch (e: CancellationException) {
            throw e
        } catch (e: DecisionFailed) {
            usage += e.usage
            return session(null, emptyList(), e.message ?: e.toString())
        } catch (e: Exception) {
            return session(null, emptyList(), e.message ?: e.toString())
        }
    }

    private fun startNote() =
        "You have sat down at your desk with your journal. Write today's entry. " +
            if (maxSearches > 0) "Before writing you may search your past, up to $maxSearches times; or write straight away." else "Write it now."

    private fun results(step: JournalStep.Search, hits: List<SearchHit>, left: Int): String = buildString {
        appendLine("Search of your ${step.source.id} for \"${step.query}\":")
        if (hits.isEmpty()) appendLine("(nothing found)")
        hits.forEach { appendLine("- ${if (it.stamp.isEmpty()) "" else "${it.stamp}: "}${it.text}") }
        append(if (left > 0) "You can search $left more time${if (left == 1) "" else "s"}, or write your entry." else "No searches left: write your entry now.")
    }

    companion object {
        const val WORD_CAP = 150
        const val TEMPERATURE = 0.8
        val WANT_KINDS = listOf("activity", "item", "place", "sense", "other")

        fun system(persona: String) = """
            |$persona
            |
            |Now you are writing in your journal. Write in the first person, as yourself: how you feel, what you hope for and what frustrates you; how you would like your world to grow; whether you want other activities, things or abilities; whether you want to see the real world outside this one, and what of it; and your plans for getting there. Be specific. Keep the entry under $WORD_CAP words.
            |
            |The one who made this world reads every entry, and may grant what you ask for, but only what you write down as a want with your reasons. A want without a reason is not considered. A good reason says what would change for you or your world, what it would cost or need, and how it fits your plans. You can ask again, and better, if a want isn't granted yet.
            |
            |Your observation's `shop` is the catalog of what you can buy: furniture, decor, tools and things to enjoy for your room, with prices and what each is for. Before you write, think about what in it you want, what you are saving toward and why, and say so in the entry. A want can be an item from the catalog, or something it doesn't have.
            |
            |Each reply is one step, a JSON object {"step": ...}:
            |- {"step": {"type": "search", "source": "memories" | "events" | "journal", "query": "a few keywords"}} looks through your past and shows you what it finds. memories: what you remember; events: the raw log of what you saw and did; journal: your earlier entries.
            |- {"step": {"type": "write", "entry": "...", "wants": [{"kind": ${WANT_KINDS.joinToString(" | ") { "\"$it\"" }}, "text": "...", "justification": "..."}]}} writes the entry and ends the session. wants may be empty.
        """.trimMargin()
    }
}
