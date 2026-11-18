package dev.lilguy.runlog.jdbc

import dev.lilguy.runlog.RunStore
import dev.lilguy.runlog.SqlDriver
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement

/** [SqlDriver] over sqlite-jdbc. Pass `null` for a throwaway in-memory database. */
class JdbcSqlDriver(file: File?) : SqlDriver {
    private val db: Connection = run {
        file?.absoluteFile?.parentFile?.mkdirs()
        DriverManager.getConnection("jdbc:sqlite:" + (file?.absolutePath ?: ":memory:"))
    }
    private var changed = 0
    private var depth = 0

    init {
        db.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA synchronous=NORMAL")
            st.execute("PRAGMA busy_timeout=5000")
        }
    }

    override fun execute(sql: String, args: List<Any?>) {
        db.prepareStatement(sql).use { ps ->
            bind(ps, args)
            ps.execute()
            changed = ps.updateCount.coerceAtLeast(0)
        }
    }

    override fun changes(): Int = changed

    override fun query(sql: String, args: List<Any?>): List<Map<String, Any?>> = db.prepareStatement(sql).use { ps ->
        bind(ps, args)
        ps.executeQuery().use { rs ->
            val meta = rs.metaData
            val names = (1..meta.columnCount).map { meta.getColumnLabel(it) }
            buildList {
                while (rs.next()) {
                    add(
                        names.withIndex().associate { (i, name) ->
                            name to when (val v = rs.getObject(i + 1)) {
                                is Int -> v.toLong()
                                is Float -> v.toDouble()
                                else -> v
                            }
                        },
                    )
                }
            }
        }
    }

    override fun <T> transaction(block: () -> T): T {
        if (depth > 0) return block()
        db.autoCommit = false
        depth++
        try {
            return block().also { db.commit() }
        } catch (e: Throwable) {
            db.rollback()
            throw e
        } finally {
            depth--
            db.autoCommit = true
        }
    }

    private fun bind(ps: PreparedStatement, args: List<Any?>) = args.forEachIndexed { i, v ->
        when (v) {
            is Boolean -> ps.setLong(i + 1, if (v) 1 else 0)
            is ByteArray -> ps.setBytes(i + 1, v)
            else -> ps.setObject(i + 1, v)
        }
    }

    override fun close() = db.close()

    companion object {
        /** The run store in [file]: `runs/lilguy.db` on the Mac. */
        fun store(file: File?): RunStore = RunStore(JdbcSqlDriver(file))
    }
}
