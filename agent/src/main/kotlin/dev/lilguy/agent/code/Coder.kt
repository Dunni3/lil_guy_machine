package dev.lilguy.agent.code

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
import dev.lilguy.sim.AuthoredView
import dev.lilguy.sim.BedroomContent
import dev.lilguy.sim.BedroomWorld
import dev.lilguy.sim.CodeDesign
import dev.lilguy.sim.CodeRules
import dev.lilguy.sim.Design
import dev.lilguy.sim.InventRules
import dev.lilguy.sim.Invention
import dev.lilguy.sim.Observation
import dev.lilguy.sim.ScriptApi
import dev.lilguy.sim.ScriptSandbox
import dev.lilguy.sim.Triggers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * One go at writing code, for logs: the [design] he settled on (null when there's none), what was
 * wrong with his first try when he had to redo it ([redid]), and why there's no design ([error]).
 * [forThing]: the name of the thing it was written for at the workbench, null at his desk.
 */
data class Coded(
    val tick: Long,
    val day: Int?,
    val time: String?,
    val coder: String,
    val design: CodeDesign?,
    val redid: List<String>,
    val raw: String?,
    val usage: Usage,
    val latency: Duration,
    val error: String? = null,
    val forThing: String? = null,
)

/**
 * Writes the code for what he does and what his things do: JavaScript against the world's API
 * ([ScriptApi]), checked by the sim's rules ([CodeRules]) here so a refused design can be redone
 * once with the reasons; the sim checks it again when it goes in. [write] is the `devise` verb at
 * his desk; [forThing] is the function of a thing he just designed at the workbench. [connect] it to
 * the world first.
 */
class Coder(private val model: DecisionModel, private val timeout: Duration = 120.seconds) {
    private var world: BedroomWorld? = null
    private val sandbox = ScriptSandbox()

    val id: String get() = model.id

    fun connect(world: BedroomWorld) {
        this.world = world
    }

    /** At his desk: new verbs, rewrites, or what a thing he has does by itself. */
    suspend fun write(call: AuthorCall): Coded {
        val content = world?.content ?: return failed(call.obs, "the coder isn't connected to a world")
        return code(call, content, DESK + "\n\n" + brief(content, call.obs, world?.inventions.orEmpty()), null) { CodeRules.problems(it, content, sandbox) }
    }

    /** At the workbench: what [design], which he's about to build, does. Null design or rules: nothing to write. */
    suspend fun forThing(call: AuthorCall, design: Design): Coded {
        val content = world?.content ?: return failed(call.obs, "the coder isn't connected to a world")
        val rules = content.invent ?: return failed(call.obs, "this world has no rules for inventing")
        // Checked against the world as it will be once the thing is built, with "this" as its id.
        val built = (rules.check(design, content, call.verb.minutes ?: 60) as? InventRules.Checked.Ok)?.invention
            ?: return failed(call.obs, "the design can't be built, so there's nothing to write code for")
        val after = runCatching { content.plus(built) }.getOrElse { return failed(call.obs, it.message ?: "the design doesn't fit") }
        val about = "You're about to build this, your own invention, at your workbench:\n" + thingLine(design) +
            "\n\nIn your code it's \"${CodeDesign.THIS}\": use \"${CodeDesign.THIS}\" as a verb's `what` and `at`, and as a behavior's `thing`. Once it's built its id is `${built.item.id}`, which is what world.args.what and thing.id will be."
        return code(call, after, WORKBENCH + "\n\n" + about + "\n\n" + brief(after, call.obs, world?.inventions.orEmpty()), design.name) { d -> CodeRules.problems(d.about(built.item.id), after, sandbox) }
    }

    private fun failed(obs: Observation, error: String) = Coded(obs.tick, obs.clock?.day, obs.clock?.time, model.id, null, emptyList(), null, Usage(), Duration.ZERO, error)

    private suspend fun code(call: AuthorCall, content: BedroomContent, ask: String, forThing: String?, problems: (CodeDesign) -> List<String>): Coded {
        val started = TimeSource.Monotonic.markNow()
        val obs = call.obs
        var usage = Usage()
        var raw: String? = null
        var redid = emptyList<String>()
        fun coded(design: CodeDesign?, error: String? = null) = Coded(obs.tick, obs.clock?.day, obs.clock?.time, model.id, design, redid, raw, usage, started.elapsedNow(), error, forThing)
        val schema = ActionSchema.of("code_design", CodeDesign.serializer(), hints(content)) { null }
        val messages = mutableListOf(Message(Message.Role.User, call.situation.trimEnd() + "\n\n" + ask))
        val system = call.system.trimEnd() + "\n\n" + INSTRUCTIONS
        return try {
            withTimeout(timeout) {
                for (attempt in 0 until TRIES) {
                    val d = model.decide(DecisionRequest(system = system, messages = messages.toList(), temperature = TEMPERATURE, timeout = timeout), schema)
                    usage += d.usage
                    raw = d.raw
                    val found = problems(d.value)
                    messages += Message(Message.Role.Assistant, d.raw)
                    if (found.isEmpty()) return@withTimeout coded(d.value)
                    // The last try goes to the sim as it is: what goes in goes in, and he hears why the rest didn't.
                    if (attempt == TRIES - 1) return@withTimeout coded(d.value)
                    redid = found
                    messages += Message(Message.Role.User, "That won't go in: ${found.joinToString("; ")}. Fix it and reply with the whole design again, as the same JSON object.")
                }
                coded(null, "no design")
            }
        } catch (e: TimeoutCancellationException) {
            coded(null, "timed out after $timeout")
        } catch (e: CancellationException) {
            throw e
        } catch (e: DecisionFailed) {
            usage += e.usage
            raw = e.attempts.lastOrNull()?.raw
            coded(null, e.message ?: e.toString())
        } catch (e: Exception) {
            coded(null, e.message ?: e.toString())
        }
    }

    private fun hints(content: BedroomContent) = SchemaHints(
        descriptions = mapOf(
            "code_design.verbs" to "New verbs of yours, or rewrites of yours (the same `called`). Can be empty.",
            "code_design.behaviors" to "What things do by themselves. Can be empty.",
            "code_design.why" to "Why you're writing this, first person, under 30 words.",
            "verb_design.called" to "Its name, a few plain words: \"water the plant\". Its id is these words joined with underscores.",
            "verb_design.phrase" to "What you do, for your list of things you can do: \"water the plant\".",
            "verb_design.says" to "What it does, in a line, for when you choose: \"waters it; it grows a leaf a day while it's watered\".",
            "verb_design.minutes" to "How long it takes, 1 to ${CodeRules.MAX_MINUTES}.",
            "verb_design.what" to "The thing it takes: an id, or a use like playable or edible, or null.",
            "verb_design.at" to "Where you do it: here, what (at the thing it takes), or the id of a thing that stands in a spot.",
            "verb_design.code" to "The JavaScript: function finish(world) { ... }, and canDo(world) if it can't always be done.",
            "behavior_design.thing" to "The id of the thing that does it.",
            "behavior_design.says" to "What it does by itself, in a line.",
            "behavior_design.code" to "The JavaScript: one or more of hourly, every, onArrive, onLeave, onFinish, onSleep, onWake, onTimer, each function(world, thing, ...).",
            "behavior_design.every_minutes" to "Minutes between runs of its every(world, thing), ${Triggers.MIN_EVERY} to ${Triggers.MAX_EVERY}; null without one.",
        ),
    )

    companion object {
        const val TEMPERATURE = 0.7
        private const val TRIES = 2

        val INSTRUCTIONS = """
            |Right now you are not choosing an action: you are writing code, the way things in your world work. For this reply only, ignore the reply format above: reply with one JSON object {"verbs": [{"called": ..., "phrase": ..., "says": ..., "minutes": ..., "what": ..., "at": ..., "code": ...}], "behaviors": [{"thing": ..., "says": ..., "code": ..., "every_minutes": ...}], "why": ...} and nothing else.
        """.trimMargin()

        private val DESK = """
            |You're at your desk, writing code. Code is how you add to what your world can do: a verb is a new thing you can do, and a behavior is what one of your things does by itself. Write something you'd like to be able to do, or give one of your things a use or a life of its own, or fix or improve one of your verbs. Make it yours: what you like, what your days are missing.
        """.trimMargin()

        private val WORKBENCH = """
            |You're at your workbench, and before you build your invention you're working out what it does: its function. Give it verbs, the ways you use it or play with it or look after it, and if it fits a behavior, what it does by itself, and when. A thing that does something is worth more than one that only sits there; make it do something that matters to you.
        """.trimMargin()

        /** How code works, the API, an example, and what he has: the second half of the ask. */
        internal fun brief(content: BedroomContent, obs: Observation, inventions: List<Invention> = emptyList()): String {
            val owned = obs.owns.orEmpty()
            val made = inventions.associateBy { it.item.id }
            val things = content.items.filter { (owned[it.id] ?: 0) > 0 && !it.carried && it.group != "fixture" }.joinToString("\n") { s ->
                "- ${s.id}: ${s.name} (${s.group}" + (if (s.affords.isEmpty()) "" else "; for ${s.affords.keys.joinToString()}") +
                    (if (s.qualities.isEmpty()) "" else "; ${s.qualities.entries.joinToString { (q, v) -> "$q ${v.toInt()}" }}") +
                    (s.slot?.let { "; stands at $it" } ?: "") + (made[s.id]?.let(::looks).orEmpty()) +
                    (s.machine?.runs?.takeIf { it.isNotEmpty() }?.let { "; a machine running ${it.joinToString()}" }.orEmpty()) + ")"
            }
            val mine = content.verbs.filter { it.code != null }
            val verbs = if (mine.isEmpty()) "None yet." else mine.joinToString("\n\n") { v ->
                "- ${v.id} (${v.minutes} min" + (v.args["what"]?.let { ", takes $it" } ?: "") + ", at ${v.at.joinToString()}): ${v.code!!.says}\n```js\n${v.code!!.source.trim()}\n```"
            }
            val others = content.verbs.filter { it.code == null }.joinToString { it.id }
            return """
                |How code works. It's JavaScript (let, const, arrow functions; in a for loop use let, not const; no Date, no imports, no network). A verb's code has `function finish(world)`, run as you finish doing it; what it returns (a string) is how it went. It may also have `function canDo(world)`, run whenever you're choosing: return a string saying why not to keep it off your list, or nothing when you can. canDo can only look. A behavior is what a thing does by itself while you own it: one or more of these functions, where `thing` is world.thing(its id):
                |${Triggers.ALL.joinToString("\n") { "- ${it.second}" }}
                |What your code does happens, but nothing comes from nothing: money comes from selling things, things from machines fed with what they take (world.machine, world.transfer), fullness from eating and energy from sleep. Machines wear with use, a thing's code too: a worn one slows, then jams until you mend it (the repair verb, with a spare part). On the land a thing's code reaches what's in its own place (a sprinkler in the garden waters the garden), what it does there you hear about when you're next there, but your machines talk over the air, so world.transfer and world.link work between them wherever they stand (world.link(machine, "market") sells what it makes by itself), and what it does for your mood or stimulation wears thin with repeating, as a verb's does. An invention can show the variants it was designed with, move its named parts and show a readout, so you can see what it's doing. Code that throws an error fails the verb with the error, and a behavior that breaks three runs in a row stops.
                |
                |The world API:
                |${ScriptApi.reference(content)}
                |
                |An example, to show the shape (write your own thing, not this). A verb:
                |```js
                |function canDo(world) {
                |  if ((world.state(world.args.what).water || 0) >= 3) return "it's watered enough";
                |}
                |function finish(world) {
                |  const id = world.args.what;
                |  const water = (world.state(id).water || 0) + 1;
                |  world.setState(id, "water", water);
                |  world.need("mood", 2);
                |  return "watered it (" + water + " of 3)";
                |}
                |```
                |and a behavior:
                |```js
                |function hourly(world, thing) {
                |  const water = thing.state.water || 0;
                |  world.readout(thing.id, water > 0 ? "water " + water : "dry");
                |  if (water <= 0) return;
                |  world.setState(thing.id, "water", water - 1);
                |  if (world.time().hour === 9) { world.setState(thing.id, "leaves", (thing.state.leaves || 0) + 1); return "grew a leaf"; }
                |}
                |```
                |
                |Your things (id: name):
                |${things.ifEmpty { "None." }}
                |
                |Your own verbs so far, with their code:
                |$verbs
                |
                |The world's own verbs (you can't rewrite these; name yours differently): $others
                |
                |What do you write?
            """.trimMargin()
        }

        private fun thingLine(d: Design) = "- ${d.name}: for ${d.use}" + (if (d.qualities.isEmpty()) "" else "; ${d.qualities.entries.joinToString { (q, v) -> "$q ${v.toInt()}" }}") +
            (d.place?.let { "; on the $it" } ?: "") + looks(d.look.map { it.part }, d.variants.keys) + (d.why.takeIf { it.isNotBlank() }?.let { ". Why: $it" } ?: "")

        /** "; parts: head, base; variants: spraying", for world.motion and world.show. */
        private fun looks(i: Invention) = looks(i.look.map { it.part }, i.variants.keys)

        private fun looks(parts: List<String>, variants: Collection<String>): String {
            val named = parts.filter { it.isNotBlank() }.distinct()
            return (if (named.isEmpty()) "" else "; parts: ${named.joinToString()}") + (if (variants.isEmpty()) "" else "; variants: ${variants.joinToString()}")
        }
    }
}

/**
 * `write: { with: verb_code, timing: before }`: the [Coder] writes the code before he sits down at
 * his desk, and it's staged for the sim's `define` ([staged]). Kept (the verb succeeded), it goes into
 * the event stream in his words. [onCoded] gets every draft as it's written.
 */
class CodeHandler(
    private val coder: Coder,
    private val store: MemoryStore,
    private val run: String,
    private val onCoded: (Coded) -> Unit = {},
) : AuthorHandler<Coded> {
    override val name = "verb_code"
    override val writer: String get() = coder.id

    override suspend fun author(call: AuthorCall) = coder.write(call).also(onCoded)

    override fun staged(result: Coded) = AuthoredResult.Coded(result.design, failed = result.error)

    override fun keep(result: Coded, call: AuthorCall, ended: Observation, view: AuthoredView?, deliver: (AuthoredResult) -> Unit) {
        val design = result.design ?: return
        val text = "I wrote code: " + summary(design) + (design.why.trim().trimEnd('.').takeIf { it.isNotEmpty() }?.let { ". $it." } ?: ".")
        if ("events" in call.op.into) store.appendEvent(run, result.tick, result.day, result.time, EventKind.Invention, text)
        deliver(AuthoredResult.Written(text))
    }

    companion object {
        /** "a verb, water the plant (waters it), and what the fern does by itself (grows)". */
        fun summary(d: CodeDesign): String = (
            d.verbs.map { "a verb, ${it.called.trim()} (${it.says.trim()})" } +
                d.behaviors.map { "what ${it.thing} does by itself (${it.says.trim()})" }
            ).joinToString(", ")
    }
}
