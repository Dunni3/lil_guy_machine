package dev.lilguy.decision

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.head
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.net.URLEncoder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** One page a search turned up: its [title] (for grounding, often just the site), where it is, and what it says to the query. */
data class WebSource(val title: String, val url: String, val snippet: String?)

/**
 * What one web search found: [summary], what the results say (null for a search API that only
 * lists results), the [sources], and the [queries] the search engine was sent (a grounded model
 * may reword or split the query). [usage] is the model's tokens, if a model wrote the summary.
 */
data class WebResults(
    val summary: String?,
    val sources: List<WebSource>,
    val queries: List<String>,
    val usage: Usage = Usage(),
    val latency: Duration = Duration.ZERO,
)

/** Real searches of the real web, read-only: a query in, what the results say out. */
interface WebSearch {
    val id: String

    /** Throws [DecisionFailed] when the search can't be made or its reply can't be read. */
    suspend fun search(query: String): WebResults
}

/**
 * `search:` in `config/endpoints.yaml`: how he searches the web, if he does. Absent, or `kind:
 * none`, and there's no search look. The key comes from [apiKeyEnv], never from the file.
 */
@Serializable
data class SearchConfig(
    /** `gemini` (a Gemini model grounded on Google Search) or `brave` (the Brave Search API); `none` turns it off. */
    val kind: String = "gemini",
    /** gemini: the model that searches and says what the results say. */
    val model: String = "gemini-3.5-flash-lite",
    @SerialName("api_key_env") val apiKeyEnv: String? = null,
    /** Overrides the API's own base URL (tests, a proxy). */
    @SerialName("base_url") val baseUrl: String? = null,
    /** Searches a game day; past it, he's told he's searched enough for today. */
    @SerialName("per_day") val perDay: Int = 20,
    /** Most sources kept from one search. */
    val results: Int = 5,
    @SerialName("timeout_s") val timeoutSeconds: Double = 30.0,
    /** gemini: Gemini 3's `thinkingLevel` ("minimal", "low", ...); unset leaves the model's default. */
    @SerialName("thinking_level") val thinkingLevel: String? = "low",
    /** gemini: caps the reply, thinking included. */
    @SerialName("max_tokens") val maxTokens: Int = 2048,
    /** Dollars per million input and output tokens (gemini), for the token report. */
    @SerialName("price_in") val priceIn: Double? = null,
    @SerialName("price_out") val priceOut: Double? = null,
    /** Dollars per search on top of the tokens: grounding and search APIs bill by the query. */
    @SerialName("price_per_search") val pricePerSearch: Double? = null,
) {
    val timeout: Duration get() = (timeoutSeconds * 1000).toLong().milliseconds

    val off: Boolean get() = kind == "none"
}

object WebSearches {
    /** The search [config] describes; throws when its key isn't set or its kind is unknown. [env] is the only place a key comes from. */
    fun create(config: SearchConfig, env: (String) -> String? = System::getenv): WebSearch {
        val key = config.apiKeyEnv?.let { variable ->
            env(variable)?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("web search needs an API key: set $variable in the environment")
        }
        return when (config.kind) {
            "gemini" -> GeminiSearch(config, key ?: throw IllegalArgumentException("gemini search needs api_key_env"))
            "brave" -> BraveSearch(config, key ?: throw IllegalArgumentException("brave search needs api_key_env"))
            else -> throw IllegalArgumentException("unknown search kind '${config.kind}'; use gemini, brave or none")
        }
    }
}

/**
 * A Gemini model with Google Search as its tool: it searches (Google picks the queries from what
 * he asked), then says what the results say, and the grounding names the pages each part came
 * from. The pages' links are Google's redirects; each is followed once to the page it points to,
 * so the viewer sees where he really read.
 */
class GeminiSearch(private val config: SearchConfig, key: String) : WebSearch, AutoCloseable {
    override val id: String = "gemini-search:${config.model}"

