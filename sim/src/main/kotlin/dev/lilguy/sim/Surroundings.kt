package dev.lilguy.sim

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/** An item's reach of one effect (items.yaml `warmth`): [strength] on its own tile, fading to nothing a tile past [radius]. */
@Serializable
data class EffectSpec(val strength: Double, val radius: Double)

/** What an item makes heard (items.yaml `sound`), as [EffectSpec]. [playing]: only while a record plays on it (the record player). */
@Serializable
data class SoundSpec(val strength: Double, val radius: Double, val playing: Boolean = false)

/** What an item draws (items.yaml `lure`): the visitor [draws] (world.yaml `visitors`) comes to it, [strength] on its own tile, reaching [radius]. */
@Serializable
data class LureSpec(val draws: String, val strength: Double, val radius: Double)

/** What a planter grows (items.yaml `grows`): [count] of [yields] after [hours] sunny hours with damp soil ([GrowingRules]). */
@Serializable
data class GrowSpec(val yields: String, val hours: Int, val count: Int = 1)

/** rules.yaml `sound`: he hears a sound at [hear] or more, and music lifts his mood in full at [full], less below it, not at all where he can't hear it. */
@Serializable
data class SoundRules(val hear: Double = 1.0, val full: Double = 4.0)

/** rules.yaml `sleep`: asleep where it's colder than [cold] degrees, or [noisy] loud or more, his energy comes back at [rest] of its rate. */
@Serializable
data class SleepRules(val cold: Double = 15.0, val noisy: Double = 2.0, val rest: Double = 0.7)

/**
 * rules.yaml `growing`: an hour counts toward what a planter grows when its soil is damp and the sun
 * on it (daylight and windows; lamps don't count) is [sun] or more. Soil holds [soil] of water and
 * loses [driesPerHour] an hour; rain and spray add their level each hour, and watering fills it.
 */
@Serializable
data class GrowingRules(val sun: Double = 6.0, val soil: Double = 10.0, @SerialName("dries_per_hour") val driesPerHour: Double = 0.4)

/**
 * world.yaml `visitors`: a kind of animal drawn to the things whose `lure` draws it, while the
 * conditions at that thing hold: within [hours], each effect in [above] at least that much there and
 * each in [below] less. [come] is when they come, for the prompt ("by day, when it isn't raining").
 * Watched there (the `watch` op), they leave one [yields] and stay away [restHours].
 */
@Serializable
data class VisitorSpec(
    val id: String,
    val name: String,
    val yields: String,
    val come: String,
    val hours: NightRules? = null,
    val above: Map<String, Double> = emptyMap(),
    val below: Map<String, Double> = emptyMap(),
    @SerialName("rest_hours") val restHours: Int = 6,
) {
    internal fun problems(): List<String> = buildList {
        (above.keys + below.keys).filter { EffectKind.of(it) == null || it == EffectKind.Lure.id }.forEach { add("visitor $id: '$it' isn't an effect it can wait for (${EffectKind.entries.filter { k -> k != EffectKind.Lure }.joinToString { k -> k.id }})") }
        hours?.let { runCatching { GameClock.parseHhmm(it.from); GameClock.parseHhmm(it.to) }.onFailure { e -> add("visitor $id: ${e.message}") } }
        if (restHours < 0) add("visitor $id: rest_hours can't be negative")
    }
}

/** Visitors [visitor] at [lure], which stands at [cell]: about [count] of them. */
data class Visit(val visitor: VisitorSpec, val lure: ItemSpec, val cell: Cell, val count: Int)

/** A planter's soil: how much [water] it holds and how many sunny damp hours it has had toward its harvest ([sun]), and the most sun it got today. */
@Serializable
data class Soil(var water: Double = 0.0, var sun: Int = 0, @SerialName("best_today") var bestToday: Double = 0.0, var why: String? = null)

