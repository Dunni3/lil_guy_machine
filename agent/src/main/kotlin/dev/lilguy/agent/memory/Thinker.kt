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
@SerialName("plan")
data class ThoughtReply(@SerialName("plan") val thought: String)

/** One step of thinking with questions left: a question for the oracle, or the thought that ends it. */
@Serializable
@SerialName("plan_step")
data class ThinkStep(val ask: String?, @SerialName("plan") val thought: String?)

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
    /** What he asked the oracle while thinking, in order, failed answers included. */
    val asked: List<Answer> = emptyList(),
    /** True for a reflection ([Reflector]): stepping back over a stretch, not a thought of the moment. */
    val reflection: Boolean = false,
)

/**
 * The think intent's model call: when he chooses to think, a text model gets the decision prompt
 * he was just shown (who he is, how his life works, his state, what he remembers and thought, what
 * he did lately) and writes one short first-person thought: what he wants to do next and what he
 * wants to achieve. [AgentMemory] puts it in the event stream; the agent loop puts it in his
 * short-term memory, so the next decisions see it.
 *
 * With an [oracle] it's a short turn rather than one reply: before the thought he may ask how
 * something in his world works, up to [MAX_ASKS] questions one at a time, each answered by the
 * oracle from the world's docs and put back in the conversation. That's how he finds things out:
 * asking is part of thinking, not an option the deciding model picks.
 *
 * [model] must write free text, so a closed-choice model (TypeSafe's Jev) can't be the thinker.
 */
