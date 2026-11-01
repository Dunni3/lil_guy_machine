package dev.lilguy.agent.memory

import dev.lilguy.agent.authored.AuthorCall
import dev.lilguy.agent.authored.AuthorHandler
import dev.lilguy.decision.ActionSchema
import dev.lilguy.decision.DecisionFailed
import dev.lilguy.decision.DecisionModel
import dev.lilguy.decision.DecisionRequest
import dev.lilguy.decision.Message
import dev.lilguy.decision.SchemaHints
import dev.lilguy.decision.Usage
import dev.lilguy.sim.AuthoredResult
import dev.lilguy.sim.AuthoredView
import dev.lilguy.sim.BedroomContent
import dev.lilguy.sim.BedroomWorld
import dev.lilguy.sim.Observation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

@Serializable
@SerialName("question")
data class QuestionReply(val question: String)

@Serializable
@SerialName("search")
data class SearchReply(val open: List<String>)

@Serializable
@SerialName("answer")
data class AnswerReply(val answer: String)

/**
 * One time he asked how his world works: the [question] he wrote, the doc sections the oracle
 * [opened], and the [reply]. A failed question leaves [question] empty; the reply's error says why.
 */
data class Answer(val question: String, val opened: List<String>, val reply: Thought) {
    val tick: Long get() = reply.tick
}

/** One section of the world's docs: a rule, a verb, an item or a book. [id] is how the oracle opens it. */
data class DocSection(val id: String, val title: String, val text: String)

/**
 * The docs the oracle searches: how his world works, one section per rule, verb, item and book,
 * generated from the content as he knows it ([BedroomWorld.knownContent]), so they never drift from
 * the world and never tell him what a book hasn't taught him yet.
 */
object WorldDocs {
    fun of(content: BedroomContent): List<DocSection> = buildList {
        add(DocSection("rules:needs", "how needs move, and when he can't work", enc(content.needs)))
        content.night?.let { add(DocSection("rules:night", "night and day", enc(it))) }
        add(DocSection("rules:shop", "the shop: ordering and delivery, and what it holds back", "$SHOP_RULE ${enc(content.shop)}"))
        content.habituation?.let { add(DocSection("rules:habituation", "repeating things pays less", enc(it))) }
        content.novelty?.let { add(DocSection("rules:novelty", "doing something for the first time", enc(it))) }
        content.frustration?.let { add(DocSection("rules:frustration", "frustration", enc(it))) }
        add(DocSection("rules:skills", "what skills do", enc(content.skills)))
        content.commissions?.let { c -> add(DocSection("rules:commissions", "requests for a piece of art", "A request comes each day at ${c.arrive}: an adjective and a noun. The first piece sold that matches it (the adjective in its title or tags, a shape of the noun drawn in it) earns $${c.reward} on top of its price.")) }
        add(DocSection("rules:gallery", "hanging art", enc(content.gallery)))
        add(DocSection("rules:qualities", "what items are worth to him", enc(content.qualityValue)))
        content.verbs.forEach { v -> add(DocSection("verb:${v.id}", "verb ${v.id}: ${v.phrase ?: v.id}", enc(v))) }
        content.items.forEach { i ->
            val users = content.verbs.filter { v -> i.id in v.at || (v.requires + v.args.values + v.effects.flatMap { it.asksFor }).any { it in i.names } }.map { it.id }
            val price = i.price?.let { ", $$it" + if (i.pack > 1) " for ${i.pack}" else "" } ?: ", not sold"
            add(DocSection("item:${i.id}", "item ${i.name} (${i.group}$price)", enc(i) + if (users.isEmpty()) "" else " Used by: ${users.joinToString()}."))
        }
        content.books.forEach { b -> add(DocSection("book:${b.id}", "book \"${b.title}\"", enc(b))) }
    }

    private const val SHOP_RULE =
        "The shop only sells an item he could use: one whose every use happens at something he doesn't own (a canvas without an easel, a record without a record player) isn't sold until he owns that thing."

    private val json = Json { encodeDefaults = false; explicitNulls = false }

    private inline fun <reified T> enc(value: T): String = json.encodeToString(kotlinx.serialization.serializer<T>(), value)
}