/** What the code he wrote has a thing give off, as saved: effect kind to its reach. */
typealias Emissions = Map<String, Map<String, EffectSpec>>

/**
 * The effects besides light round his things (docs/decisions/2026-11-15-warmth-sound-water-and-lures-*):
 * warmth, sound, water and the lures that draw visitors, each a field on the [Effects] engine like
 * light, and what reads them: the cold out of doors, how he sleeps, what music he hears, what grows
 * in a planter, and which visitors come to what. Also what his code makes his things give off
 * ([emit]). [BedroomWorld] owns one and is its [Host].
 */
class Surroundings {
    /** What these effects need of the world. */
    interface Host {
        val content: BedroomContent
        val layout: Layout
        val weather: Weather?

        /** His things that stand somewhere, each at the tile it's at now (one he's carrying, his). */
        fun placed(): List<Pair<ItemSpec, Cell>>

        /** What he owns that stands nowhere (a blanket), by spec. */
        fun unplaced(): List<ItemSpec>
        fun hisCell(): Cell

        /** Game minutes since day 1 00:00. */
        fun minutes(): Long

        /** The light to work by ([BedroomWorld.lightField] without his lantern). */
        fun light(): Field

        /** The sun alone: daylight and windows, no lamps. */
        fun sun(): Field

        /** The daylight outdoors now, 0 to 10, as the sky lets it through. */
        fun daylight(): Double

        /** The item a record is playing on now, or null. */
        fun playingOn(): String?
    }

    private val effects = Effects(keep = 8)

    /** What his code has his things give off, item id to kind to reach ([emit]). */
    private val emitted = linkedMapOf<String, LinkedHashMap<EffectKind, EffectSpec>>()

    /** Each planter's soil, by item id. */
    private val soils = linkedMapOf<String, Soil>()

    /** Lure id to the game minute its visitors are back, after he watched them. */
    private val away = linkedMapOf<String, Long>()

    // --- emitters and fields --------------------------------------------------------------------

    /** What his code has [id] give off of [kind], or null. */
    fun emitted(id: String, kind: EffectKind): EffectSpec? = emitted[id]?.get(kind)

    /** Every thing his code has give off light, as light [Emitter]s at their tiles: for [BedroomWorld]'s lamps. */
    fun codeLights(h: Host): List<Emitter> = codeEmitters(h, EffectKind.Light)

    private fun codeEmitters(h: Host, kind: EffectKind): List<Emitter> = h.placed().mapNotNull { (spec, cell) ->
        emitted[spec.id]?.get(kind)?.let { Emitter("${spec.id}:code", "the ${spec.name}", kind, cell, it.strength, it.radius) }
    }

    private fun emitters(h: Host, kind: EffectKind): List<Emitter> = when (kind) {
        EffectKind.Warmth -> h.placed().mapNotNull { (spec, cell) -> spec.warmth?.let { Emitter(spec.id, "the ${spec.name}", kind, cell, it.strength, it.radius) } } +
            // What he wears (a blanket, standing nowhere) warms him out of doors.
            h.unplaced().filter { it.warmth != null && outdoors(h, h.hisCell()) }.map { Emitter(it.id, "your ${it.name}", kind, h.hisCell(), it.warmth!!.strength, it.warmth.radius) }
        EffectKind.Sound -> h.placed().mapNotNull { (spec, cell) ->
            spec.sound?.takeIf { !it.playing || h.playingOn() == spec.id }?.let { Emitter(spec.id, "the ${spec.name}", kind, cell, it.strength, it.radius) }
        }
        else -> emptyList()
    } + codeEmitters(h, kind)

    private fun outdoors(h: Host, c: Cell) = h.layout.placeOf(c)?.kind == PlaceKind.Outdoor

    /** Degrees outdoors now: the weather's, or [NO_WEATHER] without weather. */
    fun outdoorTemperature(h: Host): Double = h.weather?.temperature(h.minutes() / 60) ?: NO_WEATHER

