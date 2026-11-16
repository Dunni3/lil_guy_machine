package dev.lilguy.sim

import org.mozilla.javascript.Callable
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.EcmaError
import org.mozilla.javascript.EvaluatorException
import org.mozilla.javascript.Function
import org.mozilla.javascript.JavaScriptException
import org.mozilla.javascript.LambdaFunction
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Script
import org.mozilla.javascript.ScriptRuntime
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import org.mozilla.javascript.WrappedException

/**
 * The world as the code he writes sees it: what each verb of his own can look at and change. Every
 * function of the JavaScript `world` object ([ScriptApi.FUNCTIONS]) calls one of these. A bad
 * argument throws [ScriptProblem], which the script sees as a JavaScript `Error` it may catch.
 * What his code does is what happens, but it makes nothing from nothing: money comes from selling
 * things, things from machines fed with inputs, and machines wear with use
 * (`docs/decisions/2026-11-15-machines-are-fed-and-wear-out-instead-of-uncapped.md`).
 */
interface ScriptWorld {
    /** `{ day, time: "14:05", hour, minute, night }`. */
    fun time(): Map<String, Any?>
    fun needs(): Map<String, Double>
    fun money(): Int
    fun skills(): Map<String, Double>
    /** Item id to count, for everything he owns. */
    fun owns(): Map<String, Int>
    /** What one thing is: its name, group, uses, qualities, spot, colour, state and how many he has; null for no such thing. */
    fun thing(id: String): Map<String, Any?>?
    /** Every kind of thing there is (owned or not), id to name: what `give` and `make` can use. */
    fun catalog(): Map<String, String>
    /** `{ free: { floor: [...], wall: [...] }, taken: { spot: id } }`: where things stand and where they could. */
    fun spots(): Map<String, Any?>
    /** The place he's in, by name. */
    fun here(): String?
    /** The state stored on [id] (a thing he owns, or `self`). */
    fun state(id: String): Map<String, Any?>

    fun need(name: String, delta: Double): Double
    fun spend(amount: Int, why: String): Boolean
    fun practice(skill: String, amount: Double): Double
    fun take(id: String, n: Int): Int
    /** Builds a new kind of thing from [design] (the invention fields) and gives him one; its id. */
    fun make(design: Map<String, Any?>): String
    fun setState(id: String, key: String, value: Any?)
    fun move(id: String, spot: String): String
    fun recolor(id: String, color: String)
    fun rename(id: String, name: String)

    /** Logs [value] under [key] on a thing he owns ([Records]); how many readings that key keeps now. */
    fun record(id: String, key: String, value: Double): Int = throw ScriptProblem("nothing is logged here")
    /** What [id] has logged under [key], oldest first: `[{ time: "d3 04:00", value }]`. */
    fun readings(id: String, key: String): List<Map<String, Any?>> = emptyList()
    /** The weather where [id] stands now: `{ sky, temperature, rain, pressure, light, outdoors }`. */
    fun conditions(id: String): Map<String, Any?> = throw ScriptProblem("there's no weather here")

    // Where things are, and what reaches what ([Reach]).
    /** The id of the place thing [id] stands in (or `self`: the one he's in); null for a thing that stands nowhere. */
    fun place(id: String): String?
    /** `{ you: tiles or null, things: [{ id, name, tiles }] }`: what's within [tiles] tiles' walk of [id]. */
    fun near(id: String, tiles: Int): Map<String, Any?>
    /** `{ x, y, place, name }`: his tile and the place he's in. */
    fun where(): Map<String, Any?>
    /** The patches he knows of (in [place], or all): `[{ id, name, place, yields, bare, until, hours }]`. */
    fun patches(place: String?): List<Map<String, Any?>>
    /** What stands in [place]: `[{ id, name, group, mine }]`. */
    fun things(place: String): List<Map<String, Any?>>
    /** Brings a bare [patch] [hours] closer to growing back; the hours left after (0 when it's grown). */
    fun regrow(patch: String, hours: Double): Double

