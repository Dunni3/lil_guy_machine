package dev.lilguy.decision

import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
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
 *
 * When the options come in groups ([ChoiceSet.groups]) and the endpoint's `beam` is above 0, it
 * walks down them by [BeamSearch] instead: one request per depth, holding one Choice question per
 * path in the beam, then one over the finalists. Its raw is the finalists, the last answer and
 * every depth's probabilities.
 *
 * As a [Scorer] it sends many Score questions in one request; System One answers each on its own,
 * in parallel, so adding questions barely adds time. A request holds at most 64k tokens.
 */
class TypesafeBackend(
    private val config: EndpointConfig,
    apiKey: String?,
    override val id: String = "typesafe:${config.model}",
) : DecisionModel, Scorer, AutoCloseable {
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
        if (choices.groups.isNotEmpty() && config.beam > 0) return beamDecide(choices, schema, started)

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
        val usage = usageOf(response)
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

    private suspend fun <A> beamDecide(choices: ChoiceSet, schema: ActionSchema<A>, started: TimeSource.Monotonic.ValueTimeMark): Decision<A> {
        var usage = Usage()
        val result = BeamSearch(config.beam).pick(choices) { questions ->
            val body = buildJsonObject {
                put("model", config.model)
                put("state", choices.state)
                put("questions", buildJsonObject {
                    questions.forEach { (key, q) ->
                        put(key, buildJsonObject {
                            put("type", "choice")
                            put("instructions", q.instructions)
                            put("criteria", buildJsonObject { q.options.forEach { (label, description) -> put(label, description) } })
                        })
                    }
                })
            }
            val response = http.post(url, body)
            usage += usageOf(response)
            val answers = response["answers"] as? JsonObject
                ?: throw DecisionFailed("no answers in ${response.toString().take(300)}", usage = usage)
            questions.keys.associateWith { key -> probabilitiesOf(answers[key] as? JsonObject).toMap() }
        }
        fun probs(m: Map<String, Double>) = buildJsonObject { m.entries.sortedByDescending { it.value }.forEach { (k, v) -> put(k, v) } }
        val raw = buildJsonObject {
            put("choice", result.option.label)
            put("finalists", buildJsonObject { result.finalists.forEach { put(it.path.joinToString(" > "), it.p) } })
            put("last", probs(result.last))
            put("depths", JsonArray(result.depths.map { d -> buildJsonObject { d.forEach { (k, m) -> put(k, probs(m)) } } }))
        }.toString()
        val value = try {
            schema.decode(result.option.reply)
        } catch (e: ActionSchema.InvalidReply) {
            throw DecisionFailed("option '${result.option.label}' doesn't fit the schema: ${e.message}", listOf(Attempt(raw, e.message)), usage)
        }
        val path = result.finalists.first { it.path.last() == result.option.label }.path.dropLast(1).map { it.removePrefix("group:") }
        val rest = result.finalists.filter { it.path.last() != result.option.label }.joinToString(", ") { "${it.path.last()} %.2f".format(it.p) }
        val rationale = (path + result.option.label).joinToString(" > ") +
            (result.last[result.option.label]?.let { " (last p %.2f".format(it) + (if (rest.isNotEmpty()) ", beside $rest)" else ")") } ?: "")
        return Decision(value, rationale, usage, started.elapsedNow(), raw, listOf(Attempt(raw)))
    }

    override suspend fun score(request: ScoreRequest): ScoreResult {
        val started = TimeSource.Monotonic.markNow()
        if (request.questions.isEmpty()) return ScoreResult(emptyMap(), Usage(), started.elapsedNow())
        val body = buildJsonObject {
            put("model", config.model)
            put("state", request.state)
            put("questions", buildJsonObject {
                request.questions.forEach { (key, q) ->
                    put(key, buildJsonObject {
                        put("type", "score")
                        put("instructions", q.instructions)
                        put("criteria", JsonArray(q.criteria.map(::JsonPrimitive)))
                    })
                }
            })
        }
        val response = withTimeout(request.timeout) { http.post(url, body) }
        val usage = usageOf(response)
        val answers = response["answers"] as? JsonObject
            ?: throw DecisionFailed("no answers in ${response.toString().take(300)}", usage = usage)
        val scored = request.questions.mapNotNull { (key, q) ->
            val a = answers[key] as? JsonObject ?: return@mapNotNull null
            val score = (a["score"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            val p = a["probabilities"] as? JsonObject
            key to ScoreAnswer(
                score = score,
                levels = q.criteria.size,
                confidence = (a["confidence"] as? JsonPrimitive)?.doubleOrNull,
                probabilities = q.criteria.indices.map { i -> (p?.get(i.toString()) as? JsonPrimitive)?.doubleOrNull ?: 0.0 },
            )
        }.toMap()
        return ScoreResult(scored, usage, started.elapsedNow())
    }

    private fun usageOf(response: JsonObject) = (response["usage"] as? JsonObject).let { u ->
        Usage(
            promptTokens = (u?.get("input_tokens") as? JsonPrimitive)?.intOrNull ?: 0,
            completionTokens = (u?.get("output_tokens") as? JsonPrimitive)?.intOrNull ?: 0,
        )
    }

    /** A Choice answer's probabilities by label, most likely first. */
    private fun probabilitiesOf(answer: JsonObject?): List<Pair<String, Double>> = (answer?.get("probabilities") as? JsonObject)
        ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.doubleOrNull?.let { k to it } }
        ?.sortedByDescending { it.second }
        .orEmpty()

    /** "pick_up:key (p 0.82, confidence 0.71, next move_to:door 0.10)" */
    private fun rationaleOf(answer: JsonObject, label: String): String {
        val probabilities = probabilitiesOf(answer)
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
