package dev.lilguy.runlog

/**
 * The little of SQLite the run store needs, so one store runs on every platform: `sqlite-jdbc`
 * on the JVM (`:runlog-jdbc`), `android.database.sqlite` on the TV (in `:android`), with no native
 * libraries of our own to package for the TV's 32-bit userspace.
 *
 * Arguments are String, Long, Int, Double, Boolean, ByteArray or null. Not thread-safe: [RunStore]
 * serializes every call.
 */
interface SqlDriver : AutoCloseable {
    fun execute(sql: String, args: List<Any?> = emptyList())

    /** Rows changed by the last [execute]. */
    fun changes(): Int

    /** Each row as column name to value: Long, Double, String, ByteArray or null. */
    fun query(sql: String, args: List<Any?> = emptyList()): List<Map<String, Any?>>

    fun <T> transaction(block: () -> T): T
}
