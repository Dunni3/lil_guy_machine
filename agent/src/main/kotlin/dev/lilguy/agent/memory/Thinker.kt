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
@SerialName("thought")
data class ThoughtReply(val thought: String)

/** One time he stopped to think, for logs and the dev view. [text] is null when the model failed ([error]). */
data class Thought(
    val tick: Long,
    val day: Int?,
    val time: String?,
    val thinker: String,
    val text: String?,
    val raw: String?,
    val usage: Usage,
    val latency: Duration,
    val error: String? = null,
)

/**
 * The think intent's model call: when he chooses to think, a text model gets the decision prompt
 * he was just shown (who he is, how his life works, his state, what he remembers and thought, what
 * he did lately) and writes one short first-person thought: what he wants to do next and what he
 * wants to achieve. One call, one reply. [AgentMemory] puts it in the event stream; the agent loop
 * puts it in his short-term memory, so the next decisions see it.
 *
 * [model] must write free text, so a closed-choice model (TypeSafe's Jev) can't be the thinker.
 */
class Thinker(
    private val model: DecisionModel,
    /** Retries included. */
    private val timeout: Duration = 60.seconds,
) {
    init {
        require(!model.capabilities.closedChoice) { "thinking needs a model that writes text; '${model.id}' only picks from options" }
    }

    val thinker: String get() = model.id

    private val schema = ActionSchema.of(
        "thought",
        ThoughtReply.serializer(),
        SchemaHints(descriptions = mapOf("thought.thought" to "What you want to do next and what you want to achieve, first person, under $WORD_CAP words.")),
    ) { null }

    /**
     * [system] and [situation] are the decision prompt's system prompt and user message (without
     * its closing ask). Never throws for a model failure: the thought says what went wrong.
     */
    suspend fun think(system: String, situation: String, tick: Long, day: Int?, time: String?): Thought {
        val started = TimeSource.Monotonic.markNow()
        val request = DecisionRequest(
            system = system.trimEnd() + "\n\n" + INSTRUCTIONS,
            messages = listOf(Message(Message.Role.User, situation.trimEnd() + "\n\n" + ASK)),
            temperature = TEMPERATURE,
            timeout = timeout,
        )
        fun failed(error: String, raw: String? = null, usage: Usage = Usage()) =
            Thought(tick, day, time, model.id, null, raw, usage, started.elapsedNow(), error)
        return try {
            val decision = withTimeout(timeout) { model.decide(request, schema) }
            val text = decision.value.thought.trim()
            if (text.isEmpty()) failed("the thought was empty", decision.raw, decision.usage)
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
        const val WORD_CAP = 80
        const val TEMPERATURE = 0.8
        const val ASK = "What do you want to do next, and what do you want to achieve?"

        val INSTRUCTIONS = """
            |Right now you are not choosing an action: you have stopped to think. Look at your situation: your needs and money, what you remember and thought before, and what you have done lately. Think about what you want to do next and what you want to achieve, and say it plainly: what you will do next, and what you are working toward. Keep in view what you are working toward, not only what keeps you fed: money and food are means, not ends. Write in the first person, as yourself, under $WORD_CAP words.
            |
            |For this reply only, ignore the reply format above: reply with a JSON object {"thought": ...} and nothing else.
        """.trimMargin()
    }
}
