package dev.lilguy.agent.web

import dev.lilguy.decision.WebSearch
import dev.lilguy.decision.WebSource
import dev.lilguy.sim.Observation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * One time he searched the web: what he typed, what came back ([summary], [sources]) or why
 * nothing did ([error]), and, once he's chosen, the [thought] he had as he chose and what he did
 * [then]. [refused] when he'd already searched as often as a day allows, so nothing was sent.
 */
@Serializable
data class Search(
    val tick: Long,
    val day: Int?,
    val time: String?,
    val query: String,
    val searcher: String,
    val summary: String? = null,
    val sources: List<Source> = emptyList(),
    /** What the search engine was sent, when it isn't just [query]. */
    val queries: List<String> = emptyList(),
    val error: String? = null,
    val refused: Boolean = false,
    @SerialName("latency_ms") val latencyMs: Long = 0,
    val thought: String? = null,
    val then: String? = null,
) {
    @Serializable
    data class Source(val title: String, val url: String, val snippet: String? = null)

    val found: Boolean get() = error == null && !refused && (summary != null || sources.isNotEmpty())

    /** What the look answers: what the results say and where from, for the deciding model. */
    fun answer(): String = when {
        refused -> error ?: "You've searched enough for today."
        error != null -> "The search didn't work ($error)."
        !found -> "Nothing came up."
        else -> buildString {
            summary?.let { appendLine(it) }
            if (sources.isNotEmpty()) {
                appendLine(if (summary != null) "From:" else "Results:")
                sources.forEach { s -> appendLine("- ${s.title}" + (s.snippet?.takeIf { summary == null }?.let { ": $it" } ?: "")) }
            }
        }.trimEnd()
    }

    /** The search in his words, for his memory: what he looked up and what he found. */
    fun remembered(): String? {
        if (!found) return null
        val what = summary ?: sources.joinToString("; ") { s -> s.title + (s.snippet?.let { ": $it" } ?: "") }
        return "I looked up \"$query\" online: ${what.take(MEMORY_CHARS)}"
    }

    private companion object {
        const val MEMORY_CHARS = 700
    }
}

/**
 * His searches of the real web: [web] does them, at most [perDay] a game day (a search costs
 * money, and he could search every turn), each bounded by [timeout].
 */
class Searcher(
    private val web: WebSearch,
    val perDay: Int = 20,
    private val timeout: Duration = 45.seconds,
) {
    val id: String get() = web.id

    /** Searches so far on [day], to hold him to [perDay]; checkpoints carry it over through the search log. */
    private val counted = HashMap<Int?, Int>()

    /** Counts [searches] toward their days, as a restored log does. */
    fun counted(searches: List<Search>) {
        counted.clear()
        searches.filter { !it.refused }.forEach { counted.merge(it.day, 1, Int::plus) }
    }

    suspend fun search(query: String, obs: Observation): Search {
        val day = obs.clock?.day
        val base = Search(obs.tick, day, obs.clock?.time, query.trim(), web.id)
        if (base.query.isEmpty()) return base.copy(error = "the search was empty", refused = true)
        if ((counted[day] ?: 0) >= perDay) return base.copy(error = "You've already searched $perDay times today; that's enough until tomorrow.", refused = true)
        counted.merge(day, 1, Int::plus)
        val started = TimeSource.Monotonic.markNow()
        return try {
            val r = withTimeout(timeout) { web.search(base.query) }
            base.copy(
                summary = r.summary,
                sources = r.sources.map { it.source() },
                queries = r.queries.filter { it != base.query },
                latencyMs = r.latency.inWholeMilliseconds,
            )
        } catch (e: TimeoutCancellationException) {
            base.copy(error = "timed out after $timeout", latencyMs = started.elapsedNow().inWholeMilliseconds)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            base.copy(error = e.message ?: e.toString(), latencyMs = started.elapsedNow().inWholeMilliseconds)
        }
    }

    private fun WebSource.source() = Search.Source(title, url, snippet)
}
