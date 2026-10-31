package dev.lilguy.agent.memory

import dev.lilguy.agent.bedroomScenario
import dev.lilguy.agent.runLockstep
import dev.lilguy.decision.ActionSchema
import dev.lilguy.decision.Capabilities
import dev.lilguy.decision.Decision
import dev.lilguy.decision.DecisionModel
import dev.lilguy.decision.DecisionRequest
import dev.lilguy.decision.Usage
import dev.lilguy.sim.Intent
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration

/** Thinks whenever the observation offers it, otherwise waits. Records whether think was offered. */
private class ThinkingDecider : DecisionModel {
    val offered = mutableListOf<Boolean>()
    override val id = "thinking-decider"
    override val capabilities = Capabilities()

    override suspend fun <A> decide(request: DecisionRequest, schema: ActionSchema<A>): Decision<A> {
        val canThink = dev.lilguy.agent.PromptBuilder.observationOf(request)!!.activities.orEmpty().any { it.id == "think" && it.locked == null }
        offered += canThink
        val raw = if (canThink) """{"thought":"","intent":{"type":"do","activity":"think","what":null,"on":null}}""" else """{"thought":"","intent":{"type":"wait","ticks":150}}"""
        return Decision(schema.decode(raw), null, Usage(), Duration.ZERO, raw)
    }
}

/** Never thinks: waits every time. Counts its calls. */
private class WaitingDecider : DecisionModel {
    var calls = 0
    override val id = "waiting-decider"
    override val capabilities = Capabilities()

    override suspend fun <A> decide(request: DecisionRequest, schema: ActionSchema<A>): Decision<A> {
        calls++
        val raw = """{"thought":"","intent":{"type":"wait","ticks":150}}"""
        return Decision(schema.decode(raw), null, Usage(), Duration.ZERO, raw)
    }
}

private class FakeThinkerModel : DecisionModel {
    val requests = mutableListOf<DecisionRequest>()
    override val id = "fake-thinker"
    override val capabilities = Capabilities()

    override suspend fun <A> decide(request: DecisionRequest, schema: ActionSchema<A>): Decision<A> {
        requests += request
        val raw = """{"thought":"I'll sketch until I can afford an easel."}"""
        return Decision(schema.decode(raw), null, Usage(5, 9), Duration.ZERO, raw)
    }
}

class ThinkTest {

    @Test
    fun `a think puts one thought in the event stream and the next prompt, costs energy, and can't repeat at once`() {
        val store = InMemoryStore()
        val thinker = FakeThinkerModel()
        val memory = AgentMemory(store, "r", thinker = Thinker(thinker))
        val decider = ThinkingDecider()
        val prompts = mutableListOf<String>()
        val thoughts = mutableListOf<Thought?>()
        val (world, runtime) = bedroomScenario(decider, longTerm = memory) { d ->
            prompts += d.request!!.messages.last().content
            thoughts += d.thought
        }
        val energyBefore = world.needs[dev.lilguy.sim.Need.Energy]
        runBlocking { runLockstep(world, runtime, maxTicks = 7200, shouldStop = { thoughts.size >= 3 }) }

        // Offered, then not straight after a think, then offered again.
        assertEquals(listOf(true, false, true), decider.offered.take(3))
        assertEquals("I'll sketch until I can afford an easel.", thoughts.first()?.text)
        assertEquals(null, thoughts[1])
        // The thinker saw the decision prompt and its situation, with its own ask.
        val call = thinker.requests.first()
        assertTrue(call.system.startsWith("You are intelligent, aspirational and creative") && "you have stopped to think" in call.system)
        assertTrue(call.messages.single().content.startsWith("Observation:") && call.messages.single().content.endsWith(Thinker.ASK))
        // In the event stream as a thought, and in the next decision's recent actions.
        assertTrue(store.eventsAfter("r", 0, 1000).any { it.kind == EventKind.Thought && it.text == "I'll sketch until I can afford an easel." })
        assertTrue("you thought: \"I'll sketch until I can afford an easel.\"" in prompts[1], prompts[1])
        // Ten minutes awake (4 an hour) plus the think's 2.
        assertTrue(world.snapshot().events.any { it.text == "finished think: energy -2" })
        assertTrue(world.needs[dev.lilguy.sim.Need.Energy] < energyBefore - 2)
        assertEquals(Intent.Do("think"), runtime.recent.first().intent)
    }

    @Test
    fun `every fourth decision is a forced think, and his latest thought is in every later prompt`() {
        val store = InMemoryStore()
        val memory = AgentMemory(store, "r", thinker = Thinker(FakeThinkerModel()))
        val decider = WaitingDecider()
        val records = mutableListOf<dev.lilguy.agent.AgentRuntime.DecisionRecord>()
        val (world, runtime) = bedroomScenario(decider, longTerm = memory) { records += it }
        runBlocking { runLockstep(world, runtime, maxTicks = 72000, shouldStop = { records.size >= 10 }) }

        val intents = records.take(10).map { it.intent }
        assertEquals(listOf(4, 9), intents.withIndex().filter { it.value == Intent.Do("think") }.map { it.index })
        assertEquals(8, decider.calls) // the forced thinks didn't ask the decision model
        val after = records[5].request!!.messages.last().content
        assertTrue(dev.lilguy.agent.PromptBuilder.THOUGHT_HEADER in after && "I'll sketch until I can afford an easel." in after, after)
        assertTrue(dev.lilguy.agent.PromptBuilder.THOUGHT_HEADER !in records[4].request!!.messages.last().content)
    }
}
