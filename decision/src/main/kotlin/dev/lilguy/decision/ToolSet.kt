package dev.lilguy.decision

import kotlinx.serialization.json.JsonObject

/**
 * A decision as named tools, for models that call tools ([Capabilities.toolCalls]). Each tool
 * stands for one kind of reply: [Tool.reply] turns a call's arguments into the reply, which still
 * goes through the schema, so the tools only change how the choice is put, not what's accepted.
 * A backend sends these in place of the schema's own tools when a request carries them.
 */
class ToolSet(val tools: List<Tool>) {
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
