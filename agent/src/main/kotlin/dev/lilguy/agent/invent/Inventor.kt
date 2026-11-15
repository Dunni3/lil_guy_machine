package dev.lilguy.agent.invent

import dev.lilguy.agent.authored.AuthorCall
import dev.lilguy.agent.authored.AuthorHandler
import dev.lilguy.agent.memory.EventKind
import dev.lilguy.agent.memory.MemoryStore
import dev.lilguy.decision.ActionSchema
import dev.lilguy.decision.DecisionFailed
import dev.lilguy.decision.DecisionModel
import dev.lilguy.decision.DecisionRequest
import dev.lilguy.decision.Message
import dev.lilguy.decision.SchemaHints
import dev.lilguy.decision.Usage
import dev.lilguy.sim.AuthoredResult
import dev.lilguy.sim.BookDesign
import dev.lilguy.sim.AuthoredView
import dev.lilguy.sim.BedroomContent
import dev.lilguy.sim.BedroomWorld
import dev.lilguy.sim.Design
import dev.lilguy.sim.InventRules
import dev.lilguy.sim.LookPart
import dev.lilguy.sim.Observation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** One quality of a design and how strong it is. */
@Serializable
@SerialName("quality_pick")
data class QualityPick(val quality: String, val strength: Int)

/** A [Design] as the model writes it: the same, with its qualities as a list a strict schema can hold. */
@Serializable
@SerialName("design_reply")
data class DesignReply(
    val name: String,
    val use: String,
    val qualities: List<QualityPick>,
    val place: String?,
    val look: List<LookPart>,
    val book: BookDesign?,
    val why: String,
) {
    fun design() = Design(name, use, qualities.associate { it.quality to it.strength.toDouble() }, place, look, book, why)
}

/**
 * One go at the workbench, for logs: the [design] he settled on (null when there's none), what was
 * wrong with his first try when he had to redo it ([redid]), and why there's no design ([error]).
 */
data class Drafted(
    val tick: Long,
    val day: Int?,
    val time: String?,
    val inventor: String,
    val design: Design?,
    val redid: List<String>,
    val raw: String?,
    val usage: Usage,
    val latency: Duration,
    val error: String? = null,
)

/** What designs the thing he invents: a text model ([Inventor]) or a fixed list ([CannedInventor]). */
interface Inventing {
    val id: String

    fun connect(world: BedroomWorld)

    /** Never throws for a model failure: the draft says what went wrong and nothing gets built. */
    suspend fun invent(call: AuthorCall): Drafted
}

/**
 * Designs what he builds when he chooses the `invent` verb: one new thing, with what it's for, what
 * it's like and how it looks ([Design]). A text model writes it from who he is, how he is, what
 * each use gives him and costs, and what he already has. The design is checked against the sim's
 * rules here ([InventRules.check]) so a refused one can be redone once with the reasons; the sim
 * checks it again when he builds it. [connect] it to the world first.
 */
class Inventor(private val model: DecisionModel, private val timeout: Duration = 90.seconds) : Inventing {
    private var world: BedroomWorld? = null

    override val id: String get() = model.id

    override fun connect(world: BedroomWorld) {
        this.world = world
    }

