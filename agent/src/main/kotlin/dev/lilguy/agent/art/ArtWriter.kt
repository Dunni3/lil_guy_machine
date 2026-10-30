package dev.lilguy.agent.art

import dev.lilguy.decision.ActionSchema
import dev.lilguy.decision.DecisionFailed
import dev.lilguy.decision.DecisionModel
import dev.lilguy.decision.DecisionRequest
import dev.lilguy.decision.Message
import dev.lilguy.decision.SchemaHints
import dev.lilguy.decision.Usage
import dev.lilguy.sim.CanvasSpec
import dev.lilguy.sim.CommissionView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** A drawing model's reply: the piece as data. Colors are "#RRGGBB"; a bad one is sent back to the model. */
@Serializable
@SerialName("art")
data class ArtReply(val title: String, val palette: List<String>, val tags: List<String>, val ops: List<DrawOp>) {
    init {
        require(title.isNotBlank()) { "the piece needs a title" }
        require(palette.isNotEmpty()) { "the palette needs at least one color" }
        palette.forEach { require(Artwork.parseColor(it) != null) { "palette colors are \"#RRGGBB\" hex, not \"$it\"" } }
    }
}

@Serializable
@SerialName("request")
data class RequestReply(val request: String)

/**
 * One time he made a piece, for logs and the gallery. [artwork] is null when the model failed
 * ([error]). [piece] is the sim's piece number once the activity has finished and it's matched up;
 * [dropped] counts operations past the canvas's limit, which were left out.
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
    val dropped: Int,
    val raw: String?,
    val usage: Usage,
    val latency: Duration,
    val error: String? = null,
    val piece: Int? = null,
)

/**
 * The art's model call, beside the journal's: when he starts an activity that makes art, a text
 * model gets his persona, his situation and the open request, and sends back the piece as a
 * palette, a title, tags, and drawing operations on a small grid ([CanvasSpec]), which
 * [Artwork.draw] rasterizes. The model never writes code, so there is nothing to sandbox. It also
 * words each day's request in the customer's voice ([word]).
 *
 * [model] must write free text, so a closed-choice model (TypeSafe's Jev) can't draw.
 */
