package dev.lilguy.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LedgerTest {
    private val clock = GameClock()

    @Test
    fun `every change is a transaction and the balance is their sum`() {
        val ledger = Ledger(40, clock)
        ledger.debit(10, 18, "bought 3 meals")
        ledger.credit(400, 24, "sold 2 sketches")
        assertEquals(46, ledger.balance)
        assertEquals(40 + ledger.transactions.sumOf { it.amount }, ledger.balance)
        val (spent, earned) = ledger.transactions
        assertEquals(Transaction(10, 1, "08:02", -18, 22, "bought 3 meals"), spent)
        assertEquals(46, earned.balance)
        assertEquals("sold 2 sketches", earned.reason)
    }

    @Test
    fun `money can't go below zero`() {
        val ledger = Ledger(10, clock)
        assertFalse(ledger.canAfford(11))
        assertFailsWith<IllegalArgumentException> { ledger.debit(0, 11, "easel") }
        assertEquals(10, ledger.balance)
        assertTrue(ledger.transactions.isEmpty())
        assertFailsWith<IllegalArgumentException> { ledger.credit(0, 0, "nothing") }
    }

    @Test
    fun `needs clamp to 0 to 100 and log what was applied`() {
        val needs = Needs(NeedLevels(energy = 98, fullness = 3, mood = 50), clock)
        assertEquals(2.0, needs.change(0, Need.Energy, 10.0, "sleep"))
        assertEquals(-3.0, needs.change(0, Need.Fullness, -5.0, "hunger"))
        assertEquals(NeedLevels(100, 0, 50), needs.levels())
        assertEquals(listOf(2.0, -3.0), needs.changes.map { it.delta })
    }

    @Test
    fun `rates are logged once per need and reason at flush`() {
        val needs = Needs(NeedLevels(80, 70, 60), clock)
        repeat(60) { needs.change(it.toLong(), Need.Energy, -4.0 / 60, "awake", rate = true) }
        repeat(60) { needs.change(it.toLong(), Need.Fullness, -5.0 / 60, "hunger", rate = true) }
        assertTrue(needs.changes.isEmpty())
        needs.flush(300)
        assertEquals(listOf("awake" to -4.0, "hunger" to -5.0), needs.changes.map { it.reason to it.delta })
        assertEquals(76.0, needs.changes.first().value)
        assertEquals(ClockReading(1, "09:00"), ClockReading(needs.changes.first().day, needs.changes.first().time))
        needs.flush(600)
        assertEquals(2, needs.changes.size)
    }
}
