package dev.lilguy.agent.memory

import dev.lilguy.agent.PromptBuilder
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
import kotlin.test.assertTrue
import kotlin.time.Duration

/** Asks whenever the observation offers it, otherwise waits. Records whether ask was offered. */
private class AskingDecider : DecisionModel {
    val offered = mutableListOf<Boolean>()
    override val id = "asking-decider"
    override val capabilities = Capabilities()

    override suspend fun <A> decide(request: DecisionRequest, schema: ActionSchema<A>): Decision<A> {
        val canAsk = PromptBuilder.observationOf(request)!!.activities.orEmpty().any { it.id == "ask" && it.locked == null }
        offered += canAsk
        val raw = if (canAsk) """{"thought":"","intent":{"type":"do","activity":"ask","what":null,"on":null}}""" else """{"thought":"","intent":{"type":"wait","ticks":150}}"""
        return Decision(schema.decode(raw), null, Usage(), Duration.ZERO, raw)
    }
}

/** Writes his question, searches by opening the sketch and sell_art sections (and one that doesn't exist), and answers with a fixed line. */
private class FakeAskWriter : DecisionModel {
    val requests = mutableListOf<DecisionRequest>()
    override val id = "fake-writer"
    override val capabilities = Capabilities()

    override suspend fun <A> decide(request: DecisionRequest, schema: ActionSchema<A>): Decision<A> {
        requests += request
        val raw = when (schema.name) {
            "question" -> """{"question":"How can I make more money?"}"""
            "search" -> """{"open":["verb:sketch","verb:sell_art","no:such"]}"""
            else -> """{"answer":"Sketch at your desk, then sell the sketches at the hatch, 3 dollars each."}"""
        }
        return Decision(schema.decode(raw), null, Usage(4, 6), Duration.ZERO, raw)
    }
}

class AskTest {
    @Test
    fun `he writes a question, the oracle searches the world's docs and answers, and the answer reaches his memory and prompts`() {
        val store = InMemoryStore()
        val writer = FakeAskWriter()
        val answers = mutableListOf<Answer>()
        val memory = AgentMemory(store, "r", questioner = Questioner(writer), oracle = Oracle(writer), onAnswer = { answers += it })
        val decider = AskingDecider()
        val prompts = mutableListOf<String>()
        val (world, runtime) = bedroomScenario(decider, longTerm = memory) { d -> prompts += d.request!!.messages.last().content }
        runBlocking { runLockstep(world, runtime, maxTicks = 7200, shouldStop = { prompts.size >= 3 }) }

        // Offered, then not straight after an ask.
        assertEquals(listOf(true, false), decider.offered.take(2))
        // His question came from his situation; the oracle searched the docs' contents, then answered from what it opened.
        val (question, search, answer) = writer.requests.take(3)
        assertTrue("you have stopped to wonder" in question.system && question.messages.last().content.startsWith("Observation:"))
        assertTrue("verb:sketch: verb sketch" in search.system && "item:meal: item meal" in search.system && "How can I make more money?" in search.messages.last().content)
        assertTrue("verb:paint" !in search.system, "the docs leave out what a book hasn't taught him yet")
        assertTrue("guide:making-art: how a piece of art gets made" in search.system, "and carry the hand-written guide")
        assertTrue("## verb sketch" in answer.system && "## verb sell_art" in answer.system && "## item meal" !in answer.system)
        assertEquals(listOf("verb:sketch", "verb:sell_art"), answers.first().opened)
        // Kept: in the event stream, and in the prompts after it.
        assertEquals(listOf("I asked \"How can I make more money?\": Sketch at your desk, then sell the sketches at the hatch, 3 dollars each."), store.eventsAfter("r", 0, 100).filter { it.kind == EventKind.Answer }.map { it.text }.take(1))
        assertTrue(PromptBuilder.FOUND_HEADER !in prompts[0] && "then sell the sketches" in prompts[1])

        // Without a questioner and an oracle, ask isn't offered to models.
        assertTrue(!AgentMemory(InMemoryStore(), "r2").offers(world.content.verb("ask")!!))
    }
}
