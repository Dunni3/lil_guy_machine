package dev.lilguy.sim

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The functions a thing's code may have, each run by the sim while he owns the thing
 * (`docs/decisions/2026-11-15-his-machines-show-their-state-*`). Named top-level functions, so
 * [ScriptSandbox.functions] can say which a behavior has when it goes in.
 */
object Triggers {
    const val HOURLY = "hourly"
    const val EVERY = "every"
    const val ARRIVE = "onArrive"
    const val LEAVE = "onLeave"
    const val FINISH = "onFinish"
    const val SLEEP = "onSleep"
    const val WAKE = "onWake"
    const val TIMER = "onTimer"

    /** Each with its signature and when it runs, for the coder's brief. */
    val ALL: List<Pair<String, String>> = listOf(
        HOURLY to "function hourly(world, thing): at the top of every game hour",
        EVERY to "function every(world, thing): every `every_minutes` game minutes (set every_minutes on the behavior, ${MIN_EVERY} or more)",
        ARRIVE to "function onArrive(world, thing, place): when you walk into a place (its id)",
        LEAVE to "function onLeave(world, thing, place): when you walk out of one",
        FINISH to "function onFinish(world, thing, verb): after you finish doing anything (the verb's id)",
        SLEEP to "function onSleep(world, thing): when you go to sleep",
        WAKE to "function onWake(world, thing): when you wake up",
        TIMER to "function onTimer(world, thing, key): when a timer set with world.after(minutes, key) runs out",
    )

    val NAMES: List<String> = ALL.map { it.first }

    const val MIN_EVERY = 10
    const val MAX_EVERY = 24 * 60

    /** What's wrong with [source] as a behavior that runs every [every] minutes (null: no `every`), or null. */
    fun problem(source: String, every: Int?, sandbox: ScriptSandbox): String? {
        val has = when (val f = sandbox.functions(source, NAMES)) {
            is ScriptSandbox.Defined.Broken -> return f.problem
            is ScriptSandbox.Defined.Has -> f.names
        }
        return when {
            has.isEmpty() -> "it needs at least one of these functions: " + NAMES.joinToString { "$it(world, thing, ...)" }
            EVERY in has && every == null -> "it has every(world, thing), so it needs every_minutes ($MIN_EVERY to $MAX_EVERY)"
            every != null && every !in MIN_EVERY..MAX_EVERY -> "every_minutes must be from $MIN_EVERY to $MAX_EVERY"
            every != null && EVERY !in has -> "it sets every_minutes but has no function every(world, thing)"
            else -> null
        }
    }
}

/** One run of a thing's code: which function ([fn]) and what comes after `world, thing` ([extra]). */
sealed class Trigger(val fn: String, val extra: List<Any?> = emptyList()) {
    data object Hourly : Trigger(Triggers.HOURLY)
    data object Every : Trigger(Triggers.EVERY)
    data class Arrive(val place: String) : Trigger(Triggers.ARRIVE, listOf(place))
    data class Leave(val place: String) : Trigger(Triggers.LEAVE, listOf(place))
    data class Finish(val verb: String) : Trigger(Triggers.FINISH, listOf(verb))
    data object Sleep : Trigger(Triggers.SLEEP)
    data object Wake : Trigger(Triggers.WAKE)
    data class Timer(val thing: String, val key: String) : Trigger(Triggers.TIMER, listOf(key))
}

/** How one of his things looks now, set by its code: a [variant] of its design, parts in [motions], a [readout] over it. */
@Serializable
data class ThingLook(val variant: String? = null, val motions: List<Motion> = emptyList(), val readout: String? = null) {
    val isPlain: Boolean get() = variant == null && motions.isEmpty() && readout == null
}

/** A named [part] of an invention's look, moving as [kind] ([Machines.MOTIONS]) at [speed] (1 is gentle). */
@Serializable
data class Motion(val part: String, val kind: String, val speed: Double)

/** `world.after`: [thing]'s `onTimer` runs with [key] at tick [due]. */
@Serializable
data class MachineTimer(val thing: String, val key: String, val due: Long)