    // How his things show what they're doing ([Machines]).
    fun show(id: String, variant: String?)
    fun motion(id: String, part: String, kind: String?, speed: Double)
    fun readout(id: String, text: String?)
    /** In [minutes] game minutes, thing [id]'s `onTimer(world, thing, key)` runs. */
    fun after(minutes: Int, key: String, id: String?)

    // Machines: what they take and make, and moving things between them ([Works]).
    /** What machine [id] runs, holds and has made, its wear, and what it's doing. */
    fun machine(id: String): Map<String, Any?> = throw ScriptProblem("there are no machines here")
    /** Moves [n] of [item] from [from] to [to] (a machine of his, or `self`); how many moved. */
    fun transfer(from: String, to: String, item: String, n: Int): Int = throw ScriptProblem("nothing moves here")
    /** Sells [n] of something of his that sells; the dollars it fetched. */
    fun sell(id: String, n: Int): Int = throw ScriptProblem("nothing sells here")
    /** What one [id] sells for now and usually: { now, usual }; null when nobody buys it. */
    fun price(id: String): Map<String, Any?>? = throw ScriptProblem("nothing sells here")
    // Effects with reach ([Surroundings]).
    /** [kind] (light, warmth, sound, water, or a visitor's lure) at [spot]: a spot, a thing of his, or `self`. */
    fun field(spot: String, kind: String): Double = throw ScriptProblem("there are no effects here")
    /** Has thing [id] give off [kind] ([strength] on its tile, reaching [radius]) until changed; 0 stops it. Returns the strength. */
    fun emit(id: String, kind: String, strength: Double, radius: Double): Double = throw ScriptProblem("nothing gives anything off here")
}

/** A bad call from his code, in words he can act on; his script sees it as an `Error`. */
class ScriptProblem(message: String) : RuntimeException(message)

/**
 * What one run of a verb's code came to: [error] when it broke (it may have changed things before
 * it did), [says] what it said ([ScriptApi] `say` and `console.log`), and [result] the text
 * `finish` returned.
 */
data class ScriptRun(val result: String?, val says: List<String>, val error: String? = null, val steps: Long = 0)

/**
 * The `world` API: each function with its signature and what it does. The coder's brief and the
 * oracle's docs are made from [FUNCTIONS], so what he's told is what there is.
 */
object ScriptApi {
    class Fn(val name: String, val signature: String, val doc: String, val writes: Boolean, val run: (ScriptWorld, List<Any?>) -> Any?)

