package dev.lilguy.decision

import kotlin.time.Duration

/**
 * Replays canned JSON replies through the same decode path a real backend uses. For tests and
 * scripted baselines; no network.
 */
class ScriptedDecisionModel(
    replies: List<String>,
    override val id: String = "scripted",
) : DecisionModel {
    private val remaining = ArrayDeque(replies)

    override val capabilities = Capabilities(jsonSchema = true)

    override suspend fun <A> decide(request: DecisionRequest, schema: ActionSchema<A>): Decision<A> {
        val raw = remaining.removeFirstOrNull() ?: error("ScriptedDecisionModel '$id' ran out of replies")
        return Decision(schema.decode(raw), rationale = null, usage = Usage(), latency = Duration.ZERO, raw = raw)
    }
}