class ArtWriter(
    private val model: DecisionModel,
    /** Per call, retries included. */
    private val timeout: Duration = 90.seconds,
) {
    init {
        require(!model.capabilities.closedChoice) { "drawing needs a model that writes text; '${model.id}' only picks from options" }
    }

    val artist: String get() = model.id

    private fun schema(canvas: CanvasSpec): ActionSchema<ArtReply> {
        val coord = 0 until canvas.size
        val span = 1..canvas.size
        val color = 0 until canvas.colors
        val ranges = buildMap<String, IntRange> {
            listOf("rect.x", "rect.y", "circle.x", "circle.y", "line.x0", "line.y0", "line.x1", "line.y1", "fill.x", "fill.y", "pixel.x", "pixel.y").forEach { put(it, coord) }
            put("rect.w", span)
            put("rect.h", span)
            put("circle.r", 1..canvas.size / 2)
            listOf("rect", "circle", "line", "fill", "pixel").forEach { put("$it.color", color) }
        }
        return ActionSchema.of(
            "art",
            ArtReply.serializer(),
            SchemaHints(
                descriptions = mapOf(
                    "art.title" to "The piece's title.",
                    "art.palette" to "Up to ${canvas.colors} colors as \"#RRGGBB\"; ops name them by index. The canvas starts as color 0.",
                    "art.tags" to "A few words for what the piece shows.",
                    "art.ops" to "Drawing operations in order, at most ${canvas.ops}.",
                ),
                ranges = ranges,
            ),
        )
    }

    private val requestSchema = ActionSchema.of(
        "request",
        RequestReply.serializer(),
        SchemaHints(descriptions = mapOf("request.request" to "The customer's request, one or two sentences.")),
    )

    /**
     * Draws one piece of [item] on [canvas]. [persona] is who he is; [situation] is his state in
     * the words of the decision prompt. Never throws for a model failure: the drawing says what went wrong.
     */
    suspend fun draw(persona: String, situation: String, item: String, canvas: CanvasSpec, commission: CommissionView?, tick: Long, day: Int?, time: String?): Drawing {
        val started = TimeSource.Monotonic.markNow()
        val asked = commission?.let { it.request ?: "I'd like ${it.wants}." }
        val request = DecisionRequest(
            system = system(persona, item, canvas),
            messages = listOf(Message(Message.Role.User, situation.trimEnd() + "\n\n" + ask(item, commission))),
            temperature = TEMPERATURE,
            timeout = timeout,
        )
        fun failed(error: String, raw: String? = null, usage: Usage = Usage()) =
            Drawing(tick, day, time, model.id, item, asked, null, emptyList(), null, 0, 0, raw, usage, started.elapsedNow(), error)
        return try {
            val decision = withTimeout(timeout) { model.decide(request, schema(canvas)) }
            val reply = decision.value
            val palette = reply.palette.take(canvas.colors).map { Artwork.parseColor(it)!! }
            val ops = reply.ops.take(canvas.ops)
            val art = Artwork.draw(canvas.size, palette, ops)
            Drawing(tick, day, time, model.id, item, asked, reply.title.trim(), reply.tags.map { it.trim() }.filter { it.isNotEmpty() }, art, ops.size, reply.ops.size - ops.size, decision.raw, decision.usage, started.elapsedNow())
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

    /** [commission] in a customer's own words, naming what it wants; null when the model fails. */
    suspend fun word(commission: CommissionView): String? = try {
        val request = DecisionRequest(
            system = WORDING,
            messages = listOf(Message(Message.Role.User, "The customer wants ${commission.wants}. Write their request.")),
            temperature = TEMPERATURE,
            timeout = timeout,
        )
        withTimeout(timeout) { model.decide(request, requestSchema) }.value.request.trim().ifEmpty { null }
    } catch (e: CancellationException) {
        if (e is TimeoutCancellationException) null else throw e
    } catch (e: Exception) {
        null
    }

    private fun ask(item: String, commission: CommissionView?): String {
        val request = commission?.let {
            "A customer has asked for a piece: \"${it.request ?: "I'd like ${it.wants}."}\" They pay $${it.reward} more for the first piece that is what they asked for, until ${it.until}. " +
                "Draw it for them, or draw what you want to instead; it's your piece. "
        }.orEmpty()
        return "You have sat down to make a $item. ${request}Draw it now."
    }

    companion object {
        const val TEMPERATURE = 0.9

        fun system(persona: String, item: String, canvas: CanvasSpec) = """
            |$persona
            |
            |Now you are making a $item: a picture on a ${canvas.size} by ${canvas.size} grid of cells, x from 0 at the left to ${canvas.size - 1} at the right, y from 0 at the top to ${canvas.size - 1} at the bottom. You choose up to ${canvas.colors} colors and draw with up to ${canvas.ops} operations, painted in order, each over what's there. Make it something you care about: what you see, want or remember. Give it a title, and tags that say plainly what it shows.
            |
            |Reply with a JSON object and nothing else:
            |{"title": "...", "palette": ["#RRGGBB", ...], "tags": ["...", ...], "ops": [...]}
            |The canvas starts filled with palette color 0. Each op names a palette color by its index:
            |- {"type": "rect", "x": 0, "y": 10, "w": ${canvas.size}, "h": ${canvas.size - 10}, "color": 1}: a filled rectangle from its top-left corner.
            |- {"type": "circle", "x": 8, "y": 5, "r": 3, "color": 2}: a filled disc around (x, y).
            |- {"type": "line", "x0": 0, "y0": 0, "x1": 5, "y1": 9, "color": 3}: a one-cell line.
            |- {"type": "fill", "x": 2, "y": 2, "color": 1}: fills the region of one color around (x, y), like a paint bucket.
            |- {"type": "pixel", "x": 4, "y": 4, "color": 0}: one cell.
        """.trimMargin()

        val WORDING = """
            |You write requests from customers who buy small pictures from an artist. Given what a customer wants, write their request in one or two sentences, in their own voice, saying who it's for or why they want it. Name what they want plainly, using its words.
            |
            |Reply with a JSON object {"request": "..."} and nothing else.
        """.trimMargin()
    }
}