/**
 * The first half of `ask`: a text model writes the question he wants answered, in his own words,
 * from the decision prompt he was just shown, as [Thinker] writes a thought. A closed-choice model
 * (Jev) decides to ask; it can't write the question.
 */
class Questioner(private val model: DecisionModel, private val timeout: Duration = 60.seconds) {
    init {
        require(!model.capabilities.closedChoice) { "writing a question needs a model that writes text; '${model.id}' only picks from options" }
    }

    val writer: String get() = model.id

    private val schema = ActionSchema.of(
        "question",
        QuestionReply.serializer(),
        SchemaHints(descriptions = mapOf("question.question" to "One question about how your world works, first person, under $WORD_CAP words.")),
    ) { null }

    /** His question, or a failure saying why. */
    suspend fun ask(system: String, situation: String): Result<Pair<String, Usage>> = call(timeout) {
        val decision = model.decide(
            DecisionRequest(
                system = system.trimEnd() + "\n\n" + INSTRUCTIONS,
                messages = listOf(Message(Message.Role.User, situation.trimEnd() + "\n\n" + ASK)),
                temperature = TEMPERATURE,
                timeout = timeout,
            ),
            schema,
        )
        decision.value.question.trim().ifEmpty { error("the question was empty") } to decision.usage
    }

    companion object {
        const val WORD_CAP = 30
        const val TEMPERATURE = 0.8
        const val ASK = "What do you want to find out about how your world works?"

        val INSTRUCTIONS = """
            |Right now you are not choosing an action: you have stopped to wonder how something in your world works, and you will get an answer. Look at your situation: what you can't do yet and why, what you want, what you have tried. Ask the one question whose answer would help you most: how to get to something, what something is for, what you could do that you haven't. Write it in the first person, as yourself, under $WORD_CAP words.
            |
            |For this reply only, ignore the reply format above: reply with a JSON object {"question": ...} and nothing else.
        """.trimMargin()
    }
}

/**
 * The second half of `ask`: the oracle gets his question and the world's docs ([WorldDocs]). It
 * searches them, opening the sections it needs from their table of contents (keyword matches on
 * the question if that call fails), then answers from what it opened and his situation.
 *
 * [connect] it to the world before the run starts.
 */
class Oracle(
    private val model: DecisionModel,
    private val timeout: Duration = 60.seconds,
) {
    init {
        require(!model.capabilities.closedChoice) { "answering needs a model that writes text; '${model.id}' only picks from options" }
    }

    private var world: BedroomWorld? = null

    val writer: String get() = model.id

    fun connect(world: BedroomWorld) {
        this.world = world
    }

    private val searchSchema = ActionSchema.of(
        "search",
        SearchReply.serializer(),
        SchemaHints(descriptions = mapOf("search.open" to "The ids of up to $MAX_OPEN sections to read, most useful first.")),
    ) { null }

    private val answerSchema = ActionSchema.of(
        "answer",
        AnswerReply.serializer(),
        SchemaHints(descriptions = mapOf("answer.answer" to "The answer, to him as \"you\", under $WORD_CAP words.")),
    ) { null }

    /** The sections opened for [question] and the answer text, with what the calls cost; a failure says why. */
    suspend fun answer(question: String, situation: String): Triple<List<String>, Result<String>, Usage> {
        val docs = world?.knownContent()?.let(WorldDocs::of) ?: return Triple(emptyList(), Result.failure(IllegalStateException("ask: the oracle isn't connected to a world")), Usage())
        val byId = docs.associateBy { it.id }
        val contents = docs.joinToString("\n") { "${it.id}: ${it.title}" }
        var usage = Usage()
        val searched = call(timeout) {
            val d = model.decide(
                DecisionRequest(
                    system = SEARCH_INSTRUCTIONS + "\n\nThe docs, one section a line as `id: title`:\n" + contents,
                    messages = listOf(Message(Message.Role.User, "His question: \"$question\"")),
                    temperature = 0.0,
                    timeout = timeout,
                ),
                searchSchema,
            )
            usage += d.usage
            d.value.open.filter { it in byId }.distinct().take(MAX_OPEN)
        }.getOrNull().orEmpty()
        val opened = searched.ifEmpty { Keywords.rank(docs, question, MAX_OPEN, { "${it.title} ${it.text}" }, { -docs.indexOf(it).toLong() }).map { it.first.id } }
        val text = call(timeout) {
            val d = model.decide(
                DecisionRequest(
                    system = ANSWER_INSTRUCTIONS + "\n\nThe doc sections you opened:\n" + opened.joinToString("\n\n") { id -> byId.getValue(id).let { "## ${it.title} (${it.id})\n${it.text}" } },
                    messages = listOf(Message(Message.Role.User, situation.trimEnd() + "\n\nHe asks: \"$question\"")),
                    temperature = TEMPERATURE,
                    timeout = timeout,
                ),
                answerSchema,
            )
            usage += d.usage
            d.value.answer.trim().ifEmpty { error("the answer was empty") }
        }
        return Triple(opened, text, usage)
    }

    companion object {
        const val WORD_CAP = 60
        const val TEMPERATURE = 0.3
        const val MAX_OPEN = 6

        val SEARCH_INSTRUCTIONS = """
            |You answer questions about how a small world works, from its docs. First, search: pick the sections you need to read to answer the question below (up to $MAX_OPEN). Reply with a JSON object {"open": [id, ...]} and nothing else.
        """.trimMargin()

        val ANSWER_INSTRUCTIONS = """
            |You answer questions about how his small world works, from its docs: the sections below are what you opened for his question, generated from the world itself. Answer it plainly and concretely, to him as "you": what to do, in what order, with the prices and numbers that matter, given his situation (his money, needs and what he owns, in the observation). Say only what the docs and his situation support; if they don't say, tell him you don't know. Don't mention docs, JSON, ids or ticks. Under $WORD_CAP words.
            |
            |Reply with a JSON object {"answer": ...} and nothing else.
        """.trimMargin()
    }
}

