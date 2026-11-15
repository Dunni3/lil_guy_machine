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
 * turn comes; `put:<thing>:<spot>` puts a thing there instead, skipped unless `put` offers both. `verb@thing` does it on that thing
 * (`explore@garden_bench`).
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
            val on = verb.substringAfter('@', "").ifEmpty { null }
            if (verb.substringBefore('@') in can) return Intent.Do(verb.substringBefore('@'), on = on) to "Scripted: $verb."
            val put = verb.split(':').takeIf { it.size == 3 && it[0] == "put" } ?: continue
            val offered = obs.put ?: continue
            if (put[1] in offered.things + offered.wall.orEmpty() && offered.spots.any { put[2] in it.floor + it.wall.orEmpty() }) {
                return Intent.Put(put[1], put[2]) to "Scripted: put the ${put[1]} at ${put[2]}."
            }
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
                eat(activities) to "Fullness ${needs.fullness} and about to sleep; eat first."
            sleepy && needs.fullness < BEFORE_BED && meals == 0 && "meal" in affordable ->
                Intent.Buy("meal") to "No food in the house before bed; buy some."
            sleepy ->
                Intent.Do("sleep") to "Energy ${needs.energy} at ${obs.clock?.time}; sleep."
            "eat" in can && needs.fullness < HUNGRY ->
                eat(activities) to "Fullness ${needs.fullness}; eat."
            "read_mail" in can ->
                Intent.Do("read_mail") to "A letter is waiting at the hatch; open it."
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
            else -> tending(obs, night) ?: watching(obs, night) ?: (Intent.Wait(BedroomPrompt.WAIT_TICKS) to "Nothing I need to do right now; wait.")
        }
    }

    /**
     * With machines, his spare daylight looks after them: mend one once it has jammed (the baseline
     * runs a thing until it breaks), fill one when he has a batch's worth of scraps, and take out food
     * it grew. What one machine makes for the one beside it he leaves to them. Without, nothing.
     */
    private fun tending(obs: Observation, night: Boolean): Pair<Intent, String>? {
        if (night) return null
        val open = obs.activities.orEmpty().filter { it.locked == null }
        open.firstOrNull { it.id == "repair" && it.gives.orEmpty().contains("works again") }?.let { return Intent.Do("repair", on = it.on) to "The ${it.on} has jammed; mend it." }
        val scraps = obs.owns.orEmpty()["food_scrap"] ?: 0
        open.firstOrNull { it.id == "tend" && ((it.gives.orEmpty().contains("put in") && scraps >= SCRAPS) || TOMATOES.containsMatchIn(it.gives.orEmpty())) }
            ?.let { return Intent.Do("tend", on = it.on) to "Tend the ${it.on}: ${it.gives}." }
        return null
    }

    /** Eat what he grew before what he bought: the first open eat that isn't a meal, else a meal. */
    private fun eat(activities: List<dev.lilguy.sim.ActivityOption>): Intent =
        Intent.Do("eat", what = activities.firstOrNull { it.id == "eat" && it.locked == null && it.what != null && it.what != "meal" }?.what)

    /** Game minutes at his last check of the station. */
    private var checked: Long? = null
    private val lookedRound = mutableSetOf<String>()

    /**
     * With a weather station, his spare daylight goes outdoors: look round each place once, collect
     * whatever has grown back, and read the station every [CHECK_HOURS]. Without one, nothing: the
     * baseline stays the baseline.
     */
    private fun watching(obs: Observation, night: Boolean): Pair<Intent, String>? {
        if ((obs.owns.orEmpty()["weather_station"] ?: 0) == 0 || night) return null
        val open = obs.activities.orEmpty().filter { it.locked == null }
        val now = obs.clock?.let { (it.day - 1L) * GameClock.MINUTES_PER_DAY + GameClock.parseHhmm(it.time) } ?: return null
        open.firstOrNull { it.id == "collect" }?.let { return Intent.Do("collect", on = it.on) to "Something has grown back at the ${it.on}; collect it." }
        if (open.any { it.id == "check_station" } && checked.let { it == null || now - it >= CHECK_HOURS * 60 }) {
            checked = now
            return Intent.Do("check_station") to "Time to read the weather station."
        }
        open.firstOrNull { it.id == "explore" && it.on != null && it.on !in lookedRound }?.let {
            lookedRound += it.on!!
            return Intent.Do("explore", on = it.on) to "I haven't looked round from the ${it.on} yet."
        }
        return null
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
        const val CHECK_HOURS = 8
        /** Scraps worth a walk to the compost bin: a batch's. */
        const val SCRAPS = 3
        val TOMATOES = Regex("""take out \d+ tomato""")
        /** How many packs of meals' worth of cash and art to earn before stopping. */
        const val RESERVE_PACKS = 1
        val DOLLARS = Regex("""\$(\d+) for""")
        val SELLS_FOR = Regex("""sells for \$(\d+)""")
        val json = Json
    }
}
