package dev.lilguy.agent.memory

import dev.lilguy.runlog.MemoryEventRow
import dev.lilguy.runlog.MemoryRow
import dev.lilguy.runlog.RunRecorder
import dev.lilguy.runlog.RunStore
import dev.lilguy.sim.GameClock
import java.util.concurrent.atomic.AtomicLong

/**
 * The memory hierarchy in the run database: `memory_events` and `memories`, the rows that sync to
 * the server with the rest of the run. Writes go straight to the store (callers need the new id),
 * with their `seq` from the [recorder].
 *
 * A resumed life sees only its own past. The session that resumed from a checkpoint sees its own
 * rows, plus each earlier session's rows up to the checkpoint it carried on from ([lineage]); what
 * an abandoned branch wrote after that (the day after the last save, before the TV was restarted)
 * stays in the database, out of sight. Ids are per run and never reused, branch or not.
 *
 * The `run` every method takes is the run id; this store answers for [recorder]'s run only.
 */
class RunMemoryStore(
    private val store: RunStore,
    private val recorder: RunRecorder,
    /** The sessions whose rows he remembers, and the highest event and memory id of each; this session's own are unlimited. */
    private val lineage: List<Visible> = emptyList(),
) : MemoryStore {
    data class Visible(val session: String, val maxEvent: Long, val maxMemory: Long)

    private val run = recorder.runId
    private val session = recorder.sessionId
    private val nextEvent = AtomicLong(max("memory_events"))
    private val nextMemory = AtomicLong(max("memories"))
    @Volatile private var lastTick = 0L
    /** The highest event and memory id this life remembers, kept here so a checkpoint on the sim thread needn't query. */
    @Volatile private var markEvent = lineage.firstOrNull()?.maxEvent ?: 0L
    @Volatile private var markMemory = lineage.firstOrNull()?.maxMemory ?: 0L

    private fun max(table: String) = (store.query("SELECT MAX(id) AS m FROM $table WHERE run_id = ?", listOf(run)).first()["m"] as Long?) ?: 0L

    /** SQL that keeps rows this life remembers, and its arguments. */
    private fun visible(maxOf: (Visible) -> Long): Pair<String, List<Any?>> {
        val parts = listOf("session_id = ?") + lineage.map { "(session_id = ? AND id <= ?)" }
        return "(" + parts.joinToString(" OR ") + ")" to (listOf<Any?>(session) + lineage.flatMap { listOf(it.session, maxOf(it)) })
    }

    private fun check(r: String) = require(r == run) { "this store holds run $run, not $r" }

    override fun appendEvent(run: String, tick: Long, day: Int?, time: String?, kind: EventKind, text: String): Long {
        check(run)
        val id = nextEvent.incrementAndGet()
        lastTick = tick
        markEvent = id
        store.write(MemoryEventRow(run, session, recorder.next(), tick, day, time?.let(GameClock::parseHhmm), id, time, kind.id, text))
        return id
    }

    override fun eventsAfter(run: String, afterId: Long, limit: Int): List<MemoryEvent> {
        check(run)
        val (where, args) = visible { it.maxEvent }
        return store.query(
            "SELECT id, tick, day, time, kind, text, significance FROM memory_events WHERE run_id = ? AND id > ? AND $where ORDER BY id LIMIT ?",
            listOf(run, afterId) + args + limit,
        ).map { r ->
            MemoryEvent(r["id"] as Long, run, r["tick"] as Long, (r["day"] as Long?)?.toInt(), r["time"] as String?, EventKind.of(r["kind"] as String), r["text"] as String, (r["significance"] as Number?)?.toDouble())
        }
    }

    override fun countEventsAfter(run: String, afterId: Long): Int {
        check(run)
        val (where, args) = visible { it.maxEvent }
        return (store.query("SELECT COUNT(*) AS n FROM memory_events WHERE run_id = ? AND id > ? AND $where", listOf(run, afterId) + args).first()["n"] as Long).toInt()
    }

    override fun setSignificance(run: String, id: Long, significance: Double) {
        check(run)
        store.execute("UPDATE memory_events SET significance = ?, _rev = _rev + 1, _dirty = 1 WHERE run_id = ? AND id = ?", listOf(significance, run, id))
    }

    override fun addMemory(run: String, level: Int, fromId: Long, toId: Long, span: Span, text: String, source: String, importance: Double?): Long {
        check(run)
        val id = nextMemory.incrementAndGet()
        markMemory = id
        store.write(
            MemoryRow(
                run, session, recorder.next(), lastTick, span.toDay, span.toTime?.let(GameClock::parseHhmm),
                id, level, fromId, toId, span.fromDay, span.fromTime, span.toDay, span.toTime, text, source, importance,
            ),
        )
        return id
    }

    override fun memories(run: String, level: Int): List<StoredMemory> {
        check(run)
        val (where, args) = visible { it.maxMemory }
        return store.query(
            "SELECT id, level, from_id, to_id, from_day, from_minute, to_day, to_minute, text, source, importance FROM memories WHERE run_id = ? AND level = ? AND $where ORDER BY id",
            listOf(run, level) + args,
        ).map { r ->
            StoredMemory(
                r["id"] as Long, run, (r["level"] as Long).toInt(), r["from_id"] as Long, r["to_id"] as Long,
                Span((r["from_day"] as Long?)?.toInt(), r["from_minute"] as String?, (r["to_day"] as Long?)?.toInt(), r["to_minute"] as String?),
                r["text"] as String, r["source"] as String, (r["importance"] as Number?)?.toDouble(),
            )
        }
    }

    /** The highest event and memory id this life remembers: a checkpoint's watermarks. No query, so safe on the sim thread. */
    fun watermarks(): Pair<Long, Long> = markEvent to markMemory

    /** The store stays open: it's the run database, which outlives one life's memory. */
    override fun close() {}

    companion object {
        /**
         * What a session that carries on from checkpoint [checkpointId] remembers: the session that
         * saved it up to the checkpoint's watermarks, and that session's own lineage, cut at the same marks.
         */
        fun lineage(store: RunStore, checkpointId: String?): List<Visible> {
            val out = mutableListOf<Visible>()
            var cp = checkpointId?.let(store::checkpoint)
            var maxEvent = Long.MAX_VALUE
            var maxMemory = Long.MAX_VALUE
            while (cp != null) {
                maxEvent = minOf(maxEvent, cp.watermarks.memoryEvent)
                maxMemory = minOf(maxMemory, cp.watermarks.memory)
                out += Visible(cp.sessionId, maxEvent, maxMemory)
                cp = store.session(cp.sessionId)?.fromCheckpoint?.let(store::checkpoint)
            }
            return out
        }
    }
}