/** What a thing's code did in [place] while he was elsewhere: [text], [times] times, from [first] to [last] (ticks). */
@Serializable
data class HeldEvent(val place: String, val text: String, val first: Long, val last: Long, val times: Int = 1)

/** What [Machines] keeps with the world ([BedroomState.machines]). */
@Serializable
data class MachineState(
    val looks: Map<String, ThingLook> = emptyMap(),
    val timers: List<MachineTimer> = emptyList(),
    val held: List<HeldEvent> = emptyList(),
)

/** One run of a thing's code as the world did it: the [run], what it changed ([did]), and what was undone because it broke ([undone]). */
class MachineRun(val run: ScriptRun, val did: List<String>, val undone: List<String> = emptyList())

/** What [Machines] and [Reach] need of the world. [BedroomWorld] is the one host. */
interface MachineHost {
    val content: BedroomContent
    val layout: Layout
    val ticks: Long
    val behaviors: Map<String, ThingCode>
    fun setBehavior(id: String, code: ThingCode?)
    fun count(id: String): Int
    fun knows(id: String): Boolean
    /** The tile thing [id] stands on, or null when it stands nowhere. */
    fun cellOf(id: String): Cell?
    fun hisCell(): Cell
    /** The tick bare [patch] grows back by, or null when it isn't bare. */
    fun bareUntil(patch: String): Long?
    fun setBareUntil(patch: String, until: Long?)
    fun ticksIn(minutes: Double): Long
    /** Game minutes since the start of day 1. */
    fun minutes(): Long
    fun minuteStart(): Boolean
    fun stamp(tick: Long): String
    fun invention(id: String): Invention?
    /** Runs [fn] of [id]'s code with [extra] after `world, thing`, in at most [limit] steps. */
    fun run(id: String, code: ThingCode, fn: String, extra: List<Any?>, limit: Long): MachineRun
    fun event(text: String, place: String?)
    /** Something a viewer or his options see changed. */
    fun changed()
}

/**
 * What his things' code does over time: runs each behavior's triggers ([Trigger]) within a step
 * budget a tick ([TICK_STEPS]) on top of each call's own; keeps how his things look ([ThingLook]),
 * their timers, and what they did in places he wasn't ([HeldEvent]), which he hears of when he next
 * walks in. Call [afterTick] once a tick, after the tick's work, on the sim thread.
 */
