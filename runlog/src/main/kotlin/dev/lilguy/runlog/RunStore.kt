package dev.lilguy.runlog

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Every run on this device, in one SQLite database: the same tables as the server ([Tables]), plus
 * which rows the server hasn't taken yet. Writers put rows here and never wait on the network; the
 * [SyncWorker] sends them on. It holds many runs at once, so one store serves a process with many
 * lives in it (the sim host), as well as one CLI run or the TV.
 *
 * One connection, every call synchronized: the recorder's writer, the memory layer and the sync
 * worker share it, and the traffic is a few rows a second per life.
 */
class RunStore(private val db: SqlDriver) : AutoCloseable {
    init {
        db.transaction {
            Tables.sqliteDdl().forEach { db.execute(it) }
            // A database made by an older build gains the columns added since; their old rows have them null.
            Tables.all.forEach { t ->
                val has = db.query("PRAGMA table_info(${t.name})").map { it["name"] as String }.toSet()
                t.columns.filter { it.name !in has }.forEach { db.execute("ALTER TABLE ${t.name} ADD COLUMN ${it.name} ${it.type.sqlite}") }
            }
        }
    }

    /** Writes [rows], each replacing the row with its key, all or none. */
    @Synchronized
    fun write(rows: List<Row>) {
        if (rows.isEmpty()) return
        db.transaction { rows.forEach { upsert(it.json()) } }
    }

    fun write(row: Row) = write(listOf(row))

    private fun upsert(json: JsonObject) {
        val table = Tables.named(json.getValue("table").jsonPrimitive.content)
        val cols = table.names
        val set = cols.filter { it !in table.key }.joinToString(", ") { "$it = excluded.$it" }
        db.execute(
            "INSERT INTO ${table.name} (${cols.joinToString()}) VALUES (${cols.joinToString { "?" }}) " +
                "ON CONFLICT (${table.key.joinToString()}) DO UPDATE SET $set, _rev = ${table.name}._rev + 1, _dirty = 1",
            cols.map { sqlValue(table.type(it), json[it]) },
        )
    }

    /** Stores a body under [hash] unless it's there already. [gzipped] is the compressed bytes; [size] the plain length. */
    @Synchronized
    fun putBlob(hash: String, mediaType: String, gzipped: ByteArray, size: Int) {
        db.execute("INSERT OR IGNORE INTO blobs (hash, encoding, media_type, bytes, size) VALUES (?, 'gzip', ?, ?, ?)", listOf(hash, mediaType, gzipped, size))
    }

    /** The plain bytes of a stored body, or null. */
    @Synchronized
    fun blob(hash: String): ByteArray? = db.query("SELECT bytes FROM blobs WHERE hash = ?", listOf(hash)).firstOrNull()?.let { Blobs.gunzip(it["bytes"] as ByteArray) }

    /** Raw access for the layers that keep their own tables here (the memory store). */
    @Synchronized
    fun query(sql: String, args: List<Any?> = emptyList()): List<Map<String, Any?>> = db.query(sql, args)

    /** An UPDATE through the store: the caller bumps `_rev` and sets `_dirty`, so the change syncs. Returns the rows changed. */
    @Synchronized
    fun execute(sql: String, args: List<Any?> = emptyList()): Int {
        db.execute(sql, args)
        return db.changes()
    }

    /** The highest `seq` any table holds for [run], or 0: where a new session's rows continue from. */
    @Synchronized
    fun maxSeq(run: String): Long = Tables.facts.maxOf { t ->
        (db.query("SELECT MAX(seq) AS m FROM ${t.name} WHERE run_id = ?", listOf(run)).first()["m"] as Long?) ?: 0L
    }

    @Synchronized
    fun run(id: String): RunRow? = rows(Tables.runs, "id = ?", listOf(id)).firstOrNull()?.let { RowJson.decodeFromJsonElement(RunRow.serializer(), it) }

    /** Runs on this device, newest first. */
    @Synchronized
    fun runs(limit: Int = 50): List<RunRow> =
        rows(Tables.runs, "1 = 1 ORDER BY started_at DESC LIMIT ?", listOf(limit)).map { RowJson.decodeFromJsonElement(RunRow.serializer(), it) }

    @Synchronized
    fun session(id: String): SessionRow? = rows(Tables.sessions, "id = ?", listOf(id)).firstOrNull()?.let { RowJson.decodeFromJsonElement(SessionRow.serializer(), it) }

    @Synchronized
    fun checkpoint(id: String): CheckpointRow? =
        rows(Tables.checkpoints, "id = ?", listOf(id)).firstOrNull()?.let { RowJson.decodeFromJsonElement(CheckpointRow.serializer(), it) }

    /** [run]'s latest checkpoint, or null. */
    @Synchronized
    fun latestCheckpoint(run: String): CheckpointRow? =
        rows(Tables.checkpoints, "run_id = ? ORDER BY tick DESC, created_at DESC LIMIT 1", listOf(run)).firstOrNull()?.let { RowJson.decodeFromJsonElement(CheckpointRow.serializer(), it) }

    /** Rows of [table] matching [where], as JSON with each column's own type. */
    @Synchronized
    fun rows(table: Tables.Table, where: String, args: List<Any?> = emptyList()): List<JsonObject> =
        db.query("SELECT * FROM ${table.name} WHERE $where", args).map { jsonOf(table, it) }

    // --- sync ------------------------------------------------------------------------------------

