package dev.lilguy.agent.memory

import dev.lilguy.decision.ActionSchema
import dev.lilguy.decision.DecisionFailed
import dev.lilguy.decision.DecisionModel
import dev.lilguy.decision.DecisionRequest
import dev.lilguy.decision.Message
import dev.lilguy.decision.SchemaHints
import dev.lilguy.decision.Usage
import dev.lilguy.sim.BedroomContent
import dev.lilguy.sim.BedroomWorld
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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
 * the world and never tell him what a book hasn't taught him yet. Then the hand-written [guide]
 * (resources/docs/bedroom-guide.md): how each part of the world works, in words, each section shown
 * once he knows what it's about. He searches and reads them himself ([search], the `search_docs` and
 * `read_docs` looks), and the [Oracle] answers from them.
 */
object WorldDocs {
    private const val INVENT_RULE = "At a workbench he designs one new thing of his own and builds it: it has one use, up to a few qualities and a look he chooses. " +
        "He never sets a price: the materials cost a base for its use plus an amount per point of quality strength, taken from his money when he finishes. " +
        "A design outside these rules isn't built, costs nothing but the time, and frustrates him. A thing to look at takes one of the room's few spare spots."

    /** The docs for [content] as he knows it; the last one is kept, since the content changes only when he learns something. */
    fun of(content: BedroomContent): List<DocSection> {
        last?.let { (c, docs) -> if (c === content) return docs }
        val known = content.verbs.mapTo(HashSet()) { it.id } + content.items.map { it.id }
        return (generated(content) + guided.filter { known.containsAll(it.first) }.map { it.second }).also { last = content to it }
    }

    @Volatile private var last: Pair<BedroomContent, List<DocSection>>? = null

    /** Every hand-written section, whatever he knows. */
    val guide: List<DocSection> get() = guided.map { it.second }

    /**
     * The hand-written sections, each with the verb and item ids it waits for: each `## id: title`
     * heading starts one, and a heading ending `[when: a, b]` waits until he knows a and b.
     */
    private val guided: List<Pair<Set<String>, DocSection>> by lazy {
        val text = WorldDocs::class.java.getResource("/docs/bedroom-guide.md")?.readText() ?: return@lazy emptyList()
        text.split(Regex("(?m)^## ")).drop(1).map { block ->
            val heading = block.substringBefore('\n')
            val gate = Regex("""\s*\[when:([^\]]*)]\s*$""").find(heading)
            val head = gate?.let { heading.substring(0, it.range.first) } ?: heading
            val waits = gate?.groupValues?.get(1)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()
            waits to DocSection(head.substringBefore(": ").trim(), head.substringAfter(": ").trim(), block.substringAfter('\n').trim().replace(Regex("\\s*\n\\s*"), " "))
        }
    }