/**
 * `write: { with: answer }`: he asks, the oracle answers. The [questioner] writes his question from
 * the decision prompt, the [oracle] searches the docs and answers. Kept, it goes into the event
 * stream as an [EventKind.Answer] event, and with `shows_as: answer` into his later prompts ([onKept]).
 * [onAnswer] gets every one as it's written, kept or not.
 */
class AnswerHandler(
    private val questioner: Questioner,
    private val oracle: Oracle,
    private val store: MemoryStore,
    private val run: String,
    private val onAnswer: (Answer) -> Unit = {},
    private val onKept: (Answer) -> Unit,
) : AuthorHandler<Answer> {
    override val name = "answer"
    override val writer: String get() = oracle.writer

    override suspend fun author(call: AuthorCall): Answer {
        val obs = call.obs
        val started = TimeSource.Monotonic.markNow()
        fun reply(text: String?, usage: Usage, error: String? = null) =
            Thought(obs.tick, obs.clock?.day, obs.clock?.time, oracle.writer, text, null, usage, started.elapsedNow(), error)
        val (question, asked) = questioner.ask(call.system, call.situation).getOrElse { e ->
            return Answer("", emptyList(), reply(null, Usage(), "question: ${e.message ?: e}")).also(onAnswer)
        }
        val (opened, text, usage) = oracle.answer(question, call.situation)
        return Answer(question, opened, reply(text.getOrNull(), asked + usage, text.exceptionOrNull()?.let { "answer: ${it.message ?: it}" })).also(onAnswer)
    }

    override fun keep(result: Answer, call: AuthorCall, ended: Observation, view: AuthoredView?, deliver: (AuthoredResult) -> Unit) {
        val text = result.reply.text ?: return
        val r = result.reply
        if ("events" in call.op.into) store.appendEvent(run, r.tick, r.day, r.time, EventKind.Answer, "I asked \"${result.question}\": $text")
        onKept(result)
        deliver(AuthoredResult.Written(text))
    }
}

/** Runs [block] with a timeout; a model failure or timeout becomes a failed result, cancellation still cancels. */
private suspend fun <T> call(timeout: Duration, block: suspend () -> T): Result<T> = try {
    Result.success(withTimeout(timeout) { block() })
} catch (e: TimeoutCancellationException) {
    Result.failure(IllegalStateException("timed out after $timeout"))
} catch (e: CancellationException) {
    throw e
} catch (e: DecisionFailed) {
    Result.failure(e)
} catch (e: Exception) {
    Result.failure(e)
}
