package dev.lilguy.agent.authored

import dev.lilguy.sim.AuthoredResult
import dev.lilguy.sim.AuthoredView
import dev.lilguy.sim.BedroomWorld
import dev.lilguy.sim.Intent
import dev.lilguy.sim.IntentStatus
import dev.lilguy.sim.Observation
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Writes "<name> at <tick>"; a drawing comes back titled "Sleepy fox". */
private class FakeHandler(override val name: String) : AuthorHandler<String> {
    val kept = mutableListOf<Pair<String, AuthoredView?>>()
    val discarded = mutableListOf<String?>()
    override val writer = "fake"

    override suspend fun author(call: AuthorCall) = "$name at ${call.obs.tick}"

    override fun keep(result: String, call: AuthorCall, ended: Observation, view: AuthoredView?, deliver: (AuthoredResult) -> Unit) {
        kept += result to view
        deliver(if (name == "draw") AuthoredResult.Drawn("Sleepy fox", listOf("sleepy", "fox")) else AuthoredResult.Written(result))
    }

    override fun discard(result: String?, call: AuthorCall) {
        discarded += result
    }
}

class AuthorsTest {
    private fun BedroomWorld.finish(intent: Intent): Observation {
        submit(agentId, intent)
        do step() while (!awaitingDecision(agentId))
        return observe(agentId)
    }

    @Test
    fun `an authored result is kept only when its verb succeeds, and reaches the sim through deliver`() {
        val world = BedroomWorld.create()
        val thought = FakeHandler("thought")
        val draw = FakeHandler("draw")
        val authors = Authors(listOf(thought, draw))
        authors.connect(world)
        fun act(obs: Observation, intent: Intent) = runBlocking { authors.acted(obs, intent, "persona", "situation", "system") }
        fun think(obs: Observation, intent: Intent) = runBlocking { authors.before(obs, intent, "persona", "situation", "system") }

        // Sketching makes a piece and draws it: the drawing is kept and titles that piece.
        val sketch = Intent.Do("sketch")
        act(world.observe(world.agentId), sketch)
        val sketched = world.finish(sketch)
        authors.observed(sketched)
        val view = draw.kept.single().second!!
        assertEquals("sketch" to 1, view.verb to view.piece)
        world.step()
        assertEquals("Sleepy fox", world.observe(world.agentId).art!!.single().title)
        assertTrue(world.observe(world.agentId).authored.single { it.id == view.id }.delivered)

        // Thinking (a `before` op) succeeds: the thought is written before the verb starts, then kept when it finishes.
        val ponder = Intent.Do("think")
        assertEquals(listOf<Any>("thought at ${sketched.tick}"), think(sketched, ponder))
        val thought1 = world.finish(ponder)
        authors.observed(thought1)
        assertEquals(listOf("thought at ${sketched.tick}"), thought.kept.map { it.first })

        // Locked right after a think (no_repeat): nothing to write.
        assertEquals(emptyList(), think(thought1, ponder))
        // The same verb, but it didn't succeed: the result is thrown away and nothing is delivered.
        think(sketched, ponder)
        authors.observed(sketched.copy(tick = sketched.tick + 1, lastResult = IntentStatus.Interrupted))
        assertEquals(listOf<String?>("thought at ${sketched.tick}"), thought.discarded)
        assertEquals(1, thought.kept.size)
        // A verb whose essential op has no handler isn't offered; a missing artist doesn't hide sketching.
        val noWriter = Authors(listOf(draw))
        assertTrue(!noWriter.offers(world.content.verb("think")!!) && noWriter.offers(world.content.verb("sketch")!!) && Authors(emptyList()).offers(world.content.verb("sketch")!!))
    }
}