class Machines(private val sandbox: ScriptSandbox) {
    private val looks = linkedMapOf<String, ThingLook>()
    private val timers = mutableListOf<MachineTimer>()
    private val held = mutableListOf<HeldEvent>()
    private val queue = ArrayDeque<Trigger>()
    private var lastPlace: String? = null
    private var placeKnown = false
    private val defined = object : LinkedHashMap<String, Set<String>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Set<String>>?) = size > 64
    }

    /** How [id] looks now; null for its plain look. */
    fun look(id: String): ThingLook? = looks[id]

    /** Runs later this tick, after the work: a verb finished, he fell asleep or woke. */
    fun queue(t: Trigger) {
        queue.addLast(t)
    }

    fun export() = MachineState(LinkedHashMap(looks), timers.toList(), held.toList())

    /** Puts [s] back; what the content or what he owns no longer has is left out. */
    fun restore(s: MachineState, keep: (String) -> Boolean) {
        looks.clear(); s.looks.forEach { (id, l) -> if (keep(id)) looks[id] = l }
        timers.clear(); timers += s.timers.filter { keep(it.thing) }
        held.clear(); held += s.held
        placeKnown = false
    }

    /** The trigger functions [source] has. */
    fun has(source: String): Set<String> = defined.getOrPut(source) {
        (sandbox.functions(source, Triggers.NAMES) as? ScriptSandbox.Defined.Has)?.names ?: emptySet()
    }

    /** Once a tick: his walking into and out of places, then this minute's hour, `every` and timers, then what's queued. */
    fun afterTick(h: MachineHost) {
        val here = h.layout.placeOf(h.hisCell())?.id
        if (!placeKnown) {
            lastPlace = here
            placeKnown = true
        } else if (here != lastPlace) {
            lastPlace?.let { queue.addLast(Trigger.Leave(it)) }
            here?.let {
                arrived(h, it)
                queue.addLast(Trigger.Arrive(it))
            }
            lastPlace = here
        }
        val due = mutableListOf<Trigger>()
        if (h.minuteStart()) {
            val minutes = h.minutes()
            if (minutes % 60 == 0L) due += Trigger.Hourly
            due += Trigger.Every
            timers.filter { it.due <= h.ticks }.sortedBy { it.due }.forEach { t ->
                timers.remove(t)
                due += Trigger.Timer(t.thing, t.key)
            }
        }
        if (due.isEmpty() && queue.isEmpty()) return
        while (queue.isNotEmpty()) due += queue.removeFirst()
        if (h.behaviors.isEmpty()) return
        var left = TICK_STEPS
        val waited = linkedSetOf<String>()
        for (t in due) {
            for ((id, code) in h.behaviors) {
                if (!runs(h, id, code, t)) continue
                if (left < CALL_STEPS) {
                    waited += id
                    continue
                }
                left -= runOne(h, id, code, t, left)
            }
        }
        if (waited.isNotEmpty()) h.event("your things' code ran out of time this minute (more than $TICK_STEPS steps between them), so the " + waited.joinToString(" and ") { h.content.item(it)?.name ?: it } + " waited", null)
    }

    private fun runs(h: MachineHost, id: String, code: ThingCode, t: Trigger): Boolean {
        if (h.count(id) == 0 || h.content.item(id) == null) return false
        if (t is Trigger.Timer && t.thing != id) return false
        if (t is Trigger.Every && (code.every == null || h.minutes() % code.every != 0L)) return false
        return t.fn in has(code.source)
    }

    /** Runs [t] of [id]; the steps it cost. What it did is a world event in its place; three broken runs in a row stop it. */
    private fun runOne(h: MachineHost, id: String, code: ThingCode, t: Trigger, left: Long): Long {
        val name = h.content.item(id)!!.name
        val m = h.run(id, code, t.fn, t.extra, maxOf(CALL_STEPS, left - CALL_STEPS))
        val run = m.run
        val place = h.cellOf(id)?.let { h.layout.placeOf(it)?.id }
        if (run.error != null) {
            val failures = code.failures + 1
            val stop = failures >= BROKEN_RUNS
            h.setBehavior(id, if (stop) null else code.copy(failures = failures))
            if (stop) {
                looks.remove(id)
                timers.removeAll { it.thing == id }
                h.changed()
            }
            placed(h, "your $name's code broke in ${t.fn}: ${run.error}" + (if (m.undone.isEmpty()) "" else "; what it did before that is undone (${m.undone.joinToString(", ")})") + if (stop) "; after $BROKEN_RUNS times in a row it stopped, until you write it again" else "", place)
        } else {
            if (code.failures > 0) h.setBehavior(id, code.copy(failures = 0))
            val what = m.did + run.says.map { "\"$it\"" } + listOfNotNull(run.result?.takeIf { it.isNotBlank() })
            if (what.isNotEmpty()) placed(h, "your $name: " + what.joinToString(", "), place)
        }
        return run.steps + CALL_STEPS
    }

    /**
     * An event in [place]: told now when he's there (or the world has no places), else held until he
     * walks in. Repeats of the same text are counted, and a place keeps its latest [HELD].
     */
    private fun placed(h: MachineHost, text: String, place: String?) {
        val here = h.layout.placeOf(h.hisCell())?.id
        if (place == null || !h.layout.outdoors || place == here) return h.event(text, place)
        val i = held.indexOfLast { it.place == place && it.text == text }
        if (i >= 0) held[i] = held[i].copy(last = h.ticks, times = held[i].times + 1)
        else {
            held += HeldEvent(place, text, h.ticks, h.ticks)
            held.filter { it.place == place }.dropLast(HELD).forEach { held.remove(it) }
        }
    }

    /** He walked into [place]: what his things did there while he was out, as one event there. */
    private fun arrived(h: MachineHost, place: String) {
        val there = held.filter { it.place == place }
        if (there.isEmpty()) return
        held.removeAll { it.place == place }
        val lines = there.joinToString("; ") { e ->
            val time = h.stamp(e.first).substringAfter(' ')
            e.text + if (e.times == 1) " ($time)" else " (${e.times} times, $time to ${h.stamp(e.last).substringAfter(' ')})"
        }
        h.event("while you were out: $lines", place)
    }

    // --- what his code can set about how a thing looks -----------------------------------------

    /** An invention of his, for variants and motion: those are what a design is made with. */
    private fun invented(h: MachineHost, id: String): Invention {
        val spec = h.content.item(id) ?: throw ScriptProblem("there's no thing '$id'; world.owns() lists yours")
        if (h.count(id) == 0) throw ScriptProblem("you don't own a ${spec.name} ('$id')")
        return h.invention(id) ?: throw ScriptProblem("the ${spec.name} isn't one of your inventions; only things you invented have variants and moving parts")
    }

    private fun set(h: MachineHost, id: String, change: (ThingLook) -> ThingLook): Boolean {
        val was = looks[id] ?: ThingLook()
        val now = change(was)
        if (now == was) return false
        if (now.isPlain) looks.remove(id) else looks[id] = now
        h.changed()
        return true
    }

    fun show(h: MachineHost, id: String, variant: String?, did: MutableList<String>) {
        val made = invented(h, id)
        val v = variant?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
        if (v != null && v !in made.variants) {
            throw ScriptProblem("the ${made.item.name} has no variant '$v'" + if (made.variants.isEmpty()) "; it was designed without any" else "; it has ${made.variants.keys.joinToString()}")
        }
        if (set(h, id) { it.copy(variant = v) }) did += if (v == null) "back to its own look" else "showed $v"
    }

    fun motion(h: MachineHost, id: String, part: String, kind: String?, speed: Double) {
        val made = invented(h, id)
        val parts = made.look.map { it.part }.filter { it.isNotBlank() }.toSet()
        if (part !in parts) throw ScriptProblem("'$part' isn't a part of the ${made.item.name}" + if (parts.isEmpty()) "; its shapes have no part names" else "; its parts are ${parts.joinToString()}")
        val k = kind?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
        if (k == null || speed == 0.0) {
            set(h, id) { l -> l.copy(motions = l.motions.filter { it.part != part }) }
            return
        }
        if (k !in MOTIONS) throw ScriptProblem("'$k' isn't a motion; they are ${MOTIONS.joinToString()}")
        if (speed.isNaN() || speed < MIN_SPEED || speed > MAX_SPEED) throw ScriptProblem("speed is from $MIN_SPEED to ${MAX_SPEED.toInt()}, or 0 to stop")
        set(h, id) { l ->
            val others = l.motions.filter { it.part != part }
            if (others.size >= MAX_MOTIONS) throw ScriptProblem("at most $MAX_MOTIONS parts of a thing move at once")
            l.copy(motions = others + Motion(part, k, (speed * 10).roundToInt() / 10.0))
        }
    }

    fun readout(h: MachineHost, id: String, text: String?) {
        val spec = h.content.item(id) ?: throw ScriptProblem("there's no thing '$id'; world.owns() lists yours")
        if (h.count(id) == 0) throw ScriptProblem("you don't own a ${spec.name} ('$id')")
        if (spec.group == "fixture") throw ScriptProblem("the ${spec.name} is part of the house or the land, not a thing of yours")
        if (spec.slot == null) throw ScriptProblem("the ${spec.name} doesn't stand anywhere, so there's nowhere to show it")
        val t = text?.replace(Regex("\\s+"), " ")?.trim()?.take(READOUT)?.takeIf { it.isNotEmpty() }
        set(h, id) { it.copy(readout = t) }
    }

    fun after(h: MachineHost, minutes: Int, key: String, id: String?, self: String?) {
        val thing = id ?: self ?: throw ScriptProblem("say which thing's onTimer runs: world.after(minutes, key, id)")
        val spec = h.content.item(thing) ?: throw ScriptProblem("there's no thing '$thing'")
        if (h.count(thing) == 0) throw ScriptProblem("you don't own a ${spec.name} ('$thing')")
        val code = h.behaviors[thing]
        if (code == null || Triggers.TIMER !in has(code.source)) throw ScriptProblem("the ${spec.name}'s code has no function onTimer(world, thing, key)")
        if (minutes !in 1..MOST_TIMER) throw ScriptProblem("a timer is 1 to $MOST_TIMER minutes")
        if (key.isBlank() || key.length > 40) throw ScriptProblem("a timer's key is 1 to 40 characters")
        timers.removeAll { it.thing == thing && it.key == key }
        if (timers.count { it.thing == thing } >= MAX_TIMERS) throw ScriptProblem("a thing has at most $MAX_TIMERS timers going")
        timers += MachineTimer(thing, key, h.ticks + h.ticksIn(minutes.toDouble()))
    }

    companion object {
        val MOTIONS = listOf("spin", "bob", "sway", "pulse", "shake")
        const val MIN_SPEED = 0.1
        const val MAX_SPEED = 4.0
        const val MAX_MOTIONS = 4
        const val READOUT = 24
        const val MOST_TIMER = 48 * 60
        const val MAX_TIMERS = 8

        /** Steps all of his things' code may take in one tick, between them; each call may take at most [ScriptSandbox.STEPS]. */
        const val TICK_STEPS = 5_000_000L
        /** What a call costs on top of the steps counted: the sandbox counts in tens of thousands. */
        const val CALL_STEPS = 10_000L
        const val BROKEN_RUNS = 3
        /** Events a place keeps for when he's back. */
        const val HELD = 8
    }
}

