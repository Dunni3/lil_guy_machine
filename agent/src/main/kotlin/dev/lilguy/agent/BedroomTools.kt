package dev.lilguy.agent

import dev.lilguy.decision.ActionSchema
import dev.lilguy.decision.ToolSet
import dev.lilguy.sim.ActivityOption
import dev.lilguy.sim.GridWorld
import dev.lilguy.sim.Intent
import dev.lilguy.sim.Observation
import dev.lilguy.sim.PutOptions
import dev.lilguy.sim.Target
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
 * things he could do it with; `buy` while there's a shop to buy from; and `wait`. Calling is optional:
 * what he writes is his thought of the moment, and a reply of only text is him thinking, put back so
 * he goes on. Every tool also takes an optional `thought`, for when he acts without writing. Locked
 * verbs aren't tools: the status message lists them with why ([view]). [looks] are `recall`,
 * `ask` and `search`, which answer and let him go on. When [MAX_STEPS] go by without an action, he's sat and
 * thought: the turn ends as the [ponder] intent.
 */
object BedroomTools {
    const val THOUGHT = "thought"

    const val LOOK_AROUND = "look_around"

    /** Tools that aren't verbs, whose names a verb can't take. */
    private val BUILT_IN = dev.lilguy.sim.CodeRules.BUILT_IN

    /** Words his thought may run to. */
    const val THOUGHT_WORDS = 80

    /** What an argument is set to when the thing can be done without it. */
    const val NONE = "none"

