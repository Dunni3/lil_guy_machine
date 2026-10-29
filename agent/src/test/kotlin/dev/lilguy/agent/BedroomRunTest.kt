package dev.lilguy.agent

import dev.lilguy.decision.ActionSchema
import dev.lilguy.sim.BedroomWorld
import dev.lilguy.sim.GameClock
import dev.lilguy.sim.Need
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BedroomRunTest {
    private class Lived(val world: BedroomWorld, val decisions: List<AgentRuntime.DecisionRecord>, val lowest: Map<Need, Double>)

    private fun live(days: Int): Lived {
        val decisions = mutableListOf<AgentRuntime.DecisionRecord>()
        val (world, runtime) = bedroomScenario(BedroomPolicy()) { decisions += it }
        val lowest = Need.entries.associateWith { Double.MAX_VALUE }.toMutableMap()
        val ticks = days.toLong() * GameClock.MINUTES_PER_DAY * world.clock.ticksPerMinute
        runBlocking {
            runLockstep(world, runtime, maxTicks = ticks) {
                Need.entries.forEach { lowest[it] = minOf(lowest.getValue(it), world.needs[it]) }
            }
        }
        return Lived(world, decisions, lowest)
    }

    @Test
    fun `the scripted policy lives 14 days without a need reaching 0 and buys the easel`() {
        val lived = live(14)
        val w = lived.world
        printDays(w)
        assertEquals(15, w.clock.day(w.tick))
        lived.lowest.forEach { (need, low) -> assertTrue(low > 0.0, "$need reached $low") }
        val easel = w.ledger.transactions.firstOrNull { it.reason == "bought 1 easel" }
        assertTrue(easel != null, "never bought the easel")
        assertTrue(easel.day <= 5, "bought the easel on day ${easel.day}")
        assertTrue(w.ledger.transactions.any { it.reason.contains("painting") }, "never sold a painting")
        assertTrue(lived.decisions.none { it.fellBack })
        val failed = w.snapshot().events.filter { "failed" in it.text }
        assertTrue(failed.isEmpty(), "failed intents: $failed")
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
        // No record player or records yet, so no use; nothing to pick up.
        assertEquals(setOf("do", "buy", "move_to", "say", "wait"), variants.keys)
        assertEquals(listOf("eat", "sketch", "sleep"), enumOf("do", "activity"))
        assertEquals(listOf("meal", "canvas", "jazz_record", "ambient_record", "folk_record", "novel", "poetry_book", "field_guide", "plant", "poster"), enumOf("buy", "item"))

        val choices = BedroomPrompt.choices(obs, emptyList(), world.clock.ticksPerMinute)
        assertEquals(
            listOf("do:eat", "do:sketch", "do:sleep") + enumOf("buy", "item").map { "buy:$it" } + "wait:${BedroomPrompt.WAIT_TICKS}",
            choices.options.map { it.label },
        )
        // Every option's reply passes the same schema a writing model is held to.
        val decoder = ActionSchema("reply", AgentReply.serializer(), schema)
        choices.options.forEach { decoder.decode(it.reply.toString()) }
        assertTrue("needs an easel" in choices.state.toString())
    }

    @Test
    fun `the system prompt describes his situation and the rules from the content`() {
        val (world, _) = bedroomScenario(BedroomPolicy())
        val system = BedroomPrompt.system(world.content, world.clock.ticksPerMinute)
        assertTrue("You live in this bedroom. You need money for food and for anything you want to own." in system)
        assertTrue("a sketch sells for $12 and a painting sells for $70" in system)
        assertTrue("goal" !in system.lowercase(), "no objective, just his situation")
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