    /** A row waiting for the server, with the `_rev` it was read at. */
    class Pending(val table: Tables.Table, val json: JsonObject, val rev: Long)

    /** Up to [limit] rows the server hasn't taken, tables in [Tables.all] order. */
    @Synchronized
    fun pending(limit: Int): List<Pending> {
        val out = mutableListOf<Pending>()
        for (t in Tables.all) {
            if (out.size >= limit) break
            db.query("SELECT * FROM ${t.name} WHERE _dirty = 1 LIMIT ?", listOf(limit - out.size)).forEach { r ->
                out += Pending(t, jsonOf(t, r), r["_rev"] as Long)
            }
        }
        return out
    }

    /** The server has [sent]: clears each unless it was written again since it was read. */
    @Synchronized
    fun sent(sent: List<Pending>) = db.transaction {
        sent.forEach { p ->
            val where = p.table.key.joinToString(" AND ") { "$it = ?" }
            db.execute("UPDATE ${p.table.name} SET _dirty = 0 WHERE $where AND _rev = ?", p.table.key.map { sqlValue(p.table.type(it), p.json[it]) } + p.rev)
        }
    }

    class PendingBlob(val hash: String, val mediaType: String, val gzipped: ByteArray)

    @Synchronized
    fun pendingBlobs(limit: Int): List<PendingBlob> =
        db.query("SELECT hash, media_type, bytes FROM blobs WHERE _dirty = 1 LIMIT ?", listOf(limit)).map { PendingBlob(it["hash"] as String, it["media_type"] as String, it["bytes"] as ByteArray) }

    @Synchronized
    fun blobSent(hash: String) = db.execute("UPDATE blobs SET _dirty = 0 WHERE hash = ?", listOf(hash))

    /** Rows and blobs the server hasn't taken yet. */
    @Synchronized
    fun unsent(): Int = Tables.all.sumOf { t -> (db.query("SELECT COUNT(*) AS n FROM ${t.name} WHERE _dirty = 1").first()["n"] as Long).toInt() } +
        (db.query("SELECT COUNT(*) AS n FROM blobs WHERE _dirty = 1").first()["n"] as Long).toInt()

    /**
     * Forgets runs that ended more than [days] ago and that the server has all of: the server keeps
     * everything, the device only what it may resume or show offline. Blobs no row names go too.
     */
    @Synchronized
    fun prune(days: Long = RETAIN_DAYS): Int {
        val cutoff = Instant.now().minus(days, ChronoUnit.DAYS).toString()
        val old = db.query("SELECT id FROM runs WHERE ended_at IS NOT NULL AND ended_at < ?", listOf(cutoff)).map { it["id"] as String }
        val gone = old.filter { id ->
            Tables.all.none { t -> t.names.contains("run_id") && db.query("SELECT 1 FROM ${t.name} WHERE run_id = ? AND _dirty = 1 LIMIT 1", listOf(id)).isNotEmpty() } &&
                db.query("SELECT 1 FROM runs WHERE id = ? AND _dirty = 1", listOf(id)).isEmpty()
        }
        if (gone.isEmpty()) return 0
        db.transaction {
            gone.forEach { id ->
                Tables.all.filter { "run_id" in it.names }.forEach { t -> db.execute("DELETE FROM ${t.name} WHERE run_id = ?", listOf(id)) }
                db.execute("DELETE FROM runs WHERE id = ?", listOf(id))
            }
            val refs = listOf("model_calls" to listOf("system_blob", "request_blob", "response_blob"), "decisions" to listOf("observation_blob"), "art_pieces" to listOf("png_blob"))
            val used = refs.joinToString(" UNION ") { (t, cols) -> cols.joinToString(" UNION ") { "SELECT $it FROM $t WHERE $it IS NOT NULL" } }
            db.execute("DELETE FROM blobs WHERE _dirty = 0 AND hash NOT IN ($used)")
        }
        return gone.size
    }

    @Synchronized
    override fun close() = db.close()

    companion object {
        /** How long a device keeps a run the server has all of. */
        const val RETAIN_DAYS = 30L

        private fun sqlValue(type: Tables.Type, e: JsonElement?): Any? {
            if (e == null || e is JsonNull) return null
            return when (type) {
                Tables.Type.Json, Tables.Type.TextArray -> e.toString()
                Tables.Type.Bool -> if (e.jsonPrimitive.boolean) 1L else 0L
                Tables.Type.Int, Tables.Type.Long -> e.jsonPrimitive.long
                Tables.Type.Real -> e.jsonPrimitive.double
                else -> e.jsonPrimitive.content
            }
        }

        private fun jsonOf(table: Tables.Table, r: Map<String, Any?>): JsonObject = JsonObject(
            buildMap {
                put("table", JsonPrimitive(table.name))
                table.columns.forEach { c ->
                    val v = r[c.name]
                    put(
                        c.name,
                        when {
                            v == null -> JsonNull
                            c.type == Tables.Type.Json || c.type == Tables.Type.TextArray -> RowJson.parseToJsonElement(v as String)
                            c.type == Tables.Type.Bool -> JsonPrimitive((v as Long) != 0L)
                            c.type == Tables.Type.Real -> JsonPrimitive((v as Number).toDouble())
                            v is Long -> JsonPrimitive(v)
                            v is Double -> JsonPrimitive(v)
                            else -> JsonPrimitive(v.toString())
                        },
                    )
                }
            },
        )
    }
}
