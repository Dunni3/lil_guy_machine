package dev.lilguy.agent.invent

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
import dev.lilguy.sim.BedroomContent
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration

/** Invents whenever it can, otherwise waits. */
private class InventingDecider : DecisionModel {
    var decisions = 0
    override val id = "inventing-decider"
    override val capabilities = Capabilities()

    override suspend fun <A> decide(request: DecisionRequest, schema: ActionSchema<A>): Decision<A> {
        decisions++
        val can = PromptBuilder.observationOf(request)!!.activities.orEmpty().any { it.id == "invent" && it.locked == null }
        val raw = if (can) """{"thought":"","intent":{"type":"do","activity":"invent","what":null,"on":null}}""" else """{"thought":"","intent":{"type":"wait","ticks":150}}"""
        return Decision(schema.decode(raw), null, Usage(), Duration.ZERO, raw)
    }
}

/** Replies with [replies] in turn, the last one from then on. */
private class FakeDesigner(private val replies: List<String>) : DecisionModel {
    val requests = mutableListOf<DecisionRequest>()
    override val id = "fake-designer"
    override val capabilities = Capabilities()

    override suspend fun <A> decide(request: DecisionRequest, schema: ActionSchema<A>): Decision<A> {
        val raw = replies[minOf(requests.size, replies.size - 1)]
        requests += request
        return Decision(schema.decode(raw), null, Usage(7, 11), Duration.ZERO, raw)
    }
}

class InventorTest {
    private val content = BedroomContent.default().let { it.copy(start = it.start.copy(money = 100, items = it.start.items + ("workbench" to 1))) }

    /** A lamp that is calm 2, natural 2 and cozy [cozy]: with cozy 3 it adds up to 7, over the quality budget of 6. */
    private fun design(cozy: Int) =
        """{"name":"moss lamp","use":"viewable","qualities":[{"quality":"calm","strength":2},{"quality":"natural","strength":2},{"quality":"cozy","strength":$cozy}],"place":"floor","look":[{"shape":"sphere","w":0.3,"h":0.3,"d":0.3,"x":0,"y":0,"z":0,"rot_x":0,"rot_y":0,"rot_z":0,"color":"#557755"}],"book":null,"why":"the room needs something green"}"""

    @Test
    fun `the writer's design is redone once when it's refused, built by the sim and remembered, and a design that stays refused fails the verb`() {
        // First a design over the quality budget, then a good one: the inventor asks again with the reason.
        val designer = FakeDesigner(listOf(design(3), design(1)))
        val store = InMemoryStore()
        val drafts = mutableListOf<Drafted>()
        val memory = AgentMemory(store, "r", inventor = Inventor(designer), onDraft = { drafts += it })
        val decider = InventingDecider()
        val (world, runtime) = bedroomScenario(decider, content = content, longTerm = memory)
        runBlocking { runLockstep(world, runtime, maxTicks = 3000, shouldStop = { decider.decisions >= 3 }) }

        assertEquals(2, designer.requests.size)
        assertTrue("add up to at most 6" in designer.requests[1].messages.last().content, designer.requests[1].messages.last().content)
        assertTrue("You're at your workbench with $100" in designer.requests[0].messages.first().content)
        assertEquals("moss_lamp", world.inventions.single().item.id)
        assertEquals(100 - (4 + 5 * 5), world.ledger.balance)
        assertEquals(1, drafts.single().redid.size)
        val event = store.eventsAfter("r", 0, 100).single { it.kind == EventKind.Invention }
        assertEquals("I invented the moss lamp and built it at my workbench (calm, natural, cozy): the room needs something green.", event.text)

        // A design that's still refused goes to the sim as it is, and the verb fails with the reason.
        val stubborn = InventingDecider()
        val failed = AgentMemory(InMemoryStore(), "r2", inventor = Inventor(FakeDesigner(listOf(design(3)))))
        val (w2, r2) = bedroomScenario(stubborn, content = content, longTerm = failed)
        runBlocking { runLockstep(w2, r2, maxTicks = 3000, shouldStop = { stubborn.decisions >= 2 }) }
        assertTrue(w2.inventions.isEmpty())
        assertEquals(100, w2.ledger.balance)
        assertTrue(w2.observe(w2.agentId).recentEvents.any { "the moss lamp didn't work" in it }, w2.observe(w2.agentId).recentEvents.toString())
    }
}