class Thinker(
    private val model: DecisionModel,
    /** Retries included; per call, and each question's answer has the oracle's own. */
    private val timeout: Duration = 60.seconds,
    /** Answers his questions while he thinks; null for a thought with no questions. */
    val oracle: Oracle? = null,
) {
    init {
        require(!model.capabilities.closedChoice) { "thinking needs a model that writes text; '${model.id}' only picks from options" }
    }

    val thinker: String get() = model.id

    private val schema = ActionSchema.of(
        "plan",
        ThoughtReply.serializer(),
        SchemaHints(descriptions = mapOf("plan.plan" to "What you want to do next and what you want to achieve, first person, under $WORD_CAP words.")),
    ) { null }

    private val stepSchema = ActionSchema.of(
        "plan_step",
        ThinkStep.serializer(),
        SchemaHints(
            descriptions = mapOf(
                "plan_step.ask" to "One question about how your world works, first person, under $QUESTION_CAP words; null when you're ready to say your plan.",
                "plan_step.plan" to "What you want to do next and what you want to achieve, first person, under $WORD_CAP words; null while you ask.",
            ),
        ),
    ) { null }

    /**
     * [system] and [situation] are the decision prompt's system prompt and user message (without
     * its closing ask). Never throws for a model failure: the thought says what went wrong.
     */
    suspend fun think(system: String, situation: String, tick: Long, day: Int?, time: String?): Thought {
        val started = TimeSource.Monotonic.markNow()
        val messages = mutableListOf(Message(Message.Role.User, situation.trimEnd() + "\n\n" + if (oracle != null) ASK_WITH_QUESTIONS else ASK))
        val asked = mutableListOf<Answer>()
        var usage = Usage()
        fun request() = DecisionRequest(
            system = system.trimEnd() + "\n\n" + if (oracle != null) INSTRUCTIONS_WITH_QUESTIONS else INSTRUCTIONS,
            messages = messages.toList(),
            temperature = TEMPERATURE,
            timeout = timeout,
        )
        fun failed(error: String, raw: String? = null) =
            Thought(tick, day, time, model.id, null, raw, usage, started.elapsedNow(), error, asked.toList())
        fun thought(text: String, raw: String) =
            if (text.isEmpty()) failed("the thought was empty", raw) else Thought(tick, day, time, model.id, text, raw, usage, started.elapsedNow(), asked = asked.toList())
        return try {
            // While questions are left he may ask one or end with his thought; then only the thought.
            while (oracle != null && asked.size < MAX_ASKS) {
                val step = withTimeout(timeout) { model.decide(request(), stepSchema) }
                usage += step.usage
                val question = step.value.ask?.trim().orEmpty()
                if (question.isEmpty()) return thought(step.value.thought?.trim().orEmpty(), step.raw)
                val answer = answer(oracle, question, situation, tick, day, time)
                asked += answer
                messages += Message(Message.Role.Assistant, step.raw)
                val left = MAX_ASKS - asked.size
                messages += Message(
                    Message.Role.User,
                    (answer.reply.text?.let { "The answer: $it" } ?: "No answer came.") + "\n\n" +
                        if (left > 0) "You can ask $left more, or say your plan." else "No more questions now. Say your plan: reply {\"plan\": ...}.",
                )
            }
            val decision = withTimeout(timeout) { model.decide(request(), schema) }
            usage += decision.usage
            thought(decision.value.thought.trim(), decision.raw)
        } catch (e: TimeoutCancellationException) {
            failed("timed out after $timeout")
        } catch (e: CancellationException) {
            throw e
        } catch (e: DecisionFailed) {
            usage += e.usage
            failed(e.message ?: e.toString(), e.attempts.lastOrNull()?.raw)
        } catch (e: Exception) {
            failed(e.message ?: e.toString())
        }
    }

    /** The oracle's answer to [question], as an [Answer]; a failed one says why in its reply. */
    private suspend fun answer(oracle: Oracle, question: String, situation: String, tick: Long, day: Int?, time: String?): Answer {
        val started = TimeSource.Monotonic.markNow()
        val (opened, text, usage) = oracle.answer(question, situation)
        val reply = Thought(tick, day, time, oracle.writer, text.getOrNull(), null, usage, started.elapsedNow(), text.exceptionOrNull()?.let { "answer: ${it.message ?: it}" })
        return Answer(question, opened, reply)
    }

    companion object {
        const val WORD_CAP = 80
        const val QUESTION_CAP = 30
        const val TEMPERATURE = 0.8
        /** Questions he may ask the oracle in one think. */
        const val MAX_ASKS = 2
        const val ASK = "What do you want to do next, and what do you want to achieve?"
        const val ASK_WITH_QUESTIONS = "What do you want to do next, and what do you want to achieve? Ask first if there's something about how your world works you'd like to know."

        val INSTRUCTIONS = """
            |Right now you are not choosing an action: you have stopped to take stock. Look at your situation: your needs and money, what you remember and what was on your mind before, and what you have done lately. Then say plainly, as yourself, what you will do next and what you are working toward. Keep in view what you are working toward, not only what keeps you fed: money and food are means, not ends. Write in the first person, as yourself, under $WORD_CAP words.
            |
            |For this reply only, ignore the reply format above: reply with a JSON object {"plan": ...} and nothing else.
        """.trimMargin()

        val INSTRUCTIONS_WITH_QUESTIONS = """
            |Right now you are not choosing an action: you have stopped to take stock. Look at your situation: your needs and money, what you remember and what was on your mind before, and what you have done lately. Then say plainly, as yourself, what you will do next and what you are working toward. Keep in view what you are working toward, not only what keeps you fed: money and food are means, not ends.
            |
            |While you take stock, you can ask how something in your world works, and someone who knows its workings will answer: how to get to something you can't do yet, what something is for, what you could do that you haven't tried. There's more to your world than you can see from here. Ask one question at a time, up to $MAX_ASKS, each in the first person under $QUESTION_CAP words, when an answer would change what you do; don't ask what you already know. Then say your plan, in the first person, as yourself, under $WORD_CAP words.
            |
            |For these replies only, ignore the reply format above: reply with a JSON object {"ask": ..., "plan": ...} and nothing else. To ask, give "ask" your question and "plan" null; to finish, give "ask" null and "plan" your plan.
        """.trimMargin()
    }
}
