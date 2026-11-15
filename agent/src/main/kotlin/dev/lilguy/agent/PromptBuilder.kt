package dev.lilguy.agent

import dev.lilguy.decision.ChoiceSet
import dev.lilguy.decision.DecisionRequest
import dev.lilguy.decision.Message
import dev.lilguy.decision.ToolCall
import dev.lilguy.decision.ToolSet
import dev.lilguy.sim.Intent
import dev.lilguy.sim.IntentStatus
import dev.lilguy.sim.Observation
import dev.lilguy.sim.GridWorld
import dev.lilguy.sim.RoomWorld
import dev.lilguy.sim.World
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Turns an observation plus recent memory into a request. The system prompt says how the world
 * works, the action space with an example of each action, the reply format and what the
 * observation fields mean; it opens with who he is, which changes when his self-model does (once
 * a night), so it's read from [systemFor] per request. The user message is the observation as one
 * line of JSON, then what he recalls (memories and earlier thoughts), his latest thought, and the
 * recent intents with how each ended (and, for a think, what he thought), each stamped by [stamp].
 *
 * [choices] builds the same decision as a closed set, for models that pick, with his self-model
 * ([self]) in its state. [toolTurn] builds it as tools to call, for models that call them; null in
 * rooms that don't. [persona] is who he is, for authored ops (thoughts, art); null in rooms
 * without one.
 */
