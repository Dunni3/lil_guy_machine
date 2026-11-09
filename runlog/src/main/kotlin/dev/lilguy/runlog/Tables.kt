package dev.lilguy.runlog

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.serializer

/**
 * The run database's tables, read from the row classes ([Rows.kt][Row]), which are their only
 * definition: each class's `@SerialName` is the table, its properties the columns, their Kotlin
 * types (and [Pg] where a String is a UUID or an instant) the column types, nullability the NOT
 * NULLs, and [Key] the primary key. From here come the device's SQLite DDL ([sqliteDdl]) and the
 * JSON Schema for the server ([RowSchema]).
 *
 * Every table also has `_rev`: how many times the row was written on its device. Sync sends a row
 * with its `_rev`, and the server keeps the highest, so a row sent twice or out of order lands
 * the same. Locally there's `_dirty` too: written since the server last took it.
 *
 * No foreign keys on the server: batches may arrive in any order (a decision before its run), and
 * ingest must take them anyway. Views join on the ids.
 */
@OptIn(ExperimentalSerializationApi::class)
object Tables {
    /** A column's type in each database; [postgres] is spelled as Postgres's `format_type` writes it. */
    enum class Type(val sqlite: String, val postgres: String) {
        Uuid("TEXT", "uuid"),
        Text("TEXT", "text"),
        Int("INTEGER", "integer"),
        Long("INTEGER", "bigint"),
        Real("REAL", "double precision"),
        Bool("INTEGER", "boolean"),
        /** A JSON value; text locally. */
        Json("TEXT", "jsonb"),
        /** A list of strings; JSON text locally. */
        TextArray("TEXT", "text[]"),
        /** ISO-8601 instant; text locally. */
        Time("TEXT", "timestamp with time zone"),
    }

    class Column(val name: String, val type: Type, val notNull: Boolean)

    /** [rowClass]: the Kotlin class its rows are, which names it in the JSON Schema. */
    class Table(val name: String, val rowClass: String, val key: List<String>, val columns: List<Column>, val indexes: List<List<String>> = emptyList()) {
        val names: List<String> = columns.map { it.name }
        fun type(column: String): Type = columns.first { it.name == column }.type
    }

    /** The column type of element [i] of a row's descriptor. */
    fun typeOf(d: SerialDescriptor, i: kotlin.Int): Type {
        d.getElementAnnotations(i).filterIsInstance<Pg>().firstOrNull()?.let { return it.type }
        val e = d.getElementDescriptor(i)
        return when (e.kind) {
            PrimitiveKind.STRING -> Type.Text
            PrimitiveKind.INT, PrimitiveKind.SHORT, PrimitiveKind.BYTE -> Type.Int
            PrimitiveKind.LONG -> Type.Long
            PrimitiveKind.DOUBLE, PrimitiveKind.FLOAT -> Type.Real
            PrimitiveKind.BOOLEAN -> Type.Bool
            StructureKind.LIST -> if (e.getElementDescriptor(0).kind == PrimitiveKind.STRING) Type.TextArray else Type.Json
            else -> Type.Json
        }
    }

    private inline fun <reified T : Row> table(indexes: List<List<String>> = emptyList()): Table {
        val d = serializer<T>().descriptor
        val key = d.annotations.filterIsInstance<Key>().single().columns.split(',').map { it.trim() }
        val columns = (0 until d.elementsCount).map { i -> Column(d.getElementName(i), typeOf(d, i), notNull = !d.getElementDescriptor(i).isNullable) }
        require(key.all { k -> columns.any { it.name == k } }) { "${d.serialName}: key $key names a column it doesn't have" }
        return Table(d.serialName, T::class.simpleName!!, key, columns, indexes)
    }

    // The local store's indexes are its own; the server's are in its migrations.
    val contentVersions = table<ContentVersionRow>()
    val batches = table<BatchRow>()
    val runs = table<RunRow>()
    val sessions = table<SessionRow>(listOf(listOf("run_id")))
    val modelCalls = table<ModelCallRow>(listOf(listOf("run_id", "seq")))
    val decisions = table<DecisionRow>(listOf(listOf("run_id", "seq")))
    val ledgerEntries = table<LedgerRow>()
    val needChanges = table<NeedRow>()
    // Keyed by seq, not tick or piece number: a resumed life replays the ticks after its checkpoint, and both branches keep their rows.
    val hourlyState = table<HourlyRow>(listOf(listOf("run_id", "tick")))
    /** The memory hierarchy, which :agent's `RunMemoryStore` reads and writes: ids are per run. */
    val memoryEvents = table<MemoryEventRow>(listOf(listOf("run_id", "session_id", "id")))
    val memories = table<MemoryRow>(listOf(listOf("run_id", "level", "id")))
    val authored = table<AuthoredRow>(listOf(listOf("run_id", "seq")))
    val artPieces = table<ArtPieceRow>(listOf(listOf("run_id", "piece")))
    val checkpoints = table<CheckpointRow>(listOf(listOf("run_id", "tick")))
    val runLineage = table<LineageRow>()
    /** Anything without a table of its own yet. */
    val runRecords = table<RecordRow>()
    val letters = table<LetterRow>()

    /** In the order sync sends them: what a row refers to goes first, so a batch reads well even though nothing requires it. */
    val all: List<Table> = listOf(
        contentVersions, batches, runs, sessions, modelCalls, decisions, ledgerEntries, needChanges, hourlyState,
        memoryEvents, memories, authored, artPieces, checkpoints, runLineage, runRecords, letters,
    )

    /** The tables whose rows carry a `seq` within their run. */
    val facts: List<Table> = all.filter { t -> t.columns.any { it.name == "seq" } }

    fun named(name: String): Table = all.firstOrNull { it.name == name } ?: throw IllegalArgumentException("no table '$name'")

    /** Bodies stored once by hash: prompts, replies, observations, pictures. Synced on their own, before the rows that name them. */
    const val BLOBS_SQLITE = """CREATE TABLE IF NOT EXISTS blobs (
        hash TEXT PRIMARY KEY, encoding TEXT NOT NULL, media_type TEXT NOT NULL, bytes BLOB NOT NULL, size INTEGER NOT NULL,
        _dirty INTEGER NOT NULL DEFAULT 1)"""

    fun sqliteDdl(): List<String> = all.flatMap { t ->
        val cols = t.columns.joinToString(", ") { "${it.name} ${it.type.sqlite}" + if (it.notNull) " NOT NULL" else "" }
        listOf("CREATE TABLE IF NOT EXISTS ${t.name} ($cols, _rev INTEGER NOT NULL DEFAULT 1, _dirty INTEGER NOT NULL DEFAULT 1, PRIMARY KEY (${t.key.joinToString()}))") +
            t.indexes.map { ix -> "CREATE INDEX IF NOT EXISTS ${t.name}_${ix.joinToString("_")} ON ${t.name} (${ix.joinToString()})" } +
            "CREATE INDEX IF NOT EXISTS ${t.name}_dirty ON ${t.name} (_dirty) WHERE _dirty = 1"
    } + BLOBS_SQLITE
}
