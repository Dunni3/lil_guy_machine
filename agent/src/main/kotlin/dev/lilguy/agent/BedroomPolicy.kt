package dev.lilguy.agent

import dev.lilguy.decision.ActionSchema
import dev.lilguy.decision.Capabilities
import dev.lilguy.decision.Decision
import dev.lilguy.decision.DecisionModel
import dev.lilguy.decision.DecisionRequest
import dev.lilguy.decision.Usage
import dev.lilguy.sim.GameClock
import dev.lilguy.sim.Intent
import dev.lilguy.sim.Observation
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * The bedroom's baseline: work, eat, sleep, and save up for the easel, then paint. Like
 * [KeyDoorPolicy] it reads the observation out of the prompt and replies through the schema, so
 * it knows only what a model would. It spends nothing on fun; that's the point of a baseline.
 *
 * [thinkTime] fakes model latency for watched runs; tests and the CLI use zero.
 */
class BedroomPolicy(
    private val thinkTime: Duration = Duration.ZERO,
    override val id: String = "scripted:bedroom",
) : DecisionModel {
    override val capabilities = Capabilities(jsonSchema = true)

    override suspend fun <A> decide(request: DecisionRequest, schema: ActionSchema<A>): Decision<A> {
        val started = TimeSource.Monotonic.markNow()
        if (thinkTime.isPositive()) delay(thinkTime)
        val obs = PromptBuilder.observationOf(request) ?: error("no observation in the prompt")
        val (intent, rationale) = choose(obs)
        val raw = json.encodeToString(AgentReply.serializer(), AgentReply(rationale, intent))
        val value = schema.decode(raw)
        return Decision(value, schema.rationaleOf(value), Usage(), started.elapsedNow(), raw)
    }

    private fun choose(obs: Observation): Pair<Intent, String> {
        val needs = obs.needs ?: error("not a bedroom observation")
        val money = obs.money ?: 0
        val can = obs.activities.orEmpty().filter { it.locked == null }.map { it.id }.toSet()
        val affordable = obs.shop.orEmpty().filter { it.affordable }.map { it.id }.toSet()
        val owns = obs.owns.orEmpty()
        val meals = owns["meal"] ?: 0
        val holding = obs.holding.orEmpty()
        val hour = obs.clock?.time?.let { GameClock.parseHhmm(it) / 60 } ?: 12
        val night = hour >= BEDTIME || hour < WAKE
        val sleepy = "sleep" in can && (needs.energy < TIRED || (night && needs.energy < NIGHT_SLEEP))
        return when {
            obs.lastIntent == null ->
                Intent.Say("New room, new start. Time to make some art.") to "First thing: say hello, then get to work."
            // Sleep can last 9 hours, and fullness keeps falling through it.
            sleepy && needs.fullness < BEFORE_BED && "eat" in can ->
                Intent.Do("eat") to "Fullness ${needs.fullness} and about to sleep; eat first."
            sleepy && needs.fullness < BEFORE_BED && meals == 0 && "meal" in affordable ->
                Intent.Buy("meal") to "No food in the house before bed; buy some."
            sleepy ->
                Intent.Do("sleep") to "Energy ${needs.energy} at ${obs.clock?.time}; sleep."
            "eat" in can && needs.fullness < HUNGRY ->
                Intent.Do("eat") to "Fullness ${needs.fullness}; eat a meal."
            meals < MEAL_STOCK && "meal" in affordable ->
                Intent.Buy("meal") to "Only $meals meals left and $$money; restock food first."
            meals == 0 && holding.isNotEmpty() && "sell_art" in can ->
                Intent.Do("sell_art") to "No food and no money for it; sell what I hold."
            "sell_art" in can && shouldSell(obs) ->
                Intent.Do("sell_art") to "Holding ${holding.size} pieces; sell them."
            "paint" in can && needs.energy >= PAINT_ENERGY ->
                Intent.Do("paint") to "I have an easel and a canvas; paint, it pays best."
            "easel" in owns && (owns["canvas"] ?: 0) == 0 && "canvas" in affordable ->
                Intent.Buy("canvas") to "Easel but no canvas; buy one."
            "easel" !in owns && "easel" in affordable && meals >= MEAL_STOCK ->
                Intent.Buy("easel") to "$$money saved and food in stock: buy the easel to paint."
            "sketch" in can && needs.energy >= SKETCH_ENERGY ->
                Intent.Do("sketch") to "Sketch: $12 each, saving for ${if ("easel" in owns) "canvases" else "the easel"} ($$money now)."
            "sell_art" in can ->
                Intent.Do("sell_art") to "Too tired to sketch; sell what I hold."
            "sleep" in can ->
                Intent.Do("sleep") to "Nothing useful left to do at energy ${needs.energy}; sleep."
            else ->
                Intent.Wait(BedroomPrompt.WAIT_TICKS) to "Nothing I can do right now; wait."
        }
    }

    /** Sell in batches (a trip to the door takes an hour), or as soon as the money is needed. */
    private fun shouldSell(obs: Observation): Boolean {
        val holding = obs.holding.orEmpty()
        val money = obs.money ?: 0
        val owns = obs.owns.orEmpty()
        val needCanvas = "easel" in owns && (owns["canvas"] ?: 0) == 0 && money < CANVAS
        return holding.count { it == "painting" } >= PAINTING_BATCH || holding.size >= SKETCH_BATCH || (needCanvas && holding.isNotEmpty())
    }

    private companion object {
        const val TIRED = 20
        const val NIGHT_SLEEP = 85
        const val BEDTIME = 22
        const val WAKE = 6
        const val HUNGRY = 45
        const val BEFORE_BED = 60
        const val MEAL_STOCK = 2
        const val PAINT_ENERGY = 40
        const val SKETCH_ENERGY = 22
        const val SKETCH_BATCH = 4
        const val PAINTING_BATCH = 2
        const val CANVAS = 15
        val json = Json
    }
}