    override suspend fun invent(call: AuthorCall): Drafted {
        val started = TimeSource.Monotonic.markNow()
        val obs = call.obs
        var usage = Usage()
        var raw: String? = null
        var redid = emptyList<String>()
        fun drafted(design: Design?, error: String? = null) =
            Drafted(obs.tick, obs.clock?.day, obs.clock?.time, model.id, design, redid, raw, usage, started.elapsedNow(), error)
        val content = world?.content ?: return drafted(null, "the inventor isn't connected to a world")
        val rules = content.invent ?: return drafted(null, "this world has no rules for inventing")
        val schema = ActionSchema.of(
            "design_reply",
            DesignReply.serializer(),
            SchemaHints(
                descriptions = mapOf(
                    "design_reply.name" to "What it's called, a few plain words, lower case: \"moss lamp\".",
                    "design_reply.use" to "The one thing it's for.",
                    "design_reply.qualities" to "What it's like to you: up to ${rules.qualities.most} qualities, each once.",
                    "design_reply.place" to "Only for a viewable thing: floor or wall. Null otherwise.",
                    "design_reply.look" to "Its shapes, up to ${rules.lookParts}.",
                    "design_reply.book" to "Only for a readable thing: the book it is. Null otherwise.",
                    "design_reply.why" to "Why you're making it, first person, under $WHY_CAP words.",
                    "book_design.chapters" to "${rules.book.minChapters} to ${rules.book.maxChapters} chapters, one sentence each of what's in it.",
                    "book_design.skill" to "The one skill reading it raises, or null.",
                ),
                enums = mapOf(
                    "design_reply.use" to rules.uses.keys.toList(),
                    "design_reply.place" to listOf(InventRules.FLOOR, InventRules.WALL),
                    "quality_pick.quality" to content.vocabulary.qualities,
                    "look_part.shape" to LookPart.SHAPES,
                    "book_design.skill" to content.vocabulary.skills,
                ),
                ranges = mapOf("quality_pick.strength" to 1..3),
            ),
        ) { null }
        val messages = mutableListOf(Message(Message.Role.User, call.situation.trimEnd() + "\n\n" + brief(content, rules, obs, call.verb.minutes ?: 0)))
        return try {
            withTimeout(timeout) {
                repeat(TRIES) { attempt ->
                    val d = model.decide(DecisionRequest(system = call.system.trimEnd() + "\n\n" + INSTRUCTIONS, messages = messages.toList(), temperature = TEMPERATURE, timeout = timeout), schema)
                    usage += d.usage
                    raw = d.raw
                    val design = d.value.design()
                    when (val checked = rules.check(design, content, call.verb.minutes ?: 0)) {
                        is InventRules.Checked.Ok -> return@withTimeout drafted(design)
                        is InventRules.Checked.Refused -> {
                            // The last try goes to the sim as it is: its refusal is what he learns from.
                            if (attempt == TRIES - 1) return@withTimeout drafted(design)
                            redid = checked.problems
                            messages += Message(Message.Role.Assistant, d.raw)
                            messages += Message(Message.Role.User, "That can't be built: ${checked.problems.joinToString("; ")}. Design it again so it can, as the same JSON object.")
                        }
                    }
                }
                drafted(null, "no design")
            }
        } catch (e: TimeoutCancellationException) {
            drafted(null, "timed out after $timeout")
        } catch (e: CancellationException) {
            throw e
        } catch (e: DecisionFailed) {
            usage += e.usage
            raw = e.attempts.lastOrNull()?.raw
            drafted(null, e.message ?: e.toString())
        } catch (e: Exception) {
            drafted(null, e.message ?: e.toString())
        }
    }