class PromptBuilder(
    private val systemFor: () -> String,
    private val timeout: Duration,
    private val choices: (Observation, List<Memory.Entry>) -> ChoiceSet,
    private val stamp: (Long) -> String = { "tick $it" },
    val persona: (() -> String)? = null,
    private val self: () -> String? = { null },
    /** The observation as the deciding model sees it (what it can choose among); unchanged by default. */
    private val view: (Observation) -> Observation = { it },
    private val toolTurn: ToolTurn? = null,
) {
    /**
     * The decision as a conversation with tools: [system] says how his life works and that he
     * acts by calling one, [tools] are what he can do in an observation (as [view] shows it),
     * [status] is that observation as the turn's message shows it, and [call] is the call an
     * earlier intent stands for, so his recent actions come back as his own tool calls.
     */
    class ToolTurn(
        val system: () -> String,
        val tools: (Observation) -> ToolSet,
        val status: (Observation) -> Observation,
        val call: (Intent) -> Pair<String, JsonObject>,
        /** A line before the closing instruction, when there's something to point out (that he hasn't searched today). */
        val note: (Observation) -> String? = { null },
    )

    /** A fixed system prompt and persona. */
    constructor(
        system: String,
        timeout: Duration,
        choices: (Observation, List<Memory.Entry>) -> ChoiceSet,
        stamp: (Long) -> String = { "tick $it" },
        persona: String? = null,
    ) : this({ system }, timeout, choices, stamp, persona?.let { p -> { p } })

    /** The key-and-door room: [goal] in the system prompt and the key-and-door choice set. */
    constructor(goal: String, timeout: Duration = 20.seconds) :
        this(systemPrompt(goal), timeout, { obs, recent -> actionChoices(obs, goal, recent) })

    /** The system prompt as it stands now. */
    val system: String get() = systemFor()

    fun stampOf(tick: Long): String = stamp(tick)

    /**
     * [closedChoice] adds the same decision as a set of options, for models that pick. [memories]
     * are what long-term memory recalled for this decision (memories and earlier thoughts);
     * [thought] is his latest thought ("stamp: text"), shown until the next one replaces it.
     * [found] is what he found out by asking how his world works, his latest answers ("stamp: I asked ...: ...").
     * [reflections] are what he concluded the latest times he stepped back ("stamp: text"), oldest first.
     */
    fun build(
        observed: Observation,
        recent: List<Memory.Entry>,
        closedChoice: Boolean = false,
        /** Put it as tools to call, when this room can ([toolTurn]). */
        tools: Boolean = false,
        memories: List<String> = emptyList(),
        thought: String? = null,
        found: List<String> = emptyList(),
        reflections: List<String> = emptyList(),
    ): DecisionRequest {
        val obs = view(observed)
        // His latest thought is the last call's in the history, so it isn't shown again.
        if (tools && toolTurn != null) return toolRequest(toolTurn, obs, recent, memories, null, found, reflections)
        val history = recent.joinToString("\n") { e ->
            "${stamp(e.tick)}: ${json.encodeToString(Intent.serializer(), e.intent)} -> ${e.result?.let(::describe) ?: "running"}" +
                (e.thought?.let { "; you thought: \"$it\"" } ?: "")
        }
        val user = buildString {
            appendLine(OBSERVATION_HEADER)
            appendLine(json.encodeToString(Observation.serializer(), obs))
            recall(memories, thought, found, reflections)
            if (history.isNotEmpty()) {
                appendLine("Your recent actions, oldest first:")
                appendLine(history)
            }
            append(ASK)
        }
        return DecisionRequest(
            system = system,
            messages = listOf(Message(Message.Role.User, user)),
            timeout = timeout,
            choices = if (closedChoice) choices(obs, recent)?.let { withRecall(it, memories, thought, self(), found, reflections) } else null,
        )
    }

    /**
     * His recent actions as calls he made, each answered by how it ended, then one message with how
     * things stand (the observation as [ToolTurn.status] shows it, and what he recalls).
     */
    private fun toolRequest(turn: ToolTurn, obs: Observation, recent: List<Memory.Entry>, memories: List<String>, thought: String?, found: List<String>, reflections: List<String>): DecisionRequest {
        val messages = mutableListOf<Message>()
        if (recent.isNotEmpty()) {
            messages += Message(Message.Role.User, HISTORY_HEADER)
            recent.forEachIndexed { i, e ->
                val (name, args) = turn.call(e.intent)
                // What he thought as he chose it is his own words, said before the call.
                val call = ToolCall("call_${e.tick}_$i", name, args)
                val ended = e.result?.let(::describe) ?: "still going"
                val result = "Started ${stamp(e.tick)}. " + (e.outcome?.let { "$ended: $it" } ?: ended) + (e.thought?.let { " You thought: \"$it\"" } ?: "")
                messages += Message(Message.Role.Assistant, e.reason.orEmpty(), call)
                messages += Message(Message.Role.Tool, result, call)
            }
        }
        val tools = turn.tools(obs)
        val user = buildString {
            appendLine(STATUS_HEADER)
            appendLine(statusJson.encodeToString(Observation.serializer(), turn.status(obs)))
            recall(memories, thought, found, reflections)
            turn.note(obs)?.let { appendLine(it) }
            append(
                when {
                    tools.ponder != null && tools.looks.isEmpty() -> THINK
                    tools.ponder != null -> THINK_LOOK
                    tools.looks.isEmpty() -> CALL
                    else -> LOOK_THEN_CALL
                },
            )
        }
        messages += Message(Message.Role.User, user)
        return DecisionRequest(system = turn.system(), messages = messages, timeout = timeout, tools = tools)
    }

    private fun StringBuilder.recall(memories: List<String>, thought: String?, found: List<String>, reflections: List<String>) {
        if (memories.isNotEmpty()) {
            appendLine(MEMORY_HEADER)
            memories.forEach { appendLine("- $it") }
        }
        if (reflections.isNotEmpty()) {
            appendLine(REFLECTION_HEADER)
            reflections.forEach { appendLine("- $it") }
        }
        if (thought != null) {
            appendLine(THOUGHT_HEADER)
            appendLine("- $thought")
        }
        if (found.isNotEmpty()) {
            appendLine(FOUND_HEADER)
            found.forEach { appendLine("- $it") }
        }
    }

    /** Adds how he sees himself, what he remembers and his latest thought to the state a picking model judges the options against. */
    private fun withRecall(set: ChoiceSet, memories: List<String>, thought: String?, self: String?, found: List<String>, reflections: List<String>): ChoiceSet {
        var state = set.state
        var instructions = set.instructions
        if (self != null) {
            state = JsonObject(state + ("self_model" to JsonPrimitive(self)))
            instructions += " `state.self_model` is how he sees himself, in his own words, revised as he lives: what he cares about and is working toward. Unless his needs won't allow it, choose in line with his aims."
        }
        if (memories.isNotEmpty()) {
            state = JsonObject(state + ("memories" to JsonArray(memories.map(::JsonPrimitive))))
            instructions += " `state.memories` is what he remembers from earlier and thoughts he had, oldest first."
        }
        if (reflections.isNotEmpty()) {
            state = JsonObject(state + ("reflections" to JsonArray(reflections.map(::JsonPrimitive))))
            instructions += " `state.reflections` is what he concluded the latest times he stepped back from his days, oldest first: the patterns and lessons he has drawn. Choose in their light."
        }
        if (thought != null) {
            state = JsonObject(state + ("latest_thought" to JsonPrimitive(thought)))
            instructions += " `state.latest_thought` is what he decided the last time he stopped to think: his plan for now. Unless his needs won't allow it, choose what carries that plan forward."
        }
        if (found.isNotEmpty()) {
            state = JsonObject(state + ("found_out" to JsonArray(found.map(::JsonPrimitive))))
            instructions += " `state.found_out` is what he found out lately by asking how his world works or looking things up online: questions he asked and their answers, oldest first."
        }
        return set.copy(state = state, instructions = instructions)
    }

    companion object {
        private const val OBSERVATION_HEADER = "Observation:"
        const val MEMORY_HEADER = "What you remember from earlier (memories, and thoughts you had), oldest first:"
        const val REFLECTION_HEADER = "What you concluded the latest times you stepped back from your days (patterns and lessons you've drawn), oldest first:"
        const val THOUGHT_HEADER = "What you decided the last time you stopped to think (your plan for now):"
        const val FOUND_HEADER = "What you found out lately, by asking how your world works or looking things up online, oldest first:"
        private const val ASK = "Choose your next action."
        private const val STATUS_HEADER = "How things stand now:"
        private const val HISTORY_HEADER = "Your recent actions, oldest first, are the calls below; each result says when it started and how it ended."
        private const val CALL = "Call one tool: what you do next."
        private const val LOOK_THEN_CALL = "If something you'd remember, or don't know about how your world works, would change what you do, look first. Then call one tool that acts: what you do next."
        private const val THINK = "Think it over, and act when you know what you want."
        private const val THINK_LOOK = "Think, remember or act: say what you're thinking, recall something or ask how something works, or do what you want to do next."

        /**
         * His situation, for authored ops: the request's last user message without the closing ask,
         * and, when the request put his recent actions as tool calls, those as lines first.
         */
        fun situationOf(request: DecisionRequest): String {
            val now = request.messages.lastOrNull { it.role == Message.Role.User }?.content?.removeSuffix(ASK)?.removeSuffix(CALL)?.removeSuffix(LOOK_THEN_CALL)?.removeSuffix(THINK)?.removeSuffix(THINK_LOOK)?.trimEnd().orEmpty()
            val calls = request.messages.filter { it.role == Message.Role.Tool && it.call != null }.map { m ->
                val c = m.call!!
                "- ${c.name} ${JsonObject(c.arguments - BedroomTools.THOUGHT)}: ${m.content}"
            }
            return if (calls.isEmpty()) now else "Your recent actions, oldest first:\n${calls.joinToString("\n")}\n$now"
        }
        private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        /** The tool turn's status leaves out what's null. */
        private val statusJson = Json { encodeDefaults = true; explicitNulls = false }
        /** Examples show every field, nulls included, the way a strict schema wants them. */
        internal val exampleJson = Json { encodeDefaults = true; explicitNulls = true }

        internal fun describe(status: IntentStatus): String = when (status) {
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
                |- move_to finds a path around walls for you and walks ${(GridWorld.SPEED * tps).toInt()} tiles a second. Moving to something that blocks the way, like a locked door, stops on a free tile beside it.
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

        internal fun tag(intent: Intent): String =
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
