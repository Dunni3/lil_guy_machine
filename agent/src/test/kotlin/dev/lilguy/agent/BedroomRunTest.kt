package dev.lilguy.agent

import dev.lilguy.decision.ActionSchema
import dev.lilguy.sim.BedroomWorld
import dev.lilguy.sim.GameClock
import dev.lilguy.sim.Intent
import dev.lilguy.sim.Need
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BedroomRunTest {
    private class Lived(
        val world: BedroomWorld,
        val decisions: List<AgentRuntime.DecisionRecord>,
        val lowest: Map<Need, Double>,
        /** Game hours per day per thing he was doing: "sketch", "wait", "sleep" (walking counts as "do"). */
        val hours: Map<Int, Map<String, Double>>,
        /** Lowest fullness by the end of each day. */
        val lowestFullnessBy: Map<Int, Double>,
    )

    private fun live(days: Int, journaling: BedroomPolicy.Journaling = BedroomPolicy.Journaling.Spare): Lived {
        val decisions = mutableListOf<AgentRuntime.DecisionRecord>()
        val (world, runtime) = bedroomScenario(BedroomPolicy(journaling = journaling)) { decisions += it }
        val lowest = Need.entries.associateWith { Double.MAX_VALUE }.toMutableMap()
        val ticksOn = mutableMapOf<Int, MutableMap<String, Long>>()
        val fullnessBy = mutableMapOf<Int, Double>()
        val ticks = days.toLong() * GameClock.MINUTES_PER_DAY * world.clock.ticksPerMinute
        runBlocking {
            runLockstep(world, runtime, maxTicks = ticks) {
                Need.entries.forEach { lowest[it] = minOf(lowest.getValue(it), world.needs[it]) }
                val s = world.snapshot()
                val day = world.clock.day(world.tick)
                val doing = s.life!!.doing ?: s.agents.first().intent?.let { world.describe(it).substringBefore(' ') } ?: "-"
                ticksOn.getOrPut(day) { mutableMapOf() }.merge(doing, 1L, Long::plus)
                fullnessBy[day] = minOf(fullnessBy[day] ?: lowest.getValue(Need.Fullness), lowest.getValue(Need.Fullness))
            }
        }
        val perHour = world.clock.ticksPerMinute * 60.0
        return Lived(world, decisions, lowest, ticksOn.mapValues { (_, m) -> m.mapValues { it.value / perHour } }, fullnessBy)
    }

    @Test
    fun `the scripted policy lives 14 days without a need reaching 0, journaling in its spare hours`() {
        val lived = live(14)
        val w = lived.world
        printDays(w)
        assertEquals(15, w.clock.day(w.tick))
        lived.lowest.forEach { (need, low) -> assertTrue(low > 0.0, "$need reached $low") }
        assertTrue(lived.decisions.count { it.intent == Intent.Do("journal") } >= 14, "journaled less than once a day")
        assertTrue(lived.decisions.none { it.fellBack })
        val failed = w.snapshot().events.filter { "failed" in it.text }
        assertTrue(failed.isEmpty(), "failed intents: $failed")
    }

    /**
     * The economy's tuning target (research_notes/2026-10-29_1644_journal-and-scarcity-plan.md):
     * earning just enough food leaves a few spare waking hours a day and no savings. Day 1 is
     * left out: he starts with food and money. The target was 3 to 5 hours until selling art
     * stopped taking an hour (Ian); that hour went to spare time.
     */
    @Test
    fun `surviving takes most of the day and leaves 3 to 6 spare waking hours`() {
        val lived = live(6, BedroomPolicy.Journaling.Never)
        val w = lived.world
        lived.hours.toSortedMap().forEach { (day, h) -> println("day $day: " + h.entries.sortedBy { it.key }.joinToString { "%s %.1f".format(it.key, it.value) }) }
        val spare = (2..6).map { lived.hours[it].orEmpty()["wait"] ?: 0.0 }
        val sketching = (2..6).map { lived.hours[it].orEmpty()["sketch"] ?: 0.0 }
        assertTrue(spare.average() in 3.0..6.0, "spare waking hours a day: $spare")
        assertTrue(sketching.average() >= 6.0, "sketching hours a day: $sketching")
        val mealPrice = w.content.item("meal")!!.price!!
        val richest = w.ledger.transactions.maxOf { it.balance }
        assertTrue(richest < 2 * mealPrice, "saved up $richest")
        lived.lowest.forEach { (need, low) -> assertTrue(low > 0.0, "$need reached $low") }
    }

    @Test
    fun `writing as much as he likes leaves him starving by day 3`() {
        val lived = live(3, BedroomPolicy.Journaling.Always)
        val low = lived.world.content.needs.low
        assertTrue(lived.lowestFullnessBy.getValue(3) < low, "lowest fullness by day: ${lived.lowestFullnessBy}")
    }

    @Test
    fun `bedroom runs are deterministic`() {
        fun run() = live(2).world.let { it.ledger.balance to it.needs.levels() }
        assertEquals(run(), run())
    }

    @Test
    fun `schema and choices offer exactly the valid activities and purchases`() {
        val (world, _) = bedroomScenario(BedroomPolicy())
        val obs = world.observe(world.agentId)
        val schema = BedroomPrompt.replySchema(obs, world.clock.ticksPerMinute).jsonSchema
        val variants = schema["properties"]!!.jsonObject["intent"]!!.jsonObject["anyOf"]!!.jsonArray.map { it.jsonObject }
            .associateBy { it["properties"]!!.jsonObject["type"]!!.jsonObject["enum"]!!.jsonArray.single().jsonPrimitive.content }
        fun enumOf(variant: String, field: String) =
            variants.getValue(variant)["properties"]!!.jsonObject[field]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        // Tier 0: no uses, a shop of meals he can't afford yet ($20 for $24), nothing to pick up.
        assertEquals(setOf("do", "move_to", "say", "wait"), variants.keys)
        assertEquals(listOf("eat", "sketch", "journal", "sleep"), enumOf("do", "activity"))
        assertEquals(listOf("meal"), obs.shop.orEmpty().map { it.id })
        assertEquals(emptyList(), obs.uses)

        val choices = BedroomPrompt.choices(obs, emptyList(), world.clock.ticksPerMinute, world.content)
        assertEquals(
            listOf("do:eat", "do:sketch", "do:journal", "do:sleep", "wait:${BedroomPrompt.WAIT_TICKS}"),
            choices.options.map { it.label },
        )
        // Every option's reply passes the same schema a writing model is held to.
        val decoder = ActionSchema("reply", AgentReply.serializer(), schema)
        choices.options.forEach { decoder.decode(it.reply.toString()) }
        assertTrue("you have no art to sell; sketch makes some" in choices.state.toString())
    }

    @Test
    fun `the system prompt opens with the persona and describes only the unlocked world`() {
        val (world, _) = bedroomScenario(BedroomPolicy())
        val system = BedroomPrompt.system(world.content, world.clock.ticksPerMinute)
        assertTrue(system.startsWith("You are intelligent, aspirational and creative"), system.take(80))
        assertTrue("the only things you can do are eat, sketch, sell your art, write in your journal and sleep." in system)
        assertTrue(Persona.CREATOR in system)
        assertTrue("You live in this bedroom. You need money for food." in system)
        assertTrue("a sketch sells for $3" in system)
        assertTrue("- Journal: writing in your journal takes 1h and costs 10 energy; it's work" in system)
        assertTrue("what you wrote in your journal" in system)
        listOf("easel", "canvas", "record", "painting", "decor", "`uses`", "music").forEach { assertTrue(it !in system, "mentions gated '$it'") }
        assertTrue("goal" !in system.lowercase(), "no objective, just his situation")

        // Granting brings the gated content into the prompt.
        val grown = BedroomPrompt.system(dev.lilguy.sim.BedroomContent.default().unlocked(setOf("paint", "easel", "canvas", "painting")), 5)
        assertTrue("sketch and paint make art" in grown && "a painting sells for $70" in grown, grown)
        assertTrue("for food and for anything you want to own" in grown)
    }

    /** One line per day for reading the economy off the test output. */
    private fun printDays(w: BedroomWorld) {
        val byDay = w.needs.changes.groupBy { it.day }
        for (day in 1..w.clock.day(w.tick)) {
            val tx = w.ledger.transactions.filter { it.day == day }
            val moods = byDay[day].orEmpty().filter { it.need == Need.Mood }.map { it.value }
            val bought = tx.filter { it.amount < 0 }.joinToString { it.reason.removePrefix("bought ") }
            println(
                "day %2d: money %4d, earned %4d, spent %4d, mood %s, bought %s".format(
                    day, tx.lastOrNull()?.balance ?: -1, tx.filter { it.amount > 0 }.sumOf { it.amount }, -tx.filter { it.amount < 0 }.sumOf { it.amount },
                    if (moods.isEmpty()) "-" else "%.0f-%.0f".format(moods.min(), moods.max()), bought.ifEmpty { "-" },
                ),
            )
        }
        Need.entries.forEach { n -> println("lowest $n: %.1f".format(w.needs.changes.filter { it.need == n }.minOfOrNull { it.value } ?: -1.0)) }
    }
}
