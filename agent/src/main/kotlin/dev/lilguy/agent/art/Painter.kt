package dev.lilguy.agent.art

import dev.lilguy.agent.Menu
import dev.lilguy.decision.ActionSchema
import dev.lilguy.decision.DecisionFailed
import dev.lilguy.decision.DecisionModel
import dev.lilguy.decision.DecisionRequest
import dev.lilguy.decision.Message
import dev.lilguy.decision.SchemaHints
import dev.lilguy.decision.Usage
import dev.lilguy.sim.CanvasSpec
import dev.lilguy.sim.CommissionRules
import dev.lilguy.sim.CommissionView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * One time he made a piece, for logs and the gallery. [artwork] is null when the model failed
 * ([error]). [piece] is the sim's piece number once the activity has finished and it's matched up;
 * [ops] counts the drawing operations the strokes came to, and [calls] the model calls it took.
 * [raw] is the run of picks, oldest first.
 */
data class Drawing(
    val tick: Long,
    val day: Int?,
    val time: String?,
    val artist: String,
    /** The item made, such as "sketch". */
    val item: String,
    /** What the open request asked for, if there was one. */
    val commission: String?,
    val title: String?,
    val tags: List<String>,
    val artwork: Artwork?,
    val ops: Int,
    val calls: Int,
    val raw: String?,
    val usage: Usage,
    val latency: Duration,
    val error: String? = null,
    val piece: Int? = null,
    /** The subject's noun when its subject stage drew a shape that shows it ([Shapes.showing]); null when none did. */
    val shows: String? = null,
)

/**
 * How his art gets made: a run of closed choices on the model that decides for him, so the cheap
 * picking model (TypeSafe's Jev) draws, and nothing has to write text. First he picks the piece's
 * subject (the customer's request, or something of his own), then a box of crayons ([Palettes]),
 * then stroke by stroke which part of the drawing spec to invoke next ([Strokes]) and which crayon
 * to draw it in, seeing the picture so far as letters, until the canvas's strokes are spent: two
 * calls a stroke, and every call adds to the picture. The strokes
 * become [DrawOp]s that [Artwork.draw] rasterizes; the model never writes code or coordinates. The
 * title and tags are the subject's words, and [Drawing.shows] says whether a shape for its noun was
 * drawn, so a piece drawn for a request is matched on what it shows.
 *
 * Any [DecisionModel] will do ([Menu]): a text model gets the same options in a prompt.
 */