    val FUNCTIONS: List<Fn> = listOf(
        Fn("time", "world.time()", "the clock: { day, time: \"14:05\", hour, minute, night }", false) { w, _ -> w.time() },
        Fn("needs", "world.needs()", "your needs, 0 to 100: { energy, fullness, mood, stimulation, frustration }", false) { w, _ -> w.needs() },
        Fn("money", "world.money()", "your money, in dollars", false) { w, _ -> w.money() },
        Fn("skills", "world.skills()", "your skills, name to level", false) { w, _ -> w.skills() },
        Fn("owns", "world.owns()", "everything you own, id to count", false) { w, _ -> w.owns() },
        Fn("thing", "world.thing(id)", "one thing: { id, name, group, uses, qualities, spot, color, state, count }, or null", false) { w, a -> w.thing(a.str(0, "id")) },
        Fn("catalog", "world.catalog()", "every kind of thing in your world, id to name (owned or not)", false) { w, _ -> w.catalog() },
        Fn("spots", "world.spots()", "{ free: { floor: [spot, ...], wall: [spot, ...] }, taken: { spot: id } }: where your things stand and the empty spots, indoors and out on the land", false) { w, _ -> w.spots() },
        Fn("here", "world.here()", "the name of the place you're in", false) { w, _ -> w.here() },
        Fn("state", "world.state(id)", "what's stored on a thing you own, or on yourself with \"self\": an object of keys", false) { w, a -> w.state(a.str(0, "id")) },
        Fn("need", "world.need(name, delta)", "changes a need by delta (it stays 0 to 100); returns its new level", true) { w, a -> w.need(a.str(0, "name"), a.num(1, "delta")) },
        Fn("spend", "world.spend(dollars, why)", "takes money if you have it; returns true, or false and takes nothing", true) { w, a -> w.spend(a.int(0, "dollars"), a.optStr(1) ?: "") },
        Fn("practice", "world.practice(skill, amount)", "raises a skill; returns its new level", true) { w, a -> w.practice(a.str(0, "skill"), a.num(1, "amount")) },
        Fn("take", "world.take(id, n)", "uses up n of something you own (an id, or a use like \"edible\"); returns how many it took", true) { w, a -> w.take(a.str(0, "id"), a.optInt(1) ?: 1) },
        Fn(
            "make", "world.make({ name, use, qualities, place, look, book, why })",
            "makes a new kind of thing and gives you one; returns its id. use is one of viewable, playable, readable, edible, sellable; qualities like { cozy: 2 }; place is floor or wall for a viewable thing; look is a list of shapes, each like { shape: \"box\", w: 0.3, h: 0.2, d: 0.3, x: 0, y: 0, z: 0, color: \"#8a5a3c\", material: \"wood\" } (shape is box, cylinder, sphere or capsule; metres, y up from the floor; material is wood, metal, copper_wire or glass), or leave it out for a plain one; book, for a readable thing, is { title, blurb, chapters: [a sentence each], skill }. It takes the materials from what you own, as the workbench does, and money for food or a book",
            true,
        ) { w, a -> w.make(a.obj(0, "design")) },
        Fn("setState", "world.setState(id, key, value)", "stores a value (a string, number, true/false, or null to remove it) on a thing you own, or on \"self\"; it stays until changed", true) { w, a -> w.setState(a.str(0, "id"), a.str(1, "key"), a.getOrNull(2)) },
        Fn("move", "world.move(id, spot)", "moves a thing of yours to another spot of the same kind (floor or wall); if another of your things stands there they swap; returns where it went", true) { w, a -> w.move(a.str(0, "id"), a.str(1, "spot")) },
        Fn("recolor", "world.recolor(id, color)", "paints a thing of yours a colour, hex like \"#7aa3c9\"; null or \"\" puts its own colours back", true) { w, a -> w.recolor(a.str(0, "id"), a.optStr(1) ?: "") },
        Fn("rename", "world.rename(id, name)", "gives a thing of yours a new name", true) { w, a -> w.rename(a.str(0, "id"), a.str(1, "name")) },
        Fn("record", "world.record(id, key, value)", "logs a number under key on a thing of yours; each key keeps its latest readings (two days of hours); returns how many it keeps", true) { w, a -> w.record(a.str(0, "id"), a.str(1, "key"), a.num(2, "value")) },
        Fn("readings", "world.readings(id, key)", "what a thing of yours has logged under key, oldest first: [{ time: \"d3 04:00\", value }]; a weather station logs temperature, rain, pressure and light", false) { w, a -> w.readings(a.str(0, "id"), a.str(1, "key")) },
        Fn("conditions", "world.conditions(id)", "the weather where a thing of yours stands now: { sky, temperature, rain (mm in the last hour), pressure (hPa), light (0 to 100), outdoors }", false) { w, a -> w.conditions(a.str(0, "id")) },

        // Where things are, and what reaches what.
        Fn("place", "world.place(id)", "the id of the place a thing stands in (\"self\" for yours), like \"garden\"; null for a thing that stands nowhere", false) { w, a -> w.place(a.str(0, "id")) },
        Fn("near", "world.near(id, tiles)", "what's within that many tiles' walk of a thing (1 to ${Reach.MOST_TILES}): { you: tiles away or null, things: [{ id, name, tiles }] }", false) { w, a -> w.near(a.str(0, "id"), a.int(1, "tiles")) },
        Fn("where", "world.where()", "where you are: { x, y, place, name }", false) { w, _ -> w.where() },
        Fn("patches", "world.patches(place)", "the patches you know of where things grow, in a place or (no argument) all: [{ id, name, place, yields, bare, until, hours }]; hours is how long until a bare one grows back", false) { w, a -> w.patches(a.optStr(0)) },
        Fn("things", "world.things(place)", "what stands in a place: [{ id, name, group, mine }]", false) { w, a -> w.things(a.str(0, "place")) },
        Fn("regrow", "world.regrow(patch, hours)", "brings a bare patch that many hours closer to growing back; the code's thing (or you, in a verb) must be in the patch's place; returns the hours left", true) { w, a -> w.regrow(a.str(0, "patch"), a.num(1, "hours")) },
        // How his things show what they're doing.
        Fn("show", "world.show(id, variant)", "shows one of the variants your invention was designed with; null for its own look", true) { w, a -> w.show(a.str(0, "id"), a.optStr(1)) },
        Fn("motion", "world.motion(id, part, kind, speed)", "moves a named part of your invention: kind is ${Machines.MOTIONS.joinToString()}; speed 0.1 to ${Machines.MAX_SPEED.toInt()} (1 is gentle); null kind or speed 0 stops it", true) { w, a -> w.motion(a.str(0, "id"), a.str(1, "part"), a.optStr(2), a.optNum(3) ?: 1.0) },
        Fn("readout", "world.readout(id, text)", "a short label (up to ${Machines.READOUT} characters) floating over a thing of yours, like \"62%\" or \"watering\"; null clears it", true) { w, a -> w.readout(a.str(0, "id"), a.optStr(1)) },
        Fn("field", "world.field(spot, kind)", "how much of an effect there is at a spot (a spot name, a thing of yours, or \"self\"): kind is light (0 to 10), warmth (degrees), sound (0 to 10), water (rain and spray, 0 to 10), or a visitor like birds (what draws them there)", false) { w, a -> w.field(a.str(0, "spot"), a.str(1, "kind")) },
        Fn("emit", "world.emit(id, kind, strength, radius)", "has a thing of yours give off light, warmth (degrees), sound or water: strength on its own tile, fading to nothing a tile past radius (0 to ${Surroundings.MOST_RADIUS.toInt()}); it lasts until you change it, and strength 0 stops it. What it gives off is real: light lets you see and work, warmth keeps off the cold, sound is heard (and keeps you awake), water waters a planter", true) { w, a -> w.emit(a.str(0, "id"), a.str(1, "kind"), a.num(2, "strength"), a.num(3, "radius")) },
        Fn("after", "world.after(minutes, key, id)", "in that many game minutes (1 to ${Machines.MOST_TIMER}), runs onTimer(world, thing, key) of a thing of yours (id; this thing when left out); the same key again resets it", true) { w, a -> w.after(a.int(0, "minutes"), a.str(1, "key"), a.optStr(2)) },
        // Machines: what they take and make.
        Fn("machine", "world.machine(id)", "a machine of yours: { runs: [{ id, takes, makes, hours, needs }], holds, in, out, wear (0 to 100; it slows past ${MachineRules().slowAt.toInt()} and jams at 100), broken, making, done (%), stuck }", false) { w, a -> w.machine(a.str(0, "id")) },
        Fn("transfer", "world.transfer(from, to, item, n)", "moves n of a thing between a machine and you (\"self\") or two machines within a few tiles of each other: out of a machine comes what it made, into one only what it takes; returns how many moved", true) { w, a -> w.transfer(a.str(0, "from"), a.str(1, "to"), a.str(2, "item"), a.optInt(3) ?: 1) },
        Fn("sell", "world.sell(id, n)", "sells n of something you own that sells (samples, not art: that's sold at the mail hatch); returns the dollars", true) { w, a -> w.sell(a.str(0, "id"), a.optInt(1) ?: 1) },
        Fn("price", "world.price(id)", "what one of something sells for: { now, usual }, or null if nobody buys it; a ware fetches less after you sell a lot of it, and recovers over days", false) { w, a -> w.price(a.str(0, "id")) },
    )

