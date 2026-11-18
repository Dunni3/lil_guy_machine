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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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

/**
 * Calls the workbench's tools as a backend would for a model: two shapes, then `build`, which the
 * look-over sends back once, then `build` again.
 */
private class BenchWorker : DecisionModel {
    val answers = mutableListOf<String>()
    val refused = mutableListOf<String>()
    override val id = "bench-worker"
    override val capabilities = Capabilities(toolCalls = true)

    override suspend fun <A> decide(request: DecisionRequest, schema: ActionSchema<A>): Decision<A> {
        val bench = request.tools!!
        fun part(json: String) = Json.parseToJsonElement(json) as JsonObject
        val add = bench.looks.single { it.name == "add_part" }
        answers += add.answer(part("""{"shape":"cylinder","w":0.3,"h":0.3,"d":0.3,"x":0,"y":0,"z":0,"rot_x":0,"rot_y":0,"rot_z":0,"color":"#8a6a4a","part":"pot","taper":0.8,"cut":1,"hole":0.85,"arc":360,"glow":0,"opacity":1,"material":"wood"}"""))
        answers += add.answer(part("""{"shape":"sphere","w":0.3,"h":0.2,"d":0.3,"x":0,"y":0.3,"z":0,"rot_x":0,"rot_y":0,"rot_z":0,"color":"#557755","part":"moss","taper":1,"cut":0.5,"hole":0,"arc":360,"glow":0,"opacity":1,"material":"wood"}"""))
        val build = bench.tools.single()
        val args = part("""{"name":"moss pot","use":"viewable","qualities":[{"quality":"natural","strength":2}],"place":"floor","book":null,"why":"something green"}""")
        try {
            build.reply(args)
        } catch (e: ActionSchema.InvalidReply) {
            refused += e.message.orEmpty()
        }
        val raw = build.reply(args).toString()
        return Decision(schema.decode(raw), null, Usage(), Duration.ZERO, raw)
    }
}

class InventorTest {
    // One build a day, so the decider's later turns don't invent again.
    private val content = BedroomContent.default().let { it.copy(start = it.start.copy(money = 100, items = it.start.items + mapOf("workbench" to 1, "wood" to 20)), invent = it.invent!!.copy(perDay = 1)) }

    /** A lamp that is calm 2, natural 2 and cozy [cozy]: with cozy 3 it adds up to 7, over the quality budget of 6. */
    private fun design(cozy: Int) =
        """{"name":"pebble lamp","use":"viewable","qualities":[{"quality":"calm","strength":2},{"quality":"natural","strength":2},{"quality":"cozy","strength":$cozy}],"place":"floor","look":[{"shape":"sphere","w":0.3,"h":0.3,"d":0.3,"x":0,"y":0,"z":0,"rot_x":0,"rot_y":0,"rot_z":0,"color":"#557755","part":"moss","taper":1,"cut":1,"hole":0,"arc":360,"glow":0,"opacity":1,"material":"wood"}],"book":null,"why":"the room needs something green","variants":[],"runs":[],"footprint":null,"door":null,"windows":[],"where":null,"recipes":[],"products":[],"send_to":null,"take_from":[]}"""

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

        // Refused, redone, then looked over once it can be built.
        assertEquals(3, designer.requests.size)
        assertTrue("add up to at most 6" in designer.requests[1].messages.last().content, designer.requests[1].messages.last().content)
        assertTrue("look it over" in designer.requests[2].messages.last().content, designer.requests[2].messages.last().content)
        assertTrue("You're at your workbench with $100" in designer.requests[0].messages.first().content)
        assertEquals("pebble_lamp", world.inventions.single().item.id)
        // Built from his wood (3 for the moss, 5 for its qualities), not his money.
        assertEquals(100, world.ledger.balance)
        assertEquals(mapOf("wood" to 8), world.inventions.single().bill)
        assertEquals(1, drafts.single().redid.size)
        val event = store.eventsAfter("r", 0, 100).single { it.kind == EventKind.Invention }
        assertEquals("I invented the pebble lamp and built it at my workbench (calm, natural, cozy): the room needs something green.", event.text)

        // A design that's still refused goes to the sim as it is, and the verb fails with the reason.
        val stubborn = InventingDecider()
        val failed = AgentMemory(InMemoryStore(), "r2", inventor = Inventor(FakeDesigner(listOf(design(3)))))
        val (w2, r2) = bedroomScenario(stubborn, content = content, longTerm = failed)
        runBlocking { runLockstep(w2, r2, maxTicks = 3000, shouldStop = { stubborn.decisions >= 2 }) }
        assertTrue(w2.inventions.isEmpty())
        assertEquals(100, w2.ledger.balance)
        assertTrue(w2.observe(w2.agentId).recentEvents.any { "the pebble lamp didn't work" in it }, w2.observe(w2.agentId).recentEvents.toString())
    }

    @Test
    fun `a writer that calls tools puts the thing together at the workbench and builds it after one look-over`() {
        val worker = BenchWorker()
        val decider = InventingDecider()
        val (world, runtime) = bedroomScenario(decider, content = content, longTerm = AgentMemory(InMemoryStore(), "r", inventor = Inventor(worker)))
        runBlocking { runLockstep(world, runtime, maxTicks = 3000, shouldStop = { decider.decisions >= 3 }) }

        assertTrue(worker.answers[1].startsWith("Added shape 2 (moss)"), worker.answers[1])
        assertTrue("look it over" in worker.refused.single(), worker.refused.toString())
        val made = world.inventions.single()
        assertEquals("moss_pot", made.item.id)
        assertEquals(listOf("pot", "moss"), made.look.map { it.part })
        assertEquals(0.85, made.look[0].hole)
    }
}