    companion object {
        const val WHY_CAP = 30
        const val TEMPERATURE = 0.9
        private const val TRIES = 2

        val INSTRUCTIONS = """
            |Right now you are not choosing an action: you are at your workbench, inventing. Design one new thing of your own that you'd like to have, and that isn't like what you already own. Make it yours: what you like, how you feel, what your room or your days are missing.
            |
            |For this reply only, ignore the reply format above: reply with a JSON object {"name": ..., "use": ..., "qualities": [{"quality": ..., "strength": ...}], "place": ..., "look": [{"shape": ..., "w": ..., "h": ..., "d": ..., "x": ..., "y": ..., "z": ..., "rot_x": ..., "rot_y": ..., "rot_z": ..., "color": ...}], "book": ..., "why": ...} and nothing else.
        """.trimMargin()

        /** What he can make, what it costs and what he has, as the user message's second half. */
        internal fun brief(content: BedroomContent, rules: InventRules, obs: Observation, minutes: Int): String {
            val owned = obs.owns.orEmpty().keys
            fun has(name: String) = content.withName(name).any { it.id in owned }
            val uses = rules.uses.entries.joinToString("\n") { (use, u) ->
                val cost = "materials $${u.base}" + if (u.perStrength > 0) " plus $${u.perStrength} a point of quality strength" else ""
                // What else its verbs ask for that he doesn't have, so he knows a record needs a player.
                val needs = content.verbs.filter { use in it.args.values }.flatMap { v -> (v.args.values + v.requires).filter { it != use && !has(it) } }.distinct()
                    .map { n -> content.withName(n).firstOrNull()?.name ?: n }
                val also = if (needs.isEmpty()) "" else "; no use to you until you have a ${needs.joinToString(" and a ")}"
                "- $use: ${what(use, content, rules, minutes)} ($cost$also)"
            }
            val qualities = content.vocabulary.qualities.joinToString { q ->
                val a = content.personality.affinity(q)
                "$q (" + when {
                    a >= 1 -> "you like it"
                    a > 0 -> "you quite like it"
                    a < 0 -> "you dislike it"
                    else -> "you don't mind it"
                } + ")"
            }
            val things = content.items.filter { it.id in owned && it.qualities.isNotEmpty() }.joinToString { it.name }
            return """
                |You're at your workbench with $${obs.money ?: 0}. You can build one new thing today. It has exactly one use:
                |$uses
                |
                |What it's like is up to ${rules.qualities.most} qualities, each with a strength from 1 to 3, adding up to at most ${rules.qualities.total.toInt()}: $qualities. Stronger qualities you like make it worth more to you and cost more in materials. You can't spend more than you have.
                |
                |How it looks is up to ${rules.lookParts} shapes (${LookPart.SHAPES.joinToString()}), in metres. It stands on one floor tile, 1 wide and 1 deep; a thing in a wall hangs flat against it. Each shape has a size (w across, h up, d front to back, each 0.02 to 1.5), and x, y, z for the bottom centre of that shape: x from -0.5 (left) to 0.5, y up from 0 at the floor to 1.5, z from -0.5 (back) to 0.5 (front). rot_x, rot_y, rot_z turn it, in degrees. color is hex, like #c96f4a. Build it up from a few simple shapes so it reads as the thing it is.
                |
                |${if (things.isEmpty()) "You own nothing with qualities yet." else "What you own that has qualities: $things."}
                |
                |What do you invent?
            """.trimMargin()
        }

        private fun what(use: String, content: BedroomContent, rules: InventRules, minutes: Int): String = when (use) {
            "viewable" -> {
                val floor = rules.freeSpots(content, InventRules.FLOOR).size
                val wall = rules.freeSpots(content, InventRules.WALL).size
                "something to look at in your room, on the floor or in a wall ($floor free on the floor, $wall in the walls); the qualities you like in it raise your room's comfort, which lifts the mood you settle to, and while it's new it's stimulating"
            }
            "playable" -> "a record to play; its qualities are what you get from playing it"
            "readable" -> "a book you write, ${rules.book.minChapters} to ${rules.book.maxChapters} chapters to read back a chapter a sitting; its qualities are what you get from reading it, and it can raise one skill"
            "edible" -> "something to eat; its qualities are what you get from eating it, on top of being fed, and once you've made it the shop will send more at what the materials cost"
            "sellable" -> "something to sell through the hatch, once, for its materials plus $${rules.wagePerHour * minutes / 60}"
            else -> "something $use"
        }
    }
}

/**
 * A few fixed designs, built one after another: a dev aid for watching him invent with no endpoint
 * (`LILGUY_INVENTOR`), as the random painter is for art.
 */
class CannedInventor(private val designs: List<Design> = DESIGNS) : Inventing {
    private var world: BedroomWorld? = null

    override val id = "canned-inventor"

    override fun connect(world: BedroomWorld) {
        this.world = world
    }

    override suspend fun invent(call: AuthorCall): Drafted {
        val made = world?.inventions.orEmpty().size
        return Drafted(call.obs.tick, call.obs.clock?.day, call.obs.clock?.time, id, designs[made % designs.size], emptyList(), null, Usage(), Duration.ZERO)
    }

