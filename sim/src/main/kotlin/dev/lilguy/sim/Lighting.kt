package dev.lilguy.sim

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * What an item gives off as light (items.yaml `light`): [strength] on its own tile, fading to nothing a tile past [radius].
 * A lamp (the `lamp` affordance) is lit on its [timer], or rules.yaml's `light.timer` when it has none,
 * unless he has switched it on or off ([LampMode]). Anything else with a light always gives it.
 */
@Serializable
data class LightSpec(val strength: Double, val radius: Double, val timer: LampTimer? = null)

/** When a lamp on its timer is lit: from [on] until [off] ("HH:MM"), across midnight when [off] is earlier. */
@Serializable
data class LampTimer(val on: String, val off: String) {
    fun covers(minuteOfDay: Int): Boolean = NightRules(on, off).covers(minuteOfDay)

    /** "18:00 to 00:00". */
    val text: String get() = "$on to $off"

    fun problems(where: String): List<String> = listOfNotNull(
        listOf(on, off).firstOrNull { runCatching { GameClock.parseHhmm(it) }.isFailure }?.let { "$where: '$it' isn't HH:MM" },
        if (on == off) "$where: on and off can't be the same time" else null,
    )
}

/**
 * How a lamp is set: on its timer, or switched [On] or [Off], where it stays until he switches it
 * again (the `switch` op). A lamp he hasn't touched is on its timer.
 */
@Serializable
enum class LampMode(val id: String) {
    @SerialName("timer")
    Timer("timer"),

    @SerialName("on")
    On("on"),

    @SerialName("off")
    Off("off"),
    ;

    companion object {
        fun of(id: String): LampMode = entries.firstOrNull { it.id == id } ?: throw IllegalArgumentException("a lamp is switched to on, off or timer, not '$id'")
    }
}

/**
 * How light works (rules.yaml `light`). Levels run from 0 (black) to about 10 (noon outdoors).
 * [see]: the light a thing needs on its tile for him to make it out. [daylight]: the level outdoors by
 * time of day ("HH:MM" to level, straight lines between, wrapping at midnight). [indoors]: the share of
 * the daylight everywhere in a room, the sky's light coming in; a [window] adds its own share of the
 * daylight as a light of its radius, from the window's tile in the wall. [lantern]: the light he carries.
 */
@Serializable
data class LightRules(
    val see: Double = 1.0,
    val daylight: Map<String, Double>,
    val indoors: Double = 0.5,
    val window: WindowLight = WindowLight(0.8, 8.0),
    /** The lantern that goes with him, or null for none: enough to make out what's beside him, not to work by. */
    val lantern: LightSpec? = null,
    /** When a lamp is lit if it has no timer of its own and he hasn't switched it. */
    val timer: LampTimer = LampTimer("18:00", "00:00"),
) {
    @Serializable
    data class WindowLight(val share: Double, val radius: Double)

    private val keys: List<Pair<Int, Double>> by lazy { daylight.entries.map { GameClock.parseHhmm(it.key) to it.value }.sortedBy { it.first } }

    /** The daylight outdoors at [minuteOfDay], between the keys. */
    fun daylightAt(minuteOfDay: Int): Double {
        val k = keys
        if (k.isEmpty()) return 0.0
        val m = Math.floorMod(minuteOfDay, GameClock.MINUTES_PER_DAY)
        val after = k.indexOfFirst { it.first > m }
        val (t0, v0) = if (after <= 0) k.last().let { (t, v) -> (if (after == 0) t - GameClock.MINUTES_PER_DAY else t) to v } else k[after - 1]
        val (t1, v1) = if (after < 0) k.first().let { (t, v) -> t + GameClock.MINUTES_PER_DAY to v } else k[after]
        return if (t1 == t0) v0 else v0 + (v1 - v0) * (m - t0).toDouble() / (t1 - t0)
    }

    fun problems(): List<String> = buildList {
        if (daylight.isEmpty()) add("light.daylight needs at least one time")
        daylight.forEach { (t, v) ->
            runCatching { GameClock.parseHhmm(t) }.onFailure { add("light.daylight: '$t' isn't HH:MM") }
            if (v < 0) add("light.daylight $t can't be negative")
        }
        if (lantern != null && (lantern.strength <= 0 || lantern.radius < 0)) add("light.lantern needs a strength above 0 and a radius not negative")
        addAll(timer.problems("light.timer"))
        if (see <= 0 || indoors < 0 || window.share < 0 || window.radius < 0) add("light: see must be positive, and indoors and the window's share and radius not negative")
    }
}

