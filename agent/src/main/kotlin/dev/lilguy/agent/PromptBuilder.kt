package dev.lilguy.agent

import dev.lilguy.decision.DecisionRequest
import dev.lilguy.decision.Message
import dev.lilguy.sim.Intent
import dev.lilguy.sim.IntentStatus
import dev.lilguy.sim.Observation
import dev.lilguy.sim.RoomWorld
import dev.lilguy.sim.World
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Turns an observation plus recent memory into a request. The system prompt is fixed per goal:
 * how the world works, the action space with an example of each action, the reply format and
 * what the observation fields mean. The user message is the observation as one line of JSON and
 * the recent intents with how each ended.
 */
class PromptBuilder(
    private val goal: String,
    private val timeout: Duration = 20.seconds,
) {
    val system: String = systemPrompt(goal)

    fun build(obs: Observation, recent: List<Memory.Entry>): DecisionRequest {
        val history = recent.joinToString("\n") { e ->
            "tick ${e.tick}: ${json.encodeToString(Intent.serializer(), e.intent)} -> ${e.result?.let(::describe) ?: "running"}"
        }
        val user = buildString {
            appendLine(OBSERVATION_HEADER)
            appendLine(json.encodeToString(Observation.serializer(), obs))
            if (history.isNotEmpty()) {
                appendLine("Your recent actions, oldest first:")
                appendLine(history)
            }
            append("Choose your next action.")
        }
        return DecisionRequest(system = system, messages = listOf(Message(Message.Role.User, user)), timeout = timeout)
    }

    companion object {
        private const val OBSERVATION_HEADER = "Observation:"
        private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        /** Examples show every field, nulls included, the way a strict schema wants them. */
        private val exampleJson = Json { encodeDefaults = true; explicitNulls = true }

        private fun describe(status: IntentStatus): String = when (status) {
            is IntentStatus.Failed -> "failed: ${status.reason}"
            else -> json.encodeToJsonElement(IntentStatus.serializer(), status).jsonObject.getValue("type").jsonPrimitive.content
        }

        fun systemPrompt(goal: String): String {
            val tps = World.TICKS_PER_SECOND
            val actions = ActionExamples.joinToString("\n") { (intent, what) ->
                "- ${tag(intent)}: $what\n  ${exampleJson.encodeToString(Intent.serializer(), intent)}"
            }
            return """
                |You are a little guy in a small top-down grid world. Each turn you choose one action. The game carries it out, then shows you a new observation that says how it went.
                |
                |Goal: $goal
                |
                |How the world works:
                |- Positions are tiles. x grows east and y grows south, so north is y = 0. Your own x and y have fractions: 3.5 is the middle of tile 3.
                |- Time is in ticks: $tps ticks = 1 second of game time.
                |- move_to finds a path around walls for you and walks ${(RoomWorld.SPEED * tps).toInt()} tiles a second. Moving to something that blocks the way, like a locked door, stops on a free tile beside it.
                |- pick_up and use only reach your own tile and the 8 tiles around it, so move next to a thing first.
                |- An action that can't be done fails at once, and lastResult says why. Read it and try something else rather than repeating it.
                |- You choose your next action when the current one has finished.
                |
                |Actions (the "intent" field), each with an example:
                |$actions
                |
                |Reply with a JSON object {"thought": ..., "intent": ...}. "thought" is your reasoning in a sentence or two; "intent" is the one action to take now. Use only ids from the observation: an id in "nearby", or an item id in your inventory.
                |
                |The observation is JSON:
                |- self: your id, position (x, y), facing, and inventory (ids of the items you hold)
                |- nearby: what you can see within ${RoomWorld.VIEW_RADIUS.toInt()} tiles, nearest first: id, kind, tile x and y, direction and distance from you, and state (a door is "locked" or "open")
                |- localMap: the tiles around you, one string per row, north at the top, you at the center. Legend: ${RoomWorld.MAP_LEGEND}
                |- lastIntent and lastResult: your previous action and how it ended (succeeded, failed with a reason, or interrupted)
                |- recentEvents: the last few things that happened, with their ticks
            """.trimMargin()
        }

        private fun tag(intent: Intent): String =
            json.encodeToJsonElement(Intent.serializer(), intent).jsonObject.getValue("type").jsonPrimitive.content

        /**
         * The observation inside a request built by [build], for in-process policies that should
         * see exactly what a language model sees and nothing more.
         */
        fun observationOf(request: DecisionRequest): Observation? {
            val lines = request.messages.lastOrNull { it.role == Message.Role.User }?.content?.lines() ?: return null
            val at = lines.indexOf(OBSERVATION_HEADER)
            if (at < 0 || at + 1 >= lines.size) return null
            return json.decodeFromString(Observation.serializer(), lines[at + 1])
        }
    }
}
