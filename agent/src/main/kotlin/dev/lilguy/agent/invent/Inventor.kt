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
import dev.lilguy.sim.Footprint
import dev.lilguy.sim.InventRules
import dev.lilguy.sim.Link
import dev.lilguy.sim.ProductDesign
import dev.lilguy.sim.RecipeDesign
import dev.lilguy.sim.Structure
import dev.lilguy.sim.LookPart
import dev.lilguy.sim.Observation
import dev.lilguy.agent.code.Coded
import dev.lilguy.sim.BehaviorDesign
import dev.lilguy.sim.VerbDesign
import dev.lilguy.sim.CodeDesign
import dev.lilguy.sim.PartChange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.ceil
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
    /** Other looks its code can show, as a list a strict schema can hold. */
    val variants: List<VariantPick> = emptyList(),
    /** The recipes it runs as a machine; empty for a thing that isn't one. */
    val runs: List<String> = emptyList(),
    /** For a big thing on the land: its footprint in tiles; null otherwise. */
    val footprint: Footprint? = null,
    /** For a building on the land: the side its door is in; null for a solid thing, or one not on the land. */
    val door: String? = null,
    val windows: List<String> = emptyList(),
    /** For a thing on the land: the place it goes in, or null for nearest the workbench. */
    val where: String? = null,
    /** Recipes of its own, as a list a strict schema can hold. */
    val recipes: List<RecipePick> = emptyList(),
    val products: List<ProductDesign> = emptyList(),
    @SerialName("send_to") val sendTo: String? = null,
    @SerialName("take_from") val takeFrom: List<String> = emptyList(),
) {
    fun design() = Design(
        name, use, qualities.associate { it.quality to it.strength.toDouble() }, place, look, book, why, variants.associate { it.name.trim() to it.changes }, runs,
        footprint, door, windows, where, recipes.map { it.design() }, products, sendTo, takeFrom,
    )
}

/** A recipe as the model writes it: what it takes and makes as lists of picks, which a strict schema can hold. */
@Serializable
@SerialName("recipe_pick")
data class RecipePick(val says: String, val takes: List<CountPick>, val makes: List<CountPick>, val hours: Double?) {
    fun design() = RecipeDesign(says, takes.associate { it.item to it.count }, makes.associate { it.item to it.count }, hours)
}

/** So many of a thing, by item id or a product's name. */
@Serializable
@SerialName("count_pick")
data class CountPick(val item: String, val count: Int)

