package dev.lilguy.agent

import dev.lilguy.agent.memory.AgentMemory
import dev.lilguy.agent.memory.InMemoryStore
import dev.lilguy.runlog.BlobRow
import dev.lilguy.runlog.ContentVersionRow
import dev.lilguy.runlog.DecisionRow
import dev.lilguy.runlog.HourRow
import dev.lilguy.runlog.JsonlRecorder
import dev.lilguy.runlog.LedgerRow
import dev.lilguy.runlog.MemoryEventRow
import dev.lilguy.runlog.ModelCallRow
import dev.lilguy.runlog.NeedChangeRow
import dev.lilguy.runlog.RunEndRow
import dev.lilguy.runlog.RunRow
import dev.lilguy.runlog.RunScoped
import dev.lilguy.runlog.Rows
import dev.lilguy.runlog.SessionRow
import dev.lilguy.sim.BedroomWorld
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecordingTest {
    @Test
    fun `a scripted day records every kind of row, in seq order, and every row reads back`() {
        val file = File.createTempFile("rows", ".jsonl").also { it.delete() }
        val recording = Recording(JsonlRecorder(file))
        val ledger = TokenLedger().also { it.onCall = recording::call }
        val store = recording.store(InMemoryStore())
        val decisions = mutableListOf<AgentRuntime.DecisionRecord>()
        val policy = ledger.meter(BedroomPolicy(), TokenPurpose.Decision)
        val (world, runtime) = bedroomScenario(policy, longTerm = AgentMemory(store, "r1")) { d ->
            decisions += d
            recording.decision(d, policy.id)
        }
        val bedroom = world as BedroomWorld
        ledger.attach({ world.tick }, bedroom.clock)
        recording.attach({ world.tick }, bedroom.clock)
        recording.start(Recording.RunInfo(Scenario.Bedroom, platform = "test", ticksPerMinute = bedroom.clock.ticksPerMinute))
        runBlocking { runLockstep(world, runtime, maxTicks = 7200) { recording.afterTick(bedroom) } }
        recording.end("lived 1 day")
        recording.close()

        val rows = Rows.read(file)
        val runRows = rows.filterIsInstance<RunScoped>()
        assertEquals(runRows.indices.map { it.toLong() }, runRows.map { it.seq }, "one counter, in the order written")
        assertTrue(runRows.all { it.runId == recording.runId })
        assertEquals(1, rows.count { it is RunRow })
        assertEquals(1, rows.count { it is SessionRow })
        assertEquals(1, rows.count { it is ContentVersionRow })
        assertEquals(decisions.size, rows.count { it is DecisionRow })
        assertEquals(ledger.calls().size, rows.count { it is ModelCallRow })
        assertEquals(bedroom.ledger.transactions.size, rows.count { it is LedgerRow })
        assertEquals(bedroom.needs.changes.size, rows.count { it is NeedChangeRow })
        assertEquals(24, rows.count { it is HourRow }, "08:00 to 07:00 the next morning, on the hour")
        assertTrue(rows.count { it is MemoryEventRow } > decisions.size / 2)
        assertEquals(1, rows.count { it is RunEndRow })
        // Observations and requests are stored once each by hash, and every reference resolves.
        val blobs = rows.filterIsInstance<BlobRow>().associateBy { it.hash }
        assertTrue(rows.filterIsInstance<DecisionRow>().all { d -> listOfNotNull(d.observationBlob, d.requestBlob).all { it in blobs } })
        assertEquals(blobs.size, rows.count { it is BlobRow })
        file.delete()
    }
}
