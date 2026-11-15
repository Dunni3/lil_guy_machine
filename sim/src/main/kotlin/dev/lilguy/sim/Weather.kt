package dev.lilguy.sim

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** The sky over the land for one game hour. */
enum class Sky(val id: String) {
    Clear("clear"), Cloudy("cloudy"), Rain("rain"), Fog("fog");

    companion object {
        fun of(id: String): Sky = entries.firstOrNull { it.id == id } ?: throw IllegalArgumentException("'$id' isn't a sky; use ${entries.joinToString { it.id }}")
    }
}

/**
 * weather.yaml: how the sky changes, how warm it is, the hidden pressure and what rain and cloud do
 * (docs/decisions/2026-11-15-weather-is-a-seeded-chain-and-a-station-logs-it.md).
 *
 * The sky changes hour by hour: [turns] gives, from each sky, the weight of each sky the next hour
 * (rows are weights, not probabilities). Fog forms only inside [fog] and lifts to cloud outside it.
 */
@Serializable
data class WeatherRules(
    /** The sky at day 1 00:00. */
    val start: String = "clear",
    val turns: Map<String, Map<String, Double>>,
    val fog: Window? = null,
    val temperature: Temperature,
    val pressure: Pressure,
    val rain: Rain,
    /** How much of the sun's light each sky lets through, 0 to 1 (what a light engine multiplies daylight by). */
    val daylight: Map<String, Double>,
    /** Outdoors below [Cold.below] degrees, his mood falls; null and the cold does nothing. */
    val cold: Cold? = null,
    /** What a recorder (the `recorder` affordance) keeps: the latest [Station.keeps] readings of each thing it logs. */
    val station: Station = Station(),
) {
    @Serializable
    data class Window(val from: String, val to: String) {
        fun covers(minuteOfDay: Int): Boolean = NightRules(from, to).covers(minuteOfDay)
    }

    /**
     * Degrees: [mean] plus a day's drift (up to [drift] either way, seeded per day), a daily swing of
     * [swing] either side, coldest at [coldest] and warmest twelve hours later, plus the sky's offset
     * ([sky]; [clearNight] more on a clear night, which loses its heat). [indoors]: the house, whatever the weather.
     */
    @Serializable
    data class Temperature(
        val mean: Double,
        val swing: Double,
        val coldest: String = "04:00",
        val drift: Double = 0.0,
        val sky: Map<String, Double> = emptyMap(),
        @SerialName("clear_night") val clearNight: Double = 0.0,
        val indoors: Double = 19.0,
    )

    /**
     * Hidden from him; only a recorder reads it. Each sky has its [level] (hPa), and the pressure at an hour is
     * the mean of the levels over that hour and the next [leadHours], less a trough round rain: [drop] at a rainy
     * hour, easing to nothing [leadHours] before it and [afterHours] after. So it falls that long before rain
     * comes, however short the shower, and climbs back after: a leading indicator. Plus up to [noise] either way.
     */
    @Serializable
    data class Pressure(
        val level: Map<String, Double>,
        @SerialName("lead_hours") val leadHours: Int,
        @SerialName("after_hours") val afterHours: Int = 0,
        val drop: Double = 0.0,
        val noise: Double = 0.0,
    )

    /** While it rains, [mmPerHour] (a range, seeded per hour) falls, and bare patches grow back [regrow] hours an hour instead of one. */
    @Serializable
    data class Rain(@SerialName("mm_per_hour") val mmPerHour: List<Double>, val regrow: Double = 1.0)

    @Serializable
    data class Cold(val below: Double, @SerialName("mood_per_hour") val moodPerHour: Double)

    @Serializable
    data class Station(val keeps: Int = 48)

    internal fun problems(): List<String> = buildList {
        val skies = Sky.entries.map { it.id }.toSet()
        fun sky(id: String, where: String) { if (id !in skies) add("weather.$where: '$id' isn't a sky (${skies.joinToString()})") }
        sky(start, "start")
        turns.forEach { (from, to) ->
            sky(from, "turns")
            to.keys.forEach { sky(it, "turns.$from") }
            if (to.values.any { it < 0 } || to.values.sum() <= 0) add("weather.turns.$from: weights can't be negative and must add up to more than 0")
        }
        (skies - turns.keys).forEach { add("weather.turns needs a row for $it") }
        (skies - pressure.level.keys).forEach { add("weather.pressure.level needs $it") }
        (skies - daylight.keys).forEach { add("weather.daylight needs $it") }
        temperature.sky.keys.forEach { sky(it, "temperature.sky") }
        if (daylight.values.any { it !in 0.0..1.0 }) add("weather.daylight: each is 0 to 1")
        if (pressure.leadHours < 0 || pressure.afterHours < 0 || pressure.drop < 0) add("weather.pressure: lead_hours, after_hours and drop can't be negative")
        if (rain.mmPerHour.size != 2 || rain.mmPerHour[0] < 0 || rain.mmPerHour[1] < rain.mmPerHour[0]) add("weather.rain.mm_per_hour is [least, most]")
        if (rain.regrow < 1) add("weather.rain.regrow is at least 1")
        if (station.keeps < 1) add("weather.station.keeps is at least 1")
        runCatching { GameClock.parseHhmm(temperature.coldest); fog?.let { GameClock.parseHhmm(it.from); GameClock.parseHhmm(it.to) } }.exceptionOrNull()?.let { add("weather: ${it.message}") }
    }
}

