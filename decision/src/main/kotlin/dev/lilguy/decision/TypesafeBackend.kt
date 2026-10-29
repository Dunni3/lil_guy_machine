package dev.lilguy.decision

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.TimeSource

/**
 * TypeSafe's System One API (`POST /v1/systemone`, docs.typesafe.ai), whose models (Jev) answer
 * typed questions about a `state` with calibrated probabilities instead of writing text. A
 * decision here is one Choice question over [DecisionRequest.choices]: each option's label is a
 * criteria key and its description the criteria text. The picked option's reply goes through
 * [ActionSchema.decode] like any other backend's, and the rationale is the pick's probability and
 * the runner-up, since the model writes no thought.
 *
 * [Decision.raw] is the answer object (choice, confidence, probabilities), which is what a log
 * needs to see how sure it was.
 */
class TypesafeBackend(
    private val config: EndpointConfig,
    apiKey: String?,
    override val id: String = "typesafe:${config.model}",
) : DecisionModel, AutoCloseable {
    override val capabilities = Capabilities(closedChoice = true, logprobs = true)

    private val http = JsonHttp(config.timeout, apiKey)
    private val url = config.baseUrl.trimEnd('/') + "/v1/systemone"

    override suspend fun <A> decide(request: DecisionRequest, schema: ActionSchema<A>): Decision<A> {
        val started = TimeSource.Monotonic.markNow()
        val choices = request.choices ?: throw IllegalArgumentException("$id picks from a closed set; the request has no choices")
        require(choices.options.isNotEmpty()) { "no options to choose from" }
        require(choices.options.size <= MAX_OPTIONS) { "${choices.options.size} options; System One takes at most $MAX_OPTIONS" }
        val byLabel = choices.options.associateBy { it.label }
        require(byLabel.size == choices.options.size) { "option labels must be unique" }

        val body = buildJsonObject {
            put("model", config.model)
            put("state", choices.state)
            put("questions", buildJsonObject {
                put(QUESTION, buildJsonObject {
                    put("type", "choice")
                    put("instructions", choices.instructions)
                    put("criteria", buildJsonObject { choices.options.forEach { put(it.label, it.description) } })
                })
            })
        }
        val response = http.post(url, body)
        val usage = (response["usage"] as? JsonObject).let { u ->
            Usage(
                promptTokens = (u?.get("input_tokens") as? JsonPrimitive)?.intOrNull ?: 0,
                completionTokens = (u?.get("output_tokens") as? JsonPrimitive)?.intOrNull ?: 0,
            )
        }
        val answer = response["answers"]?.jsonObject?.get(QUESTION) as? JsonObject
            ?: throw DecisionFailed("no answer to '$QUESTION' in ${response.toString().take(300)}", usage = usage)
        val raw = answer.toString()
        val label = answer["choice"]?.jsonPrimitive?.content
        val option = byLabel[label]
            ?: throw DecisionFailed("picked '$label', which isn't one of the options", listOf(Attempt(raw, "unknown option")), usage)

        val value = try {
            schema.decode(option.reply)
        } catch (e: ActionSchema.InvalidReply) {
            // The options are built from the same observation as the schema, so this is a bug.
            throw DecisionFailed("option '$label' doesn't fit the schema: ${e.message}", listOf(Attempt(raw, e.message)), usage)
        }
        return Decision(value, rationaleOf(answer, label!!), usage, started.elapsedNow(), raw, listOf(Attempt(raw)))
    }

    /** "pick_up:key (p 0.82, confidence 0.71, next move_to:door 0.10)" */
    private fun rationaleOf(answer: JsonObject, label: String): String {
        val probabilities = (answer["probabilities"] as? JsonObject)
            ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.doubleOrNull?.let { k to it } }
            ?.sortedByDescending { it.second }
            .orEmpty()
        val p = probabilities.firstOrNull { it.first == label }?.second
        val confidence = (answer["confidence"] as? JsonPrimitive)?.doubleOrNull
        val next = probabilities.firstOrNull { it.first != label }
        val details = listOfNotNull(
            p?.let { "p %.2f".format(it) },
            confidence?.let { "confidence %.2f".format(it) },
            next?.let { "next ${it.first} %.2f".format(it.second) },
        )
        return if (details.isEmpty()) label else "$label (${details.joinToString(", ")})"
    }

    override fun close() = http.close()

    companion object {
        /** System One's limit on options in one Choice question. */
        const val MAX_OPTIONS = 255
        private const val QUESTION = "next_action"
    }
}
