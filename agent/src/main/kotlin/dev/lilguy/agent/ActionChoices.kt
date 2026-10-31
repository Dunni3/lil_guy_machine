package dev.lilguy.agent

import dev.lilguy.decision.ChoiceSet
import dev.lilguy.sim.Intent
import dev.lilguy.sim.IntentStatus
import dev.lilguy.sim.Observation
import dev.lilguy.sim.SeenEntity
import dev.lilguy.sim.Target
import dev.lilguy.sim.World
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The decision as a closed set, for models that pick rather than write (TypeSafe's Jev). The
 * options are every concrete action with an entity argument the reply schema allows for this
 * observation: walk to, pick up and use on each thing in view, use a held item by itself, and
 * wait a second. Tiles and `say` are left out: tiles are too many to list, and a picker can't
 * write the text. The state is a compact summary rather than the full observation (no ASCII map),
 * since irrelevant detail costs this kind of model accuracy, and "in reach" is computed for it.
 *
 * Options are ordered pick_up, use, move_to, wait. Jev leans toward earlier options, so actions
 * that change something come before walking, and waiting is last.
 */
fun actionChoices(obs: Observation, goal: String, recent: List<Memory.Entry>): ChoiceSet {
    val seen = obs.nearby
    val held = obs.self.inventory
    val options = buildList {
        seen.forEach { e -> add(option(Intent.PickUp(e.id), "Pick up the ${name(e)}. ${reach(e)}")) }
        held.forEach { item ->
            seen.forEach { e -> add(option(Intent.Use(item, on = e.id), "Use the $item you are holding on the ${name(e)}. ${reach(e)}")) }
            add(option(Intent.Use(item, on = null), "Use the $item you are holding by itself, not on anything."))
        }
        seen.forEach { e -> add(option(Intent.MoveTo(Target.Entity(e.id)), "Walk to the ${name(e)}, ${e.distance} tiles ${e.direction}.")) }
        add(option(Intent.Wait(World.TICKS_PER_SECOND), "Wait one second and do nothing."))
    }
    val state = buildJsonObject {
        put("goal", "Get the character out of the room: $goal")
        putJsonArray("rules") {
            add("Walking to something finds a path around walls. Walking to something that blocks the way, like a locked door, stops beside it.")
            add("Pick up and use only work on things that are in reach (on or next to the character). Walk next to a thing first.")
            add("An action that can't be done fails at once with a reason, shown in last_action.")
        }
        putJsonObject("character") {
            putJsonArray("holding") { held.forEach { add(it) } }
            put("tile", "(${obs.self.x.toInt()}, ${obs.self.y.toInt()})")
        }
        putJsonArray("can_see") {
            seen.forEach { e ->
                add(buildJsonObject {
                    put("id", e.id)
                    put("kind", e.kind)
                    e.state?.let { put("state", it) }
                    put("distance_tiles", e.distance)
                    put("direction", e.direction.name)
                    put("in_reach", e.inReach)
                })
            }
        }
        put("last_action", obs.lastIntent?.let { describe(it) + ": " + result(obs.lastResult) } ?: "none yet")
        if (recent.isNotEmpty()) {
            putJsonArray("recent_actions") { recent.forEach { add(describe(it.intent) + ": " + result(it.result)) } }
        }
        if (obs.recentEvents.isNotEmpty()) putJsonArray("recent_events") { obs.recentEvents.forEach { add(it) } }
    }
    return ChoiceSet(
        instructions = "Which action should the character in `state` take next to reach `state.goal`? " +
            "Read `state.last_action` and `state.recent_actions`: don't repeat an action that just failed.",
        state = state,
        options = options,
    )
}

private val json = Json { encodeDefaults = true; explicitNulls = true }

private fun option(intent: Intent, description: String) = ChoiceSet.Option(
    label = label(intent),
    description = description,
    reply = json.encodeToJsonElement(AgentReply.serializer(), AgentReply(description, intent)).jsonObject,
)

/** "pick_up:key", "use:key:door", "move_to:exit", "wait:20". */
private fun label(intent: Intent): String = when (intent) {
    is Intent.MoveTo -> "move_to:" + when (val t = intent.target) {
        is Target.Entity -> t.id
        is Target.Tile -> "${t.x},${t.y}"
    }
    is Intent.PickUp -> "pick_up:${intent.itemId}"
    is Intent.Use -> "use:${intent.itemId}" + (intent.on?.let { ":$it" } ?: "")
    is Intent.Say -> "say"
    is Intent.Wait -> "wait:${intent.ticks}"
    is Intent.Do -> (listOf("do", intent.activity) + listOfNotNull(intent.what, intent.on)).joinToString(":")
    is Intent.Buy -> "buy:${intent.item}"
    Intent.Think -> "think"
}

private fun name(e: SeenEntity): String =
    (if (e.id == e.kind) e.kind else "${e.kind} '${e.id}'") + (e.state?.let { " ($it)" } ?: "")

private fun reach(e: SeenEntity) = if (e.inReach) "It is in reach." else "It is not in reach: it is ${e.distance} tiles away."

private fun describe(intent: Intent): String = label(intent).replace(':', ' ')

private fun result(status: IntentStatus?): String = when (status) {
    null -> "still going"
    is IntentStatus.Failed -> "failed: ${status.reason}"
    IntentStatus.Succeeded -> "succeeded"
    IntentStatus.Interrupted -> "interrupted"
    else -> "still going"
}
