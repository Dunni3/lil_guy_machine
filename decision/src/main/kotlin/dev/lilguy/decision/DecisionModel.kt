package dev.lilguy.decision

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * "Given this context, return a value of type A." Backends (OpenAI-compatible HTTP, choice
 * scoring, scripted/RL policies) and decorators (logging, caching, retry) all implement this.
 */
interface DecisionModel {
    val id: String
    val capabilities: Capabilities

    suspend fun <A> decide(request: DecisionRequest, schema: ActionSchema<A>): Decision<A>
}

data class Capabilities(
    val jsonSchema: Boolean = false,
    val toolCalls: Boolean = false,
    val logprobs: Boolean = false,
    val maxContext: Int = 8_192,
)

data class DecisionRequest(
    val system: String,
    val messages: List<Message>,
    val temperature: Double = 0.7,
    val timeout: Duration = 20.seconds,
)

data class Message(val role: Role, val content: String) {
    enum class Role { System, User, Assistant }
}

data class Decision<A>(
    val value: A,
    /** Optional "thought" field from the schema. */
    val rationale: String?,
    val usage: Usage,
    val latency: Duration,
    /** Raw model output, kept for logs and replays. */
    val raw: String,
    /** Every reply that came back, in order; the last one is [raw]. Empty for in-process models. */
    val attempts: List<Attempt> = emptyList(),
)

data class Usage(val promptTokens: Int = 0, val completionTokens: Int = 0) {
    operator fun plus(other: Usage) = Usage(promptTokens + other.promptTokens, completionTokens + other.completionTokens)
}

/** One reply from the model and, if it was rejected, why. */
data class Attempt(val raw: String, val error: String? = null)

/**
 * The model gave no usable reply. Carries what it did send, so the caller can log it before
 * falling back.
 */
class DecisionFailed(
    message: String,
    val attempts: List<Attempt> = emptyList(),
    val usage: Usage = Usage(),
    cause: Throwable? = null,
) : Exception(message, cause)
