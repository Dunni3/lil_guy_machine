package dev.lilguy.agent

import dev.lilguy.decision.DecisionRequest
import dev.lilguy.decision.Message
import dev.lilguy.sim.Intent
import dev.lilguy.sim.Observation
import kotlinx.serialization.json.Json

/** Turns an observation plus recent memory into a request. MVP: a goal string and JSON. */
class PromptBuilder(private val goal: String) {
    private val json = Json { encodeDefaults = true }

    fun build(obs: Observation, recent: List<Memory.Entry>): DecisionRequest {
        val history = recent.joinToString("\n") { "tick ${it.tick}: ${json.encodeToString(Intent.serializer(), it.intent)}" }
        val user = buildString {
            appendLine("Observation:")
            appendLine(json.encodeToString(Observation.serializer(), obs))
            if (history.isNotEmpty()) {
                appendLine("Your recent intents:")
                appendLine(history)
            }
            append("Choose your next intent.")
        }
        return DecisionRequest(
            system = "You control a character in a small 3D world. Goal: $goal",
            messages = listOf(Message(Message.Role.User, user)),
        )
    }
}
