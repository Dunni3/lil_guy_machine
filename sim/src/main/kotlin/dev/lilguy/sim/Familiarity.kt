package dev.lilguy.sim

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.pow

/**
 * How familiar what he does is, judged by what it was like rather than what it's called
 * (docs/decisions/2026-11-15-fun-comes-from-what-is-unfamiliar-*). Each event he finishes is a set of
 * [Feature]s the sim knows: the verb and its kind, where it happened, the things it was done with (each
 * thing, its group and its qualities), the skill it practised, what it made and the sky when it was
 * outdoors. Meeting a feature adds 1 to its level, and levels halve every [FamiliarityRules.halfLifeDays].
 *
 * A feature's freshness is 1 / (1 + level); an event's is the weighted mean over its families of the
 * mean freshness within each family, so three qualities count as much as one place. Basking at a
 * fourth warm lamp shares nearly everything with the first three; playing the guitar at the pond in
 * the rain doesn't.
 */
@Serializable
data class FamiliarityRules(
    @SerialName("half_life_days") val halfLifeDays: Double,
    /** Family to its weight; a family left out weighs 1, and 0 leaves it out. */
    val weights: Map<String, Double> = emptyMap(),
) {
    fun weight(family: String): Double = weights[family] ?: 1.0

    fun problems(): List<String> =
        (if (halfLifeDays <= 0) listOf("familiarity: half_life_days must be positive") else emptyList()) +
            weights.keys.filter { it !in Feature.FAMILIES }.map { "familiarity: '$it' isn't a feature family (${Feature.FAMILIES.joinToString()})" } +
            weights.filterValues { it < 0 }.keys.map { "familiarity: $it's weight is negative" }
}

/** One thing an event was like: "place:pond", "quality:cozy". */
data class Feature(val family: String, val value: String) {
    val key: String get() = "$family:$value"

    companion object {
        val FAMILIES = listOf("verb", "kind", "place", "thing", "group", "quality", "skill", "makes", "sky")
    }
}

/** The levels of every feature he has met, each with the tick it was last set; they fade from there. */
class Familiarity(private val rules: FamiliarityRules, private val ticksPerHour: Long) {
    private val levels = mutableMapOf<String, Pair<Double, Long>>()

    fun level(key: String, now: Long): Double {
        val (level, since) = levels[key] ?: return 0.0
        return level * 0.5.pow((now - since).toDouble() / ticksPerHour / 24 / rules.halfLifeDays)
    }

    /** 1 when everything about it is new to him, toward 0 for what he meets every day; 1 with no features. */
    fun freshness(features: Collection<Feature>, now: Long): Double {
        var sum = 0.0
        var weight = 0.0
        features.distinct().groupBy { it.family }.forEach { (family, fs) ->
            val w = rules.weight(family)
            if (w <= 0) return@forEach
            sum += w * fs.map { 1.0 / (1.0 + level(it.key, now)) }.average()
            weight += w
        }
        return if (weight == 0.0) 1.0 else sum / weight
    }

    /** He has done something like this: each of its features is a little more familiar. */
    fun meet(features: Collection<Feature>, now: Long) {
        features.map { it.key }.distinct().forEach { k -> levels[k] = (level(k, now) + 1.0) to now }
    }

    fun export(): Map<String, BedroomState.Wear> = levels.toSortedMap().mapValues { (_, v) -> BedroomState.Wear(v.first, v.second) }

    fun restore(saved: Map<String, BedroomState.Wear>) {
        levels.clear()
        saved.forEach { (k, w) -> levels[k] = w.level to w.tick }
    }
}
