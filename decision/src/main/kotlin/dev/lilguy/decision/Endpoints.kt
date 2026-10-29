package dev.lilguy.decision

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * `config/endpoints.yaml`: named model endpoints and which one each character uses. API keys are
 * never in this file; [EndpointConfig.apiKeyEnv] names the environment variable that holds one.
 */
@Serializable
data class EndpointsFile(
    val endpoints: Map<String, EndpointConfig> = emptyMap(),
    val agents: List<AgentConfig> = emptyList(),
) {
    /** The endpoint the first character is configured to use, if any. */
    val defaultEndpoint: String? get() = agents.firstOrNull()?.endpoint
}

@Serializable
data class EndpointConfig(
    /** Backend: `openai_compat`, or `typesafe` (System One; picks from a closed set). */
    val kind: String = "openai_compat",
    @SerialName("base_url") val baseUrl: String,
    val model: String,
    /** Name of the environment variable holding the key; omit for servers without auth. */
    @SerialName("api_key_env") val apiKeyEnv: String? = null,
    /** How to get typed output; see [StructuredOutput]. */
    @SerialName("structured_output") val structuredOutput: StructuredOutput = StructuredOutput.JsonSchema,
    /** Ask the server to enforce the schema exactly (OpenAI's `strict`). */
    val strict: Boolean = true,
    /** Overrides the request's temperature. */
    val temperature: Double? = null,
    /** Some reasoning models reject any temperature; set false to leave it out. */
    @SerialName("send_temperature") val sendTemperature: Boolean = true,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    /** Passed through as `reasoning_effort` when set (OpenAI reasoning models). */
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    /** Per HTTP call. */
    @SerialName("timeout_s") val timeoutSeconds: Double = 30.0,
    /** Replies that fail validation are retried with the error this many times. */
    @SerialName("max_retries") val maxRetries: Int = 2,
    /** `required` forces a tool call; some servers only accept `auto`. */
    @SerialName("tool_choice") val toolChoice: String = "required",
) {
    val timeout: Duration get() = (timeoutSeconds * 1000).toLong().milliseconds

    /** Long enough for every try plus the backoff between them; for the agent loop's timeout. */
    val decisionTimeout: Duration get() = timeout * (maxRetries + 1) + JsonHttp.MAX_BACKOFF
}

@Serializable
enum class StructuredOutput {
    /** `response_format: json_schema`; the server constrains decoding. */
    @SerialName("json_schema") JsonSchema,
    /** One tool per intent variant; validated anyway. */
    @SerialName("tools") Tools,
    /** Schema in the prompt; parse, validate, retry with the error. */
    @SerialName("text") Text,
}

@Serializable
data class AgentConfig(
    val character: String,
    val endpoint: String,
    @SerialName("decision_interval") val decisionInterval: String = "on_intent_complete",
)

object Endpoints {
    const val DEFAULT_PATH = "config/endpoints.yaml"

    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))

    fun parse(text: String): EndpointsFile = yaml.decodeFromString(EndpointsFile.serializer(), text)

    fun load(file: File = File(DEFAULT_PATH)): EndpointsFile {
        require(file.isFile) { "no endpoint config at ${file.path}; copy config/endpoints.example.yaml to config/endpoints.yaml" }
        return parse(file.readText())
    }

    /**
     * The backend for endpoint [name]. [env] looks up environment variables; it is the only place
     * a key comes from.
     */
    fun create(config: EndpointsFile, name: String, env: (String) -> String? = System::getenv): DecisionModel {
        val endpoint = config.endpoints[name]
            ?: throw IllegalArgumentException("no endpoint '$name' in the config; have ${config.endpoints.keys.sorted()}")
        val key = endpoint.apiKeyEnv?.let { variable ->
            env(variable)?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("endpoint '$name' needs an API key: set $variable in the environment")
        }
        return when (endpoint.kind) {
            "openai_compat" -> OpenAiCompatBackend(endpoint, key, id = "$name:${endpoint.model}")
            "typesafe" -> TypesafeBackend(endpoint, key, id = "$name:${endpoint.model}")
            else -> throw IllegalArgumentException("endpoint '$name' has unknown kind '${endpoint.kind}'")
        }
    }
}
