package dev.lilguy.agent

import dev.lilguy.sim.BedroomContent
import dev.lilguy.sim.Direction
import dev.lilguy.sim.Observation
import dev.lilguy.sim.SeenEntity
import dev.lilguy.sim.VerbSpec
import kotlin.math.roundToInt

/**
 * What he sees round him, in words, for a model that calls tools: [brief] is the status message's
 * `here` (each thing in his place, which way and how far), and [look] is what `look_around` answers:
 * where he is, each thing round him with what he could do with it, the light and the weather, and
 * the other places he could walk to. Both are built from the observation the sim made, so they say
 * only what he can see or knows is there.
 */
object Surroundings {
    /** Things listed by [brief]. */
    const val MAX_BRIEF = 12

    /** "bench: 2 tiles north-west, within reach", nearest first; null when there's nothing to list. */
    fun brief(obs: Observation): List<String>? =
        obs.nearby.take(MAX_BRIEF).map { e -> "${e.id}: ${whereIs(e)}" + (if (e.remembered) ", too dark to see" else "") }.ifEmpty { null }

    /** What `look_around` answers. */
    fun look(obs: Observation, content: BedroomContent): String = buildString {
        appendLine("You look around." + (obs.place?.let { " You're in $it." } ?: ""))
        obs.weather?.let { appendLine("The weather: $it.") }
        obs.light?.let { appendLine("The light: ${it.joinToString("; ")}.") }
        val uses = uses(obs, content)
        if (obs.nearby.isEmpty()) {
            appendLine("There's nothing of yours here to see.")
        } else {
            appendLine("Round you here, nearest first (walk_to takes the id before the colon):")
            obs.nearby.forEach { e ->
                val name = content.item(e.id)?.name
                val state = e.state?.let { ", $it" }.orEmpty()
                val seen = if (e.remembered) "; too dark to make out, but you know it's there" else ""
                val does = uses[e.id]?.takeIf { it.isNotEmpty() }?.let { "; you can ${or(it)} there" }.orEmpty()
                appendLine("- ${e.id}${name?.takeIf { it != e.id.replace('_', ' ') }?.let { " (the $it)" }.orEmpty()}: ${whereIs(e)}$state$seen$does")
            }
        }
        val places = obs.places.orEmpty()
        if (places.isNotEmpty()) {
            appendLine("Other places you could go to, nearest first (go takes the id before the colon):")
            places.forEach { p -> appendLine("- ${p.id}: ${p.name}, ${p.says}; ${p.walk} min walk") }
        }
        val elsewhere = obs.activities.orEmpty().filter { it.locked == null && it.where != null }.groupBy { it.where!! }
        if (elsewhere.isNotEmpty()) {
            appendLine("What you could do elsewhere: " + elsewhere.entries.joinToString("; ") { (where, ways) -> "${ways.map { it.id }.distinct().joinToString(", ")} in $where (${ways.minOf { it.walk ?: 0 }} min walk)" } + ".")
        }
    }.trimEnd()

    /** "eat, sketch or devise". */
    private fun or(verbs: List<String>): String = if (verbs.size < 2) verbs.joinToString() else verbs.dropLast(1).joinToString(", ") + " or " + verbs.last()

    /** "2 tiles north-west, within reach", or "beside you". */
    private fun whereIs(e: SeenEntity): String {
        val tiles = e.distance.roundToInt()
        if (tiles == 0) return "beside you"
        return "$tiles tile${if (tiles == 1) "" else "s"} ${words(e.direction)}" + if (e.inReach) ", within reach" else ""
    }

    private fun words(d: Direction): String = when (d) {
        Direction.N -> "north"
        Direction.NE -> "north-east"
        Direction.E -> "east"
        Direction.SE -> "south-east"
        Direction.S -> "south"
        Direction.SW -> "south-west"
        Direction.W -> "west"
        Direction.NW -> "north-west"
    }

    /**
     * Thing id to the verbs he could do now at it: a verb's `at` names the thing, or an argument the
     * option fills with it. Only the open ones, as he's offered them.
     */
    private fun uses(obs: Observation, content: BedroomContent): Map<String, List<String>> {
        val out = linkedMapOf<String, MutableList<String>>()
        obs.activities.orEmpty().filter { it.locked == null }.forEach { o ->
            val verb = content.verb(o.id) ?: return@forEach
            verb.at.forEach { at ->
                val id = when (at) {
                    "what" -> o.what
                    "on" -> o.on
                    else -> at.takeIf { it !in verb.args && it != VerbSpec.HERE }
                } ?: return@forEach
                val list = out.getOrPut(id) { mutableListOf() }
                if (o.id !in list) list += o.id
            }
        }
        return out
    }
}
