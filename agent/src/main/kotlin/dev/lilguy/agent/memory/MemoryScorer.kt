package dev.lilguy.agent.memory

import dev.lilguy.decision.ScoreQuestion
import dev.lilguy.decision.ScoreRequest
import dev.lilguy.decision.Scorer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Memory's two graded questions, put to a [Scorer] (TypeSafe's Jev) as one Score question per item
 * in a single request against his situation (`state`):
 * - [relevance]: how useful each recall candidate would be to him in deciding what to do next.
 *   [Recall.selectRanked] weighs it with importance and recency.
 * - [significance]: how much each new event matters in his life, which decides when a stretch of
 *   events is written up ([Summarizer.threshold]) and how important the memory is.
 *
 * Both return 0 to 1 by item; an item the model didn't answer is left out, and a failed request
 * returns nothing ([onFailure] hears why), so callers fall back to their defaults. [significant]
 * asks the significance questions; it's [scorer] unless the two are metered apart.
 */
class MemoryScorer(
    private val scorer: Scorer,
    private val significant: Scorer = scorer,
    private val timeout: Duration = 30.seconds,
    private val onFailure: (Throwable) -> Unit = {},
) {
    val id: String get() = scorer.id

    /** By [Recollection.key]. */
    suspend fun relevance(state: JsonObject, candidates: List<Recollection>): Map<String, Double> =
        ask(scorer, state, candidates.associate { c ->
            c.key to ScoreQuestion(
                buildJsonObject {
                    put("question", RELEVANCE_QUESTION + if (c.kind == Recollection.Kind.Thought) " It is something he thought earlier, in his words." else " It is something he remembers.")
                    put("memory", c.render())
                },
                RELEVANCE,
            )
        })

    /** By event id. */
    suspend fun significance(state: JsonObject, events: List<MemoryEvent>): Map<Long, Double> =
        ask(significant, state, events.associate { e ->
            "event_${e.id}" to ScoreQuestion(
                buildJsonObject {
                    put("question", SIGNIFICANCE_QUESTION)
                    put("event", "[${e.stamp}] ${e.kind.id}: ${e.text}")
                },
                SIGNIFICANCE,
            )
        }).mapKeys { it.key.removePrefix("event_").toLong() }

    private suspend fun ask(by: Scorer, state: JsonObject, questions: Map<String, ScoreQuestion>): Map<String, Double> {
        if (questions.isEmpty()) return emptyMap()
        return try {
            by.score(ScoreRequest(state, questions, timeout)).answers.mapValues { it.value.normalized }
        } catch (e: TimeoutCancellationException) {
            onFailure(e)
            emptyMap()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onFailure(e)
            emptyMap()
        }
    }

    companion object {
        const val RELEVANCE_QUESTION =
            "How useful would `memory` be to the character in `state` in deciding what to do next, given his situation and what he just chose?"

        val RELEVANCE = listOf(
            "Unrelated to his situation now.",
            "Loosely related: the same things or places, but no bearing on what he does next.",
            "Bears on what he is doing now or just chose.",
            "Bears on a choice he faces now: what to do, buy or work toward next.",
            "He would likely choose badly without it.",
        )

        const val SIGNIFICANCE_QUESTION =
            "How significant is `event` in the life of the character in `state`? Judge it against his situation: what is new, rare or consequential for him, not what is routine."

        val SIGNIFICANCE = listOf(
            "Routine: like countless times before.",
            "A little out of the ordinary.",
            "Notable: worth remembering for a few days.",
            "Important: it changes his plans, his prospects or how he feels about his life.",
            "It changes his life.",
        )
    }
}