/**
 * Comfort from what he can see (rules.yaml `comfort`): a thing counts in full within [near] tiles of
 * him, less and less out to [far], where it stops; and in full at [lit] light or more, less in dimmer
 * light, none below what he can see by ([LightRules.see]). Asleep, he counts what's round him in the
 * room he sleeps in, lit or not. [adapt]: he gets used to what he has.
 */
@Serializable
data class ComfortRules(val near: Double = 2.0, val far: Double = 6.0, val lit: Double = 4.0, val adapt: Adapt? = null) {
    fun problems(): List<String> = (if (near < 0 || far <= near || lit <= 0) listOf("comfort: near not negative, far above near, lit positive") else emptyList()) +
        (if (adapt != null && (adapt.days <= 0 || adapt.floor !in 0.0..1.0)) listOf("comfort: adapt needs positive days and a floor from 0 to 1") else emptyList())

    /** A thing's comfort falls from full when he gets it to [floor] of it over [days], and stays there. */
    @Serializable
    data class Adapt(val days: Double, val floor: Double) {
        fun share(ageDays: Double): Double = (1 - (1 - floor) * ageDays / days).coerceIn(floor, 1.0)
    }
}

/**
 * The light over the world for [BedroomWorld]: daylight on the land by game hour (dimmed by
 * the sky: what the weather lets through), some of it inside through the rooms' windows, and every
 * placed thing with a `light`. Kept in an [Effects] field, recomputed only when the daylight's level
 * (to a tenth), the layout or the lights change.
 */
class Lighting(private val effects: Effects = Effects()) {
    /** How many times the field has been worked out. */
    val computed: Int get() = effects.computed

    /** The daylight outdoors at [minuteOfDay], to a tenth, with [sky] of it getting through (1 clear; cloud and rain less). */
    fun daylight(rules: LightRules, minuteOfDay: Int, sky: Double = 1.0): Double = (rules.daylightAt(minuteOfDay) * sky * 10).roundToInt() / 10.0

    /**
     * The light field now. [lights]: what's lit and where; [house]: room id to the site it's built on,
     * for its windows; [sky]: how much of the sun the weather lets through.
     */
    fun field(content: BedroomContent, layout: Layout, house: Map<String, String>, minuteOfDay: Int, lights: List<Emitter>, sky: Double = 1.0): Field {
        val rules = content.light ?: return Effects.spread(EffectKind.Light, layout, emptyList()) { _, _ -> FULL }
        val day = daylight(rules, minuteOfDay, sky)
        val windows = windows(content, layout, house).map { (id, cell) -> Emitter(id, "the window", EffectKind.Light, cell, day * rules.window.share, rules.window.radius) }
        return effects.field(EffectKind.Light, layout, windows + lights, day) { _, place ->
            if (place?.kind == PlaceKind.Room) day * rules.indoors else day
        }
    }

