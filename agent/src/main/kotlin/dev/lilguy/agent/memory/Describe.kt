package dev.lilguy.agent.memory

import dev.lilguy.sim.Intent
import dev.lilguy.sim.IntentStatus
import dev.lilguy.sim.Observation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Turns observations and intents into the free text of the event stream. */
object Describe {
    private val json = Json { encodeDefaults = true }

    /** "do activity=sketch", "do activity=read what=novel", "buy item=canvas", "move_to target=door". */
    fun intent(intent: Intent): String {
        val fields = json.encodeToJsonElement(Intent.serializer(), intent).jsonObject
        val type = fields.getValue("type").jsonPrimitive.content
        val args = fields.filter { (k, v) -> k != "type" && v !is JsonNull }.entries.joinToString(" ") { (k, v) ->
            k + "=" + ((v as? JsonPrimitive)?.content ?: v.toString())
        }
        return if (args.isEmpty()) type else "$type $args"
    }

    private fun result(status: IntentStatus?): String = when (status) {
        is IntentStatus.Failed -> "failed (${status.reason})"
        IntentStatus.Succeeded -> "succeeded"
        IntentStatus.Interrupted -> "interrupted"
        else -> "still going"
    }

    /**
     * The state he was shown, without the option lists, which are the same every time. [newEvents]
     * are the sim's recent events not seen in the previous observation.
     */
    fun observation(obs: Observation, newEvents: List<String>): String = buildList {
        obs.lastIntent?.let { add("last action ${intent(it)}: ${result(obs.lastResult)}") }
        obs.money?.let { add("money $$it") }
        obs.needs?.let { add("energy ${it.energy}, fullness ${it.fullness}, mood ${it.mood}, stimulation ${it.stimulation}") }
        obs.holding?.takeIf { it.isNotEmpty() }?.let { add("holding ${it.joinToString()}") }
        obs.owns?.takeIf { it.isNotEmpty() }?.let { o -> add("owns " + o.entries.joinToString { (k, v) -> if (v > 1) "$k x$v" else k }) }
        obs.music?.let { add("playing $it") }
        if (obs.clock == null) {
            add("at (%.1f, %.1f)".format(obs.self.x, obs.self.y))
            if (obs.self.inventory.isNotEmpty()) add("carrying ${obs.self.inventory.joinToString()}")
            if (obs.nearby.isNotEmpty()) add("sees " + obs.nearby.joinToString { e -> e.id + (e.state?.let { " ($it)" } ?: "") })
        }
        newEvents.forEach { add("event: $it") }
    }.joinToString("; ")
}