    /** Degrees in the house now: its own warmth, losing [WeatherRules.Temperature.leak] of the gap to the cold (or heat) outside. */
    fun indoorTemperature(h: Host): Double {
        val t = h.weather?.rules?.temperature ?: return WeatherRules.Temperature(0.0, 0.0).indoors
        return t.indoors + t.leak * (outdoorTemperature(h) - t.indoors)
    }

    /** Degrees on every tile: the weather outdoors, the house's indoors, plus heaters, fires and what he wears. */
    fun warmth(h: Host): Field {
        val out = Weather.round1(outdoorTemperature(h))
        val inside = Weather.round1(indoorTemperature(h))
        return effects.field(EffectKind.Warmth, h.layout, emitters(h, EffectKind.Warmth), out to inside) { _, place -> if (place?.kind?.indoors == true) inside else out }
    }

    /** How loud it is on every tile: nothing but what sounds (a record playing, a chime). */
    fun sound(h: Host): Field = effects.field(EffectKind.Sound, h.layout, emitters(h, EffectKind.Sound), Unit) { _, _ -> 0.0 }

    /** How wet it is on every tile: the rain this hour outdoors (millimetres), plus spray. */
    fun water(h: Host): Field {
        val rain = h.weather?.let { Weather.round1(it.rain(h.minutes() / 60)) } ?: 0.0
        return effects.field(EffectKind.Water, h.layout, emitters(h, EffectKind.Water), rain) { _, place -> if (place?.kind == PlaceKind.Outdoor) rain else 0.0 }
    }

    /** What draws [visitor] on every tile: the things whose lure draws it. */
    fun lure(h: Host, visitor: String): Field {
        val lures = h.placed().mapNotNull { (spec, cell) -> spec.lure?.takeIf { it.draws == visitor }?.let { Emitter(spec.id, "the ${spec.name}", EffectKind.Lure, cell, it.strength, it.radius) } }
        return effects.field(EffectKind.Lure, h.layout, lures, visitor) { _, _ -> 0.0 }
    }

    /** [kind] on every tile now; a visitor's id is its lure. Null for no such effect. */
    fun field(h: Host, kind: String): Field? = when (EffectKind.of(kind)) {
        EffectKind.Light -> h.light()
        EffectKind.Warmth -> warmth(h)
        EffectKind.Sound -> sound(h)
        EffectKind.Water -> water(h)
        else -> h.content.world?.visitor(kind)?.let { lure(h, it.id) }
    }

    /** How much of [item]'s sound reaches [cell], as a share of what lifts mood in full (0 to 1): 0 where he can't hear it, or when it's silent. */
    fun heard(h: Host, item: ItemSpec, cell: Cell): Double {
        val rules = h.content.sound ?: return 1.0
        val s = item.sound?.takeIf { !it.playing || h.playingOn() == item.id } ?: return 0.0
        val at = h.placed().firstOrNull { it.first.id == item.id }?.second ?: return 0.0
        val level = Effects.reach(Emitter(item.id, item.name, EffectKind.Sound, at, s.strength, s.radius), h.layout, cell)
        return if (level < rules.hear) 0.0 else (level / rules.full).coerceAtMost(1.0)
    }

    // --- what he feels -----------------------------------------------------------------------------

    /** Why he'd sleep badly at [cell] now ("cold (14°)", "noisy: the jazz record"), or null. */
    fun restless(h: Host, cell: Cell): String? {
        val rules = h.content.sleep ?: return null
        val warm = warmth(h)[cell]
        if (h.weather != null && warm < rules.cold) return "cold (${deg(warm)})"
        val loud = sound(h)
        if (loud[cell] >= rules.noisy) return "noisy (${loud.source(cell)?.name ?: "it's loud"})"
        return null
    }

    // --- planters ----------------------------------------------------------------------------------

    fun soil(id: String): Soil = soils.getOrPut(id) { Soil() }

