package dev.lilguy.agent.memory

import dev.lilguy.agent.BedroomPolicy
import dev.lilguy.agent.bedroomScenario
import dev.lilguy.agent.runLockstep
import dev.lilguy.decision.ActionSchema
import dev.lilguy.decision.Capabilities
import dev.lilguy.decision.Decision
import dev.lilguy.decision.DecisionModel
import dev.lilguy.decision.DecisionRequest
import dev.lilguy.decision.Usage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

/** Writes a canned memory for every batch and keeps the logs it was shown. */
private class FakeWriter(val closedChoice: Boolean = false, val fail: () -> Boolean = { false }) : DecisionModel {
    val prompts = mutableListOf<String>()
    override val id = "fake-writer"
    override val capabilities = Capabilities(closedChoice = closedChoice)

    @Suppress("UNCHECKED_CAST")
    override suspend fun <A> decide(request: DecisionRequest, schema: ActionSchema<A>): Decision<A> {
        prompts += request.messages.last().content
        if (fail()) error("model down")
        val raw = """{"memory":"I sketched and sold; the easel is next. (batch ${prompts.size})"}"""
        return Decision(schema.decode(raw), null, Usage(), Duration.ZERO, raw)
    }
}

class MemoryTest {
    @Test
    fun `a run records outcomes, what happened around him, and a snapshot when he wakes`() {
        val store = InMemoryStore()
        val decisions = mutableListOf<dev.lilguy.agent.AgentRuntime.DecisionRecord>()
        val (world, runtime) = bedroomScenario(BedroomPolicy(), longTerm = AgentMemory(store, "r1")) { decisions += it }
        runBlocking { runLockstep(world, runtime, maxTicks = 2 * 7200) }
        val events = store.eventsAfter("r1", 0, 10_000)
        val outcomes = events.filter { it.kind == EventKind.Outcome }
        assertTrue(outcomes.size in decisions.size - 1..decisions.size, "one outcome per finished intent: ${outcomes.size} for ${decisions.size} decisions")
        // From the sim's own words for what the ops did, with how long it took.
        assertTrue(outcomes.any { it.text.startsWith("sketch (1h): done; +1 sketch, art skill 0.2 (+0.1), energy -3") }, outcomes.map { it.text }.toString())
        assertTrue(outcomes.none { "money $" in it.text && it.text.startsWith("sketch") }, "outcomes don't restate his state")
        // How things stood: at the start, then once each morning he woke (two days from 08:00 reach day 3).
        val snapshots = events.filter { it.kind == EventKind.Snapshot }
        assertEquals(listOf(1, 2, 3), snapshots.map { it.day })
        assertTrue(snapshots.first().text.startsWith("money $20; energy 80"), snapshots.first().text)
        assertTrue(events.any { it.kind == EventKind.World && it.text == "day 2 begins" })
    }

    @Test
    fun `summaries cover each event once, in order, and survive a failing model`() {
        val store = InMemoryStore()
        for (i in 1..10) store.appendEvent("r", i * 10L, 1, "08:%02d".format(i), if (i % 2 == 0) EventKind.Outcome else EventKind.World, "event $i")
        var down = true
        val writer = FakeWriter(fail = { down })
        val summarizer = Summarizer(writer, store, "r", batchSize = 4)
        runBlocking {
            assertNull(summarizer.summarizeOnce())
            assertEquals(10, summarizer.pending(), "a failed job stores nothing")
            down = false
            summarizer.catchUp()
            assertEquals(2, store.memories("r", 1).size)
            assertEquals(2, summarizer.pending())
            summarizer.flush()
        }
        val ms = store.memories("r", 1)
        assertEquals(listOf(1L to 4L, 5L to 8L, 9L to 10L), ms.map { it.fromId to it.toId })
        assertEquals("fake-writer", ms.first().source)
        assertEquals("Day 1 08:01-08:04", ms.first().span.label)
        assertTrue(writer.prompts.last().contains("[d1 08:09] Happened: event 9"), writer.prompts.last())
        assertTrue(writer.prompts.last().contains("I did: event 10"))
        // Each memory is written with the one before it in view.
        assertTrue("Your memory of just before this" in writer.prompts.last() && "(batch 3)" in writer.prompts.last(), writer.prompts.last())
    }

