package dev.lilguy.decision

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * JSON over HTTP for the backends: bearer auth, and 429 / 5xx (including 529 "overloaded")
 * retried with backoff 1 s, 2 s, 4 s. Anything else fails at once as [DecisionFailed].
 */
internal class JsonHttp(timeout: Duration, private val apiKey: String?) : AutoCloseable {
    private val client = HttpClient(OkHttp) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = timeout.inWholeMilliseconds
            socketTimeoutMillis = timeout.inWholeMilliseconds
        }
    }

    /** [attempts] and [usage] so far go into any [DecisionFailed], for the log. */
    suspend fun post(url: String, body: JsonObject, attempts: List<Attempt> = emptyList(), usage: Usage = Usage()): JsonObject {
        var backoff = 1.seconds
        while (true) {
            val (status, text) = try {
                val response = client.post(url) {
                    apiKey?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                    setBody(TextContent(body.toString(), ContentType.Application.Json))
                }
                response.status to response.bodyAsText()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw DecisionFailed("request to $url failed: $e", attempts.toList(), usage, e)
            }
            if (status.isSuccess()) {
                return try {
                    Json.parseToJsonElement(text).jsonObject
                } catch (e: Exception) {
                    throw DecisionFailed("HTTP ${status.value} with a body that isn't a JSON object: ${text.take(300)}", attempts.toList(), usage, e)
                }
            }
            val retryable = status.value == 429 || status.value >= 500
            if (!retryable || backoff > MAX_BACKOFF / 2) {
                throw DecisionFailed("HTTP ${status.value}: ${text.take(500)}", attempts.toList(), usage)
            }
            delay(backoff)
            backoff *= 2
        }
    }

    override fun close() = client.close()

    companion object {
        /** Upper bound on the time spent backing off in one decision. */
        val MAX_BACKOFF = 8.seconds
    }
}