    /**
     * One game hour for each planter he has standing somewhere: rain and spray on its tile water it,
     * and an hour of sun on damp soil counts toward its harvest. [hourOfDay] 0 starts a new day's best sun.
     */
    fun growHour(h: Host, hourOfDay: Int) {
        val rules = h.content.growing ?: return
        val planters = h.placed().filter { it.first.grows != null }
        if (planters.isEmpty()) return
        val sun = h.sun()
        val wet = water(h)
        val daylight = h.daylight()
        planters.forEach { (spec, cell) ->
            val g = spec.grows!!
            val s = soil(spec.id)
            if (hourOfDay == 0) {
                s.bestToday = 0.0
                s.why = null
            }
            s.water = (s.water + wet[cell]).coerceAtMost(rules.soil)
            val light = sun[cell]
            s.bestToday = maxOf(s.bestToday, light)
            val sunny = light >= rules.sun
            if (s.water > 0 && sunny) {
                s.sun = (s.sun + 1).coerceAtMost(g.hours)
                s.why = null
            } else if (daylight >= rules.sun) {
                // A bright hour outdoors that did it no good says why.
                s.why = if (!sunny) "too little sun reaches it here (light ${Lighting.level(s.bestToday, down = true)} at best today; it needs ${Lighting.level(rules.sun)})" else "its soil is dry"
            }
            s.water = (s.water - rules.driesPerHour).coerceAtLeast(0.0)
        }
    }

    /** "the planter's tomatoes aren't ready: 4 of 10 sunny hours; its soil is dry", or null when they're ready. */
    fun harvestLock(planter: ItemSpec): String? {
        val g = planter.grows ?: return "the ${planter.name} doesn't grow anything"
        val s = soils[planter.id] ?: Soil()
        if (s.sun >= g.hours) return null
        return "the ${planter.name} isn't ready: ${s.sun} of ${g.hours} sunny hours" + (s.why?.let { "; $it" } ?: "")
    }

    /** Picks what [planter] has grown; the event's parts. [add] gives him the harvest. */
    fun harvest(planter: ItemSpec, add: (String, Int) -> String): List<String> {
        val g = planter.grows!!
        soil(planter.id).sun = 0
        return listOf("+${g.count} ${add(g.yields, g.count)}", "the ${planter.name} starts growing again")
    }

    /** "the planter's soil is still wet", or null when it could do with water. */
    fun waterLock(h: Host, planter: ItemSpec): String? {
        val rules = h.content.growing ?: return "nothing here needs watering"
        return if ((soils[planter.id]?.water ?: 0.0) >= rules.soil * WET) "the ${planter.name}'s soil is still wet" else null
    }

    fun water(h: Host, planter: ItemSpec): List<String> {
        val rules = h.content.growing!!
        soil(planter.id).water = rules.soil
        return listOf("watered the ${planter.name}: its soil stays damp about ${(rules.soil / rules.driesPerHour).roundToInt()} hours")
    }

    /** "soil damp, 4 of 10 sunny hours toward its tomatoes", for options. */
    fun soilText(planter: ItemSpec, yieldsName: String): String {
        val s = soils[planter.id] ?: Soil()
        val g = planter.grows ?: return ""
        return "soil ${if (s.water > 0) "damp" else "dry"}, ${s.sun} of ${g.hours} sunny hours toward its $yieldsName"
    }

    // --- visitors ----------------------------------------------------------------------------------

    /** Why [v] isn't at [cell] now, or null when they'd be there: the hour, or an effect there. */
    private fun absent(h: Host, v: VisitorSpec, cell: Cell): String? {
        val minute = (h.minutes() % GameClock.MINUTES_PER_DAY).toInt()
        if (v.hours?.covers(minute) == false) return "the hour"
        v.above.forEach { (k, least) -> if ((field(h, k)?.get(cell) ?: 0.0) < least) return k }
        v.below.forEach { (k, most) -> if ((field(h, k)?.get(cell) ?: 0.0) >= most) return k }
        return null
    }

