package dev.lilguy.decision

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Many graded questions about one [ScoreRequest.state] in one call, each answered on its own:
 * TypeSafe's Score questions (docs.typesafe.ai/primitives/score). Memory uses it to rate every
 * recall candidate, and every new event's significance, without one long Choice over all of them.
 * [TypesafeBackend] is one; other backends aren't.
 */
interface Scorer {
    val id: String

    suspend fun score(request: ScoreRequest): ScoreResult
}

/**
 * [instructions] is the question, as text or as an object carrying its own data beside it (the
 * memory being rated). [criteria] are the levels from lowest to highest, at least 2 and at most
 * [MAX_LEVELS]; the model sees only their text.
 */
data class ScoreQuestion(val instructions: JsonElement, val criteria: List<String>) {
    init {
        require(criteria.size in 2..MAX_LEVELS) { "a score question takes 2 to $MAX_LEVELS levels; this one has ${criteria.size}" }
    }

    companion object {
        const val MAX_LEVELS = 10
    }
}

data class ScoreRequest(
    val state: JsonObject,
    /** By key; keys come back in [ScoreResult.answers]. */
    val questions: Map<String, ScoreQuestion>,
    val timeout: Duration = 30.seconds,
)

/**
 * [score] is the probability-weighted level, 0 to `levels - 1`; [probabilities] is one per level,
 * lowest first.
 */
data class ScoreAnswer(val score: Double, val levels: Int, val confidence: Double?, val probabilities: List<Double>) {
    /** [score] scaled to 0..1. */
    val normalized: Double get() = if (levels <= 1) 0.0 else (score / (levels - 1)).coerceIn(0.0, 1.0)
}

/** Answers by question key; a question the server didn't answer is missing. */
data class ScoreResult(val answers: Map<String, ScoreAnswer>, val usage: Usage, val latency: Duration)
