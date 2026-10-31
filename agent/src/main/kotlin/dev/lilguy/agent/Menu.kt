package dev.lilguy.agent

import dev.lilguy.decision.ActionSchema
import dev.lilguy.decision.ChoiceSet
import dev.lilguy.decision.Decision
import dev.lilguy.decision.DecisionModel
import dev.lilguy.decision.DecisionRequest
import dev.lilguy.decision.Message
import dev.lilguy.decision.SchemaHints
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration

/**
 * One pick from a closed set, put to any model. A picking model (TypeSafe's Jev) gets it as a
 * [ChoiceSet] over [state]; a text model gets the state and the options in a prompt and replies
 * `{"pick": label}`, with the schema holding it to the labels. Either way the answer is one
 * option's label, so a loop that builds its options as it goes (a drawing, stroke by stroke)
 * never parses free text and runs on the cheap model.
 */
object Menu {
    @Serializable
    @SerialName("pick")
    data class Pick(val pick: String)

    class Option(
        /** Short unique key, such as "disc:top_left:small". */
        val label: String,
        /** What choosing it means, in plain words. */
        val description: String,
    )

    /** [instructions] say what the pick decides and how to read [state]; [timeout] is per call. */
    suspend fun pick(
        model: DecisionModel,
        instructions: String,
        state: JsonObject,
        options: List<Option>,
        timeout: Duration,
        temperature: Double = 0.7,
    ): Decision<Pick> {
        require(options.isNotEmpty()) { "a menu needs at least one option" }
        require(options.distinctBy { it.label }.size == options.size) { "menu labels must be unique" }
        val labels = options.map { it.label }
        val schema = ActionSchema.of(
            "pick",
            Pick.serializer(),
            SchemaHints(
                descriptions = mapOf("pick.pick" to "The label of the one option chosen."),
                enums = mapOf("pick.pick" to labels),
            ),
        )
        val choices = ChoiceSet(
            instructions = instructions,
            state = state,
            options = options.map { ChoiceSet.Option(it.label, it.description, buildJsonObject { put("pick", it.label) }) },
        )
        val user = buildString {
            appendLine("State:")
            appendLine(state.toString())
            appendLine()
            appendLine("Options, each as `label: what it means`:")
            options.forEach { appendLine("- ${it.label}: ${it.description}") }
            append("Reply with a JSON object {\"pick\": \"<label>\"} naming one option's label, and nothing else.")
        }
        val request = DecisionRequest(
            system = instructions,
            messages = listOf(Message(Message.Role.User, user)),
            temperature = temperature,
            timeout = timeout,
            choices = choices,
        )
        return model.decide(request, schema)
    }
}