    /** Functions there used to be, and where what they did comes from now: calling one throws this. */
    val RETIRED: Map<String, String> = mapOf(
        "earn" to "money doesn't come from nothing: sell something with world.sell(id, n), or make things that sell",
        "give" to "things don't come from nothing: a machine makes them from what it takes (world.machine), and world.transfer(machine, \"self\", item) takes them out; or collect or buy them",
    )

    /** The API as lines for a prompt: one per function, plus `say` and the verb's arguments. */
    fun reference(): String = (
        FUNCTIONS.map { "- ${it.signature}: ${it.doc}" } + listOf(
            "- world.say(text): says something out loud as the verb finishes; console.log does the same",
            "- world.args: the things this doing of the verb is bound to, { what, on } (ids), when the verb takes them",
            "- world.verb: this verb's id",
        )
        ).joinToString("\n")

    private fun List<Any?>.str(i: Int, name: String): String = (getOrNull(i) as? String) ?: throw ScriptProblem("argument ${i + 1} ($name) must be a string")
    private fun List<Any?>.optStr(i: Int): String? = getOrNull(i)?.let { it as? String ?: it.toString() }
    private fun List<Any?>.num(i: Int, name: String): Double = (getOrNull(i) as? Double)?.takeIf { !it.isNaN() } ?: throw ScriptProblem("argument ${i + 1} ($name) must be a number")
    private fun List<Any?>.int(i: Int, name: String): Int = num(i, name).let { if (it != Math.floor(it)) throw ScriptProblem("argument ${i + 1} ($name) must be a whole number") else it.toInt() }
    private fun List<Any?>.optInt(i: Int): Int? = getOrNull(i)?.let { int(i, "n") }
    private fun List<Any?>.optNum(i: Int): Double? = getOrNull(i)?.let { num(i, "speed") }

