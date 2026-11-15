package dev.lilguy.agent.mail

import dev.lilguy.agent.PromptBuilder
import dev.lilguy.agent.bedroomScenario
import dev.lilguy.agent.memory.AgentMemory
import dev.lilguy.agent.memory.EventKind
import dev.lilguy.agent.memory.InMemoryStore
import dev.lilguy.agent.runLockstep
import dev.lilguy.decision.ActionSchema
import dev.lilguy.decision.Capabilities
import dev.lilguy.decision.Decision
import dev.lilguy.decision.DecisionModel
import dev.lilguy.decision.DecisionRequest
import dev.lilguy.decision.Usage
import dev.lilguy.sim.Letter
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration

/** Opens a letter when one is waiting, writes one when it can, otherwise waits. */
private class MailDecider : DecisionModel {
    var decisions = 0
    override val id = "mail-decider"
    override val capabilities = Capabilities()

    override suspend fun <A> decide(request: DecisionRequest, schema: ActionSchema<A>): Decision<A> {
        decisions++
        val open = PromptBuilder.observationOf(request)!!.activities.orEmpty().filter { it.locked == null }.map { it.id }
        val verb = listOf("read_mail", "write_letter").firstOrNull { it in open }
        val raw = if (verb != null) """{"thought":"","intent":{"type":"do","activity":"$verb","what":null,"on":null}}""" else """{"thought":"","intent":{"type":"wait","ticks":150}}"""
        return Decision(schema.decode(raw), null, Usage(), Duration.ZERO, raw)
    }
}

private class FakeWriter : DecisionModel {
    val requests = mutableListOf<DecisionRequest>()
    override val id = "fake-writer"
    override val capabilities = Capabilities()

    override suspend fun <A> decide(request: DecisionRequest, schema: ActionSchema<A>): Decision<A> {
        requests += request
        val raw = """{"to":"Sam","letter":"Dear Sam, yes, I love the rain. -- him"}"""
        return Decision(schema.decode(raw), null, Usage(5, 9), Duration.ZERO, raw)
    }
}

class LetterWriterTest {
    @Test
    fun `he opens a letter, the writer answers it with the letter quoted, and it goes out through the hatch`() {
        val writer = FakeWriter()
        val store = InMemoryStore()
        val decider = MailDecider()
        val memory = AgentMemory(store, "r", letterWriter = LetterWriter(writer))
        val (world, runtime) = bedroomScenario(decider, longTerm = memory)
        world.post(Letter(7, "Sam", "Do you like the rain? What did you draw today?", sender = 3))
        runBlocking { runLockstep(world, runtime, maxTicks = 3000, shouldStop = { decider.decisions >= 4 }) }

        val prompt = writer.requests.single().messages.last().content
        assertTrue("Sam wrote: \"Do you like the rain? What did you draw today?\"" in prompt, prompt)
        assertTrue("Sam's last letter is waiting for your answer." in prompt, prompt)
        assertTrue("answer it properly" in writer.requests.single().system)
        assertEquals(listOf(3L to "Dear Sam, yes, I love the rain. -- him"), world.lettersSent.map { it.to to it.body })
        assertEquals("I wrote to Sam, answering their letter: \"Dear Sam, yes, I love the rain. -- him\"", store.eventsAfter("r", 0, 100).single { it.kind == EventKind.Letter }.text)
    }
}