class Painter(
    private val model: DecisionModel,
    /** For the whole piece, every call included. */
    private val timeout: Duration = 3.minutes,
) {
    /** The words a subject of his own is made from; the content's commission lists, normally. */
    class Subjects(val adjectives: List<String>, val nouns: List<String>) {
        companion object {
            fun of(rules: CommissionRules?): Subjects = rules?.let { Subjects(it.adjectives, it.nouns) } ?: DEFAULT

            val DEFAULT = Subjects(
                listOf("quiet", "tiny", "golden", "lonely", "cheerful", "stormy", "sleepy", "wild"),
                listOf("moon", "window", "cat", "mountain", "garden", "bridge", "boat", "tree"),
            )
        }
    }

    private class Subject(val adjective: String, val noun: String, val forRequest: Boolean) {
        val wants: String get() = "${article(adjective)} $adjective $noun"
        val title: String get() = titleOf(adjective, noun)
        val tags: List<String> get() = listOf(adjective, noun)
        val shapes: List<Shapes.Shape> get() = Shapes.forNoun(noun)
    }

    val artist: String get() = model.id

    /**
     * Draws one piece of [item] on [canvas]. [persona] is who he is; [situation] is his state in
     * the words of the decision prompt, which is what the subject is chosen from. [drawn] are the
     * titles of pieces he has already made, which aren't offered as subjects of his own again.
     * Never throws for a model failure: the drawing says what went wrong.
     */
    suspend fun draw(
        persona: String,
        situation: String,
        item: String,
        canvas: CanvasSpec,
        commission: CommissionView?,
        subjects: Subjects,
        tick: Long,
        day: Int?,
        time: String?,
        drawn: Collection<String> = emptyList(),
    ): Drawing {
        val started = TimeSource.Monotonic.markNow()
        val asked = commission?.let { it.request ?: "I'd like ${it.wants}." }
        var usage = Usage()
        var calls = 0
        val picks = mutableListOf<String>()
        fun failed(error: String) = Drawing(tick, day, time, model.id, item, asked, null, emptyList(), null, 0, calls, picks.joinToString(" ").ifEmpty { null }, usage, started.elapsedNow(), error)

        val pick: suspend (String, JsonObject, List<Menu.Option>) -> String = { instructions, state, options ->
            val d = Menu.pick(model, instructions, state, options, timeout, TEMPERATURE)
            usage += d.usage
            calls++
            picks += d.value.pick
            d.value.pick
        }

        return try {
            withTimeout(timeout) {
                val random = Random(tick)
                val subject = pickSubject(pick, persona, situation, item, commission, subjects, drawn.toSet(), random)
                val palette = pickPalette(pick, item, subject, canvas, random)
                // The subject stage's shapes are drawn in front of everything else, whenever they were made.
                val back = mutableListOf<DrawOp>()
                val front = mutableListOf<DrawOp>()
                val made = mutableListOf<String>()
                // Every call adds to the picture: there is no "done" and no stroke-free step, because a
                // picking model takes a way out the moment it's offered (done at once, else crayon
                // changes back and forth until the cap). A stroke already made isn't offered again.
                val used = mutableSetOf<String>()
                var copies = 0
                var shows: String? = null
                for (strokes in 0 until canvas.strokes) {
                    val stage = Strokes.stage(strokes, canvas.strokes, subject.shapes.isNotEmpty())
                    val seed = tick * 101 + strokes
                    // The crayons shuffled, as the strokes are, with the paper last: a picker leans to early options, and the paper rubs out.
                    val crayons = (1 until palette.crayons.size).shuffled(Random(seed)) + 0
                    val art = Artwork.draw(canvas.size, palette.rgb, back + front)
                    fun with(stroke: Strokes.Stroke, k: Int) = if (stroke.front) back + front + stroke.ops(k) else back + stroke.ops(k) + front
                    // A stroke that wouldn't show in any crayon (a detail wholly behind the subject) isn't offered.
                    val menu = Strokes.menu(canvas.size, seed, stage, subject.shapes).filter { it.label !in used && !(it.copy && copies >= MAX_COPIES) }
                        .let { all -> all.filter { visible(canvas.size, palette.rgb, art, it, crayons) { k -> with(it, k) }.isNotEmpty() }.ifEmpty { all } }
                    val shared = buildJsonObject {
                        put("subject", "$item: ${subject.wants}")
                        put("now", stage.says)
                        putJsonObject("palette") { palette.crayons.forEachIndexed { k, c -> put(('a' + k).toString(), c.name + if (k == 0) " (the paper)" else "") } }
                        putJsonArray("canvas") { art.letters().forEach { add(it) } }
                        putJsonArray("strokes_so_far") { made.forEach { add(it) } }
                        put("strokes_left", canvas.strokes - strokes)
                    }
                    val chosen = pick(STROKE_INSTRUCTIONS, shared, menu.map { Menu.Option(it.label, it.description) })
                    val stroke = menu.first { it.label == chosen }
                    val crayon = if (palette.crayons.size == 2) 1 else {
                        val state = JsonObject(shared + ("stroke" to kotlinx.serialization.json.JsonPrimitive(stroke.description)))
                        val showing = visible(canvas.size, palette.rgb, art, stroke, crayons) { k -> with(stroke, k) }.ifEmpty { crayons }
                        pick(CRAYON_INSTRUCTIONS, state, showing.map { k -> Menu.Option(Strokes.colorLabel(k), palette.crayons[k].name + if (k == 0) " (the paper: rubs out)" else "") })
                            .removePrefix("crayon:").toInt()
                    }
                    (if (stroke.front) front else back) += stroke.ops(crayon)
                    made += "${stroke.description.trimEnd('.')} in ${palette.crayons[crayon].name}"
                    used += chosen
                    if (stroke.copy) copies++
                    if (stroke.front && stroke.shape in Shapes.showing(subject.noun)) shows = subject.noun
                }
                val ops = back + front
                val art = Artwork.draw(canvas.size, palette.rgb, ops)
                if (art.blank()) failed("the piece came out one solid color")
                else Drawing(tick, day, time, model.id, item, asked, subject.title, subject.tags, art, ops.size, calls, picks.joinToString(" "), usage, started.elapsedNow(), shows = shows)
            }
        } catch (e: TimeoutCancellationException) {
            failed("timed out after $timeout")
        } catch (e: CancellationException) {
            throw e
        } catch (e: DecisionFailed) {
            usage += e.usage
            failed(e.message ?: e.toString())
        } catch (e: Exception) {
            failed(e.message ?: e.toString())
        }
    }

    /**
     * The [crayons] [stroke] would show in on [art]: at least half the cells it covers change. A shape
     * drawn in the color already under it vanishes (a green tree on green grass), or behind the
     * subject, and that pick is wasted, so it isn't offered.
     */
    private fun visible(size: Int, rgb: List<Int>, art: Artwork, stroke: Strokes.Stroke, crayons: List<Int>, with: (Int) -> List<DrawOp>): List<Int> {
        val covers = Artwork.draw(size, listOf(0, 1), stroke.ops(1)).letters().sumOf { row -> row.count { it == 'b' } }
        val before = art.letters()
        return crayons.filter { k ->
            val after = Artwork.draw(size, rgb, with(k)).letters()
            val changed = before.indices.sumOf { y -> before[y].indices.count { x -> before[y][x] != after[y][x] } }
            2 * changed >= covers
        }
    }

    private suspend fun pickSubject(
        pick: suspend (String, JsonObject, List<Menu.Option>) -> String,
        persona: String,
        situation: String,
        item: String,
        commission: CommissionView?,
        subjects: Subjects,
        drawn: Set<String>,
        random: Random,
    ): Subject {
        // The request first (he's offered it plainly), then a handful of his own, seeded by the piece,
        // leaving out what he has drawn before.
        val all = subjects.adjectives.flatMap { a -> subjects.nouns.map { n -> a to n } }
        val own = all.filter { (a, n) -> titleOf(a, n) !in drawn }.ifEmpty { all }.shuffled(random).take(OWN_SUBJECTS).map { (a, n) -> Subject(a, n, forRequest = false) }
        val request = commission?.let { c ->
            val words = c.wants.split(' ').filter { it !in setOf("a", "an", "the") }
            if (words.size >= 2) Subject(words.dropLast(1).joinToString(" "), words.last(), forRequest = true) else null
        }
        val options = buildList {
            request?.let { add(Menu.Option("request", "What the customer asked for: \"${commission!!.request ?: "I'd like ${commission.wants}."}\" It pays $${commission.reward} more if the piece is what they asked for, until ${commission.until}.")) }
            own.forEachIndexed { i, s -> add(Menu.Option("own:$i", "A subject of his own: ${s.wants}.")) }
        }
        val state = buildJsonObject {
            put("making", "a $item: a small picture he draws himself")
            put("who", persona)
            put("situation", situation)
        }
        val chosen = pick(SUBJECT_INSTRUCTIONS, state, options)
        return if (chosen == "request") request!! else own[chosen.removePrefix("own:").toInt()]
    }

    private suspend fun pickPalette(pick: suspend (String, JsonObject, List<Menu.Option>) -> String, item: String, subject: Subject, canvas: CanvasSpec, random: Random): Palette {
        val boxes = Palettes.fitting(canvas.colors).shuffled(random)
        if (boxes.size == 1) return boxes.single()
        val state = buildJsonObject { put("subject", "$item: ${subject.wants}") }
        val chosen = pick(PALETTE_INSTRUCTIONS, state, boxes.map { Menu.Option("box:${it.id}", "Draw it in ${it.description}.") })
        return boxes.first { "box:${it.id}" == chosen }
    }

    companion object {
        const val TEMPERATURE = 0.9
        const val OWN_SUBJECTS = 8

        /** Small copies of the subject among the details; Jev would otherwise put one in every region. */
        const val MAX_COPIES = 3

        /** A piece's title from its subject's words: "Wild Mountain". */
        fun titleOf(adjective: String, noun: String): String =
            "$adjective $noun".split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

        private fun article(word: String) = if (word.firstOrNull()?.lowercaseChar() in "aeiou".toSet()) "an" else "a"

        val SUBJECT_INSTRUCTIONS = "The character in `state.who` is sitting down to make `state.making`. `state.situation` is his life right now: what he sees, " +
            "what he remembers, the thoughts he had and what he planned the last time he stopped to think. Which subject should the picture be? " +
            "The customer's request earns him money he may need; a subject of his own should fit what's on his mind."

        val PALETTE_INSTRUCTIONS = "The character is about to draw `state.subject`. Which box of crayons suits it best?"

        val STROKE_INSTRUCTIONS = "The character is drawing `state.subject` on a small square grid, one stroke at a time, and `state.now` says what this stroke is for. `state.canvas` is the picture so far: " +
            "one row per line, the top row first, one letter per cell, and `state.palette` says which crayon each letter is (the first is the paper the " +
            "picture starts as). Later strokes paint over earlier ones, except that the subject stays in front. `state.strokes_so_far` are the strokes he has made and `state.strokes_left` how " +
            "many he has left; he picks the crayon after the stroke. Which stroke should he add next so that the picture comes to show its subject, " +
            "simply, as a child might draw it?"

        val CRAYON_INSTRUCTIONS = "The character is drawing `state.subject` on a small square grid. `state.canvas` is the picture so far, one row per " +
            "line, the top row first, one letter per cell, and `state.palette` says which crayon each letter is (the first is the paper). He has chosen " +
            "his next stroke, `state.stroke`. Which crayon should he draw it in, so that the picture comes to show its subject?"
    }
}

@Serializable
@SerialName("request")
data class RequestReply(val request: String)

/** Words each day's commission in the customer's voice. Needs a model that writes text; null from [word] when it can't. */
class CommissionVoice(
    private val model: DecisionModel,
    private val timeout: Duration = 60.seconds,
) {
    init {
        require(!model.capabilities.closedChoice) { "wording a request needs a model that writes text; '${model.id}' only picks from options" }
    }

    private val schema = ActionSchema.of(
        "request",
        RequestReply.serializer(),
        SchemaHints(descriptions = mapOf("request.request" to "The customer's request, one or two sentences.")),
    )

    /** [commission] in a customer's own words, naming what it wants; null when the model fails. */
    suspend fun word(commission: CommissionView): String? = try {
        val request = DecisionRequest(
            system = WORDING,
            messages = listOf(Message(Message.Role.User, "The customer wants ${commission.wants}. Write their request.")),
            temperature = Painter.TEMPERATURE,
            timeout = timeout,
        )
        withTimeout(timeout) { model.decide(request, schema) }.value.request.trim().ifEmpty { null }
    } catch (e: CancellationException) {
        if (e is TimeoutCancellationException) null else throw e
    } catch (e: Exception) {
        null
    }

    companion object {
        val WORDING = """
            |You write requests from customers who buy small pictures from an artist. Given what a customer wants, write their request in one or two sentences, in their own voice, saying who it's for or why they want it. Name what they want plainly, using its words.
            |
            |Reply with a JSON object {"request": "..."} and nothing else.
        """.trimMargin()
    }
}
