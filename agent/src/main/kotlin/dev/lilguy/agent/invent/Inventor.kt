package dev.lilguy.agent.invent

import dev.lilguy.agent.authored.AuthorCall
import dev.lilguy.agent.authored.AuthorHandler
import dev.lilguy.agent.memory.EventKind
import dev.lilguy.agent.memory.MemoryStore
import dev.lilguy.decision.ActionSchema
import dev.lilguy.decision.DecisionFailed
import dev.lilguy.decision.DecisionModel
import dev.lilguy.decision.DecisionRequest
import dev.lilguy.decision.Looked
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
import kotlinx.coroutines.withTimeoutOrNull
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
    /** The design before the look-over changed it; null when it didn't. */
    val before: Design? = null,
    /** At the workbench: each tool he used before he built it, with its answer. */
    val steps: List<Looked> = emptyList(),
    /** What it does: the code written for it once the design was settled, when there's a coder. */
    val code: dev.lilguy.agent.code.Coded? = null,
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
                    "design_reply.name" to "What it's called, a few plain words, lower case: \"pebble lamp\".",
                    "design_reply.use" to "The one thing it's for.",
                    "design_reply.qualities" to "What it's like to you: up to ${rules.qualities.most} qualities, each once.",
                    "design_reply.place" to "Only for a viewable thing: floor or wall. Null otherwise.",
                    "design_reply.look" to "Its shapes, up to ${rules.lookParts}, one per part of the thing.",
                    "design_reply.book" to "Only for a readable thing: the book it is. Null otherwise.",
                    "design_reply.why" to "Why you're making it, first person, under $WHY_CAP words.",
                    "book_design.chapters" to "${rules.book.minChapters} to ${rules.book.maxChapters} chapters, one sentence each of what's in it.",
                    "book_design.skill" to "The one skill reading it raises, or null.",
                ) + Workbench.partHints(rules).descriptions,
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
        val system = call.system.trimEnd() + "\n\n" + INSTRUCTIONS
        fun problems(design: Design) = when (val checked = rules.check(design, content, call.verb.minutes ?: 0)) {
            is InventRules.Checked.Ok -> listOfNotNull(unaffordable(checked.invention.cost, obs.money ?: 0))
            is InventRules.Checked.Refused -> checked.problems
        }
        if (model.capabilities.toolCalls) {
            // A writer that calls tools puts the thing together at the workbench a shape at a time.
            val bench = Workbench(rules, content, ::problems)
            val ask = call.situation.trimEnd() + "\n\n" + brief(content, rules, obs, call.verb.minutes ?: 0) + "\n\n" + AT_THE_BENCH
            val limit = timeout * BENCH_TIME
            return try {
                withTimeout(limit) {
                    val d = model.decide(
                        DecisionRequest(system = call.system.trimEnd() + "\n\n" + BENCH_INSTRUCTIONS, messages = listOf(Message(Message.Role.User, ask)), temperature = TEMPERATURE, timeout = timeout, tools = bench.tools()),
                        schema,
                    )
                    usage += d.usage
                    raw = d.raw
                    val design = d.value.design()
                    drafted(design).copy(steps = d.looks, before = bench.lookedOver?.takeIf { it != design })
                }
            } catch (e: TimeoutCancellationException) {
                drafted(null, "timed out after $limit").copy(steps = emptyList())
            } catch (e: CancellationException) {
                throw e
            } catch (e: DecisionFailed) {
                usage += e.usage
                raw = e.attempts.lastOrNull()?.raw
                drafted(null, e.message ?: e.toString()).copy(steps = e.looked)
            } catch (e: Exception) {
                drafted(null, e.message ?: e.toString())
            }
        }
        return try {
            val built = withTimeout(timeout) {
                for (attempt in 0 until TRIES) {
                    val d = model.decide(DecisionRequest(system = system, messages = messages.toList(), temperature = TEMPERATURE, timeout = timeout), schema)
                    usage += d.usage
                    raw = d.raw
                    val design = d.value.design()
                    val problems = problems(design)
                    messages += Message(Message.Role.Assistant, d.raw)
                    if (problems.isEmpty()) return@withTimeout design
                    // The last try goes to the sim as it is: its refusal is what he learns from.
                    if (attempt == TRIES - 1) return@withTimeout drafted(design)
                    redid = problems
                    messages += Message(Message.Role.User, "That can't be built: ${problems.joinToString("; ")}. Design it again so it can, as the same JSON object.")
                }
                drafted(null, "no design")
            }
            if (built !is Design) return built as Drafted
            // One look over a design that can be built, to make it read as the thing it is. The
            // improved one is kept only if it can still be built; otherwise, or if the look-over
            // fails or runs out of time, the design stands as it was.
            messages += Message(Message.Role.User, review(built))
            val looked = try {
                withTimeoutOrNull(timeout / 2) {
                    model.decide(DecisionRequest(system = system, messages = messages.toList(), temperature = TEMPERATURE, timeout = timeout / 2), schema)
                }
            } catch (e: DecisionFailed) {
                usage += e.usage
                null
            }
            val again = looked?.let { usage += it.usage; it.value.design() }?.takeIf { problems(it).isEmpty() }
            if (again == null) return drafted(built)
            raw = looked.raw
            drafted(again).copy(before = built)
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

        /** Why materials at [cost] can't be paid from [money], or null when they can: the sim refuses the build then, so the design is redone first. */
        fun unaffordable(cost: Int, money: Int): String? =
            if (cost <= money) null else "its materials cost $$cost and you have $$money; make it simpler, with fewer or weaker qualities, or a use whose materials cost less"

        const val TEMPERATURE = 0.9
        private const val TRIES = 2

        val INSTRUCTIONS = """
            |Right now you are not choosing an action: you are at your workbench, inventing. Design one new thing of your own that you'd like to have, and that isn't like what you already own. Make it yours: what you like, how you feel, what your room or your days are missing.
            |
            |For this reply only, ignore the reply format above: reply with a JSON object {"name": ..., "use": ..., "qualities": [{"quality": ..., "strength": ...}], "place": ..., "look": [{"shape": ..., "w": ..., "h": ..., "d": ..., "x": ..., "y": ..., "z": ..., "rot_x": ..., "rot_y": ..., "rot_z": ..., "color": ..., "part": ..., "taper": ..., "cut": ..., "hole": ..., "arc": ..., "glow": ..., "opacity": ...}], "book": ..., "why": ...} and nothing else.
        """.trimMargin()

        /** A writer that calls tools gets this in place of [INSTRUCTIONS]. */
        val BENCH_INSTRUCTIONS = """
            |Right now you are not choosing an action: you are at your workbench, inventing. Design one new thing of your own that you'd like to have, and that isn't like what you already own. Make it yours: what you like, how you feel, what your room or your days are missing.
            |
            |For this turn only, ignore the tools and reply format above: you have your workbench's tools instead.
        """.trimMargin()

        /** How the workbench's tools go, after the brief. */
        val AT_THE_BENCH = """
            |Make it with your workbench tools. Put it together with add_part, one call a shape (you can add several in one go), from the bottom up. look_at_it shows you every shape so far; redo_part and remove_part fix one. When it's all there, call build on its own with its name, use, qualities and why. Each part of the lantern above would be one add_part.
        """.trimMargin()

        /** How many times [timeout] a writer has at the workbench, where it takes many calls. */
        private const val BENCH_TIME = 2

        /** How a look is built, with a worked example: the brief's paragraph on it. */
        internal fun look(most: Int): String {
            val example = EXAMPLE.joinToString(",\n", "[\n", "\n]") { EXAMPLE_JSON.encodeToString(LookPart.serializer(), it) }
            return """
                |How it looks is up to $most shapes, in metres, put together the way the real thing's parts are. Give each shape a `part`, what it is of the thing ("rim", "handle", "leaf"), and give the thing the parts it really has, each with its own colour: a plant is a pot, soil and several leaves; a clock is a case, a face, two hands. Most things want 5 to $most shapes; one or two is never enough.
                |
                |Size it for the room: a floor tile is 1 by 1, the bed is about 0.55 tall, the desk 0.75, and you stand about 0.7. A thing on the floor needs to be 0.3 to 1.2 tall to be seen from across the room; a thing in a wall, 0.3 to 0.8 across. Parts rest on or touch each other; none floats.
                |
                |Each shape: shape (${LookPart.SHAPES.joinToString()}); w across, h up, d front to back, each 0.02 to 1.5; x, y, z for the bottom centre of the shape: x from -0.5 (left) to 0.5, y up from 0 at the floor to 1.5, z from -0.5 (back) to 0.5 (front); rot_x, rot_y, rot_z turn it about its bottom centre, in degrees; color is hex, like #c96f4a. A thing in a wall hangs flat against it, its back at z = -0.5. These shape it further, and leave it plain at the values in brackets:
                |- taper (cylinders, [1]): the top's width as a fraction of the bottom's: 0.6 for a vase's neck or a lampshade, 0 for a cone.
                |- cut (spheres, [1]): how much of it there is, from the top: 0.5 for a dome, a mound of soil or a mushroom cap.
                |- hole (cylinders, [0]): the hole's width as a fraction of the outside's: 0.85 for a flowerpot or a bowl, 0.7 for a ring.
                |- arc (cylinders, [360]): the degrees of the ring there are, centred on the front: 180, with a hole, for a handle or an arch. rot_x -90 stands a ring upright with its arc on top.
                |- glow ([0]): how it lights the room at night, 1 for a lamp's bulb or a flame.
                |- opacity ([1]): less for glass, 0.4 for a clear pane.
                |
                |How a lantern goes together, to show how the shapes fit (make your own thing, not this):
                |$example
            """.trimMargin()
        }

        /** The worked example in [look]: a lantern, which uses every way a shape can be shaped. */
        private val EXAMPLE = listOf(
            LookPart("cylinder", 0.3, 0.04, 0.3, color = "#4a3f38", part = "base"),
            LookPart("cylinder", 0.24, 0.3, 0.24, y = 0.04, color = "#e6efec", part = "glass", opacity = 0.4),
            LookPart("cylinder", 0.07, 0.12, 0.07, y = 0.04, color = "#f3ead6", part = "candle"),
            LookPart("sphere", 0.05, 0.08, 0.05, y = 0.16, color = "#ffcf5a", part = "flame", glow = 1.0),
            LookPart("cylinder", 0.32, 0.1, 0.32, y = 0.34, color = "#4a3f38", part = "roof", taper = 0.2),
            LookPart("cylinder", 0.16, 0.03, 0.16, y = 0.44, rotX = -90.0, color = "#4a3f38", part = "handle", hole = 0.75, arc = 180.0),
        )

        private val EXAMPLE_JSON = kotlinx.serialization.json.Json { encodeDefaults = true }

        /**
         * The one look-over a design gets once it can be built: what its shapes add up to, the ones
         * that touch nothing ([lookNotes]), and the ask to make it read as the thing it is.
         */
        internal fun review(design: Design): String = """
            |That can be built. Before you build it, look it over as it would stand in your room. ${lookNotes(design.look, design.place)}
            |
            |Does each shape read as the part you named? Is anything missing that the real thing has? Does every part rest on or touch another, with nothing floating or lost inside another? Is it big enough to see from across the room? Then reply with the whole design again as the same JSON object, its look improved where it needs it. Keep its name, use, qualities and place.
        """.trimMargin()

        /** What [look] adds up to: its size, the unturned shapes off the floor that touch no other, and whether it's too small to see where it stands ([place]). */
        internal fun lookNotes(look: List<LookPart>, place: String?): String {
            if (look.isEmpty()) return "It has no shapes yet."
            val wide = look.maxOf { it.x + it.w / 2 } - look.minOf { it.x - it.w / 2 }
            val tall = look.maxOf { it.y + it.h }
            val deep = look.maxOf { it.z + it.d / 2 } - look.minOf { it.z - it.d / 2 }
            fun turned(p: LookPart) = p.rotX != 0.0 || p.rotY != 0.0 || p.rotZ != 0.0
            fun touch(a: LookPart, b: LookPart): Boolean {
                fun overlap(a0: Double, a1: Double, b0: Double, b1: Double) = a0 <= b1 + TOUCH && b0 <= a1 + TOUCH
                return turned(a) || turned(b) ||
                    (overlap(a.x - a.w / 2, a.x + a.w / 2, b.x - b.w / 2, b.x + b.w / 2) &&
                        overlap(a.y, a.y + a.h, b.y, b.y + b.h) &&
                        overlap(a.z - a.d / 2, a.z + a.d / 2, b.z - b.d / 2, b.z + b.d / 2))
            }
            val floating = look.withIndex().filter { (i, p) -> !turned(p) && p.y > TOUCH && look.withIndex().none { (j, q) -> j != i && touch(p, q) } }
            val size = "It has ${look.size} shape${if (look.size == 1) "" else "s"} and is %.2f wide, %.2f tall and %.2f deep.".format(java.util.Locale.ROOT, wide, tall, deep)
            val off = if (floating.isEmpty()) "" else " These touch nothing: " + floating.joinToString { (i, p) -> "shape ${i + 1}" + (p.part.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") } + "."
            val small = if (place == InventRules.FLOOR && tall < SMALL) " At %.2f tall it would be hard to see from across the room; a thing on the floor is at least $SMALL tall.".format(java.util.Locale.ROOT, tall) else ""
            return size + off + small
        }

        /** A thing shorter than this, in metres, is too small to make out from across the room. */
        private const val SMALL = 0.4

        /** How close two shapes' boxes can be and still count as touching, in metres. */
        private const val TOUCH = 0.02

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
                |${look(rules.lookParts)}
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
            "edible" -> "something to eat or drink, which is used up when you have it; its qualities are what you get from eating it, on top of being fed, and once you've made it the shop will send more at what the materials cost"
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
                name = "pebble lamp", use = "viewable", qualities = mapOf("calm" to 2.0, "natural" to 2.0), place = InventRules.FLOOR,
                look = listOf(
                    LookPart("cylinder", 0.34, 0.1, 0.34, color = "#8a6a4a"),
                    LookPart("cylinder", 0.06, 0.5, 0.06, y = 0.1, color = "#6b5238"),
                    LookPart("sphere", 0.36, 0.3, 0.36, y = 0.55, color = "#f2dc8a", part = "shade", glow = 1.0),
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
    /** Writes what each thing does (its verbs and behavior) once its design is settled; without one, things only sit there. */
    private val coder: dev.lilguy.agent.code.Coder? = null,
) : AuthorHandler<Drafted> {
    override val name = "invention"
    override val writer: String get() = inventor.id

    override suspend fun author(call: AuthorCall): Drafted {
        val drafted = inventor.invent(call)
        val code = drafted.design?.let { d -> coder?.forThing(call, d) }
        return drafted.copy(code = code).also(onDraft)
    }

    override fun staged(result: Drafted) = AuthoredResult.Designed(result.design, failed = result.error, code = result.code?.design, codeFailed = result.code?.error)

    override fun keep(result: Drafted, call: AuthorCall, ended: Observation, view: AuthoredView?, deliver: (AuthoredResult) -> Unit) {
        val design = result.design ?: return
        val like = design.qualities.keys.joinToString(", ")
        val does = result.code?.design?.takeIf { !it.isEmpty }?.let { " What it does: " + dev.lilguy.agent.code.CodeHandler.summary(it) + "." }.orEmpty()
        val text = "I invented the ${design.name.trim()} and built it at my workbench" + (if (like.isEmpty()) "" else " ($like)") +
            (design.why.trim().trimEnd('.').takeIf { it.isNotEmpty() }?.let { ": $it." } ?: ".") + does
        if ("events" in call.op.into) store.appendEvent(run, result.tick, result.day, result.time, EventKind.Invention, text)
        deliver(AuthoredResult.Written(text))
    }
}