    /**
     * The tools for [obs], as models deciding see it ([BedroomPrompt.deciding]). [ponder] is what he
     * does when his steps run out with no action (think); null makes every reply call a tool.
     */
    fun of(obs: Observation, ticksPerMinute: Int, looks: List<ToolSet.Look> = emptyList(), ponder: Intent? = null): ToolSet {
        val open = obs.activities.orEmpty().filter { it.locked == null }
        val tools = buildList {
            // A verb named like a built-in tool would hide it; his own verbs can't be (CodeRules), and the content's aren't.
            open.groupBy { it.id }.filterKeys { it !in BUILT_IN }.forEach { (id, ways) -> add(verb(id, ways)) }
            obs.places?.takeIf { it.isNotEmpty() }?.let { places ->
                add(ToolSet.Tool(
                    name = "go",
                    description = "Walk to a place just to be there (the activities walk you where they're done on their own):\n" +
                        places.joinToString("\n") { "- ${it.id}: ${it.name}, ${it.says}; ${it.walk} min walk." },
                    parameters = parameters(mapOf("place" to places.map { it.id })),
                ) { args -> reply(args, Intent.MoveTo(Target.Place(string(args, "place")))) })
            }
            obs.nearby.takeIf { it.isNotEmpty() }?.let { seen ->
                add(ToolSet.Tool(
                    name = "walk_to",
                    description = "Walk over to something round you (`here` in how things stand) and stand beside it: to see it up close, or just to be by it. " +
                        "The activities walk you to what they use on their own. It takes only the walk.",
                    parameters = parameters(mapOf("to" to seen.map { it.id })),
                ) { args -> reply(args, Intent.MoveTo(Target.Entity(string(args, "to")))) })
            }
            obs.put?.let { add(place(it)) }
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
        return ToolSet(
            tools, looks, MAX_STEPS,
            ponder = ponder?.let { intent -> { said -> reply(buildJsonObject { put(THOUGHT, said) }, intent) } },
            thoughtArg = THOUGHT,
        )
    }

    /** Said at his turn until he has searched the web that day. */
    const val SEARCH_NOTE = "You haven't looked anything up online today. What are you wondering about: something in what you're reading, drawing or listening to, " +
        "or something you'd like to make or try? Search the internet for it before you act; what you find out is yours to use."

    /** Replies he may give in one turn without acting: each one of thinking, of looks, or both. */
    const val MAX_STEPS = 5

    /**
     * `look_around` says where he is and what's round him with [look]; `recall` searches his memories
     * and earlier thoughts with [recall]; `ask` asks how his world works with [ask]; `search` searches
     * the real web with [search]. Any is left out when null.
     */
    fun looks(
        recall: (suspend (String) -> String)?,
        ask: (suspend (String) -> String)?,
        search: (suspend (String) -> String)? = null,
        look: (() -> String)? = null,
    ): List<ToolSet.Look> = listOfNotNull(
        look?.let { l ->
            ToolSet.Look(
                LOOK_AROUND,
                "Look around you: where you are, each thing round you here (which way, how far, whether it's within reach, what you could do with it, what's too dark to make out), " +
                    "the light and the weather, and the other places you could walk to and how far. It takes no time; then choose what to do.",
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("for") {
                            put("type", "string")
                            put("description", "Only if you're looking for something: what, in a few words.")
                        }
                    }
                    put("additionalProperties", false)
                },
            ) { _ -> l() }
        },
        recall?.let { r ->
            ToolSet.Look(
                "recall",
                "Try to remember something: what happened, something you did or found out, a plan you made. It searches your memories and earlier thoughts and gives back the few that fit. It takes no time; then choose what to do.",
                text("query", "What you're trying to remember, in a few words."),
            ) { args -> r(string(args, "query")) }
        },
        ask?.let { a ->
            ToolSet.Look(
                "ask",
                "Ask how something in your world works, and someone who knows its workings answers: how to get to something you can't do yet, what something is for, what you could do that you haven't tried. It takes no time; then choose what to do.",
                text("question", "Your question, first person, under 30 words."),
            ) { args -> a(string(args, "question")) }
        },
        search?.let { s ->
            ToolSet.Look(
                "search",
                "Search the internet: the real one, outside your room. You get back what the top results say and which sites said it. " +
                    "Look up anything you're curious about in the real world: how to do something, what something is, music, art, books, ideas, what's going on. " +
                    "It's only for reading; it doesn't change anything in your room. It takes no time; then choose what to do.",
                text("query", "What to search for, as you'd type it into a search engine."),
            ) { args -> s(string(args, "query")) }
        },
    )

    private fun text(name: String, description: String) = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject(name) {
                put("type", "string")
                put("description", description)
            }
        }
        putJsonArray("required") { add(name) }
        put("additionalProperties", false)
    }

    /**
     * [obs] as the turn's status message shows it: no map or coordinates, what's round him only briefly
     * (`here`: which way and how far; `look_around` says more), only the locked activities (the open ones are the tools), and no last intent,
     * result or outcomes among the recent events (his earlier calls and their results say those).
     */
    fun view(obs: Observation): Observation =
        obs.copy(
            here = Surroundings.brief(obs),
            nearby = emptyList(),
            localMap = emptyList(),
            localMapAt = null,
            places = null,
            put = null,
            activities = obs.activities?.filter { it.locked != null },
            lastIntent = null,
            lastResult = null,
            recentEvents = obs.news,
        )

    /**
     * `place`: put one of his things at a free spot, as [Intent.Put]. The spots are listed by place,
     * on one line each, so the list stays short as the house and the land grow.
     */
    private fun place(put: PutOptions): ToolSet.Tool {
        val wall = put.wall.orEmpty()
        val description = "Put one of your things somewhere else, indoors or out: you walk to it, carry it there and set it down, which takes a few minutes besides the walks." +
            (if (wall.isNotEmpty()) " ${wall.joinToString()} go${if (wall.size == 1) "es" else ""} only in a wall spot." else "") +
            " Free spots:\n" + put.spots.joinToString("\n") { g ->
                "- ${g.place}: " + listOfNotNull(g.floor.takeIf { it.isNotEmpty() }?.joinToString(), g.wall?.let { "in the wall " + it.joinToString() }).joinToString("; ")
            }
        val spots = put.spots.flatMap { it.floor + it.wall.orEmpty() }
        return ToolSet.Tool("place", description, parameters(mapOf("what" to put.things + wall, "spot" to spots))) { args ->
            reply(args, Intent.Put(string(args, "what"), string(args, "spot")))
        }
    }

    /** One verb, done any of [ways]: each a way to fill its arguments, with what it takes and gives. */
    private fun verb(id: String, ways: List<ActivityOption>): ToolSet.Tool {
        fun choices(of: (ActivityOption) -> String?): List<String>? {
            val named = ways.mapNotNull(of).distinct()
            if (named.isEmpty()) return null
            return if (ways.any { of(it) == null }) named + NONE else named
        }
        val what = choices { it.what }
        val on = choices { it.on }
        fun payoff(w: ActivityOption) = listOfNotNull(w.where?.let { "in $it, ${w.walk} min walk from here" }, w.takes?.let { "takes $it" }, w.gives?.let { "gives $it" }).joinToString("; ")
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

    /** An optional `thought`, then each of [enums] as a required string enum, then `minutes` when [minutes] is set. */
    private fun parameters(enums: Map<String, List<String>>, minutes: IntRange? = null) = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject(THOUGHT) {
                put("type", "string")
                put("description", "Only if you haven't said it: what goes through your head as you choose this, in your own voice. First person, under $THOUGHT_WORDS words.")
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
            enums.keys.forEach { add(it) }
            if (minutes != null) add("minutes")
        }
        put("additionalProperties", false)
    }

    private val json = Json { encodeDefaults = true; explicitNulls = true }

    private fun reply(args: JsonObject, intent: Intent): JsonObject =
        json.encodeToJsonElement(AgentReply.serializer(), AgentReply(string(args, THOUGHT, ""), intent)).jsonObject

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
        is Intent.Put -> "place" to buildJsonObject {
            put("what", intent.item)
            put("spot", intent.spot)
        }
        is Intent.Wait -> "wait" to buildJsonObject { put("minutes", maxOf(1, intent.ticks / ticksPerMinute)) }
        is Intent.MoveTo -> when (val t = intent.target) {
            is Target.Place -> "go" to buildJsonObject { put("place", t.id) }
            is Target.Entity -> "walk_to" to buildJsonObject { put("to", t.id) }
            else -> PromptBuilder.tag(intent) to json.encodeToJsonElement(Intent.serializer(), intent).jsonObject.let { JsonObject(it - "type") }
        }
        else -> PromptBuilder.tag(intent) to json.encodeToJsonElement(Intent.serializer(), intent).jsonObject.let { JsonObject(it - "type") }
    }
}