    /**
     * The [limit] sections of [docs] that best match [query]: each of its words counts most in a
     * section's id, then its title, then its text, with plurals and -ing folded; the guide's first on
     * ties. None for a query with no words, or that shares none.
     */
    fun search(docs: List<DocSection>, query: String, limit: Int = 8): List<DocSection> {
        val words = terms(query)
        if (words.isEmpty()) return emptyList()
        return docs.withIndex()
            .map { (i, d) ->
                val id = terms(d.id.replace(Regex("[:_]"), " "))
                val title = terms(d.title)
                val text = terms(d.text)
                Triple(d, words.sumOf { w -> (if (w in id) 4 else 0) + (if (w in title) 3 else 0) + (if (w in text) 1 else 0) }, i)
            }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Triple<DocSection, Int, Int>> { it.second }.thenBy { !it.first.id.startsWith("guide:") }.thenBy { it.third })
            .take(limit)
            .map { it.first }
    }

    private fun terms(text: String): Set<String> =
        text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 && it !in STOP }.map(::stem).toSet()

    private fun stem(w: String): String = when {
        w.length > 5 && w.endsWith("ing") -> w.dropLast(3)
        w.length > 4 && w.endsWith("ies") -> w.dropLast(3) + "y"
        w.length > 4 && w.endsWith("ed") -> w.dropLast(2)
        w.length > 3 && w.endsWith("s") && !w.endsWith("ss") -> w.dropLast(1)
        else -> w
    }

    private val STOP = setOf(
        "the", "and", "how", "what", "can", "you", "your", "does", "for", "get", "his", "him", "are", "why", "when", "where", "with", "that", "this",
        "into", "from", "there", "about", "some", "more", "just", "have", "its", "not", "was", "who", "which", "much", "many", "make", "way", "any", "all",
    )

    private fun generated(content: BedroomContent): List<DocSection> = buildList {
        add(DocSection("rules:needs", "how needs move, and when he can't work", enc(content.needs)))
        content.night?.let { add(DocSection("rules:night", "night and day", enc(it))) }
        add(DocSection("rules:shop", "the shop: ordering and delivery, and what it holds back", "$SHOP_RULE ${enc(content.shop)}"))
        content.habituation?.let { add(DocSection("rules:habituation", "repeating things pays less", enc(it))) }
        content.novelty?.let { add(DocSection("rules:novelty", if (content.familiarity != null) "what a fresh finish pays" else "doing something for the first time", enc(it))) }
        content.familiarity?.let { add(DocSection("rules:familiarity", "what makes something fresh or familiar: features of what it's like (verb, kind, place, thing, group, quality, skill, makes, sky), each fading by half every half_life_days", enc(it))) }
        content.frustration?.let { add(DocSection("rules:frustration", "frustration", enc(it))) }
        add(DocSection("rules:skills", "what skills do", enc(content.skills)))
        add(DocSection("rules:gallery", "hanging art", enc(content.gallery)))
        add(DocSection("rules:qualities", "what items are worth to him", enc(content.qualityValue)))
        content.comfort?.let { add(DocSection("rules:comfort", "comfort: what he can see from where he stands", "A thing counts toward comfort in full within `near` tiles of him and fades out by `far`; it counts in full at `lit` light, less when dimmer, none in the dark. Only the place he's in counts. Asleep, what's round him counts lit or not. ${enc(it)}")) }
        content.light?.let { add(DocSection("rules:light", "light: daylight, windows, lamps and the dark", "Light runs 0 to about 10. Outdoors it's the daylight by time of day; in a room, `indoors` of it everywhere, plus each window's `share` reaching `radius` tiles. Lamps (an item's `light`) add their strength, fading to nothing a tile past their radius; walls and trees block light, and a lamp in a room lights only that room. He sees a thing with `see` light on it. A verb's `needs: { light: N }` is locked where it would be done below N. A lamp (the `lamp` affordance) is lit on its timer, its own `light.timer` or `timer` here, unless he switches it on or off (switch_on, switch_off), where it stays until he switches it again or puts it back on its timer (lamp_timer); other lights, such as the desk's, the glowcaps and his lantern, are always lit. ${enc(it)}")) }
        content.weather?.let { w -> add(DocSection("rules:warmth", "warmth: the cold, heaters, fires and blankets", "Warmth is degrees on each tile: outdoors the weather's temperature; in the house its own ${w.temperature.indoors.toInt()}°, less ${(w.temperature.leak * 100).toInt()}% of the gap to outside. Things with `warmth` (a heater, a fire pit) add degrees round them, fading to nothing a tile past their radius; walls stop it, and a heater in a room warms only that room. A blanket he owns warms him wherever he is outdoors. Where he stands below `cold.below` degrees, mood and energy fall each hour. A verb's `needs: { warmth: N }` is locked where it's colder. " + (w.cold?.let(::enc) ?: ""))) }
        content.sound?.let { add(DocSection("rules:sound", "sound: music he can hear, a chime, and quiet for sleep", "Sound runs 0 to 10. A record player is heard while a record plays on it; walls let about a third through, and sound carries out of a room, so a record in the bedroom is faint in the garden. Music lifts his mood in full where it's `full` loud, less where fainter, not at all where he can't hear it (`hear`). A nice thing he can hear (a wind chime) counts toward comfort as if he saw it. ${enc(it)}")) }
        content.sleep?.let { add(DocSection("rules:sleep", "what spoils sleep", "Asleep where it's colder than `cold` degrees or `noisy` loud, his energy comes back at `rest` of its rate. ${enc(it)}")) }
        content.growing?.let { add(DocSection("rules:growing", "planters: sun and water", "A planter (`grows`) counts an hour toward its harvest for each hour its soil is damp and the sun on it (daylight and the windows' share, never lamps) is `sun` or more: by a window or outdoors on a clear day, not in a dim corner. Soil holds `soil` of water and loses `dries_per_hour`; rain and spray on its tile add to it each hour, and watering fills it. ${enc(it)}")) }
        content.world?.visitors?.takeIf { it.isNotEmpty() }?.let { vs -> add(DocSection("world:visitors", "visitors: birds, moths and fireflies", "Visitors come to the things whose `lure` draws them (a feeder, a lit porch lantern, the reeds at the pond) when the conditions there suit them, so where such a thing stands decides who comes. Watched there they leave something behind and stay away a while. A weather station within a lure's reach logs how many came each hour. " + vs.joinToString(" ") { enc(it) })) }
        content.invent?.let { add(DocSection("rules:invent", "inventing: what he can design and build at a workbench, and what it costs", "$INVENT_RULE ${enc(it)}")) }
        if (content.verbs.any { v -> v.effects.any { it is dev.lilguy.sim.DefineOp } }) add(
            DocSection(
                "rules:code", "code: writing his own verbs and what his things do, and the world API it uses",
                "At his desk he writes JavaScript: a verb has function finish(world) (and canDo(world) to say why not), a thing's behavior has one or more trigger functions run while he owns it (" + dev.lilguy.sim.Triggers.ALL.joinToString("; ") { it.second } + "); a thing's code reaches only its own place, and what it does where he isn't he hears of when he next goes there. Code makes nothing from nothing (money comes from selling things, things from machines fed with what their recipes take, and machines wear, slow, jam at 100 and are mended with the repair verb); code that throws fails the verb with the error. A thing he invents gets its code written with it. The API: " +
                    dev.lilguy.sim.ScriptApi.reference(content).replace("\n", " "),
            ),
        )
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

    // Defaults are written out: a rule left at its default (the gallery's comfort) is still a rule.
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    private inline fun <reified T> enc(value: T): String = json.encodeToString(kotlinx.serialization.serializer<T>(), value)
}

/**
 * Answers what he asks while he thinks ([Thinker]): the oracle gets his question and the world's
 * docs ([WorldDocs]). It searches them, opening the sections it needs from their table of contents (keyword matches on
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
        val docs = world?.knownContent()?.let(WorldDocs::of) ?: return Triple(emptyList(), Result.failure(IllegalStateException("the oracle isn't connected to a world")), Usage())
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
        val opened = searched.ifEmpty { WorldDocs.search(docs, question, MAX_OPEN).map { it.id } }
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
            |You answer questions about how his small world works, from its docs: the sections below are what you opened for his question, generated from the world itself. Answer it plainly and concretely, to him as "you": what to do, in what order, with the prices and numbers that matter, given his situation (his money, needs and what he owns, in the observation). Say only what the docs and his situation support. He can only do what a verb does: if no verb does it, tell him he can't. If it's something a book will teach him, tell him which book to finish. If the docs don't say, tell him you don't know. Don't mention docs, JSON, ids or ticks. Under $WORD_CAP words.
            |
            |Reply with a JSON object {"answer": ...} and nothing else.
        """.trimMargin()
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
