package dev.lilguy.agent

import dev.lilguy.agent.memory.MemoryReply
import dev.lilguy.decision.ActionSchema
import dev.lilguy.decision.Attempt
import dev.lilguy.decision.Capabilities
import dev.lilguy.decision.Decision
import dev.lilguy.decision.DecisionFailed
import dev.lilguy.decision.DecisionModel
import dev.lilguy.decision.DecisionRequest
import dev.lilguy.decision.SchemaHints
import dev.lilguy.decision.Usage
import dev.lilguy.sim.GameClock
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds

class TokenLedgerTest {
    private val schema = ActionSchema.of("memory", MemoryReply.serializer(), SchemaHints()) { null }
    private val request = DecisionRequest("system", emptyList())

    /** Spends 100+10 tokens a call, or fails after spending 40+0. */
    private class Spender(var fail: Boolean = false) : DecisionModel {
        override val id = "spender"
        override val capabilities = Capabilities()
        override suspend fun <A> decide(request: DecisionRequest, schema: ActionSchema<A>): Decision<A> {
            if (fail) throw DecisionFailed("bad", listOf(Attempt("x", "no"), Attempt("y", "no")), Usage(40, 0))
            return Decision(schema.decode("""{"memory":"m"}"""), null, Usage(100, 10), 5.milliseconds, "raw", listOf(Attempt("raw")))
        }
    }

    @Test
    fun `calls are bucketed by game day and purpose, failures included`() = runBlocking {
        val clock = GameClock(ticksPerMinute = 1)
        val ledger = TokenLedger()
        val inner = Spender()
        val decisions = ledger.meter(inner, TokenPurpose.Decision, TokenPrice(1_000_000.0, 0.0))
        val summaries = ledger.meter(Spender(), TokenPurpose.Summary)
        var tick = 0L
        ledger.attach({ tick }, clock)

        decisions.decide(request, schema) // day 1
        tick = GameClock.MINUTES_PER_DAY.toLong() // a day later
        decisions.decide(request, schema)
        summaries.decide(request, schema)
        inner.fail = true
        assertFailsWith<DecisionFailed> { decisions.decide(request, schema) }

        val days = ledger.byDay()
        assertEquals(listOf(1, 2), days.map { it.day })
        assertEquals(110, days[0].total.tokens)
        assertEquals(110 + 110 + 40, days[1].total.tokens)
        assertEquals(3, days[1].byPurpose.getValue(TokenPurpose.Decision).calls) // one good call, and the failed decision's two HTTP calls
        assertEquals(110, ledger.byPurpose().getValue(TokenPurpose.Summary).tokens)
        assertEquals(240.0, ledger.byPurpose().getValue(TokenPurpose.Decision).costUsd!!, 1e-9) // priced at $1 per prompt token
    }
}