/** A variant of a look as the model writes it: its [name] and the [changes] it makes to named parts. */
@Serializable
@SerialName("variant_pick")
data class VariantPick(val name: String, val changes: List<PartChange>)

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
                    "design_reply.place" to "Only for a viewable thing: floor, wall, or land for something big that stands out on the land (a building, a tower, a big machine). Null otherwise.",
                    "design_reply.look" to "Its shapes, up to ${rules.lookParts} (${rules.land.lookParts} for a thing on the land), one per part of the thing.",
                    "design_reply.book" to "Only for a readable thing: the book it is. Null otherwise.",
                    "design_reply.why" to "Why you're making it, first person, under $WHY_CAP words.",
                    "design_reply.variants" to VARIANTS_HINT,
                    "design_reply.runs" to runsHint(content),
                ) + landHints("design_reply", rules) + mapOf(
                    "book_design.chapters" to "${rules.book.minChapters} to ${rules.book.maxChapters} chapters, one sentence each of what's in it.",
                    "book_design.skill" to "The one skill reading it raises, or null.",
                ) + Workbench.partHints(rules).descriptions + Workbench.variantHints,
                enums = mapOf(
                    "design_reply.use" to rules.uses.keys.toList(),
                    "design_reply.place" to listOf(InventRules.FLOOR, InventRules.WALL, InventRules.LAND),
                    "design_reply.door" to Structure.SIDES,
                    "quality_pick.quality" to content.vocabulary.qualities,
                    "look_part.shape" to LookPart.SHAPES,
                    "book_design.skill" to content.vocabulary.skills,
                ) + Workbench.partHints(rules).enums,
                ranges = mapOf("quality_pick.strength" to 1..3),
            ),
        ) { null }
        val w = world
        val spots: (String) -> List<String> = { place -> w?.inventSpots(place) ?: rules.freeSpots(content, place) }
        val messages = mutableListOf(Message(Message.Role.User, call.situation.trimEnd() + "\n\n" + brief(content, rules, obs, call.verb.minutes ?: 0, spots)))
        val system = call.system.trimEnd() + "\n\n" + INSTRUCTIONS
        val skill = content.machines?.earning?.skill?.let { obs.skills.orEmpty()[it] } ?: 0.0
        val site: (Design) -> InventRules.Sited = { d -> w?.inventSite(d) ?: InventRules.Sited.Nowhere("there's no land here to build on") }
        fun problems(design: Design) = when (val checked = rules.check(design, content, call.verb.minutes ?: 0, { spots(it).firstOrNull() }, site, skill)) {
            is InventRules.Checked.Ok -> listOfNotNull(
                if (checked.invention.bill.isNotEmpty()) short(checked.invention.bill, obs.owns.orEmpty(), content)
                else unaffordable(checked.invention.cost, obs.money ?: 0),
            )
            is InventRules.Checked.Refused -> checked.problems
        }
        if (model.capabilities.toolCalls) {
            // A writer that calls tools puts the thing together at the workbench a shape at a time.
            val bench = Workbench(rules, content, ::problems)
            val ask = call.situation.trimEnd() + "\n\n" + brief(content, rules, obs, call.verb.minutes ?: 0, spots) + "\n\n" + AT_THE_BENCH
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
            if (cost <= money) null else "it costs $$cost and you have $$money; make it simpler, with fewer or weaker qualities, or a use that costs less"

        /** Why a thing whose materials are [bill] can't be built from what he [owns], or null when it can: it's redone smaller or of what he has first. */
        fun short(bill: Map<String, Int>, owns: Map<String, Int>, content: BedroomContent): String? {
            val short = bill.filter { (m, n) -> (owns[m] ?: 0) < n }
            if (short.isEmpty()) return null
            fun named(m: String, n: Int) = "$n " + plural(content.item(m)?.name ?: m, n)
            // What would be left over says what to swap to: a design a part or two over in one material was redone a little smaller and still over.
            val spare = content.invent?.materials?.keys.orEmpty().associateWith { m -> (owns[m] ?: 0) - (bill[m] ?: 0) }.filterValues { it > 0 }
            return "it takes " + bill.entries.joinToString(" and ") { (m, n) -> named(m, n) } + ", and you have " +
                short.keys.joinToString(" and ") { m -> named(m, owns[m] ?: 0) } +
                (if (spare.isEmpty()) "" else "; you'd have " + spare.entries.joinToString(" and ") { (m, n) -> named(m, n) } + " to spare, so make some of its parts of those") +
                "; or make it smaller or of fewer parts (you can buy more on a shopping trip, but not now)"
        }

        /** [name] for [n] of it, as the sim counts things ("1 wood plank", "3 glass panes"). */
        internal fun plural(name: String, n: Int) = if (n == 1) name else if (name.endsWith("s") || name.endsWith("sh") || name.endsWith("ch")) "${name}es" else "${name}s"

        const val TEMPERATURE = 0.9
        private const val TRIES = 2

        val INSTRUCTIONS = """
            |Right now you are not choosing an action: you are at your workbench, inventing. Design one new thing of your own that you'd like to have, and that isn't like what you already own. Make it yours: what you like, how you feel, what your room or your days are missing.
            |
            |For this reply only, ignore the reply format above: reply with a JSON object {"name": ..., "use": ..., "qualities": [{"quality": ..., "strength": ...}], "place": ..., "look": [{"shape": ..., "w": ..., "h": ..., "d": ..., "x": ..., "y": ..., "z": ..., "rot_x": ..., "rot_y": ..., "rot_z": ..., "color": ..., "part": ..., "taper": ..., "cut": ..., "hole": ..., "arc": ..., "glow": ..., "opacity": ..., "material": ...}], "book": ..., "why": ..., "variants": [{"name": ..., "changes": [{"part": ..., ...}]}], "runs": [...], "footprint": {"w": ..., "d": ...} or null, "door": ..., "windows": [...], "where": ..., "recipes": [{"says": ..., "takes": [{"item": ..., "count": ...}], "makes": [...], "hours": ...}], "products": [{"name": ..., "says": ...}], "send_to": ..., "take_from": [...]} and nothing else.
        """.trimMargin()

        /** What the land, recipe and link fields mean, for the design's schema ([owner] `design_reply`) and the workbench's `build` (`build_args`). */
        fun landHints(owner: String, rules: InventRules) = mapOf(
            "$owner.footprint" to "Only for place land: the tiles it covers, w across (east-west) and d front to back (north-south), 1 to ${rules.land.most} each. Null otherwise.",
            "$owner.door" to "Only for place land: the side its door is in (north, south, east or west), so you can go inside; its edge tiles are its walls. Null for a solid thing you don't go into.",
            "$owner.windows" to "Only for a building with a door: the sides with a window, for daylight inside. Empty otherwise.",
            "$owner.where" to "Only for place land: the id of the place on the land it goes in (like meadow), or null for open ground nearest your workbench.",
            "$owner.recipes" to "For a machine: recipes of your own. Each takes some things you keep (item ids) and makes others (item ids, or the name of one of its products), in hours, or null hours for the fewest it needs. Empty for none.",
            "$owner.products" to "For a machine: new wares its recipes make, by name, each with a line of what it is. They sell at the hatch and by themselves through send_to market. Empty for none.",
            "$owner.send_to" to "For a machine: where what it makes goes by itself: market (sold at the going price), you (into your things), or the id of a machine of yours. Null to keep it in the machine until you take it out.",
            "$owner.take_from" to "For a machine: where it takes what it needs from by itself: you (your things), or ids of machines of yours. Empty to fill it by hand.",
            "recipe_pick.says" to "What it's doing while it works, a word or two: \"pressing\".",
            "recipe_pick.hours" to "How long a batch takes, or null for the fewest the machine needs.",
            "count_pick.item" to "An item id you keep (wood, clay, food_scrap...) or one of its products' names.",
        )

        /** The brief's paragraph on building big, out on the land; empty where there's no land. */
        internal fun land(content: BedroomContent, rules: InventRules): String {
            val places = content.world?.places.orEmpty()
            if (places.isEmpty()) return ""
            return """
                |
                |Something big can stand out on the land instead (place land): a cabin, a shed, a tower, a windmill, a big machine. Give it a footprint, the tiles it covers (1 by 1 to ${rules.land.most} by ${rules.land.most}), and its look covers that: x from -w/2 to w/2, z from -d/2 (back, north) to d/2 (front, south), with up to ${InventRules.OVERHANG} more each way for eaves, y from 0 up to ${rules.land.height.toInt()} metres, up to ${rules.land.lookParts} shapes. Out there a unit of material makes ${rules.land.unit} cubic metres, so a small cabin's floor, walls and roof take a hundred or so planks. With a door (a side: north, south, east or west) it's a building you can walk into: its edge tiles are its walls, but for the door in the middle of that side, so draw walls along its edges with a gap there, a floor, and a roof; inside it's a place of its own, where you can put things down, and windows (other sides) let the day in. Without a door it's solid, every tile of it in the way. It goes on open ground nearest your workbench, or in the place you name (where): ${places.joinToString { it.id }}. It can be a machine too, and run recipes.
            """.trimMargin()
        }

        /** The brief's paragraph on machines of his own design: recipes he writes, the wares they make, how they're timed and priced, and connecting them. */
        internal fun machines(content: BedroomContent, rules: InventRules, obs: Observation): String {
            val m = content.machines ?: return ""
            val e = m.earning
            val level = e.skill?.let { obs.skills.orEmpty()[it] } ?: 0.0
            val per = e.perHour * (1 + e.perLevel * level) * 100
            return """
                |
                |A thing to look at can be a machine of your own design: give it recipes. Each batch takes things you keep (item ids, like wood, clay or food_scrap) and makes others, existing things or new wares of your own (its products, by name). A machine you build adds ${String.format(java.util.Locale.ROOT, "%.1f", per)}% of what its materials are worth in value each hour it works${e.skill?.let { " (more as your $it grows)" }.orEmpty()}, so a bigger, finer machine earns more: a recipe takes as long as the value it adds needs at that rate, and a product sells for what goes into it plus those hours. What holds a ware back is the market: each kind fetches less the more of it is sold, and climbs back over days, so new kinds pay. Your machines talk to each other over the air: take_from you and it fills itself from your things; send_to market and what it makes is sold by itself; send_to another machine and it goes in there, however far apart they stand. A machine needs a mechanism (in its bill) and wears with each batch.
            """.trimMargin()
        }

        /** What `runs` is, with the world's recipes: for the design's schema and the workbench's `build`. */
        fun runsHint(content: BedroomContent): String = "For a machine that makes things (it stands somewhere, so it's viewable): the recipes it runs, " +
            (content.machines?.recipes.orEmpty().joinToString("; ") { r -> "${r.id}: ${r.takes.entries.joinToString { (i, n) -> "$n $i" }} into ${r.makes.entries.joinToString { (i, n) -> "$n $i" }} in ${r.hours.toInt()}h" }.ifEmpty { "none yet" }) +
            ". It wears with each batch and needs mending. Empty for anything else."

        /** What `variants` are, for the design's schema and the workbench's `build`. */
        const val VARIANTS_HINT = "Other looks it can show when its code says (world.show), up to ${InventRules.MAX_VARIANTS}; often none. Each is a name (\"spraying\", \"off\") and changes to parts you named: a new color or glow, a turn, a scale, or hidden. Empty for none."

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
                |Each shape: shape (${LookPart.SHAPES.joinToString()}); w across and d front to back, 0.02 to 1.5, and h up, 0.02 to 3; x, y, z for the bottom centre of the shape: x from -0.5 (left) to 0.5, y up from 0 at the floor to 3, z from -0.5 (back) to 0.5 (front) (a thing on the land is bigger: see below); rot_x, rot_y, rot_z turn it about its bottom centre, in degrees; color is hex, like #c96f4a. A thing in a wall hangs flat against it, its back at z = -0.5. These shape it further, and leave it plain at the values in brackets:
                |- taper (cylinders, [1]): the top's width as a fraction of the bottom's: 0.6 for a vase's neck or a lampshade, 0 for a cone.
                |- cut (spheres, [1]): how much of it there is, from the top: 0.5 for a dome, a mound of soil or a mushroom cap.
                |- hole (cylinders, [0]): the hole's width as a fraction of the outside's: 0.85 for a flowerpot or a bowl, 0.7 for a ring.
                |- arc (cylinders, [360]): the degrees of the ring there are, centred on the front: 180, with a hole, for a handle or an arch. rot_x -90 stands a ring upright with its arc on top.
                |- glow ([0]): how it lights the room at night, 1 for a lamp's bulb or a flame.
                |- opacity ([1]): less for glass, 0.4 for a clear pane.
                |
                |A thing that does something can have variants: other looks its code shows, each changing named parts (a lamp's "off" with its bulb's glow 0; a sprinkler's "spraying" with its spray shown, hidden in its own look). Its code can also set a named part moving (spin, bob, sway, pulse, shake). So name the parts that change or move.
                |
                |How a lantern goes together, to show how the shapes fit (make your own thing, not this):
                |$example
            """.trimMargin()
        }

        /** The worked example in [look]: a lantern, which uses every way a shape can be shaped. */
        private val EXAMPLE = listOf(
            LookPart("cylinder", 0.3, 0.04, 0.3, color = "#4a3f38", part = "base", material = "metal"),
            LookPart("cylinder", 0.24, 0.3, 0.24, y = 0.04, color = "#e6efec", part = "glass", opacity = 0.4, material = "glass"),
            LookPart("cylinder", 0.07, 0.12, 0.07, y = 0.04, color = "#f3ead6", part = "candle", material = "wood"),
            LookPart("sphere", 0.05, 0.08, 0.05, y = 0.16, color = "#ffcf5a", part = "flame", glow = 1.0, material = "glass"),
            LookPart("cylinder", 0.32, 0.1, 0.32, y = 0.34, color = "#4a3f38", part = "roof", taper = 0.2, material = "metal"),
            LookPart("cylinder", 0.16, 0.03, 0.16, y = 0.44, rotX = -90.0, color = "#4a3f38", part = "handle", hole = 0.75, arc = 180.0, material = "copper_wire"),
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

        /**
         * What he can make, what it costs and what he has, as the user message's second half. [spots]:
         * the free spots a thing of a place (`floor` or `wall`) could go, the first where it would.
         */
        internal fun brief(content: BedroomContent, rules: InventRules, obs: Observation, minutes: Int, spots: (String) -> List<String> = { rules.freeSpots(content, it) }): String {
            val owned = obs.owns.orEmpty().keys
            fun has(name: String) = content.withName(name).any { it.id in owned }
            val uses = rules.uses.entries.joinToString("\n") { (use, u) ->
                val cost = if (u.built) "built from materials" else "$${u.base}" + if (u.perStrength > 0) " plus $${u.perStrength} a point of quality strength" else ""
                // What else its verbs ask for that he doesn't have, so he knows a record needs a player.
                val needs = content.verbs.filter { use in it.args.values }.flatMap { v -> (v.args.values + v.requires).filter { it != use && !has(it) } }.distinct()
                    .map { n -> content.withName(n).firstOrNull()?.name ?: n }
                val also = if (needs.isEmpty()) "" else "; no use to you until you have a ${needs.joinToString(" and a ")}"
                "- $use: ${what(use, content, rules, minutes, spots)} ($cost$also)"
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
                |What it's like is up to ${rules.qualities.most} qualities, each with a strength from 1 to 3, adding up to at most ${rules.qualities.total.toInt()}: $qualities. Stronger qualities you like make it worth more to you and take more to make. You can't use more than you have.
                |${materials(content, rules, obs)}
                |
                |${look(rules.lookParts)}
                |${land(content, rules)}
                |${machines(content, rules, obs)}
                |
                |${if (things.isEmpty()) "You own nothing with qualities yet." else "What you own that has qualities: $things."}
                |
                |What do you invent?
            """.trimMargin()
        }

        /**
         * The brief's paragraph on materials, when built things are made of them: each material, what
         * it's for and costs, how a look adds up to a bill, the workings, and what he has. Empty without.
         */
        internal fun materials(content: BedroomContent, rules: InventRules, obs: Observation): String {
            if (rules.materials.isEmpty()) return ""
            val owns = obs.owns.orEmpty()
            fun named(m: String, n: Int) = "$n " + plural(content.item(m)?.name ?: m, n)
            val list = rules.materials.entries.joinToString("\n") { (m, mat) ->
                val s = content.item(m)
                val each = s?.price?.let { p -> " ($" + String.format(java.util.Locale.ROOT, "%.0f", p.toDouble() / s.pack) + " each, " + (if (s.pack > 1) "${s.pack} for $$p" else "$$p") + ")" }.orEmpty()
                "- $m$each: ${mat.what}"
            }
            val side = Math.cbrt(rules.unit)
            val workings = listOfNotNull(
                rules.workings.code.takeIf { it.isNotEmpty() }?.let { w -> "a thing with code needs " + w.entries.joinToString(" and ") { (m, n) -> named(m, n) } + " of wiring, or it's built but does nothing" },
                rules.workings.moving.takeIf { it.isNotEmpty() }?.let { w -> "one whose code moves a part, or that runs recipes, needs " + w.entries.joinToString(" and ") { (m, n) -> named(m, n) } + " for its mechanism" },
            ).joinToString("; ")
            val has = rules.materials.keys.filter { (owns[it] ?: 0) > 0 }.joinToString { named(it, owns.getValue(it)) }.ifEmpty { "none" }
            return """
                |
                |What you build to look at, to play or to sell is made of materials, and each shape of its look names one (its `material`):
                |$list
                |A shape takes one of its material for every ${String.format(java.util.Locale.ROOT, "%.2f", side)} by ${String.format(java.util.Locale.ROOT, "%.2f", side)} by ${String.format(java.util.Locale.ROOT, "%.2f", side)} of it (a 0.3 cube takes ${ceil(0.027 / rules.unit).toInt()}), and at least 1; what most of it is made of takes ${rules.finish} more for each point of quality strength. ${workings.replaceFirstChar { it.uppercase() }}. Food and books are bought with money instead. The materials you have: $has. Build it from those: you can't buy more until you go shopping.
            """.trimMargin()
        }

        private fun what(use: String, content: BedroomContent, rules: InventRules, minutes: Int, spots: (String) -> List<String>): String = when (use) {
            "viewable" -> {
                val floor = spots(InventRules.FLOOR)
                val wall = spots(InventRules.WALL)
                fun free(s: List<String>) = if (s.isEmpty()) "none" else "${s.size}, the next at ${s.first()}"
                "something to look at, on the floor or in a wall (free spots on the floor, indoors or out: ${free(floor)}; in a wall: ${free(wall)}); the qualities you like in it raise the comfort where you can see it, which lifts the mood you settle to, and while it's new it's stimulating"
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
 * (`LILGUY_INVENTOR`), as the random painter is for art. A design whose name he already has is
 * built again under a numbered name ("pebble lamp 2"), so a long run keeps filling his spots.
 */
class CannedInventor(private val designs: List<Design> = DESIGNS, private val codes: Map<String, CodeDesign> = emptyMap()) : Inventing {
    private var world: BedroomWorld? = null
    private var calls = 0

    override val id = "canned-inventor"

    override fun connect(world: BedroomWorld) {
        this.world = world
    }

    override suspend fun invent(call: AuthorCall): Drafted {
        val canned = designs[calls++ % designs.size]
        val names = world?.content?.items.orEmpty().map { it.name.lowercase() }.toSet()
        val design = generateSequence(1) { it + 1 }.map { if (it == 1) canned.name else "${canned.name} $it" }.first { it !in names }.let { canned.copy(name = it) }
        val code = codes[canned.name]?.let { Coded(call.obs.tick, call.obs.clock?.day, call.obs.clock?.time, id, it, emptyList(), null, Usage(), Duration.ZERO, forThing = design.name) }
        return Drafted(call.obs.tick, call.obs.clock?.day, call.obs.clock?.time, id, design, emptyList(), null, Usage(), Duration.ZERO, code = code)
    }

    companion object {
        /**
         * `LILGUY_INVENTOR=machines`: things that do something by themselves, with their code.
         * `workshop`: things that stand on the ground, the sprinkler with its code among them, for
         * filling his spots. Anything else: [DESIGNS], which only sit there.
         */
        fun of(which: String): CannedInventor = when (which) {
            "build", "land" -> CannedInventor(listOf(SHED, BOWL_LATHE))
            "machines" -> CannedInventor(listOf(SPRINKLER), mapOf(SPRINKLER.name to SPRINKLER_CODE))
            "workshop" -> CannedInventor(listOf(SPRINKLER) + DESIGNS.filter { it.place == InventRules.FLOOR }, mapOf(SPRINKLER.name to SPRINKLER_CODE))
            else -> CannedInventor()
        }

        private fun plank(part: String, w: Double, h: Double, d: Double, x: Double = 0.0, y: Double = 0.0, z: Double = 0.0, color: String = "#b08356", rotY: Double = 0.0) =
            LookPart("box", w, h, d, x, y, z, rotY = rotY, color = color, part = part, material = "wood")

        /** A 5 by 4 shed out on the land, its door on the south side and a window east: a floor, walls with a gap for the door, a stepped roof and a chimney. */
        val SHED = Design(
            name = "garden shed", use = "viewable", qualities = mapOf("cozy" to 2.0, "natural" to 1.0), place = InventRules.LAND,
            footprint = Footprint(5, 4), door = Structure.SOUTH, windows = listOf(Structure.EAST),
            look = listOf(
                plank("floor", 5.0, 0.1, 4.0, color = "#c9a477"),
                plank("back wall", 5.0, 2.4, 0.15, z = -1.925),
                plank("front wall", 2.0, 2.4, 0.15, x = -1.5, z = 1.925),
                plank("front wall", 2.0, 2.4, 0.15, x = 1.5, z = 1.925),
                plank("lintel", 1.0, 0.4, 0.15, y = 2.0, z = 1.925),
                plank("door", 0.9, 2.0, 0.06, x = 0.9, z = 2.3, rotY = 75.0, color = "#7f5a3a"),
                plank("side wall", 0.15, 2.4, 3.7, x = -2.425),
                plank("side wall", 0.15, 2.4, 3.7, x = 2.425),
                LookPart("box", 0.04, 0.7, 1.0, x = 2.52, y = 1.0, color = "#d9eef0", part = "window", opacity = 0.45, material = "glass"),
                plank("roof", 5.4, 0.15, 4.4, y = 2.4, color = "#6e4f3a"),
                plank("roof", 4.0, 0.15, 3.0, y = 2.55, color = "#7a5940"),
                plank("roof", 2.6, 0.15, 1.6, y = 2.7, color = "#86634a"),
                LookPart("cylinder", 0.3, 1.0, 0.3, x = 1.6, y = 2.4, z = -1.0, color = "#5c6266", part = "chimney", material = "metal"),
            ),
            why = "a place of my own out on the grass, to keep my things and get away from the house",
        )

        /** A lathe that turns planks into bowls: it fills itself from his wood and sells what it turns. */
        val BOWL_LATHE = Design(
            name = "bowl lathe", use = "viewable", qualities = mapOf("lively" to 1.0), place = InventRules.FLOOR,
            look = listOf(
                LookPart("box", 0.8, 0.45, 0.45, color = "#6f7b82", part = "bed", material = "metal"),
                LookPart("cylinder", 0.08, 0.25, 0.08, x = -0.3, y = 0.45, color = "#9aa3a8", part = "headstock", material = "metal"),
                LookPart("cylinder", 0.22, 0.1, 0.22, x = 0.05, y = 0.5, rotZ = 90.0, color = "#c69a6b", part = "bowl", material = "wood"),
                LookPart("cylinder", 0.08, 0.25, 0.08, x = 0.3, y = 0.45, color = "#9aa3a8", part = "tailstock", material = "metal"),
            ),
            recipes = listOf(RecipeDesign("turning", takes = mapOf("wood" to 2), makes = mapOf("wooden bowl" to 1))),
            products = listOf(ProductDesign("wooden bowl", "a bowl turned from one of my planks")),
            takeFrom = listOf(Link.YOU), sendTo = Link.MARKET,
            why = "so my planks turn into bowls and money while I do other things",
        )

        /** A garden sprinkler: a spinning head over a mist dome, which swells and glows in its `spraying` variant. */
        val SPRINKLER = Design(
            name = "garden sprinkler", use = "viewable", qualities = mapOf("natural" to 1.0, "lively" to 1.0), place = InventRules.FLOOR,
            look = listOf(
                LookPart("cylinder", 0.36, 0.04, 0.36, color = "#6b7a5a", part = "base", material = "metal"),
                LookPart("cylinder", 0.05, 0.34, 0.05, y = 0.04, color = "#9aa3a8", part = "pipe", material = "metal"),
                LookPart("cylinder", 0.12, 0.06, 0.12, y = 0.38, color = "#c9b458", part = "head", material = "metal"),
                LookPart("box", 0.36, 0.025, 0.04, y = 0.41, color = "#c9b458", part = "head", material = "metal"),
                LookPart("sphere", 0.06, 0.06, 0.06, x = -0.18, y = 0.4, color = "#4f8fc0", part = "head", material = "copper_wire"),
                LookPart("sphere", 0.06, 0.06, 0.06, x = 0.18, y = 0.4, color = "#4f8fc0", part = "head", material = "copper_wire"),
                LookPart("sphere", 0.5, 0.26, 0.5, y = 0.44, color = "#cfe8f3", part = "spray", cut = 0.5, opacity = 0.25, material = "glass"),
            ),
            why = "the seed-pod bush takes a day and a half to grow back, and it could use some help",
            variants = mapOf(
                "spraying" to listOf(
                    PartChange("spray", color = "#8fd0ee", glow = 0.6, scale = 1.8),
                    PartChange("head", color = "#e8d36a"),
                ),
            ),
        )

        /** Every half hour, a bare patch in its place gets two hours' growth, and it shows it: spraying, its head spinning, a readout, and real spray on the tiles round it (a planter there is watered). */
        val SPRINKLER_CODE = CodeDesign(
            verbs = listOf(
                VerbDesign(
                    called = "set out the sprinkler", phrase = "set the sprinkler out in the garden", says = "carries it out beside the seed-pod bush, where it can water what grows in the garden", minutes = 10,
                    what = CodeDesign.THIS, at = "what",
                    code = """
                        function canDo(world) {
                          if (world.place(world.args.what) === "garden") return "it's out in the garden already";
                          if (!world.spots().free.floor.includes("by_seedpod_bush")) return "there's nowhere free in the garden to set it";
                        }
                        function finish(world) {
                          world.move(world.args.what, "by_seedpod_bush");
                          return "set it out by the seed-pod bush";
                        }
                    """.trimIndent(),
                ),
            ),
            behaviors = listOf(
                BehaviorDesign(
                    CodeDesign.THIS, "every half hour it waters whatever's bare in its place, so it grows back sooner",
                    """
                        function every(world, thing) {
                          const dry = world.patches(world.place(thing.id)).filter(p => p.bare);
                          if (dry.length === 0) {
                            world.show(thing.id, null);
                            world.motion(thing.id, "head", null);
                            world.readout(thing.id, null);
                            world.emit(thing.id, "water", 0, 0);
                            return;
                          }
                          dry.forEach(p => world.regrow(p.id, 2));
                          world.emit(thing.id, "water", 3, 2);
                          world.show(thing.id, "spraying");
                          world.motion(thing.id, "head", "spin", 2);
                          world.readout(thing.id, "watering");
                        }
                    """.trimIndent(),
                    everyMinutes = 30,
                ),
            ),
            why = "so it looks after the garden while I'm inside",
        )

        val DESIGNS = listOf(
            Design(
                name = "pebble lamp", use = "viewable", qualities = mapOf("calm" to 2.0, "natural" to 2.0), place = InventRules.FLOOR,
                look = listOf(
                    LookPart("cylinder", 0.34, 0.1, 0.34, color = "#8a6a4a", material = "wood"),
                    LookPart("cylinder", 0.06, 0.5, 0.06, y = 0.1, color = "#6b5238", material = "wood"),
                    LookPart("sphere", 0.36, 0.3, 0.36, y = 0.55, color = "#f2dc8a", part = "shade", glow = 1.0, material = "glass"),
                    LookPart("sphere", 0.2, 0.12, 0.2, x = 0.1, y = 0.08, z = 0.1, color = "#5f8a4e", material = "wood"),
                    LookPart("sphere", 0.16, 0.1, 0.16, x = -0.12, y = 0.08, z = 0.06, color = "#74a05c", material = "wood"),
                ),
                why = "the room needs something green that glows",
            ),
            Design(
                name = "paper kite", use = "viewable", qualities = mapOf("bright" to 2.0, "lively" to 1.0), place = InventRules.WALL,
                look = listOf(
                    LookPart("box", 0.4, 0.4, 0.03, y = 0.2, rotZ = 45.0, color = "#e2574c", material = "wood"),
                    LookPart("box", 0.03, 0.5, 0.02, y = 0.15, z = 0.02, color = "#f4e9c9", material = "wood"),
                    LookPart("box", 0.05, 0.3, 0.02, x = 0.05, y = -0.0, z = 0.02, rotZ = 15.0, color = "#4f8fc0", material = "wood"),
                ),
                why = "I can't go outside, so the sky can come in",
            ),
            Design(
                name = "honey toast", use = "edible", qualities = mapOf("warm" to 2.0, "cozy" to 1.0),
                look = listOf(LookPart("box", 0.2, 0.04, 0.2, color = "#d9a352", material = "wood")),
                why = "meals are fine but nothing about them is nice",
            ),
            Design(
                name = "tin robot", use = "viewable", qualities = mapOf("strange" to 2.0, "lively" to 2.0), place = InventRules.FLOOR,
                look = listOf(
                    LookPart("box", 0.3, 0.34, 0.22, y = 0.2, color = "#9fb4c2", material = "metal"),
                    LookPart("box", 0.22, 0.2, 0.2, y = 0.56, color = "#b9cad6", material = "metal"),
                    LookPart("cylinder", 0.08, 0.2, 0.08, x = -0.09, color = "#70848f", material = "metal"),
                    LookPart("cylinder", 0.08, 0.2, 0.08, x = 0.09, color = "#70848f", material = "metal"),
                    LookPart("sphere", 0.05, 0.05, 0.03, x = -0.05, y = 0.66, z = 0.1, color = "#f2c94c", material = "glass"),
                    LookPart("sphere", 0.05, 0.05, 0.03, x = 0.05, y = 0.66, z = 0.1, color = "#f2c94c", material = "glass"),
                    LookPart("cylinder", 0.02, 0.14, 0.02, y = 0.76, color = "#70848f", material = "copper_wire"),
                    LookPart("sphere", 0.06, 0.06, 0.06, y = 0.9, color = "#e2574c", material = "metal"),
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
        // A canned inventor brings its own code.
        val code = drafted.code ?: drafted.design?.let { d -> coder?.forThing(call, d) }
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
