package dev.lilguy.decision

import kotlinx.coroutines.delay
import kotlin.random.Random
import kotlin.time.Duration

/**
 * Picks one of [DecisionRequest.choices] at random after [latency], the way a picking model would
 * but with no network. A dev aid: it lets the app paint with no endpoint (`LILGUY_PAINTER`), at
 * about Jev's pace, so the painting scene can be watched.
 */
class RandomPicker(
    private val latency: Duration,
    seed: Long = 0,
    override val id: String = "random-picker",
) : DecisionModel {
    private val random = Random(seed)

    override val capabilities = Capabilities(closedChoice = true)

    override suspend fun <A> decide(request: DecisionRequest, schema: ActionSchema<A>): Decision<A> {
        val choices = request.choices ?: throw DecisionFailed("'$id' only picks from a choice set")
        if (latency.isPositive()) delay(latency)
        val option = choices.options[random.nextInt(choices.options.size)]
        return Decision(schema.decode(option.reply.toString()), null, Usage(), latency, option.label)
    }
}
