package dev.lilguy.agent.memory

import dev.lilguy.sim.GameClock
import dev.lilguy.sim.Intent
import dev.lilguy.sim.IntentStatus
import dev.lilguy.sim.Observation
import dev.lilguy.sim.Target
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Turns intents, their outcomes and his state into the free text of the event stream. */
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

    /** "sketch", "read the novel", "play_record the jazz_record on the record_player", "buy canvas", "wait". */
    fun action(intent: Intent): String = when (intent) {
        is Intent.Do -> intent.activity + (intent.what?.let { " the $it" } ?: "") + (intent.on?.let { " on the $it" } ?: "")
        is Intent.Buy -> "buy ${intent.item}"
        is Intent.Wait -> "wait"
        is Intent.MoveTo -> "walk to " + when (val t = intent.target) {
            is Target.Entity -> t.id
            is Target.Tile -> "(${t.x}, ${t.y})"
        }
        is Intent.PickUp -> "pick up ${intent.itemId}"
        is Intent.Use -> "use ${intent.itemId}" + (intent.on?.let { " on $it" } ?: "")
        is Intent.Say -> "say \"${intent.text}\""
        Intent.Think -> "think"
    }

    /**
     * How one intent turned out, from the sim's own words for what it did ([Observation.outcome]):
     * "sketch (1h): done; +1 sketch, energy -6, mood -4, stimulation +3", "buy meal (10m): done;
     * bought 3 meals for $20 (money $4)", "paint: failed (too low to make art)". [minutes] is how
     * long it took, walking included; null or 0 leaves it out.
     */
    fun outcome(intent: Intent, status: IntentStatus, said: String?, minutes: Int?): String {
        val took = minutes?.takeIf { it > 0 }?.let { " (${GameClock.duration(it)})" }.orEmpty()
        val what = action(intent) + took
        return when (status) {
            is IntentStatus.Failed -> "${action(intent)}: failed (${status.reason})"
            IntentStatus.Interrupted -> "$what: interrupted"
            else -> {
                val verb = (intent as? Intent.Do)?.activity
                val parts = said?.let { s -> if (verb != null) s.removePrefix("finished $verb").removePrefix(": ") else s }?.takeIf { it.isNotBlank() }
                "$what: done" + (parts?.let { "; $it" } ?: "")
            }
        }
    }

    /** How things stand, without the option lists: money, needs, what he holds and owns, what's playing. */
    fun state(obs: Observation): String = buildList {
        obs.money?.let { add("money $$it") }
        obs.needs?.let { add("energy ${it.energy}, fullness ${it.fullness}, mood ${it.mood}, stimulation ${it.stimulation}") }
        obs.holding?.takeIf { it.isNotEmpty() }?.let { add("holding ${it.joinToString()}") }
        obs.owns?.takeIf { it.isNotEmpty() }?.let { o -> add("owns " + o.entries.joinToString { (k, v) -> if (v > 1) "$k x$v" else k }) }
        obs.music?.let { add("playing $it") }
        if (obs.clock == null) {
            add("at (%.1f, %.1f)".format(obs.self.x, obs.self.y))
            if (obs.self.inventory.isNotEmpty()) add("carrying ${obs.self.inventory.joinToString()}")
        }
    }.joinToString("; ")

    /** Minutes from [from] to [to] ("HH:MM" on game days), or null when either has no clock. */
    fun minutesBetween(fromDay: Int?, fromTime: String?, toDay: Int?, toTime: String?): Int? {
        if (fromDay == null || fromTime == null || toDay == null || toTime == null) return null
        return (toDay - fromDay) * GameClock.MINUTES_PER_DAY + GameClock.parseHhmm(toTime) - GameClock.parseHhmm(fromTime)
    }
}
