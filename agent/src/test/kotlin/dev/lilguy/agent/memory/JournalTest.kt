package dev.lilguy.agent.memory

import dev.lilguy.agent.BedroomPolicy
import dev.lilguy.agent.PromptBuilder
import dev.lilguy.agent.bedroomScenario
import dev.lilguy.agent.runLockstep
import dev.lilguy.decision.ActionSchema
import dev.lilguy.decision.Capabilities
import dev.lilguy.decision.Decision
import dev.lilguy.decision.DecisionFailed
import dev.lilguy.decision.DecisionModel
import dev.lilguy.decision.DecisionRequest
import dev.lilguy.decision.Usage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * Searches for each of [queries] while the schema allows a search, then writes. [greedy] keeps
 * searching for as long as the schema lets it.
 */
private class FakeJournalModel(
    val queries: List<String> = emptyList(),
    val greedy: Boolean = false,
    val fail: Boolean = false,
    val closedChoice: Boolean = false,
) : DecisionModel {
    val requests = mutableListOf<DecisionRequest>()
    val offeredSearch = mutableListOf<Boolean>()
    override val id = "fake-journal"
    override val capabilities = Capabilities(closedChoice = closedChoice)

    override suspend fun <A> decide(request: DecisionRequest, schema: ActionSchema<A>): Decision<A> {
        requests += request
        if (fail) throw DecisionFailed("model down", usage = Usage(7, 0))
        val canSearch = schema.tools!!.any { it.name == "search" }
        offeredSearch += canSearch
        val searchesSoFar = offeredSearch.size - 1
        val raw = if (canSearch && (greedy || searchesSoFar < queries.size)) {
            """{"step":{"type":"search","source":"memories","query":"${queries.getOrElse(searchesSoFar) { "sketch" }}"}}"""
        } else {
            """{"step":{"type":"write","entry":"I want a window. Entry ${requests.size}.","wants":[{"kind":"place","text":"a window onto a street","justification":"I could draw people instead of my desk, and sell better sketches."}]}}"""
        }
        return Decision(schema.decode(raw), null, Usage(10, 20), Duration.ZERO, raw)
    }
}

class JournalTest {

    @Test
    fun `after the last search the schema only allows writing`() {
        val model = FakeJournalModel(greedy = true)
        val session = runBlocking { JournalWriter(model, InMemoryStore(), "r", maxSearches = 2).write("p", "s", 1, 1, "08:00") }
        assertEquals(listOf(true, true, false), model.offeredSearch)
        assertEquals(2, session.searches)
        assertEquals(JournalSession.Status.Written, session.status)
        assertTrue("No searches left" in model.requests.last().messages.last().content)
    }

    @Test
    fun `entries made during a run are kept, logged, and reach every later decision`() {
        val store = InMemoryStore()
        val model = FakeJournalModel(queries = listOf("sketch"))
        val sessions = mutableListOf<JournalSession>()
        val memory = AgentMemory(store, "r", journal = JournalWriter(model, store, "r"), onJournal = { sessions += it })
        val prompts = mutableListOf<String>()
        val journals = mutableListOf<List<String>>()
        val (world, runtime) = bedroomScenario(BedroomPolicy(), longTerm = memory) { d ->
            prompts += d.request!!.messages.last().content
            journals += d.recalledJournal
        }
        runBlocking {
            runLockstep(world, runtime, maxTicks = 7200 * 2)
            memory.flush()
        }
        val entries = store.journal("r")
        assertTrue(entries.size >= 2, "entries: ${entries.size}")
        assertTrue(sessions.count { it.status == JournalSession.Status.Stored } == entries.size)
        assertEquals("a window onto a street", entries.first().wants.single().text)
        assertTrue(entries.first().label.startsWith("Day 1 "), entries.first().label)
        // The writer saw his situation in the decision prompt's words, and his persona.
        val first = model.requests.first()
        assertTrue(first.messages.first().content.startsWith("Observation:"))
        assertTrue("Choose your next action" !in first.messages.first().content)
        assertTrue(first.system.startsWith("You are intelligent, aspirational and creative"))
        // Only "wrote in my journal" goes in the event stream, never the entry itself.
        val events = store.eventsAfter("r", 0, 100_000)
        assertTrue(events.any { it.kind == EventKind.Journal && it.text == "wrote in my journal and wrote down 1 want" })
        assertTrue(events.none { "I want a window" in it.text })
        // Every decision after the first entry shows the journal, latest entry included.
        val firstShown = journals.indexOfFirst { it.isNotEmpty() }
        assertTrue(firstShown > 0)
        assertTrue(journals.drop(firstShown).all { it.isNotEmpty() })
        assertTrue(PromptBuilder.JOURNAL_HEADER in prompts[firstShown])
        assertTrue(prompts[firstShown].contains("- Day 1 ") && "Wants: a window onto a street (because I could draw people" in prompts[firstShown], prompts[firstShown])
        assertTrue(PromptBuilder.JOURNAL_HEADER !in prompts.first())
    }

    @Test
    fun `a failing writer stores nothing and the activity still completes`() {
        val store = InMemoryStore()
        val sessions = mutableListOf<JournalSession>()
        val memory = AgentMemory(store, "r", journal = JournalWriter(FakeJournalModel(fail = true), store, "r"), onJournal = { sessions += it })
        val (world, runtime) = bedroomScenario(BedroomPolicy(), longTerm = memory)
        runBlocking { runLockstep(world, runtime, maxTicks = 7200) }
        assertEquals(emptyList(), store.journal("r"))
        assertTrue(sessions.isNotEmpty() && sessions.all { it.status == JournalSession.Status.Failed && it.error == "model down" }, sessions.toString())
        assertTrue(world.snapshot().events.any { "finished journal" in it.text })
    }
}
