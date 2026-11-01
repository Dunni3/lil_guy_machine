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

    private fun live(days: Int): Lived {
        val decisions = mutableListOf<AgentRuntime.DecisionRecord>()
        val (world, runtime) = bedroomScenario(BedroomPolicy()) { decisions += it }
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
    fun `the scripted policy lives 14 days without a need reaching 0`() {
        val lived = live(14)
        val w = lived.world
        printDays(w)
        assertEquals(15, w.clock.day(w.tick))
        // The baseline buys nothing to do, so it ends up bored; stimulation at 0 isn't harm. Frustration at 0 is calm.
        lived.lowest.filterKeys { it != Need.Stimulation && it != Need.Frustration }.forEach { (need, low) -> assertTrue(low > 0.0, "$need reached $low") }
        assertTrue(lived.decisions.none { it.fellBack })
        val failed = w.snapshot().events.filter { "failed" in it.text }
        assertTrue(failed.isEmpty(), "failed intents: $failed")
    }

    /**
     * The economy's tuning target (research_notes/2026-10-29_1644_journal-and-scarcity-plan.md):
     * earning just enough food leaves a few spare waking hours a day and no savings. Day 1 is
     * left out: he starts with food and money. The target was 3 to 5 hours until selling art
     * stopped taking an hour (Ian); that hour went to spare time.
     *
     * The knowledge-and-skill retune (sketch energy 6 to 3, fullness asleep 5 to 2 an hour) left
     * him about 11 spare hours and 4 to 5 sketching; the bounds hold that until a Jev run says
     * whether the economy needs tightening again.
     */
    @Test
    fun `surviving takes part of the day and leaves 8 to 14 spare waking hours`() {
        val lived = live(6)
        val w = lived.world
        lived.hours.toSortedMap().forEach { (day, h) -> println("day $day: " + h.entries.sortedBy { it.key }.joinToString { "%s %.1f".format(it.key, it.value) }) }
        val spare = (2..6).map { lived.hours[it].orEmpty()["wait"] ?: 0.0 }
        val sketching = (2..6).map { lived.hours[it].orEmpty()["sketch"] ?: 0.0 }
        assertTrue(spare.average() in 8.0..14.0, "spare waking hours a day: $spare")
        assertTrue(sketching.average() >= 4.0, "sketching hours a day: $sketching")
        val mealPrice = w.content.item("meal")!!.price!!
        val richest = w.ledger.transactions.maxOf { it.balance }
        assertTrue(richest < 2 * mealPrice, "saved up $richest")
        lived.lowest.filterKeys { it != Need.Stimulation && it != Need.Frustration }.forEach { (need, low) -> assertTrue(low > 0.0, "$need reached $low") }
    }

    @Test
    fun `bedroom runs are deterministic`() {
        fun run() = live(2).world.let { it.ledger.balance to it.needs.levels() }
        assertEquals(run(), run())
    }

    /** The shipped file gates nothing; the small world and its grants are tested on what used to be tier 1, and all decor. */
    private val smallWorld = dev.lilguy.sim.BedroomContent.default().let { c ->
        val tier1 = setOf(
            "canvas", "easel", "record_player", "jazz_record", "ambient_record", "folk_record", "novel", "poetry_book",
            "field_guide", "drawing_book", "music_book", "guitar", "painting", "paint", "read", "study",
            "listen", "play_record", "play_guitar",
        ) + c.items.filter { it.group == "decor" }.map { it.id }
        c.copy(
            items = c.items.map { if (it.id in tier1) it.copy(tier = 1) else it },
            verbs = c.verbs.map { if (it.id in tier1) it.copy(tier = 1) else it },
        )
    }

    @Test
    fun `schema and choices offer exactly the valid activities and purchases`() {
        val (world, _) = bedroomScenario(BedroomPolicy(), content = smallWorld)
        val obs = world.observe(world.agentId)
        val schema = BedroomPrompt.replySchema(obs, world.clock.ticksPerMinute).jsonSchema
        val variants = schema["properties"]!!.jsonObject["intent"]!!.jsonObject["anyOf"]!!.jsonArray.map { it.jsonObject }
            .associateBy { it["properties"]!!.jsonObject["type"]!!.jsonObject["enum"]!!.jsonArray.single().jsonPrimitive.content }
        fun enumOf(variant: String, field: String) =
            variants.getValue(variant)["properties"]!!.jsonObject[field]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        // Tier 0: a shop of meals he can't afford yet ($20 for $24), nothing to pick up, and use is for other rooms.
        assertEquals(setOf("do", "move_to", "say", "wait"), variants.keys)
        // The schema takes every verb the sim would; choices only offer what's shown. Sleep isn't: at 08:00 it's only a nap, when exhausted.
        assertEquals(listOf("eat", "sketch", "think"), enumOf("do", "activity"))
        assertEquals(listOf("meal"), obs.shop.orEmpty().map { it.id })

        val choices = BedroomPrompt.choices(obs, emptyList(), world.clock.ticksPerMinute, world.content)
        assertEquals(
            listOf("do:eat", "do:sketch", "do:think", "wait:${BedroomPrompt.WAIT_TICKS}"),
            choices.options.map { it.label },
        )
        // Every option's reply passes the same schema a writing model is held to.
        val decoder = ActionSchema("reply", AgentReply.serializer(), schema)
        choices.options.forEach { decoder.decode(it.reply.toString()) }
        assertTrue("you have no sketch to sell; sketch makes some" in choices.state.toString())
    }

    @Test
    fun `the system prompt opens with the persona and describes only the unlocked world`() {
        val (world, _) = bedroomScenario(BedroomPolicy(), content = smallWorld)
        val system = BedroomPrompt.system(world.content, world.clock.ticksPerMinute)
        assertTrue(system.startsWith(world.content.personality.core.you), system.take(80))
        assertTrue("- What you can do: eat, eat plain oats from the cupboard, sketch, sell your art, hang your art on the wall, sleep and stop and think." in system)
        assertTrue("You live in this bedroom. You need money for food." in system)
        assertTrue("a sketch sells for $3" in system)
        listOf("easel", "canvas", "record", "painting", "decor", "`uses`", "music", "journal", "restrictive").forEach { w -> assertTrue(w !in system, "mentions '$w': " + system.lines().filter { w in it }) }
        assertTrue(Persona.SELF_HEADER !in system, "no self-model unless something revises it")
        val withSelf = BedroomPrompt.system(world.content, world.clock.ticksPerMinute, self = "- Who I am: someone who sketches.")
        assertTrue("${Persona.SELF_HEADER}\n- Who I am: someone who sketches." in withSelf)

        // Granting brings the gated content into the prompt.
        val grown = BedroomPrompt.system(smallWorld.unlocked(setOf("paint", "easel", "canvas", "painting")), 5)
        assertTrue("sketch and paint make art" in grown && "a painting sells for $30" in grown, grown)
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