    /** The visitors at his things now: each lure with its visitor there, unless he watched them lately. */
    fun visits(h: Host): List<Visit> {
        val world = h.content.world ?: return emptyList()
        if (world.visitors.isEmpty()) return emptyList()
        val now = h.minutes()
        return h.placed().mapNotNull { (spec, cell) ->
            val l = spec.lure ?: return@mapNotNull null
            val v = world.visitor(l.draws) ?: return@mapNotNull null
            if ((away[spec.id] ?: 0) > now || absent(h, v, cell) != null) return@mapNotNull null
            Visit(v, spec, cell, lure(h, v.id)[cell].roundToInt().coerceAtLeast(1))
        }
    }

    /** Why there's nothing to watch at [lure] now ("no birds at the bird feeder now; they come by day, when it isn't raining"), or null. */
    fun watchLock(h: Host, lure: ItemSpec): String? {
        val v = lure.lure?.let { h.content.world?.visitor(it.draws) } ?: return "nothing comes to the ${lure.name}"
        away[lure.id]?.takeIf { it > h.minutes() }?.let { return "the ${v.name} have gone from the ${lure.name} for now; back after ${GameClock.hhmm((it % GameClock.MINUTES_PER_DAY).toInt())}" }
        if (visits(h).none { it.lure.id == lure.id }) return "no ${v.name} at the ${lure.name} now; they come ${v.come}"
        return null
    }

    /** Watches the visitors at [lure]; they leave one of what they leave, and stay away a while. [add] gives it to him. */
    fun watch(h: Host, lure: ItemSpec, add: (String, Int) -> String): List<String> {
        val visit = visits(h).firstOrNull { it.lure.id == lure.id } ?: return listOf("nothing came")
        away[lure.id] = h.minutes() + visit.visitor.restHours * 60L
        return listOf("watched ${visit.count} ${visit.visitor.name} at the ${lure.name}", "+1 ${add(visit.visitor.yields, 1)}")
    }

    /** "the birds at the bird feeder", for options: who's there now, or who comes. */
    fun watchText(h: Host, lure: ItemSpec): String {
        val v = lure.lure?.let { h.content.world?.visitor(it.draws) } ?: return ""
        val here = visits(h).firstOrNull { it.lure.id == lure.id }
        return if (here != null) "${here.count} ${v.name} at the ${lure.name} now" else "${v.name} come ${v.come}"
    }

    /**
     * What a recorder at [cell] logs of visitors: each visitor kind one of his lures draws near it
     * (the lure's reach is on [cell]), to how many are about it now (0 when none are).
     */
    fun visitorsAt(h: Host, cell: Cell): Map<String, Double> {
        val world = h.content.world ?: return emptyMap()
        val visiting = visits(h)
        return world.visitors.mapNotNull { v ->
            val f = lure(h, v.id)
            if (f[cell] <= 0) return@mapNotNull null
            val here = visiting.filter { it.visitor.id == v.id }.sumOf { visit -> Effects.reach(Emitter(visit.lure.id, visit.lure.name, EffectKind.Lure, visit.cell, visit.lure.lure!!.strength, visit.lure.lure.radius), h.layout, cell) }
            v.id to Weather.round1(here)
        }.toMap()
    }

    // --- his code ----------------------------------------------------------------------------------

    /** Has [id] give off [kind]: [strength] on its tile reaching [radius], until he changes it; strength 0 stops it. True when it changed anything. */
    fun emit(id: String, kind: EffectKind, strength: Double, radius: Double): Boolean {
        val now = emitted[id]?.get(kind)
        if (strength <= 0) {
            val gone = emitted[id]?.remove(kind) != null
            if (emitted[id]?.isEmpty() == true) emitted.remove(id)
            return gone
        }
        val spec = EffectSpec(strength, radius)
        if (now == spec) return false
        emitted.getOrPut(id) { linkedMapOf() }[kind] = spec
        return true
    }