    companion object {
        /** The light everywhere in content without light rules: as at noon, so nothing is ever too dark. */
        const val FULL = 10.0

        /** Each room's windows on the map: the bedroom's (room.yaml) from where it stands, a built room's from its site. */
        fun windows(content: BedroomContent, layout: Layout, house: Map<String, String>): List<Pair<String, Cell>> = buildList {
            content.room.windows.forEachIndexed { i, (x, y) -> add("window_${layout.places.first().id}_$i" to Cell(layout.roomOrigin.x + x, layout.roomOrigin.y + y)) }
            house.forEach { (roomId, siteId) ->
                val room = content.world?.room(roomId) ?: return@forEach
                val site = content.world.site(siteId) ?: return@forEach
                room.windows.forEachIndexed { i, (x, y) -> add("window_${roomId}_$i" to Cell(site.at[0] + x, site.at[1] + y)) }
            }
        }

        /** "4", "0.5": a light level as the prompt says it; [down] rounds down, so a level short of a need never reads as the need. */
        fun level(v: Double, down: Boolean = false): String = ((if (down) floor(v * 10) else (v * 10).roundToInt().toDouble()) / 10.0).let { if (it == it.toInt().toDouble()) it.toInt().toString() else it.toString() }

        /**
         * How much a thing at [at] counts toward what he sees from [from] ([ComfortRules]): its
         * nearness times its light, 0 to 1. Out of his place or behind a wall, nothing.
         */
        fun viewWeight(rules: ComfortRules, light: LightRules?, layout: Layout, field: Field, from: Cell, at: Cell, asleep: Boolean): Double {
            if (layout.placeOf(from) != layout.placeOf(at)) return 0.0
            val d = Effects.distance(from, at)
            val near = when {
                d <= rules.near -> 1.0
                d >= rules.far -> 0.0
                else -> 1 - (d - rules.near) / (rules.far - rules.near)
            }
            if (near <= 0) return 0.0
            if (asleep) return near
            if (!Effects.clear(EffectKind.Light, layout, from, at)) return 0.0
            val level = field[at]
            val see = light?.see ?: 0.0
            if (level < see) return 0.0
            return near * (level / rules.lit).coerceAtMost(1.0)
        }

        /**
         * A few landmark phrases for how the light is where he stands, or null when it limits nothing:
         * no verb is [limited] (locked as too dark), nothing in his place is too dark to see, and he
         * isn't standing in the dark. By day that's most of the time. Levels are [work]'s, the light to
         * work by; what only his lantern shows ([sight] has it) is "by your lantern". [things]: the things
         * in his place by name, with their tiles and whether he knows of them without seeing them.
         */
        fun describe(work: Field, sight: Field, rules: LightRules, at: Cell, outdoors: Boolean, things: List<Landmark>, limited: Boolean): List<String>? {
            val here = work[at]
            val lit = things.filter { work[it.cell] >= rules.see }
            val lantern = things.filter { work[it.cell] < rules.see && sight[it.cell] >= rules.see }
            val dark = things - lit.toSet() - lantern.toSet()
            if (!limited && dark.isEmpty() && lantern.isEmpty() && here >= rules.see) return null
            fun from(c: Cell): String = work.source(c)?.name ?: "daylight"
            return buildList {
                add("where you stand: " + if (here < rules.see) "dark (light ${level(here, down = true)})" else "light ${level(here, down = true)}, from ${from(at)}")
                // A lamp lights itself; it's said as what lights the rest, or else as glowing.
                val glowing = lit.filter { work.source(it.cell)?.name == "the ${it.name}" }
                val groups = (lit - glowing.toSet()).sortedWith(compareByDescending<Landmark> { it.key }.thenByDescending { work[it.cell] }).groupBy { from(it.cell) }.entries.sortedByDescending { (_, ts) -> ts.maxOf { work[it.cell] } }.take(2)
                groups.forEach { (src, ts) ->
                    val levels = ts.map { work[it.cell] }
                    val range = level(levels.min(), down = true).let { lo -> level(levels.max(), down = true).let { hi -> if (lo == hi) lo else "$lo to $hi" } }
                    add("lit by $src (light $range): " + names(ts.take(3)))
                }
                if (groups.size < 2) glowing.filter { g -> groups.none { it.key == "the ${g.name}" } }.takeIf { it.isNotEmpty() }?.let { add("glowing: " + names(it.take(2))) }
                if (lantern.isNotEmpty()) add("by your lantern: " + names(lantern.take(2)))
                if (dark.isNotEmpty()) {
                    val known = dark.filter { it.known }
                    add(
                        "too dark to see " + (if (lit.isEmpty() && lantern.isEmpty()) "anything " + (if (outdoors) "here" else "in the room") else "the rest " + (if (outdoors) "here" else "of the room")) +
                            if (known.isEmpty()) "" else "; you remember ${names(known.sortedByDescending { it.key }.take(3))} there",
                    )
                }
            }
        }

        private fun names(ts: List<Landmark>): String {
            val n = ts.map { "the ${it.name}" }
            return if (n.size <= 1) n.joinToString() else n.dropLast(1).joinToString() + " and " + n.last()
        }
    }
}

/**
 * A thing in his place for [Lighting.describe]: what it's called, where, whether he knows it's there
 * without seeing it, and whether it's a [key] landmark (furniture and fixtures, where things are done),
 * said before the decor.
 */
data class Landmark(val name: String, val cell: Cell, val known: Boolean, val key: Boolean = false)
