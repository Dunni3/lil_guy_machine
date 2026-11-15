package dev.lilguy.decision

import kotlinx.serialization.json.JsonObject

/**
 * A decision as named tools, for models that call tools ([Capabilities.toolCalls]). Each tool
 * stands for one kind of reply: [Tool.reply] turns a call's arguments into the reply, which still
 * goes through the schema, so the tools only change how the choice is put, not what's accepted.
 * A backend sends these in place of the schema's own tools when a request carries them.
 *
 * The same tools are offered at every step of a turn, so a server that caches the prompt's prefix
 * keeps it while the model looks and thinks.
 */
class ToolSet(
    val tools: List<Tool>,
    /** Tools that answer rather than act: a call to one is answered and put back, and the model goes on. */
    val looks: List<Look> = emptyList(),
    /**
     * Steps a turn may take without acting: each reply of only looks is one, and with [ponder] so is
     * each reply of only text. After that a look is refused, or with [ponder] the turn ends there.
     */
    val maxSteps: Int = 3,
    /**
     * Makes calling optional: a reply of only text is the model thinking, put back so it goes on;
     * when [maxSteps] run out without an action, this is the reply, given what it wrote (maybe
     * nothing). Null: every reply must call a tool.
     */
    val ponder: ((String) -> JsonObject)? = null,
    /** The argument a tool's call fills with what the model wrote, when the call leaves it out. */
    val thoughtArg: String? = null,
) {
    class Look(
        val name: String,
        val description: String,
        val parameters: JsonObject,
        /** The answer, as the tool's result; a thrown exception is answered with its message. */
        val answer: suspend (JsonObject) -> String,
    )

    class Tool(
        val name: String,
        val description: String,
        /** JSON Schema of the arguments. */
        val parameters: JsonObject,
        /** The reply this call stands for; throws [ActionSchema.InvalidReply] for arguments it can't take. */
        val reply: (JsonObject) -> JsonObject,
    )

    fun reply(name: String, arguments: JsonObject): JsonObject {
        val tool = tools.firstOrNull { it.name == name }
            ?: throw ActionSchema.InvalidReply("unknown tool \"$name\"; call one of ${tools.joinToString(", ") { it.name }}")
        return tool.reply(arguments)
    }
}

/** A call the model made: [arguments] as it sent them. [id] pairs it with its result. */
data class ToolCall(val id: String, val name: String, val arguments: JsonObject)

/** One look a model took before it acted: what it called, with what, and the answer. */
data class Looked(val name: String, val arguments: JsonObject, val result: String)