/**
 * Where things are and what reaches what, for his code: which place a thing is in, what's near it by
 * walking, the patches and their regrowth. [self] is the thing whose code is running, null in a verb
 * (where it's him).
 */
class Reach(private val h: MachineHost) {
    private fun placeOf(c: Cell): Place? = h.layout.placeOf(c)

    private fun known(id: String) = h.content.item(id)?.takeIf { h.knows(id) } ?: throw ScriptProblem("there's no thing '$id' that you know of")

    /** Things that stand somewhere he'd see them: his own, and fixtures he knows. */
    private fun standing(): List<Pair<ItemSpec, Cell>> = h.content.items.mapNotNull { s ->
        if (s.slot == null || !h.knows(s.id) || (h.count(s.id) == 0 && s.group != "fixture")) return@mapNotNull null
        h.cellOf(s.id)?.let { s to it }
    }

    private fun placeId(p: String): Place = h.layout.place(p) ?: h.layout.places.firstOrNull { it.name.equals(p, ignoreCase = true) || it.name.removePrefix("the ").equals(p, ignoreCase = true) }
        ?: throw ScriptProblem("there's no place '$p'; they are ${h.layout.places.joinToString { it.id }}")

    fun place(id: String): String? {
        if (id == SELF) return placeOf(h.hisCell())?.id
        known(id)
        return h.cellOf(id)?.let { placeOf(it)?.id }
    }