    @Suppress("UNCHECKED_CAST")
    private fun List<Any?>.obj(i: Int, name: String): Map<String, Any?> = getOrNull(i) as? Map<String, Any?> ?: throw ScriptProblem("argument ${i + 1} ($name) must be an object")
}

/**
 * Runs the JavaScript he writes, sealed off from everything but [ScriptWorld]: no Java, no files,
 * no network, no clock but the game's, and at most [steps] interpreted instructions a call, so a
 * loop that never ends just breaks the verb. Rhino in interpreted mode, which runs on Android too.
 * Not thread-safe: the sim thread runs it.
 */
class ScriptSandbox(private val steps: Int = STEPS) {
    private class OutOfSteps(limit: Long) : Error("it ran too long (more than $limit steps); a loop that never ends?")

    private class Counter(val limit: Long, var n: Long = 0)

    private val factory = object : ContextFactory() {
        override fun makeContext(): Context = super.makeContext().apply {
            optimizationLevel = -1
            languageVersion = Context.VERSION_ES6
            instructionObserverThreshold = OBSERVE_EVERY
            setClassShutter(ClassShutter { false })
            maximumInterpreterStackDepth = 200
        }

        override fun observeInstructionCount(cx: Context, instructionCount: Int) {
            val c = cx.getThreadLocal(COUNTER) as? Counter ?: return
            c.n += instructionCount
            if (c.n > c.limit) throw OutOfSteps(c.limit)
        }
    }