/** The weather now, for renderers: the [sky], degrees outdoors, rain this hour in mm, and how much of the sun gets through (0 to 1). */
@Serializable
data class WeatherView(val sky: String, val temperature: Double, val rain: Double, val daylight: Double)

/** What the weather is at one spot at one moment. [rain]: millimetres in the hour just gone. [light]: daylight, 0 to 100. */
data class Conditions(val sky: Sky, val temperature: Double, val rain: Double, val pressure: Double, val light: Double, val outdoors: Boolean)

/**
 * The weather of a run: a pure function of the [seed], the [rules] and the game hour (hour 0 is day 1
 * 00:00), so a run plays back the same and a checkpoint needs nothing of it. The sky is a Markov chain
 * stepped once an hour, each step with its own seeded random, worked out as far ahead as the pressure
 * looks ([WeatherRules.Pressure.leadHours]) and kept.
 */
class Weather(val rules: WeatherRules, private val seed: Long) {
    private val skies = ArrayList<Sky>().apply { add(Sky.of(rules.start)) }
    private val turns = rules.turns.map { (from, to) -> Sky.of(from) to to.map { (s, w) -> Sky.of(s) to w } }.toMap()

    /** The sky during game hour [hour]. */
    @Synchronized
    fun sky(hour: Long): Sky {
        if (hour < 0) return skies[0]
        while (skies.size <= hour) skies += next(skies.last(), skies.size.toLong())
        return skies[hour.toInt()]
    }

    private fun next(from: Sky, hour: Long): Sky {
        val minute = ((hour % 24) * 60).toInt()
        val fogOk = rules.fog?.covers(minute) ?: true
        val options = turns.getValue(from).filter { (s, _) -> s != Sky.Fog || fogOk }
        val pick = random(hour, 1).nextDouble() * options.sumOf { it.second }
        var acc = 0.0
        val to = options.firstOrNull { (_, w) -> acc += w; pick < acc }?.first ?: options.last().first
        // Fog outside its hours lifts to cloud.
        return if (to == Sky.Fog && !fogOk) Sky.Cloudy else to
    }

    /** Its own random for [hour] and [salt], so what one hour draws never shifts another's. */
    private fun random(hour: Long, salt: Int) = Random(seed * 1_000_003L + hour * 31 + salt * 7_919L + 0x5EED)

    private fun uniform(hour: Long, salt: Int, spread: Double) = (random(hour, salt).nextDouble() * 2 - 1) * spread

