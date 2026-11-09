package dev.lilguy.agent

import dev.lilguy.agent.memory.AgentMemory
import dev.lilguy.agent.memory.RunMemoryStore
import dev.lilguy.decision.DecisionModel
import dev.lilguy.runlog.CheckpointRow
import dev.lilguy.runlog.Ids
import dev.lilguy.runlog.RunRecorder
import dev.lilguy.runlog.RunRow
import dev.lilguy.runlog.RunStore
import dev.lilguy.runlog.SessionRow
import dev.lilguy.runlog.jdbc.JdbcSqlDriver
import dev.lilguy.sim.BedroomWorld
import dev.lilguy.sim.GameClock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Saves are an explicit state export, so this checks the export against the one thing that can't
 * drift: the sim is deterministic in lockstep. Three days with recorded replies; restore the day-3
 * wake checkpoint in a fresh world, replay day 3 from the same replies, and day 3 writes the same
 * rows as the first time.
 */
class CheckpointRoundTripTest {
    private val tpm = GameClock.DEFAULT_TICKS_PER_MINUTE
    private val end = 3L * GameClock.MINUTES_PER_DAY * tpm

    /** One session of [runId] in [db], from [from] or from the start, to [end]. */
    private fun live(db: RunStore, runId: String, model: DecisionModel, from: Carried? = null, onDecision: (AgentRuntime.DecisionRecord) -> Unit = {}): LifeRecorder {
        val recorder = RunRecorder(db, runId, Ids.uuid7())
        val life = LifeRecorder(recorder)
        val memoryStore = RunMemoryStore(db, recorder, RunMemoryStore.lineage(db, from?.resume?.checkpoint?.id))
        val memory = AgentMemory(memoryStore, runId)
        val (world, runtime) = Scenario.Bedroom.create(model, longTerm = memory, from = from?.resume?.world) { d ->
            life.decision(d)
            onDecision(d)
        }
        from?.resume?.agent?.let { a ->
            runtime.restore(a.runtime)
            a.memory?.let(memory::restore)
        }
        val run = from?.run ?: RunRow(id = runId, scenario = "bedroom", platform = "test", host = null, gitSha = null, gitDirty = null, contentHash = null, seed = 0, ticksPerMinute = tpm, models = JsonObject(emptyMap()), startedAt = Ids.now())
        life.begin(run, SessionRow(recorder.sessionId, runId, from?.resume?.checkpoint?.id, null, null, null, "test", null, Ids.now()), null)
        life.attach(world as BedroomWorld, runtime, memory, memoryStore)
        runBlocking {
            runLockstep(world, runtime, end, beforeStep = life::beforeStep, afterStep = life::afterStep)
            life.settle(world.observe(world.agentId))
            recorder.flush()
        }
        recorder.close()
        return life
    }

    /** What [session] wrote from [tick] on, without the ids, seqs and session that differ between the two. */
    private fun rows(db: RunStore, session: String, tick: Long): Map<String, List<List<Any?>>> = mapOf(
        "decisions" to "SELECT tick, intent, thought, fallback, outcome_status, outcome_text FROM decisions WHERE session_id = ? AND tick >= ? ORDER BY seq",
        "memory_events" to "SELECT tick, kind, text FROM memory_events WHERE session_id = ? AND tick >= ? ORDER BY seq",
        // Changes made during the checkpoint's own tick were logged before it was taken.
        "ledger_entries" to "SELECT tick, amount, balance, reason FROM ledger_entries WHERE session_id = ? AND tick > ? ORDER BY seq",
        "need_changes" to "SELECT tick, need, delta, value, reason FROM need_changes WHERE session_id = ? AND tick > ? ORDER BY seq",
        "hourly_state" to "SELECT tick, money, needs, doing FROM hourly_state WHERE session_id = ? AND tick > ? ORDER BY seq",
    ).mapValues { (_, sql) -> db.query(sql, listOf(session, tick)).map { it.values.toList() } }

    @Test
    fun `a restored day 3 writes the rows the original did`() {
        val db = JdbcSqlDriver.store(null)
        val runId = Ids.uuid7()
        val replies = mutableListOf<DecisionLogEntry>()
        val first = live(db, runId, BedroomPolicy()) { replies += DecisionLog.entryOf(runId, "scripted:bedroom", it) }
        val saved: CheckpointRow = first.checkpoints.firstOrNull { it.day == 3 } ?: error("no day-3 checkpoint; got days ${first.checkpoints.map { it.day }}")

        val carried = carried(db, runId).let { Carried(it.run, Resume(saved)) }
        val again = live(db, runId, ReplayDecisionModel(replies.filter { it.tick >= saved.tick }), from = carried)

        val before = rows(db, first.sessionId, saved.tick)
        val after = rows(db, again.sessionId, saved.tick)
        assertTrue(before.getValue("decisions").size > 5 && before.getValue("need_changes").isNotEmpty(), "day 3 should have a day's worth of rows: ${before.mapValues { it.value.size }}")
        before.keys.forEach { t -> assertEquals(before.getValue(t), after.getValue(t), "$t differ after the restore") }
        db.close()
    }
}