    /** Forgets what [keep] says no longer exists. */
    fun prune(keep: (String) -> Boolean) {
        emitted.keys.retainAll(keep)
        soils.keys.retainAll(keep)
        away.keys.retainAll(keep)
    }

    // --- what the prompt says ------------------------------------------------------------------------

    /**
     * What he feels of these effects where he stands, only where one limits or explains something:
     * the cold out of doors, music he hears only faintly or not at all, a bed that would sleep badly.
     * Null when there's nothing to say, which is most of the time.
     */
    fun senses(h: Host, music: Pair<ItemSpec, String>?, bed: Cell?, evening: Boolean): List<String>? {
        val at = h.hisCell()
        val place = h.layout.placeOf(at)
        val lines = buildList {
            val cold = h.weather?.rules?.cold
            val warm = warmth(h)
            if (cold != null && warm[at] < cold.below) {
                val warmer = h.placed().filter { (s, c) -> s.warmth != null && h.layout.placeOf(c) == place }.maxByOrNull { (_, c) -> warm[c] }
                add("cold here (${deg(warm[at])}): your mood and energy fall" + (warmer?.let { (s, c) -> "; by the ${s.name} it's ${deg(warm[c])}" } ?: ""))
            }
            if (music != null) {
                val (player, record) = music
                val share = heard(h, player, at)
                if (share < 1) add(if (share <= 0) "you can't hear the $record from here" else "you hear the $record faintly from here (it lifts your mood ${percent(share)} as much)")
            }
            if (evening && bed != null) restless(h, bed)?.let { add("the bed is $it: you'd sleep less well tonight") }
            // Visitors aren't said here: the watch option says who's there, and their coming is an event.
        }
        return lines.ifEmpty { null }
    }

    // --- checkpoints -------------------------------------------------------------------------------

    fun export(): SurroundingsState = SurroundingsState(
        emitted.mapValues { (_, m) -> m.mapKeys { it.key.id } },
        soils.mapValues { it.value.copy() },
        LinkedHashMap(away),
    )

    fun restore(s: SurroundingsState, keep: (String) -> Boolean) {
        emitted.clear()
        s.emitted.forEach { (id, m) -> if (keep(id)) emitted[id] = LinkedHashMap(m.mapNotNull { (k, v) -> EffectKind.of(k)?.let { it to v } }.toMap()) }
        soils.clear()
        s.soils.forEach { (id, soil) -> if (keep(id)) soils[id] = soil.copy() }
        away.clear()
        s.away.forEach { (id, until) -> if (keep(id)) away[id] = until }
    }

    companion object {
        /** Degrees outdoors with no weather. */
        const val NO_WEATHER = 15.0

        /** Soil this wet (a share of what it holds) doesn't want watering. */
        private const val WET = 0.75

        /** The kinds his code can have a thing give off. */
        val EMITTABLE = listOf(EffectKind.Light, EffectKind.Warmth, EffectKind.Sound, EffectKind.Water)

        /** The widest reach his code can give a thing. */
        const val MOST_RADIUS = 20.0

        /** Whole degrees, rounded down, so a tile short of a threshold never reads as it. */
        fun deg(v: Double) = "${kotlin.math.floor(v).toInt()}°"

        private fun percent(share: Double) = when {
            share < 0.4 -> "a third"
            share < 0.6 -> "half"
            else -> "three quarters"
        }
    }
}

/** What [Surroundings] keeps in a checkpoint: what his code has things give off, the planters' soil, and when visitors are back. */
@Serializable
data class SurroundingsState(
    val emitted: Emissions = emptyMap(),
    val soils: Map<String, Soil> = emptyMap(),
    val away: Map<String, Long> = emptyMap(),
)