    /** Degrees outdoors at the start of [hour]. */
    fun temperature(hour: Long): Double {
        val t = rules.temperature
        val minute = ((hour % 24) * 60).toInt()
        val coldest = GameClock.parseHhmm(t.coldest)
        val sky = sky(hour)
        val night = sun(minute) == 0.0
        // Each day drifts warmer or colder, eased from one day's to the next so midnight has no step.
        val day = hour / 24
        val f = (hour % 24) / 24.0
        val drift = uniform(-1 - day, 3, t.drift) * (1 - f) + uniform(-2 - day, 3, t.drift) * f
        return t.mean + drift - t.swing * cos(2 * PI * (minute - coldest) / GameClock.MINUTES_PER_DAY) +
            (t.sky[sky.id] ?: 0.0) + (if (sky == Sky.Clear && night) t.clearNight else 0.0) + uniform(hour, 4, 0.4)
    }

    /** The hidden pressure at [hour], hPa. */
    fun pressure(hour: Long): Double {
        val p = rules.pressure
        val ahead = (0..p.leadHours).map { p.level.getValue(sky(hour + it).id) }
        val trough = (-p.afterHours..p.leadHours).maxOf { k ->
            if (sky(hour + k) != Sky.Rain) 0.0 else 1 - abs(k).toDouble() / ((if (k >= 0) p.leadHours else p.afterHours) + 1)
        }
        return ahead.average() - p.drop * trough + uniform(hour, 5, p.noise)
    }

    /** Millimetres of rain during [hour]. */
    fun rain(hour: Long): Double {
        if (sky(hour) != Sky.Rain) return 0.0
        val (least, most) = rules.rain.mmPerHour
        return least + random(hour, 6).nextDouble() * (most - least)
    }

    /** How much of the sun gets through the sky at [hour], 0 to 1: the multiplier a light engine puts on daylight. */
    fun daylight(hour: Long): Double = rules.daylight.getValue(sky(hour).id)

    /** Daylight outdoors at [minute] (since day 1 00:00), 0 to 100: the sun's height times what the sky lets through. */
    fun light(minute: Long): Double = 100 * sun((minute % GameClock.MINUTES_PER_DAY).toInt()) * daylight(minute / 60)

    /** Outdoors at [minute] since day 1 00:00. Rain is what fell in the hour before it. */
    fun at(minute: Long, outdoors: Boolean = true): Conditions {
        val hour = minute / 60
        val sky = sky(hour)
        return Conditions(
            sky = sky,
            temperature = if (outdoors) temperature(hour) else rules.temperature.indoors,
            rain = if (outdoors) rain(hour - 1) else 0.0,
            pressure = pressure(hour),
            light = if (outdoors) light(minute) else 0.0,
            outdoors = outdoors,
        )
    }

    /**
     * What he sees of the weather now: the sky, and how it feels when he's out in it ("raining, cold");
     * from indoors only the sky through a window. Never the pressure or what came before.
     */
    fun seen(minute: Long, outdoors: Boolean): String {
        val c = at(minute, outdoors = true)
        val night = sun((minute % GameClock.MINUTES_PER_DAY).toInt()) == 0.0
        val sky = when (c.sky) {
            Sky.Clear -> if (night) "a clear night" else "sunny"
            Sky.Cloudy -> if (night) "a cloudy night" else "cloudy"
            Sky.Rain -> "raining"
            Sky.Fog -> "foggy"
        }
        if (!outdoors) return "$sky outside (through the window)"
        val feel = when {
            c.temperature < 3 -> "freezing"
            c.temperature < 8 -> "cold"
            c.temperature < 14 -> "cool"
            c.temperature < 21 -> "mild"
            else -> "warm"
        }
        return "$sky, $feel"
    }

    companion object {
        /** The sun's height at [minuteOfDay], 0 (down, 18:00 to 06:00) to 1 (noon). */
        fun sun(minuteOfDay: Int): Double = sin(PI * (minuteOfDay - 360) / 720.0).coerceAtLeast(0.0)

        /** One decimal, for logs and code. */
        fun round1(v: Double): Double = (v * 10).roundToInt() / 10.0
    }
}
