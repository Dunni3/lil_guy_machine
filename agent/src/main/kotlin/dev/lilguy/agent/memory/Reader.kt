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

@Serializable
@SerialName("reading")
data class ReadingReply(val stuck: String)

/**
 * The `reading` handler's model call: after he reads or studies a chapter, a text model writes, in
 * his words, what stuck with him from it. One call, one short reply, from who he is and what
 * happens in the chapter (not his whole situation, so it stays cheap). The result is a [Thought]
 * with [Thought.thinker] the reader; [ReadingHandler] puts it in the event stream.
 *
 * [model] must write free text, so a closed-choice model (TypeSafe's Jev) can't be the reader.
 */
class Reader(
    private val model: DecisionModel,
    /** Retries included. */
    private val timeout: Duration = 60.seconds,
) {
    init {
        require(!model.capabilities.closedChoice) { "reading needs a model that writes text; '${model.id}' only picks from options" }
    }

    val reader: String get() = model.id

    private val schema = ActionSchema.of(
        "reading",
        ReadingReply.serializer(),
        SchemaHints(descriptions = mapOf("reading.stuck" to "What stuck with you from the chapter, first person, under $WORD_CAP words.")),
    ) { null }

    /**
     * [persona] is who he is; [title] the book; [chapter] what happens in the chapter he read, or null
     * when he reread a book he'd finished. Never throws for a model failure: the result says what went wrong.
     */
    suspend fun read(persona: String, title: String, chapter: String?, studied: Boolean, tick: Long, day: Int?, time: String?): Thought {
        val started = TimeSource.Monotonic.markNow()
        val what = if (chapter == null) "You reread \"$title\", which you've finished before." else
            "You ${if (studied) "studied" else "read"} a chapter of \"$title\". What happens in it: $chapter"
        val request = DecisionRequest(
            system = persona.trimEnd() + "\n\n" + INSTRUCTIONS,
            messages = listOf(Message(Message.Role.User, "$what\n\n$ASK")),
            temperature = TEMPERATURE,
            timeout = timeout,
        )
        fun failed(error: String, raw: String? = null, usage: Usage = Usage()) =
            Thought(tick, day, time, model.id, null, raw, usage, started.elapsedNow(), error)
        return try {
            val decision = withTimeout(timeout) { model.decide(request, schema) }
            val text = decision.value.stuck.trim()
            if (text.isEmpty()) failed("the reply was empty", decision.raw, decision.usage)
            else Thought(tick, day, time, model.id, text, decision.raw, decision.usage, started.elapsedNow())
        } catch (e: TimeoutCancellationException) {
            failed("timed out after $timeout")
        } catch (e: CancellationException) {
            throw e
        } catch (e: DecisionFailed) {
            failed(e.message ?: e.toString(), e.attempts.lastOrNull()?.raw, e.usage)
        } catch (e: Exception) {
            failed(e.message ?: e.toString())
        }
    }

    companion object {
        const val WORD_CAP = 40
        const val TEMPERATURE = 0.8
        const val ASK = "What stuck with you?"

        val INSTRUCTIONS = """
            |You have just put a book down. Say what stuck with you from what you read: an idea, a feeling, a line you keep thinking about, or something it makes you want to try. Write in the first person, as yourself, in one or two sentences under $WORD_CAP words.
            |
            |Reply with a JSON object {"stuck": ...} and nothing else.
        """.trimMargin()
    }
}