    @Test
    fun `a model that only picks cannot be the summarizer`() {
        assertFailsWith<IllegalArgumentException> { Summarizer(FakeWriter(closedChoice = true), InMemoryStore(), "r") }
    }

    @Test
    fun `recall takes the latest memories plus older memories and thoughts that share words, within the budget`() {
        fun m(id: Long, text: String) = Recollection(Recollection.Kind.Memory, id, "Day 1 08:00-09:00", text, null, id * 10)
        val easel = Recollection(Recollection.Kind.Thought, 7, "Day 1 08:30", "I want the easel.", null, 15)
        val all = listOf(m(1, "I bought the canvas and it was worth it."), easel, m(2, "I napped."), m(3, "I ate a meal."), m(4, "I sold a sketch."), m(5, "I slept."), m(6, "I painted."))
        assertEquals(listOf(1L, 5L, 6L), Recall(latest = 2, related = 1).select(all, "buy canvas").map { it.id })
        assertEquals(listOf(7L, 5L, 6L), Recall(latest = 2, related = 1).select(all, "easel").map { it.id })
        assertEquals(listOf(5L, 6L), Recall(latest = 2, related = 1).select(all, "nothing relevant").map { it.id })
        assertEquals(listOf(6L), Recall(latest = 3, related = 0, charBudget = 30).select(all, "").map { it.id })
        assertEquals("Day 1 08:00-09:00: I painted.", all.last().render())
        assertEquals("Day 1 08:30, you thought: \"I want the easel.\"", easel.render())
        // Ranked: the latest memory always, then by relevance, importance and recency.
        assertEquals(listOf(7L, 6L), Recall(pick = 2).selectRanked(all, mapOf(easel.key to 1.0), now = 70).map { it.id })
    }

    @Test
    fun `memories made during a run reach later prompts`() {
        val store = InMemoryStore()
        val writer = FakeWriter()
        val memory = AgentMemory(store, "r", Summarizer(writer, store, "r", batchSize = 10))
        val prompts = mutableListOf<String>()
        val (world, runtime) = bedroomScenario(BedroomPolicy(), longTerm = memory) { d -> prompts += d.request!!.messages.last().content }
        runBlocking {
            runLockstep(world, runtime, maxTicks = 7200)
            memory.flush()
        }
        assertTrue(store.memories("r", 1).size >= 2)
        assertTrue(prompts.first().contains("What you remember").not(), "nothing to remember at the start")
        val later = prompts.first { "What you remember from earlier" in it }
        assertTrue(later.contains("I sketched and sold; the easel is next. (batch 1)"), later)
        assertNotNull(prompts.last().lines().firstOrNull { it.startsWith("- Day 1") })
    }

    @Test
    fun `memories reach a picking model through the choice set state`() {
        val (world, _) = bedroomScenario(BedroomPolicy())
        val obs = world.observe(world.agentId)
        val prompts = dev.lilguy.agent.PromptBuilder(
            dev.lilguy.agent.BedroomPrompt.system(dev.lilguy.sim.BedroomContent.default(), 5), Duration.ZERO,
            { o, r -> dev.lilguy.agent.BedroomPrompt.choices(o, r, 5, world.content) },
        )
        val plain = prompts.build(obs, emptyList(), closedChoice = true).choices!!
        assertNull(plain.state["memories"])
        val withMemory = prompts.build(obs, emptyList(), closedChoice = true, memories = listOf("Day 1: I sketched.")).choices!!
        assertEquals("Day 1: I sketched.", withMemory.state["memories"]!!.toString().trim('[', ']', '"'))
        assertTrue("state.memories" in withMemory.instructions)
        assertEquals(plain.options, withMemory.options)
    }
}
