package dev.lilguy.agent

import dev.lilguy.decision.ActionSchema
import dev.lilguy.decision.ToolSet
import dev.lilguy.sim.ActivityOption
import dev.lilguy.sim.GridWorld
import dev.lilguy.sim.Intent
import dev.lilguy.sim.Observation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The bedroom's decision as tools, for models that call them: one tool per verb he can do now,
 * named by its id and described by what it takes and gives, with `what` and `on` as enums of the
 * things he could do it with; `buy` while there's a shop to buy from; and `wait`. Every tool takes
 * a `reason` first, which becomes the reply's `thought`. Locked verbs aren't tools: the status
 * message lists them with why ([view]).
 */
object BedroomTools {
    const val REASON = "reason"

    /** What an argument is set to when the thing can be done without it. */
    const val NONE = "none"

    /** The tools for [obs], as models deciding see it ([BedroomPrompt.deciding]). */
    fun of(obs: Observation, ticksPerMinute: Int): ToolSet {
        val open = obs.activities.orEmpty().filter { it.locked == null }
        val tools = buildList {
            open.groupBy { it.id }.forEach { (id, ways) ->
                require(id != "buy" && id != "wait") { "a verb named \"$id\" would hide the $id tool" }
                add(verb(id, ways))
            }
            obs.shop.orEmpty().filter { it.affordable }.takeIf { it.isNotEmpty() }?.let { forSale ->
                add(ToolSet.Tool(
                    name = "buy",
                    description = "Buy one thing from `shop` that you can afford. It's delivered through the hatch and you pay when it arrives.",
                    parameters = parameters(mapOf("item" to forSale.map { it.id })),
                ) { args -> reply(args, Intent.Buy(string(args, "item"))) })
            }
            val most = GridWorld.MAX_WAIT / ticksPerMinute
            add(ToolSet.Tool(
                name = "wait",
                description = "Do nothing for a while, up to $most game minutes.",
                parameters = parameters(emptyMap(), minutes = 1..most),
            ) { args ->
                val minutes = (args["minutes"] as? JsonPrimitive)?.intOrNull ?: throw ActionSchema.InvalidReply("wait needs `minutes`, a whole number")
                if (minutes !in 1..most) throw ActionSchema.InvalidReply("wait takes 1 to $most minutes")
                reply(args, Intent.Wait(minutes * ticksPerMinute))
            })
        }
        return ToolSet(tools)
    }

    /**
     * [obs] as the turn's status message shows it: no map, coordinates or furniture (the verbs walk
     * him there), only the locked activities (the open ones are the tools), and no last intent,
     * result or outcomes among the recent events (his earlier calls and their results say those).
     */
    fun view(obs: Observation): Observation =
        obs.copy(
            nearby = emptyList(),
            localMap = emptyList(),
            activities = obs.activities?.filter { it.locked != null },
            lastIntent = null,
            lastResult = null,
            recentEvents = obs.news,
        )

    /** One verb, done any of [ways]: each a way to fill its arguments, with what it takes and gives. */
    private fun verb(id: String, ways: List<ActivityOption>): ToolSet.Tool {
        fun choices(of: (ActivityOption) -> String?): List<String>? {
            val named = ways.mapNotNull(of).distinct()
            if (named.isEmpty()) return null
            return if (ways.any { of(it) == null }) named + NONE else named
        }
        val what = choices { it.what }
        val on = choices { it.on }
        fun payoff(w: ActivityOption) = listOfNotNull(w.takes?.let { "takes $it" }, w.gives?.let { "gives $it" }).joinToString("; ")
        val description = if (ways.size == 1 && what == null && on == null) {
            payoff(ways[0]).replaceFirstChar { it.uppercase() } + "."
        } else {
            ways.joinToString("\n") { w ->
                val with = listOfNotNull(w.what?.let { "what=$it" }, w.on?.let { "on=$it" }).joinToString(", ").ifEmpty { "on its own" }
                "- $with: ${payoff(w)}."
            }
        }
        val args = buildMap {
            what?.let { put("what", it) }
            on?.let { put("on", it) }
        }
        return ToolSet.Tool(id, description, parameters(args)) { a ->
            val w = optional(a, "what")
            val o = optional(a, "on")
            if (ways.none { it.what == w && it.on == o }) {
                val can = ways.joinToString("; ") { listOfNotNull(it.what?.let { v -> "what=$v" }, it.on?.let { v -> "on=$v" }).joinToString(", ").ifEmpty { "with nothing" } }
                throw ActionSchema.InvalidReply("$id can't be done with what=${w ?: NONE}, on=${o ?: NONE}; it can be done $can")
            }
            reply(a, Intent.Do(id, w, o))
        }
    }

    /** `reason`, then each of [enums] as a required string enum, then `minutes` when [minutes] is set. */
    private fun parameters(enums: Map<String, List<String>>, minutes: IntRange? = null) = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject(REASON) {
                put("type", "string")
                put("description", "Why you're doing this, in a sentence or two, in your own words.")
            }
            enums.forEach { (name, values) ->
                putJsonObject(name) {
                    put("type", "string")
                    putJsonArray("enum") { values.forEach { add(it) } }
                    if (NONE in values) put("description", "\"$NONE\" to do it without one.")
                }
            }
            minutes?.let { r ->
                putJsonObject("minutes") {
                    put("type", "integer")
                    put("minimum", r.first)
                    put("maximum", r.last)
                }
            }
        }
        putJsonArray("required") {
            add(REASON)
            enums.keys.forEach { add(it) }
            if (minutes != null) add("minutes")
        }
        put("additionalProperties", false)
    }

    private val json = Json { encodeDefaults = true; explicitNulls = true }

    private fun reply(args: JsonObject, intent: Intent): JsonObject =
        json.encodeToJsonElement(AgentReply.serializer(), AgentReply(string(args, REASON, ""), intent)).jsonObject

    private fun string(args: JsonObject, name: String, default: String? = null): String =
        (args[name] as? JsonPrimitive)?.contentOrNull ?: default ?: throw ActionSchema.InvalidReply("missing `$name`")

    private fun optional(args: JsonObject, name: String): String? =
        (args[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it != NONE && it.isNotBlank() }

    /** The arguments a call to do [intent] would have had, without its reason: for his earlier calls in the history. */
    fun call(intent: Intent, ticksPerMinute: Int): Pair<String, JsonObject> = when (intent) {
        is Intent.Do -> intent.activity to buildJsonObject {
            intent.what?.let { put("what", it) }
            intent.on?.let { put("on", it) }
        }
        is Intent.Buy -> "buy" to buildJsonObject { put("item", intent.item) }
        is Intent.Wait -> "wait" to buildJsonObject { put("minutes", maxOf(1, intent.ticks / ticksPerMinute)) }
        else -> PromptBuilder.tag(intent) to json.encodeToJsonElement(Intent.serializer(), intent).jsonObject.let { JsonObject(it - "type") }
    }
}