    private val base = config.baseUrl ?: "https://generativelanguage.googleapis.com/v1beta"
    private val http = JsonHttp(config.timeout, apiKey = null, extraHeaders = mapOf("x-goog-api-key" to key))
    private val redirects = HttpClient(OkHttp) {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) { requestTimeoutMillis = RESOLVE.inWholeMilliseconds }
    }

    /**
     * The model decides whether to search, and now and then answers from what it knows instead:
     * a reply with no pages behind it is asked again once, then fails, so what he reads is always
     * what the web said.
     */
    override suspend fun search(query: String): WebResults {
        val started = TimeSource.Monotonic.markNow()
        var usage = Usage()
        repeat(TRIES) {
            val (r, u) = once(query)
            usage += u
            if (r != null) return r.copy(usage = usage, latency = started.elapsedNow())
        }
        throw DecisionFailed("the search model answered without searching, $TRIES times", emptyList(), usage)
    }

    /** One call: the results, or null when no page backs the reply; and what it cost. */
    private suspend fun once(query: String): Pair<WebResults?, Usage> {
        val reply = http.post("$base/models/${config.model}:generateContent", body(query))
        val usage = reply["usageMetadata"]?.jsonObject?.let { u ->
            Usage(
                (u["promptTokenCount"] as? JsonPrimitive)?.intOrNull ?: 0,
                ((u["candidatesTokenCount"] as? JsonPrimitive)?.intOrNull ?: 0) + ((u["thoughtsTokenCount"] as? JsonPrimitive)?.intOrNull ?: 0),
            )
        } ?: Usage()
        val candidate = (reply["candidates"] as? JsonArray)?.firstOrNull()?.jsonObject
            ?: throw DecisionFailed("no candidate in the search reply: ${reply.toString().take(300)}", emptyList(), usage)
        val text = (candidate["content"] as? JsonObject)?.get("parts")?.jsonArray.orEmpty()
            .filter { (it.jsonObject["thought"] as? JsonPrimitive)?.contentOrNull != "true" }
            .mapNotNull { (it.jsonObject["text"] as? JsonPrimitive)?.contentOrNull }.joinToString("").trim()
        val grounding = candidate["groundingMetadata"] as? JsonObject
        val chunks = (grounding?.get("groundingChunks") as? JsonArray).orEmpty().map { it.jsonObject["web"] as? JsonObject }
        // What each source backs up, from the supports: the summary's segments, by source.
        val backed = HashMap<Int, MutableList<String>>()
        (grounding?.get("groundingSupports") as? JsonArray).orEmpty().forEach { s ->
            val o = s.jsonObject
            val segment = (o["segment"] as? JsonObject)?.get("text")?.let { (it as? JsonPrimitive)?.contentOrNull } ?: return@forEach
            (o["groundingChunkIndices"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.intOrNull }.forEach { i -> backed.getOrPut(i) { mutableListOf() } += plain(segment) }
        }
        val listed = chunks.mapIndexedNotNull { i, web ->
            val uri = (web?.get("uri") as? JsonPrimitive)?.contentOrNull ?: return@mapIndexedNotNull null
            val title = (web["title"] as? JsonPrimitive)?.contentOrNull ?: host(uri)
            WebSource(title, uri, backed[i]?.distinct()?.joinToString(" … ")?.take(SNIPPET))
        }.take(config.results)
        if (listed.isEmpty()) return null to usage
        val sources = resolve(listed)
        val queries = (grounding?.get("webSearchQueries") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        return WebResults(plain(text).ifEmpty { null }, sources, queries) to usage
    }

    private fun body(query: String) = buildJsonObject {
        putJsonArray("contents") {
            add(buildJsonObject {
                put("role", "user")
                putJsonArray("parts") { add(buildJsonObject { put("text", PROMPT.replace("{query}", query)) }) }
            })
        }
        putJsonArray("tools") { add(buildJsonObject { putJsonObject("google_search") {} }) }
        putJsonObject("generationConfig") {
            put("temperature", 0.2)
            put("maxOutputTokens", config.maxTokens)
            config.thinkingLevel?.let { putJsonObject("thinkingConfig") { put("thinkingLevel", it) } }
        }
    }

    /** Each source's link followed once to where it points, in parallel; a link that doesn't redirect, or is slow to, stays as it is. */
    private suspend fun resolve(sources: List<WebSource>): List<WebSource> = coroutineScope {
        sources.map { s ->
            async {
                if (!s.url.contains("grounding-api-redirect")) return@async s
                val to = try {
                    withTimeoutOrNull(RESOLVE) { redirects.head(s.url).headers[HttpHeaders.Location] }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (to != null && to.startsWith("http")) s.copy(url = to) else s
            }
        }.awaitAll()
    }

    override fun close() {
        http.close()
        redirects.close()
    }

    private companion object {
        val RESOLVE = 5.seconds

        /** Calls a search may take before it fails for want of pages. */
        const val TRIES = 2

        // "Always run the search" matters: without it the lite model answered about one query in three from what it knew.
        const val PROMPT = "Use Google Search to look this up: {query}\n\n" +
            "Always run the search, even if you think you know the answer, and answer only from what the search results say. " +
            "Say what the top results say, plainly and concretely, with the names, numbers and dates that matter, in under 120 words. " +
            "No advice or opinions of your own. Plain sentences, no headings or lists."
    }
}

/** The Brave Search API: the top results as they are, title, link and the snippet under each; no summary. */
class BraveSearch(private val config: SearchConfig, key: String) : WebSearch, AutoCloseable {
    override val id: String = "brave-search"

    private val base = config.baseUrl ?: "https://api.search.brave.com/res/v1"
    private val http = JsonHttp(config.timeout, apiKey = null, extraHeaders = mapOf("X-Subscription-Token" to key))

    override suspend fun search(query: String): WebResults {
        val started = TimeSource.Monotonic.markNow()
        val q = URLEncoder.encode(query, Charsets.UTF_8)
        val reply = http.get("$base/web/search?q=$q&count=${config.results}&safesearch=moderate&text_decorations=false")
        val results = ((reply["web"] as? JsonObject)?.get("results") as? JsonArray).orEmpty().mapNotNull { r ->
            val o = r.jsonObject
            val url = (o["url"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            WebSource(
                plain((o["title"] as? JsonPrimitive)?.contentOrNull ?: host(url)), url,
                (o["description"] as? JsonPrimitive)?.contentOrNull?.let(::plain)?.take(SNIPPET),
            )
        }.take(config.results)
        return WebResults(null, results, listOf(query), latency = started.elapsedNow())
    }

    override fun close() = http.close()
}

/** Most characters of one source's snippet. */
private const val SNIPPET = 400

/** [text] without HTML tags, markdown emphasis and runs of whitespace. */
internal fun plain(text: String): String = text
    .replace(Regex("<[^>]+>"), "")
    .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
    .replace(Regex("\\*\\*|__"), "")
    .replace(Regex("\\s+"), " ")
    .trim()

private fun host(url: String): String = runCatching { URI(url).host?.removePrefix("www.") }.getOrNull() ?: url