    companion object {
        val DESIGNS = listOf(
            Design(
                name = "moss lamp", use = "viewable", qualities = mapOf("calm" to 2.0, "natural" to 2.0), place = InventRules.FLOOR,
                look = listOf(
                    LookPart("cylinder", 0.34, 0.1, 0.34, color = "#8a6a4a"),
                    LookPart("cylinder", 0.06, 0.5, 0.06, y = 0.1, color = "#6b5238"),
                    LookPart("sphere", 0.36, 0.3, 0.36, y = 0.55, color = "#f2dc8a"),
                    LookPart("sphere", 0.2, 0.12, 0.2, x = 0.1, y = 0.08, z = 0.1, color = "#5f8a4e"),
                    LookPart("sphere", 0.16, 0.1, 0.16, x = -0.12, y = 0.08, z = 0.06, color = "#74a05c"),
                ),
                why = "the room needs something green that glows",
            ),
            Design(
                name = "paper kite", use = "viewable", qualities = mapOf("bright" to 2.0, "lively" to 1.0), place = InventRules.WALL,
                look = listOf(
                    LookPart("box", 0.4, 0.4, 0.03, y = 0.2, rotZ = 45.0, color = "#e2574c"),
                    LookPart("box", 0.03, 0.5, 0.02, y = 0.15, z = 0.02, color = "#f4e9c9"),
                    LookPart("box", 0.05, 0.3, 0.02, x = 0.05, y = -0.0, z = 0.02, rotZ = 15.0, color = "#4f8fc0"),
                ),
                why = "I can't go outside, so the sky can come in",
            ),
            Design(
                name = "honey toast", use = "edible", qualities = mapOf("warm" to 2.0, "cozy" to 1.0),
                look = listOf(LookPart("box", 0.2, 0.04, 0.2, color = "#d9a352")),
                why = "meals are fine but nothing about them is nice",
            ),
            Design(
                name = "tin robot", use = "viewable", qualities = mapOf("strange" to 2.0, "lively" to 2.0), place = InventRules.FLOOR,
                look = listOf(
                    LookPart("box", 0.3, 0.34, 0.22, y = 0.2, color = "#9fb4c2"),
                    LookPart("box", 0.22, 0.2, 0.2, y = 0.56, color = "#b9cad6"),
                    LookPart("cylinder", 0.08, 0.2, 0.08, x = -0.09, color = "#70848f"),
                    LookPart("cylinder", 0.08, 0.2, 0.08, x = 0.09, color = "#70848f"),
                    LookPart("sphere", 0.05, 0.05, 0.03, x = -0.05, y = 0.66, z = 0.1, color = "#f2c94c"),
                    LookPart("sphere", 0.05, 0.05, 0.03, x = 0.05, y = 0.66, z = 0.1, color = "#f2c94c"),
                    LookPart("cylinder", 0.02, 0.14, 0.02, y = 0.76, color = "#70848f"),
                    LookPart("sphere", 0.06, 0.06, 0.06, y = 0.9, color = "#e2574c"),
                ),
                why = "someone to keep me company who doesn't need feeding",
            ),
        )
    }
}

/**
 * `write: { with: invention, timing: before }`: an [Inventing] designs the thing before he walks to
 * the workbench, and the design is staged for the sim's `build` ([staged]). Kept (he built it), it
 * goes into the event stream as an [EventKind.Invention] event in his words. [onDraft] gets every
 * draft as it's written, built or not.
 */
class InventionHandler(
    private val inventor: Inventing,
    private val store: MemoryStore,
    private val run: String,
    private val onDraft: (Drafted) -> Unit = {},
) : AuthorHandler<Drafted> {
    override val name = "invention"
    override val writer: String get() = inventor.id

    override suspend fun author(call: AuthorCall) = inventor.invent(call).also(onDraft)

    override fun staged(result: Drafted) = AuthoredResult.Designed(result.design, failed = result.error)

    override fun keep(result: Drafted, call: AuthorCall, ended: Observation, view: AuthoredView?, deliver: (AuthoredResult) -> Unit) {
        val design = result.design ?: return
        val like = design.qualities.keys.joinToString(", ")
        val text = "I invented the ${design.name.trim()} and built it at my workbench" + (if (like.isEmpty()) "" else " ($like)") +
            (design.why.trim().trimEnd('.').takeIf { it.isNotEmpty() }?.let { ": $it." } ?: ".")
        if ("events" in call.op.into) store.appendEvent(run, result.tick, result.day, result.time, EventKind.Invention, text)
        deliver(AuthoredResult.Written(text))
    }
}