    fun near(id: String, tiles: Int): Map<String, Any?> {
        if (tiles !in 1..MOST_TILES) throw ScriptProblem("tiles is 1 to $MOST_TILES")
        val from = if (id == SELF) h.hisCell() else h.cellOf(known(id).id) ?: throw ScriptProblem("the ${h.content.item(id)!!.name} doesn't stand anywhere")
        val dist = walk(from, tiles)
        fun reach(c: Cell): Int? = dist[c] ?: NEIGHBOURS.mapNotNull { (dx, dy) -> dist[Cell(c.x + dx, c.y + dy)]?.plus(1) }.minOrNull()?.takeIf { it <= tiles }
        val things = standing().filter { (s, _) -> s.id != id }.mapNotNull { (s, c) -> reach(c)?.let { mapOf("id" to s.id, "name" to s.name, "tiles" to it) } }
            .sortedBy { it["tiles"] as Int }
        return mapOf("you" to if (id == SELF) 0 else reach(h.hisCell()), "things" to things)
    }

    /** Walking tiles from [from] over the floor, as far as [most]. */
    private fun walk(from: Cell, most: Int): Map<Cell, Int> {
        val map = h.layout.map
        val dist = hashMapOf(from to 0)
        val frontier = ArrayDeque(listOf(from))
        while (frontier.isNotEmpty()) {
            val c = frontier.removeFirst()
            val d = dist.getValue(c)
            if (d >= most) continue
            for ((dx, dy) in NEIGHBOURS) {
                val n = Cell(c.x + dx, c.y + dy)
                if (n in dist || !map.isFloor(n.x, n.y)) continue
                dist[n] = d + 1
                frontier.addLast(n)
            }
        }
        return dist
    }

