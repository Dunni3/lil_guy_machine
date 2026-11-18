package dev.lilguy.agent

import dev.lilguy.sim.BedroomContent
import dev.lilguy.sim.BedroomWorld
import dev.lilguy.sim.GameClock
import dev.lilguy.sim.PlaceKind
import dev.lilguy.sim.SurveyOp
import dev.lilguy.sim.VerbSpec

/**
 * His map of the world, for the system prompt: each place, what it is, how far it is from his
 * front door, and what he can do there, so he can plan "go to the forest to look around". It's
 * built from the layout and the verbs he knows ([content]), so it changes only when the house or
 * what he knows does, and the prompt's prefix stays cacheable. Null where there's only the bedroom.
 */
object WorldMap {
    fun text(world: BedroomWorld, content: BedroomContent): String? {
        val layout = world.layout
        if (!layout.outdoors) return null
        val door = layout.doors.firstOrNull()
        val explored = world.exploredPlaces()
        val surveys = content.verbs.filter { v -> v.effects.any { it is SurveyOp } }.map { it.id }.toSet()
        // Where each verb is done: the places of the slots its things stand in.
        val verbsAt = linkedMapOf<String, MutableList<String>>()
        val anywhere = mutableListOf<String>()
        content.verbs.forEach { v ->
            if (VerbSpec.HERE in v.at) {
                anywhere += v.phrase ?: v.id
                return@forEach
            }
            val things = v.at.flatMap { at -> v.args[at]?.let { content.withName(it) } ?: listOfNotNull(content.item(at)) }
            things.mapNotNull { it.slot?.let(layout.slotCells::get)?.let(layout::placeOf)?.id }.distinct()
                .forEach { verbsAt.getOrPut(it) { mutableListOf() } += v.id }
        }
        val lines = buildList {
            add("Your world is a small house on a piece of land. Walking takes about a game minute a tile; times below are from your front door.")
            // Rooms first, then the land nearest first.
            val walks = layout.places.associate { p -> p.id to door?.let { d -> layout.spot(p)?.let { s -> layout.distance(d.cell, s) } }?.let { world.walkMinutes(it) } }
            layout.places.sortedWith(compareBy({ it.kind != PlaceKind.Room }, { walks[it.id] ?: 0 })).forEach { p ->
                val does = verbsAt[p.id]?.distinct()
                val what = if (does.isNullOrEmpty()) "nothing to do there yet but look around and be there" else "you can ${does.joinToString(", ")} there"
                when (p.kind) {
                    PlaceKind.Room -> {
                        val out = layout.doors.filter { it.inside == p.id }.joinToString(" and ") { d -> "its front door opens onto ${d.outside?.let { layout.place(it)?.name } ?: "the land"}" }
                        add("- ${p.name}: ${p.says}; $what." + (if (out.isNotEmpty()) " ${out.replaceFirstChar { it.uppercase() }}." else ""))
                    }
                    PlaceKind.Building -> {
                        val walk = walks[p.id]
                        val out = layout.doors.firstOrNull { it.inside == p.id }?.outside?.let { layout.place(it)?.name }
                        add("- ${p.name}: you built it" + (out?.let { ", in $it" }.orEmpty()) + (p.says.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()) +
                            (walk?.let { "; ${GameClock.duration(it.coerceAtLeast(1))} walk" }.orEmpty()) + "; $what.")
                    }
                    PlaceKind.Outdoor -> {
                        val walk = walks[p.id]
                        val looked = if (p.id in explored) " You've explored it." else if (does?.any { it in surveys } == true) " You haven't explored it yet." else ""
                        add("- ${p.name}: ${p.says}" + (walk?.let { "; ${GameClock.duration(it.coerceAtLeast(1))} walk" }.orEmpty()) + "; $what.$looked")
                    }
                }
            }
            if (anywhere.isNotEmpty()) add("Anywhere, you can ${anywhere.joinToString(", ")}.")
            add("The activities walk you there, and say where they're done when it's somewhere else and how long the walk is. To go somewhere just to be there, go to it; each place shows the walk from where you are.")
        }
        return lines.joinToString("\n")
    }
}
