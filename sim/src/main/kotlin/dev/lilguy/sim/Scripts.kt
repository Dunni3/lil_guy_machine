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
 * Nothing here is capped: what his code does is what happens (Ian's call, 2026-11-15).
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
    fun earn(amount: Int, why: String): Int
    fun spend(amount: Int, why: String): Boolean
    fun practice(skill: String, amount: Double): Double
    fun give(id: String, n: Int): Int
    fun take(id: String, n: Int): Int
    /** Builds a new kind of thing from [design] (the invention fields) and gives him one; its id. */
    fun make(design: Map<String, Any?>): String
    fun setState(id: String, key: String, value: Any?)
    fun move(id: String, spot: String): String
    fun recolor(id: String, color: String)
    fun rename(id: String, name: String)
}

/** A bad call from his code, in words he can act on; his script sees it as an `Error`. */
class ScriptProblem(message: String) : RuntimeException(message)

/**
 * What one run of a verb's code came to: [error] when it broke (it may have changed things before
 * it did), [says] what it said ([ScriptApi] `say` and `console.log`), and [result] the text
 * `finish` returned.
 */
data class ScriptRun(val result: String?, val says: List<String>, val error: String? = null)

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
        Fn("spots", "world.spots()", "{ free: { floor: [spot, ...], wall: [spot, ...] }, taken: { spot: id } }: where your things stand and the empty spots", false) { w, _ -> w.spots() },
        Fn("here", "world.here()", "the name of the place you're in", false) { w, _ -> w.here() },
        Fn("state", "world.state(id)", "what's stored on a thing you own, or on yourself with \"self\": an object of keys", false) { w, a -> w.state(a.str(0, "id")) },
        Fn("need", "world.need(name, delta)", "changes a need by delta (it stays 0 to 100); returns its new level", true) { w, a -> w.need(a.str(0, "name"), a.num(1, "delta")) },
        Fn("earn", "world.earn(dollars, why)", "adds money; returns your money", true) { w, a -> w.earn(a.int(0, "dollars"), a.optStr(1) ?: "") },
        Fn("spend", "world.spend(dollars, why)", "takes money if you have it; returns true, or false and takes nothing", true) { w, a -> w.spend(a.int(0, "dollars"), a.optStr(1) ?: "") },
        Fn("practice", "world.practice(skill, amount)", "raises a skill; returns its new level", true) { w, a -> w.practice(a.str(0, "skill"), a.num(1, "amount")) },
        Fn("give", "world.give(id, n)", "gives you n more of a kind of thing that exists (an id from world.catalog()); returns how many you have", true) { w, a -> w.give(a.str(0, "id"), a.optInt(1) ?: 1) },
        Fn("take", "world.take(id, n)", "uses up n of something you own (an id, or a use like \"edible\"); returns how many it took", true) { w, a -> w.take(a.str(0, "id"), a.optInt(1) ?: 1) },
        Fn(
            "make", "world.make({ name, use, qualities, place, look, book, why })",
            "makes a new kind of thing and gives you one; returns its id. use is one of viewable, playable, readable, edible, sellable; qualities like { cozy: 2 }; place is floor or wall for a viewable thing; look and book as when you invent",
            true,
        ) { w, a -> w.make(a.obj(0, "design")) },
        Fn("setState", "world.setState(id, key, value)", "stores a value (a string, number, true/false, or null to remove it) on a thing you own, or on \"self\"; it stays until changed", true) { w, a -> w.setState(a.str(0, "id"), a.str(1, "key"), a.getOrNull(2)) },
        Fn("move", "world.move(id, spot)", "moves a thing of yours to another spot of the same kind (floor or wall); if another of your things stands there they swap; returns where it went", true) { w, a -> w.move(a.str(0, "id"), a.str(1, "spot")) },
        Fn("recolor", "world.recolor(id, color)", "paints a thing of yours a colour, hex like \"#7aa3c9\"; null or \"\" puts its own colours back", true) { w, a -> w.recolor(a.str(0, "id"), a.optStr(1) ?: "") },
        Fn("rename", "world.rename(id, name)", "gives a thing of yours a new name", true) { w, a -> w.rename(a.str(0, "id"), a.str(1, "name")) },
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
    private class OutOfSteps : Error("it ran too long (more than $STEPS steps); a loop that never ends?")

    private class Counter(var n: Long = 0)

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
            if (c.n > steps) throw OutOfSteps()
        }
    }

    private var shared: ScriptableObject? = null
    private val compiled = object : LinkedHashMap<String, Script>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Script>?) = size > CACHE
    }

    /** What's wrong with [source] as code (it doesn't parse, or has no function [needs]), or null. */
    fun check(source: String, needs: String = FINISH): String? = factory.call { cx ->
        try {
            val scope = scope(cx)
            cx.putThreadLocal(COUNTER, Counter())
            compile(cx, source).exec(cx, scope)
            if (scope.get(needs, scope) !is Function) "it needs a function $needs(world${if (needs == HOURLY) ", thing" else ""})" else null
        } catch (e: OutOfSteps) {
            "its top level " + e.message
        } catch (e: RhinoException) {
            describe(e)
        } catch (e: WrappedException) {
            e.wrappedException.message
        }
    }

    /**
     * Calls [fn] (`finish` or `canDo`) of [source] with [world] as its `world`. [readOnly]: a
     * function that changes anything breaks (for `canDo`). [args] and [verb] are `world.args` and
     * `world.verb`. A missing [fn] returns an empty run.
     */
    fun call(source: String, fn: String, world: ScriptWorld, args: Map<String, String>, verb: String, readOnly: Boolean = false, extra: Any? = null): ScriptRun {
        val says = mutableListOf<String>()
        return factory.call { cx ->
            try {
                cx.putThreadLocal(COUNTER, Counter())
                val scope = scope(cx)
                compile(cx, source).exec(cx, scope)
                val f = scope.get(fn, scope) as? Function ?: return@call ScriptRun(null, emptyList())
                val api = api(cx, scope, world, args, verb, readOnly, says)
                ScriptableObject.putProperty(scope, "world", api)
                val console = cx.newObject(scope)
                ScriptableObject.putProperty(console, "log", lambda(scope, "log") { a -> say(says, a.joinToString(" ") { it?.let(::text) ?: "null" }) })
                ScriptableObject.putProperty(scope, "console", console)
                val out = f.call(cx, scope, scope, if (extra == null) arrayOf(api) else arrayOf(api, toJs(cx, scope, extra)))
                ScriptRun(out.takeIf { it != null && it !is Undefined }?.let { Context.toString(it) }?.take(TEXT), says.toList())
            } catch (e: OutOfSteps) {
                ScriptRun(null, says.toList(), "$fn " + e.message)
            } catch (e: RhinoException) {
                ScriptRun(null, says.toList(), describe(e))
            } catch (e: WrappedException) {
                ScriptRun(null, says.toList(), (e.wrappedException as? ScriptProblem)?.message ?: e.wrappedException.toString())
            } catch (e: StackOverflowError) {
                ScriptRun(null, says.toList(), "$fn recursed too deep")
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
