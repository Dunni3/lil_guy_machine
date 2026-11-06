package dev.lilguy.android

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import dev.lilguy.runlog.SqlDriver
import java.io.File

/** The run store's [SqlDriver] on the TV: the platform's own SQLite, so there are no natives to package. */
class AndroidSqlDriver(file: File) : SqlDriver {
    private val db: SQLiteDatabase = run {
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).also { it.enableWriteAheadLogging() }
    }
    private var changed = 0

    override fun execute(sql: String, args: List<Any?>) {
        val st = db.compileStatement(sql)
        try {
            bind(st, args)
            val verb = sql.trimStart().substringBefore(' ').uppercase()
            changed = if (verb == "UPDATE" || verb == "DELETE" || verb == "INSERT") st.executeUpdateDelete() else 0.also { st.execute() }
        } finally {
            st.close()
        }
    }

    override fun changes(): Int = changed

    override fun query(sql: String, args: List<Any?>): List<Map<String, Any?>> {
        // rawQuery binds only strings, which LIMIT won't take: numbers and nulls go in as literals, strings stay bound.
        val strings = mutableListOf<String>()
        var next = 0
        val inlined = buildString {
            for (ch in sql) {
                if (ch != '?') { append(ch); continue }
                when (val v = args[next++]) {
                    null -> append("NULL")
                    is Boolean -> append(if (v) "1" else "0")
                    is Number -> append(v.toString())
                    else -> { append('?'); strings += v.toString() }
                }
            }
        }
        return db.rawQuery(inlined, strings.toTypedArray()).use { rows(it) }
    }

    private fun rows(c: Cursor): List<Map<String, Any?>> = buildList {
        while (c.moveToNext()) {
            add(
                (0 until c.columnCount).associate { i ->
                    c.getColumnName(i) to when (c.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                        Cursor.FIELD_TYPE_BLOB -> c.getBlob(i)
                        else -> c.getString(i)
                    }
                },
            )
        }
    }

    override fun <T> transaction(block: () -> T): T {
        if (db.inTransaction()) return block()
        db.beginTransaction()
        try {
            return block().also { db.setTransactionSuccessful() }
        } finally {
            db.endTransaction()
        }
    }

    private fun bind(st: SQLiteStatement, args: List<Any?>) = args.forEachIndexed { i, v ->
        val n = i + 1
        when (v) {
            null -> st.bindNull(n)
            is Long -> st.bindLong(n, v)
            is Int -> st.bindLong(n, v.toLong())
            is Boolean -> st.bindLong(n, if (v) 1 else 0)
            is Double -> st.bindDouble(n, v)
            is Float -> st.bindDouble(n, v.toDouble())
            is ByteArray -> st.bindBlob(n, v)
            else -> st.bindString(n, v.toString())
        }
    }

    override fun close() = db.close()
}
