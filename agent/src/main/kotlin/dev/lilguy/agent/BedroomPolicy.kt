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
 * The bedroom's baseline: earn just enough for food, eat, sleep, and wait out the spare hours,
 * whose waiting is the economy's slack. Like [KeyDoorPolicy] it reads the observation out of the
 * prompt and replies through the schema, so it knows only what a model would. It buys nothing but
 * food; that's the point of a baseline.
 *
 * [thinkTime] fakes model latency for watched runs; tests and the CLI use zero. [script] is a dev aid
 * for screenshots (LILGUY_SCRIPT): verbs done first, in order, each skipped if it's locked when its
 * turn comes.
 */
class BedroomPolicy(
    private val thinkTime: Duration = Duration.ZERO,
    override val id: String = "scripted:bedroom",
    script: List<String> = emptyList(),
) : DecisionModel {
    private val script = ArrayDeque(script)

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
        val activities = obs.activities.orEmpty()
        val can = activities.filter { it.locked == null }.map { it.id }.toSet()
        while (script.isNotEmpty()) {
            val verb = script.removeFirst()
            if (verb in can) return Intent.Do(verb) to "Scripted: $verb."
        }
        val mealPrice = obs.shop.orEmpty().firstOrNull { it.id == "meal" }?.price ?: 0
        val affordable = obs.shop.orEmpty().filter { it.affordable }.map { it.id }.toSet()
        val meals = obs.owns.orEmpty()["meal"] ?: 0
        val holding = obs.holding.orEmpty()
        // What the art he holds would fetch: sell_art's "gives" says, or each piece at the sketch price.
        val held = activities.firstOrNull { it.id == "sell_art" }?.gives?.let { DOLLARS.find(it)?.groupValues?.get(1)?.toInt() }
            ?: holding.size * (activities.firstOrNull { it.id == "sketch" }?.gives?.let { SELLS_FOR.find(it)?.groupValues?.get(1)?.toInt() } ?: 0)
        // Earn until the cash and art on hand would buy the next pack of meals, and no further.
        val earning = money + held < mealPrice * RESERVE_PACKS
        val hour = obs.clock?.time?.let { GameClock.parseHhmm(it) / 60 } ?: 12
        val night = hour >= BEDTIME || hour < WAKE
        val sleepy = "sleep" in can && (needs.energy < TIRED || (night && needs.energy < NIGHT_SLEEP))
        return when {
            obs.lastIntent == null ->
                Intent.Say("New day. Let's see what I can make of it.") to "First thing: say hello, then get going."
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
            "sell_art" in can && meals < MEAL_STOCK && money < mealPrice && money + held >= mealPrice ->
                Intent.Do("sell_art") to "Low on food; sell what I hold so I can buy more."
            "sell_art" in can && !earning && holding.isNotEmpty() ->
                Intent.Do("sell_art") to "That's enough art for the next pack of meals; sell it."
            earning && "sketch" in can && needs.energy >= SKETCH_ENERGY ->
                Intent.Do("sketch") to "Sketch: $$money and $$held in art, short of the next pack of meals ($$mealPrice)."
            "sell_art" in can && meals == 0 ->
                Intent.Do("sell_art") to "No food and too tired to sketch; sell what I hold."
            else ->
                Intent.Wait(BedroomPrompt.WAIT_TICKS) to "Nothing I need to do right now; wait."
        }
    }

    private companion object {
        const val TIRED = 20
        const val NIGHT_SLEEP = 85
        const val BEDTIME = 22
        const val WAKE = 6
        const val HUNGRY = 45
        const val BEFORE_BED = 60
        const val MEAL_STOCK = 2
        const val SKETCH_ENERGY = 22
        /** How many packs of meals' worth of cash and art to earn before stopping. */
        const val RESERVE_PACKS = 1
        val DOLLARS = Regex("""\$(\d+) for""")
        val SELLS_FOR = Regex("""sells for \$(\d+)""")
        val json = Json
    }
}