    private var shared: ScriptableObject? = null
    private val compiled = object : LinkedHashMap<String, Script>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Script>?) = size > CACHE
    }

    /** What's wrong with [source] as code (it doesn't parse, or has no function [needs]), or null. */
    fun check(source: String, needs: String = FINISH): String? = when (val f = functions(source, listOf(needs))) {
        is Defined.Broken -> f.problem
        is Defined.Has -> if (needs !in f.names) "it needs a function $needs(world${if (needs == HOURLY) ", thing" else ""})" else null
    }

    /** Which of [names] [source] defines as top-level functions, or why it doesn't run. */
    fun functions(source: String, names: Collection<String>): Defined = factory.call { cx ->
        try {
            val scope = scope(cx)
            cx.putThreadLocal(COUNTER, Counter(steps.toLong()))
            compile(cx, source).exec(cx, scope)
            Defined.Has(names.filter { scope.get(it, scope) is Function }.toSet())
        } catch (e: OutOfSteps) {
            Defined.Broken("its top level " + e.message)
        } catch (e: RhinoException) {
            Defined.Broken(describe(e))
        } catch (e: WrappedException) {
            Defined.Broken(e.wrappedException.message ?: e.wrappedException.toString())
        }
    }

    sealed interface Defined {
        data class Has(val names: Set<String>) : Defined
        data class Broken(val problem: String) : Defined
    }

    /**
     * Calls [fn] (`finish` or `canDo`, or a thing's trigger) of [source] with [world] as its `world`
     * and [extra] after it. [readOnly]: a function that changes anything breaks (for `canDo`). [args]
     * and [verb] are `world.args` and `world.verb`. [limit]: the most steps it may take, at most
     * [steps]. A missing [fn] returns an empty run. [ScriptRun.steps] is about how many it took.
     */
    fun call(source: String, fn: String, world: ScriptWorld, args: Map<String, String>, verb: String, readOnly: Boolean = false, extra: List<Any?> = emptyList(), limit: Long = steps.toLong()): ScriptRun {
        val says = mutableListOf<String>()
        val counter = Counter(minOf(limit, steps.toLong()))
        return factory.call { cx ->
            try {
                cx.putThreadLocal(COUNTER, counter)
                val scope = scope(cx)
                compile(cx, source).exec(cx, scope)
                val f = scope.get(fn, scope) as? Function ?: return@call ScriptRun(null, emptyList())
                val api = api(cx, scope, world, args, verb, readOnly, says)
                ScriptableObject.putProperty(scope, "world", api)
                val console = cx.newObject(scope)
                ScriptableObject.putProperty(console, "log", lambda(scope, "log") { a -> say(says, a.joinToString(" ") { it?.let(::text) ?: "null" }) })
                ScriptableObject.putProperty(scope, "console", console)
                val out = f.call(cx, scope, scope, (listOf<Any?>(api) + extra.map { toJs(cx, scope, it) }).toTypedArray())
                ScriptRun(out.takeIf { it != null && it !is Undefined }?.let { Context.toString(it) }?.take(TEXT), says.toList(), steps = counter.n)
            } catch (e: OutOfSteps) {
                ScriptRun(null, says.toList(), "$fn " + e.message, counter.n)
            } catch (e: RhinoException) {
                ScriptRun(null, says.toList(), describe(e), counter.n)
            } catch (e: WrappedException) {
                ScriptRun(null, says.toList(), (e.wrappedException as? ScriptProblem)?.message ?: e.wrappedException.toString(), counter.n)
            } catch (e: StackOverflowError) {
                ScriptRun(null, says.toList(), "$fn recursed too deep", counter.n)
            }
        }
    }

    private fun compile(cx: Context, source: String): Script = compiled.getOrPut(source) { cx.compileString(source, "your code", 1, null) }

    /** A fresh top-level scope for one call, over the shared, sealed standard objects. */
    private fun scope(cx: Context): Scriptable {
        val std = shared ?: cx.initSafeStandardObjects().also { s ->
            // His code knows the time from world.time(): no wall clock, so a run plays back the same.
            s.delete("Date")
            s.sealObject()
            shared = s
        }
        return cx.newObject(std).apply {
            prototype = std
            parentScope = null
        }
    }

    private fun api(cx: Context, scope: Scriptable, world: ScriptWorld, args: Map<String, String>, verb: String, readOnly: Boolean, says: MutableList<String>): Scriptable {
        val api = cx.newObject(scope)
        ScriptApi.FUNCTIONS.forEach { fn ->
            ScriptableObject.putProperty(api, fn.name, lambda(scope, fn.name) { a ->
                if (readOnly && fn.writes) throw ScriptProblem("canDo can only look; world.${fn.name} changes things, so call it from finish")
                fn.run(world, a)
            })
        }
        ScriptApi.RETIRED.forEach { (name, why) -> ScriptableObject.putProperty(api, name, lambda(scope, name) { throw ScriptProblem(why) }) }
        ScriptableObject.putProperty(api, "say", lambda(scope, "say") { a ->
            if (readOnly) throw ScriptProblem("canDo can only look; say it from finish")
            say(says, a.firstOrNull()?.let(::text) ?: "")
        })
        ScriptableObject.putProperty(api, "args", toJs(cx, scope, args))
        ScriptableObject.putProperty(api, "verb", verb)
        return api
    }

    private fun say(says: MutableList<String>, text: String) {
        if (says.size < SAYS && text.isNotBlank()) says += text.trim().take(TEXT)
    }

    /** A JavaScript function that calls [body] with its arguments as Kotlin values; a [ScriptProblem] becomes a JavaScript Error. */
    private fun lambda(scope: Scriptable, name: String, body: (List<Any?>) -> Any?): LambdaFunction =
        LambdaFunction(scope, name, 0, Callable { cx, s, _, a ->
            val out = try {
                body(a.map(::fromJs))
            } catch (e: ScriptProblem) {
                throw ScriptRuntime.constructError("Error", "world.$name: ${e.message}")
            }
            toJs(cx, s, out)
        })

    companion object {
        /** Instructions a call may run. */
        const val STEPS = 2_000_000
        private const val OBSERVE_EVERY = 10_000
        private const val CACHE = 64
        private const val SAYS = 5
        private const val TEXT = 300
        private val COUNTER = Any()
        const val FINISH = "finish"
        const val CAN_DO = "canDo"
        const val HOURLY = "hourly"

        private fun describe(e: RhinoException): String {
            val what = when (e) {
                is EcmaError -> "${e.name}: ${e.errorMessage}"
                is JavaScriptException -> e.details()
                is EvaluatorException -> "syntax error: ${e.details()}"
                else -> e.details()
            }
            return what + (e.lineNumber().takeIf { it > 0 }?.let { " (line $it)" } ?: "") + (e.lineSource()?.trim()?.takeIf { it.isNotEmpty() }?.let { ": $it" } ?: "")
        }

        private fun text(v: Any?): String = when (v) {
            is Double -> if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
            is Map<*, *>, is List<*> -> v.toString()
            else -> v.toString()
        }

        /** A JavaScript value as a plain Kotlin one: string, Double, Boolean, List, Map, or null. */
        fun fromJs(v: Any?): Any? = when (v) {
            null, is Undefined -> null
            is CharSequence -> v.toString()
            is Boolean -> v
            is Number -> v.toDouble()
            is NativeArray -> (0 until v.length).map { fromJs(v.get(it.toInt(), v)) }
            is Function -> null
            is Scriptable -> v.ids.associate { id ->
                val value = if (id is Number) v.get(id.toInt(), v) else v.get(id.toString(), v)
                id.toString() to fromJs(value)
            }
            else -> v.toString()
        }

        /** A Kotlin value as a JavaScript one. */
        fun toJs(cx: Context, scope: Scriptable, v: Any?): Any? = when (v) {
            null -> null
            Unit -> Undefined.instance
            is String, is Boolean -> v
            is Int -> v.toDouble()
            is Long -> v.toDouble()
            is Number -> v.toDouble()
            is Map<*, *> -> cx.newObject(scope).also { o -> v.forEach { (k, x) -> ScriptableObject.putProperty(o, k.toString(), toJs(cx, scope, x)) } }
            is Collection<*> -> cx.newArray(scope, v.map { toJs(cx, scope, it) }.toTypedArray())
            is kotlinx.serialization.json.JsonPrimitive -> when {
                v is kotlinx.serialization.json.JsonNull -> null
                v.isString -> v.content
                v.content == "true" || v.content == "false" -> v.content == "true"
                else -> v.content.toDoubleOrNull()
            }
            else -> v.toString()
        }
    }
}
