package dev.lilguy.agent.memory

import dev.lilguy.agent.PromptBuilder
import dev.lilguy.agent.bedroomScenario
import dev.lilguy.agent.runLockstep
import dev.lilguy.decision.ActionSchema
import dev.lilguy.decision.Capabilities
import dev.lilguy.decision.Decision
import dev.lilguy.decision.DecisionModel
import dev.lilguy.decision.DecisionRequest
import dev.lilguy.decision.Message
import dev.lilguy.decision.Usage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration

/** Thinks whenever the observation offers it, otherwise waits. */
private class AskThinkingDecider : DecisionModel {
    override val id = "thinking-decider"
    override val capabilities = Capabilities()

    override suspend fun <A> decide(request: DecisionRequest, schema: ActionSchema<A>): Decision<A> {
        val canThink = PromptBuilder.observationOf(request)!!.activities.orEmpty().any { it.id == "think" && it.locked == null }
        val raw = if (canThink) """{"thought":"","intent":{"type":"do","activity":"think","what":null,"on":null}}""" else """{"thought":"","intent":{"type":"wait","ticks":150}}"""
        return Decision(schema.decode(raw), null, Usage(), Duration.ZERO, raw)
    }
}

/**
 * Thinks by asking once, then saying a thought. As the oracle it searches by opening the sketch and
 * sell_art sections (and one that doesn't exist) and answers with a fixed line.
 */
private class FakeAskWriter : DecisionModel {
    val requests = mutableListOf<DecisionRequest>()
    override val id = "fake-writer"
    override val capabilities = Capabilities()

    override suspend fun <A> decide(request: DecisionRequest, schema: ActionSchema<A>): Decision<A> {
        requests += request
        val raw = when (schema.name) {
            "think" -> if (request.messages.size == 1) """{"ask":"How can I make more money?","thought":null}""" else """{"ask":null,"thought":"I'll sketch and sell at the hatch."}"""
            "search" -> """{"open":["verb:sketch","verb:sell_art","no:such"]}"""
            else -> """{"answer":"Sketch at your desk, then sell the sketches at the hatch, 3 dollars each."}"""
        }
        return Decision(schema.decode(raw), null, Usage(4, 6), Duration.ZERO, raw)
    }
}

class AskTest {
    @Test
    fun `while he thinks he asks, the oracle searches the world's docs and answers, and both reach his memory and prompts`() {
        val store = InMemoryStore()
        val writer = FakeAskWriter()
        val answers = mutableListOf<Answer>()
        val memory = AgentMemory(store, "r", thinker = Thinker(writer, oracle = Oracle(writer)), onAnswer = { answers += it })
        val prompts = mutableListOf<String>()
        val (world, runtime) = bedroomScenario(AskThinkingDecider(), longTerm = memory) { d -> prompts += d.request!!.messages.last().content }
        runBlocking { runLockstep(world, runtime, maxTicks = 7200, shouldStop = { prompts.size >= 2 }) }

        // He asked from his situation; the oracle searched the docs' contents, then answered from what it opened.
        val (ask, search, answer, thought) = writer.requests.take(4)
        assertTrue("you can ask how something in your world works" in ask.system && ask.messages.single().content.startsWith("Observation:"))
        assertTrue("verb:sketch: verb sketch" in search.system && "item:meal: item meal" in search.system && "How can I make more money?" in search.messages.last().content)
        assertTrue("verb:paint" !in search.system, "the docs leave out what a book hasn't taught him yet")
        assertTrue("guide:making-art: how a piece of art gets made" in search.system, "and carry the hand-written guide")
        assertTrue("## verb sketch" in answer.system && "## verb sell_art" in answer.system && "## item meal" !in answer.system)
        assertEquals(listOf("verb:sketch", "verb:sell_art"), answers.single().opened)
        // The answer came back into his thinking before the thought.
        assertEquals(listOf(Message.Role.User, Message.Role.Assistant, Message.Role.User), thought.messages.map { it.role })
        assertTrue(thought.messages.last().content.startsWith("The answer: Sketch at your desk"))
        // Kept: the answer, then the thought, in the event stream; the answer in the next prompt.
        assertEquals(
            listOf(EventKind.Answer to "I asked \"How can I make more money?\": Sketch at your desk, then sell the sketches at the hatch, 3 dollars each.", EventKind.Thought to "I'll sketch and sell at the hatch."),
            store.eventsAfter("r", 0, 100).filter { it.kind == EventKind.Answer || it.kind == EventKind.Thought }.map { it.kind to it.text }.take(2),
        )
        assertTrue(PromptBuilder.FOUND_HEADER !in prompts[0] && "then sell the sketches" in prompts[1])
    }
}
