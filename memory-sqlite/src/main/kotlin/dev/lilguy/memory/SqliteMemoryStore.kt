package dev.lilguy.memory

import dev.lilguy.agent.memory.EventKind
import dev.lilguy.agent.memory.MemoryEvent
import dev.lilguy.agent.memory.MemoryStore
import dev.lilguy.agent.memory.Span
import dev.lilguy.agent.memory.StoredMemory
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * The memory hierarchy in one SQLite file.
 *
 * - `events`: level 0, the stream of observations and actions as free text.
 * - `memories`: summaries; `level` 1 covers events `from_id..to_id`, level 2 would cover level-1
 *   memory ids, and so on.
 *
 * One connection, every call synchronized: the agent loop and a background summary both use it,
 * and the traffic is a few rows a second at most. Pass `null` for a throwaway in-memory database.
 */
class SqliteMemoryStore(file: File?) : MemoryStore {
    private val db: Connection = run {
        file?.absoluteFile?.parentFile?.mkdirs()
        DriverManager.getConnection("jdbc:sqlite:" + (file?.absolutePath ?: ":memory:"))
    }

    init {
        db.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute(
                """CREATE TABLE IF NOT EXISTS events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    run TEXT NOT NULL, tick INTEGER NOT NULL, day INTEGER, time TEXT,
                    kind TEXT NOT NULL, text TEXT NOT NULL)""",
            )
            st.execute("CREATE INDEX IF NOT EXISTS events_run ON events (run, id)")
            st.execute(
                """CREATE TABLE IF NOT EXISTS memories (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    run TEXT NOT NULL, level INTEGER NOT NULL, from_id INTEGER NOT NULL, to_id INTEGER NOT NULL,
                    from_day INTEGER, from_time TEXT, to_day INTEGER, to_time TEXT,
                    text TEXT NOT NULL, source TEXT NOT NULL)""",
            )
            st.execute("CREATE INDEX IF NOT EXISTS memories_run ON memories (run, level, id)")
        }
    }

    @Synchronized
    override fun appendEvent(run: String, tick: Long, day: Int?, time: String?, kind: EventKind, text: String): Long =
        db.prepareStatement("INSERT INTO events (run, tick, day, time, kind, text) VALUES (?,?,?,?,?,?)").use { ps ->
            ps.setString(1, run); ps.setLong(2, tick); ps.setObject(3, day); ps.setString(4, time)
            ps.setString(5, kind.id); ps.setString(6, text)
            ps.executeUpdate()
            lastId()
        }

    @Synchronized
    override fun eventsAfter(run: String, afterId: Long, limit: Int): List<MemoryEvent> =
        db.prepareStatement("SELECT id, run, tick, day, time, kind, text FROM events WHERE run = ? AND id > ? ORDER BY id LIMIT ?").use { ps ->
            ps.setString(1, run); ps.setLong(2, afterId); ps.setInt(3, limit)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(
                        MemoryEvent(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.intOrNull(4), rs.getString(5), EventKind.of(rs.getString(6)), rs.getString(7)),
                    )
                }
            }
        }

    @Synchronized
    override fun countEventsAfter(run: String, afterId: Long): Int =
        db.prepareStatement("SELECT COUNT(*) FROM events WHERE run = ? AND id > ?").use { ps ->
            ps.setString(1, run); ps.setLong(2, afterId)
            ps.executeQuery().use { it.next(); it.getInt(1) }
        }

    @Synchronized
    override fun addMemory(run: String, level: Int, fromId: Long, toId: Long, span: Span, text: String, source: String): Long =
        db.prepareStatement(
            "INSERT INTO memories (run, level, from_id, to_id, from_day, from_time, to_day, to_time, text, source) VALUES (?,?,?,?,?,?,?,?,?,?)",
        ).use { ps ->
            ps.setString(1, run); ps.setInt(2, level); ps.setLong(3, fromId); ps.setLong(4, toId)
            ps.setObject(5, span.fromDay); ps.setString(6, span.fromTime); ps.setObject(7, span.toDay); ps.setString(8, span.toTime)
            ps.setString(9, text); ps.setString(10, source)
            ps.executeUpdate()
            lastId()
        }

    @Synchronized
    override fun memories(run: String, level: Int): List<StoredMemory> =
        db.prepareStatement(
            "SELECT id, run, level, from_id, to_id, from_day, from_time, to_day, to_time, text, source FROM memories WHERE run = ? AND level = ? ORDER BY id",
        ).use { ps ->
            ps.setString(1, run); ps.setInt(2, level)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(
                        StoredMemory(
                            rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getLong(4), rs.getLong(5),
                            Span(rs.intOrNull(6), rs.getString(7), rs.intOrNull(8), rs.getString(9)),
                            rs.getString(10), rs.getString(11),
                        ),
                    )
                }
            }
        }

    @Synchronized
    override fun close() = db.close()

    private fun lastId(): Long = db.createStatement().use { st -> st.executeQuery("SELECT last_insert_rowid()").use { it.next(); it.getLong(1) } }

    private fun ResultSet.intOrNull(column: Int): Int? = getInt(column).takeUnless { wasNull() }
}
