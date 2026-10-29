package dev.lilguy.agent

import dev.lilguy.decision.DecisionRequest
import dev.lilguy.decision.Message
import dev.lilguy.sim.Intent
import dev.lilguy.sim.Observation
import dev.lilguy.sim.RoomWorld
import kotlinx.serialization.json.Json

/** Turns an observation plus recent memory into a request. MVP: a goal string and JSON. */
class PromptBuilder(private val goal: String) {
    fun build(obs: Observation, recent: List<Memory.Entry>): DecisionRequest {
        val history = recent.joinToString("\n") { "tick ${it.tick}: ${json.encodeToString(Intent.serializer(), it.intent)}" }
        val user = buildString {
            appendLine(OBSERVATION_HEADER)
            appendLine(json.encodeToString(Observation.serializer(), obs))
            if (history.isNotEmpty()) {
                appendLine("Your recent intents:")
                appendLine(history)
            }
            append("Choose your next intent.")
        }
        return DecisionRequest(
            system = "You control a character in a small world. Goal: $goal\n" +
                "Local map legend: ${RoomWorld.MAP_LEGEND}.",
            messages = listOf(Message(Message.Role.User, user)),
        )
    }

    companion object {
        private const val OBSERVATION_HEADER = "Observation:"
        private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

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
