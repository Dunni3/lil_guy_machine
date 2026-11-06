package dev.lilguy.runlog

/**
 * The run database's tables, once, for both stores: the local SQLite ([sqliteDdl]) and the
 * server's Postgres ([postgresDdl], written to `server/sql/001_tables.sql` by `make server-schema`).
 * The server's ingest reads its column types from Postgres itself, so this list is the one place a
 * column is added.
 *
 * Every table also has `_rev`: how many times the row was written on its device. Sync sends a row
 * with its `_rev`, and the server keeps the highest, so a row sent twice or out of order lands
 * the same. Locally there's `_dirty` too: written since the server last took it.
 *
 * No foreign keys on the server: batches may arrive in any order (a decision before its run), and
 * ingest must take them anyway. Views join on the ids.
 */
object Tables {
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
        UuidArray("TEXT", "uuid[]"),
        /** ISO-8601 instant; text locally. */
        Time("TEXT", "timestamptz"),
        Bytes("BLOB", "bytea"),
    }

    class Column(val name: String, val type: Type, val notNull: Boolean = false)

    class Table(val name: String, val key: List<String>, val columns: List<Column>, val indexes: List<List<String>> = emptyList()) {
        val names: List<String> = columns.map { it.name }
        fun type(column: String): Type = columns.first { it.name == column }.type
    }

    private fun c(name: String, type: Type, notNull: Boolean = false) = Column(name, type, notNull)

    /** What every fact row starts with: whose life, which process, its place in the run, and when in game time. */
    private val stamp = listOf(
        c("run_id", Type.Uuid, true), c("session_id", Type.Uuid, true), c("seq", Type.Long, true),
        c("tick", Type.Long, true), c("day", Type.Int), c("minute_of_day", Type.Int),
    )

    val contentVersions = Table("content_versions", listOf("hash"), listOf(c("hash", Type.Text, true), c("files", Type.Json, true), c("created_at", Type.Time, true)))

    val batches = Table("batches", listOf("id"), listOf(c("id", Type.Uuid, true), c("name", Type.Text), c("spec", Type.Json), c("created_at", Type.Time, true), c("notes", Type.Text)))

    val runs = Table(
        "runs", listOf("id"),
        listOf(
            c("id", Type.Uuid, true), c("parent_id", Type.Uuid), c("forked_from", Type.Uuid), c("batch_id", Type.Uuid), c("label", Type.Text),
            c("scenario", Type.Text, true), c("platform", Type.Text, true), c("host", Type.Text), c("git_sha", Type.Text), c("git_dirty", Type.Bool),
            c("content_hash", Type.Text), c("seed", Type.Long, true), c("ticks_per_minute", Type.Int, true), c("personality", Type.Json), c("granted", Type.TextArray),
            c("models", Type.Json, true), c("settings", Type.Json), c("started_at", Type.Time, true), c("ended_at", Type.Time), c("end_reason", Type.Text), c("notes", Type.Text),
        ),
    )

    val sessions = Table(
        "sessions", listOf("id"),
        listOf(
            c("id", Type.Uuid, true), c("run_id", Type.Uuid, true), c("from_checkpoint", Type.Uuid), c("git_sha", Type.Text), c("git_dirty", Type.Bool),
            c("content_hash", Type.Text), c("platform", Type.Text, true), c("host", Type.Text), c("started_at", Type.Time, true), c("ended_at", Type.Time),
        ),
        indexes = listOf(listOf("run_id")),
    )

    val modelCalls = Table(
        "model_calls", listOf("id"),
        listOf(c("id", Type.Uuid, true)) + stamp + listOf(
            c("purpose", Type.Text, true), c("endpoint", Type.Text, true), c("model", Type.Text, true), c("calls", Type.Int, true),
            c("prompt_tokens", Type.Int), c("completion_tokens", Type.Int), c("reasoning_tokens", Type.Int), c("total_tokens", Type.Int),
            c("latency_ms", Type.Int), c("price_in", Type.Real), c("price_out", Type.Real), c("cost_usd", Type.Real), c("failed", Type.Bool, true),
            c("system_blob", Type.Text), c("request_blob", Type.Text), c("response_blob", Type.Text),
        ),
        indexes = listOf(listOf("run_id", "seq")),
    )

    val decisions = Table(
        "decisions", listOf("id"),
        listOf(c("id", Type.Uuid, true)) + stamp + listOf(
            c("model_call_id", Type.Uuid), c("intent", Type.Json, true), c("intent_label", Type.Text, true), c("thought", Type.Text), c("choices", Type.Json),
            c("fallback", Type.Bool, true), c("error", Type.Text), c("outcome_status", Type.Text), c("outcome_text", Type.Text), c("observation_blob", Type.Text),
        ),
        indexes = listOf(listOf("run_id", "seq")),
    )

    val ledgerEntries = Table("ledger_entries", listOf("run_id", "seq"), stamp + listOf(c("amount", Type.Int, true), c("balance", Type.Int, true), c("reason", Type.Text, true)))

    val needChanges = Table("need_changes", listOf("run_id", "seq"), stamp + listOf(c("need", Type.Text, true), c("delta", Type.Real, true), c("value", Type.Real, true), c("reason", Type.Text, true)))

    // Keyed by seq, not tick or piece number: a resumed life replays the ticks after its checkpoint, and both branches keep their rows.
    val hourlyState = Table(
        "hourly_state", listOf("run_id", "seq"),
        stamp + listOf(c("hour", Type.Int, true), c("money", Type.Int, true), c("needs", Type.Json, true), c("skills", Type.Json), c("doing", Type.Text)),
        indexes = listOf(listOf("run_id", "tick")),
    )

    /** The memory hierarchy, which :agent's `RunMemoryStore` reads and writes: ids are per run. */
    val memoryEvents = Table(
        "memory_events", listOf("run_id", "id"),
        stamp + listOf(c("id", Type.Long, true), c("time", Type.Text), c("kind", Type.Text, true), c("text", Type.Text, true), c("significance", Type.Real)),
        indexes = listOf(listOf("run_id", "session_id", "id")),
    )

    val memories = Table(
        "memories", listOf("run_id", "id"),
        stamp + listOf(
            c("id", Type.Long, true), c("level", Type.Int, true), c("from_id", Type.Long, true), c("to_id", Type.Long, true),
            c("from_day", Type.Int), c("from_minute", Type.Text), c("to_day", Type.Int), c("to_minute", Type.Text),
            c("text", Type.Text, true), c("source", Type.Text, true), c("importance", Type.Real),
        ),
        indexes = listOf(listOf("run_id", "level", "id")),
    )

    val authored = Table(
        "authored", listOf("id"),
        listOf(c("id", Type.Uuid, true)) + stamp + listOf(
            c("op", Type.Text, true), c("verb", Type.Text), c("kept", Type.Bool, true), c("writer", Type.Text), c("result", Type.Json, true), c("error", Type.Text),
        ),
        indexes = listOf(listOf("run_id", "seq")),
    )

    val artPieces = Table(
        "art_pieces", listOf("run_id", "seq"),
        stamp + listOf(
            c("piece", Type.Int, true), c("medium", Type.Text, true), c("title", Type.Text), c("tags", Type.TextArray), c("shows", Type.Text),
            c("palette", Type.TextArray), c("letters", Type.TextArray), c("ops", Type.Int), c("png_blob", Type.Text), c("commission", Type.Text), c("artist", Type.Text),
        ),
        indexes = listOf(listOf("run_id", "piece")),
    )

    val checkpoints = Table(
        "checkpoints", listOf("id"),
        listOf(
            c("id", Type.Uuid, true), c("run_id", Type.Uuid, true), c("session_id", Type.Uuid, true), c("tick", Type.Long, true), c("day", Type.Int, true),
            c("minute_of_day", Type.Int, true), c("reason", Type.Text, true), c("content_hash", Type.Text, true), c("git_sha", Type.Text),
            c("world_state", Type.Json, true), c("agent_state", Type.Json, true), c("watermarks", Type.Json, true), c("created_at", Type.Time, true),
        ),
        indexes = listOf(listOf("run_id", "tick")),
    )

    val runLineage = Table(
        "run_lineage", listOf("run_id", "source_run_id", "scope"),
        listOf(c("run_id", Type.Uuid, true), c("source_run_id", Type.Uuid, true), c("scope", Type.Text, true), c("mode", Type.Text, true), c("note", Type.Text)),
    )

    /** Anything without a table of its own yet. */
    val runRecords = Table("run_records", listOf("run_id", "seq"), stamp + listOf(c("kind", Type.Text, true), c("data", Type.Json, true)))

    /** Reserved for the phone and letters (phase 3). */
    val letters = Table(
        "letters", listOf("id"),
        listOf(
            c("id", Type.Uuid, true), c("run_id", Type.Uuid, true), c("direction", Type.Text, true), c("tick", Type.Long), c("day", Type.Int), c("minute_of_day", Type.Int),
            c("text", Type.Text, true), c("reply_to", Type.Uuid), c("written_at", Type.Time), c("delivered_at", Type.Time), c("read_at", Type.Time),
        ),
    )

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

    const val BLOBS_POSTGRES = """CREATE TABLE IF NOT EXISTS blobs (
    hash text PRIMARY KEY,
    encoding text NOT NULL,
    media_type text NOT NULL,
    bytes bytea NOT NULL,
    size integer NOT NULL
);"""

    fun sqliteDdl(): List<String> = all.flatMap { t ->
        val cols = t.columns.joinToString(", ") { "${it.name} ${it.type.sqlite}" + if (it.notNull) " NOT NULL" else "" }
        listOf("CREATE TABLE IF NOT EXISTS ${t.name} ($cols, _rev INTEGER NOT NULL DEFAULT 1, _dirty INTEGER NOT NULL DEFAULT 1, PRIMARY KEY (${t.key.joinToString()}))") +
            t.indexes.map { ix -> "CREATE INDEX IF NOT EXISTS ${t.name}_${ix.joinToString("_")} ON ${t.name} (${ix.joinToString()})" } +
            "CREATE INDEX IF NOT EXISTS ${t.name}_dirty ON ${t.name} (_dirty) WHERE _dirty = 1"
    } + BLOBS_SQLITE

    /** The server's tables, as `server/sql/001_tables.sql` holds them. */
    fun postgresDdl(): String = buildString {
        appendLine("-- Generated from runlog/src/main/kotlin/dev/lilguy/runlog/Tables.kt by `make server-schema`. Don't edit by hand.")
        appendLine()
        all.forEach { t ->
            appendLine("CREATE TABLE IF NOT EXISTS ${t.name} (")
            t.columns.forEach { appendLine("    ${it.name} ${it.type.postgres}" + (if (it.notNull) " NOT NULL" else "") + ",") }
            appendLine("    _rev bigint NOT NULL DEFAULT 1,")
            appendLine("    PRIMARY KEY (${t.key.joinToString()})")
            appendLine(");")
            // A column added since the table was made: rows from before have it null.
            t.columns.filter { it.name !in t.key }.forEach { appendLine("ALTER TABLE ${t.name} ADD COLUMN IF NOT EXISTS ${it.name} ${it.type.postgres};") }
            t.indexes.forEach { ix -> appendLine("CREATE INDEX IF NOT EXISTS ${t.name}_${ix.joinToString("_")} ON ${t.name} (${ix.joinToString()});") }
            appendLine()
        }
        appendLine(BLOBS_POSTGRES)
    }
}