    fun where(): Map<String, Any?> {
        val c = h.hisCell()
        val p = placeOf(c)
        return mapOf("x" to c.x, "y" to c.y, "place" to p?.id, "name" to p?.name)
    }

    fun patches(place: String?): List<Map<String, Any?>> {
        val only = place?.let { placeId(it).id }
        return h.content.world?.finds.orEmpty().mapNotNull { f ->
            val s = h.content.item(f.patch)?.takeIf { h.knows(it.id) } ?: return@mapNotNull null
            val at = h.cellOf(s.id)?.let { placeOf(it)?.id }
            if (only != null && at != only) return@mapNotNull null
            val until = h.bareUntil(s.id)
            mapOf(
                "id" to s.id, "name" to s.name, "place" to at, "yields" to f.yields, "bare" to (until != null),
                "until" to until?.let(h::stamp), "hours" to (until?.let { hoursLeft(it) } ?: 0.0),
            )
        }
    }

    fun things(place: String): List<Map<String, Any?>> {
        val p = placeId(place)
        return standing().filter { (_, c) -> placeOf(c) == p }.map { (s, _) ->
            mapOf("id" to s.id, "name" to s.name, "group" to s.group, "mine" to (h.count(s.id) > 0 && s.group != "fixture"))
        }
    }

    fun regrow(patch: String, hours: Double, self: String?, did: MutableList<String>): Double {
        val find = h.content.world?.find(patch)
        val spec = h.content.item(patch)?.takeIf { find != null && h.knows(patch) } ?: throw ScriptProblem("'$patch' isn't a patch you know of; world.patches() lists them")
        if (hours.isNaN() || hours < 0.1 || hours > MOST_REGROW) throw ScriptProblem("hours is from 0.1 to $MOST_REGROW")
        val at = h.cellOf(patch)?.let(::placeOf)
        val from = self?.let { s -> h.cellOf(s)?.let(::placeOf) } ?: if (self == null) placeOf(h.hisCell()) else null
        if (at != null && from != at) {
            val who = self?.let { "your ${h.content.item(it)!!.name} is" } ?: "you're"
            throw ScriptProblem("the ${spec.name} is in ${at.name} and $who ${from?.name?.let { "in $it" } ?: "nowhere"}: it only reaches what's in its own place")
        }
        val until = h.bareUntil(patch) ?: return 0.0
        val now = until - h.ticksIn(hours * 60)
        if (now <= h.ticks) {
            h.setBareUntil(patch, null)
            did += "the ${spec.name} grew back"
            return 0.0
        }
        h.setBareUntil(patch, now)
        // Without when it's due, so a machine's repeats are counted rather than listed.
        did += "the ${spec.name} will grow back ${fmt(hours)} hours sooner"
        return hoursLeft(now)
    }

    private fun hoursLeft(until: Long): Double = ceil((until - h.ticks).toDouble() / h.ticksIn(60.0) * 10) / 10

    private fun fmt(v: Double) = if (abs(v - v.roundToInt()) < 1e-9) v.roundToInt().toString() else ((v * 10).roundToInt() / 10.0).toString()

    companion object {
        const val SELF = "self"
        const val MOST_TILES = 20
        const val MOST_REGROW = 48.0
        private val NEIGHBOURS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
    }
}
