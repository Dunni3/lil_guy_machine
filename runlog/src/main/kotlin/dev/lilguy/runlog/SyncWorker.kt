package dev.lilguy.runlog

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Where rows go: the ingest server and this writer's bearer token (from the environment, never a file in the repo). */
data class SyncTarget(val url: String, val token: String) {
    companion object {
        /** `LILGUY_SYNC_URL` and `LILGUY_SYNC_TOKEN`, or null when either is missing. */
        fun fromEnv(env: (String) -> String? = System::getenv): SyncTarget? {
            val url = env("LILGUY_SYNC_URL")?.takeIf { it.isNotBlank() } ?: return null
            val token = env("LILGUY_SYNC_TOKEN")?.takeIf { it.isNotBlank() } ?: return null
            return SyncTarget(url.trimEnd('/'), token)
        }
    }
}

/**
 * Sends what the [store] holds that the server doesn't, in the background: blobs first (`HEAD`
 * then `PUT` only if missing), then rows in batches (`POST /v1/ingest`). The server keeps the
 * highest `_rev` of each row, so a batch sent twice, or after a newer one, changes nothing; a row
 * stays pending locally until a send of it succeeds. Offline, rows wait. One worker per process
 * and store, however many runs are in it.
 */
class SyncWorker(
    private val store: RunStore,
    private val target: SyncTarget,
    /** Who is sending, for the server's logs: "cli@host", "tv". */
    private val client: String,
    private val every: Duration = 10.seconds,
) {
    /** The latest failure, or null after a clean pass. Readable from any thread. */
    @Volatile var lastError: String? = null
        private set

    /** Rows the server has taken from this worker. */
    @Volatile var sentRows = 0L
        private set

    /** Syncs every [every] until [scope] ends, pruning old synced runs once at the start. */
    fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) {
        runCatching { store.prune() }
        while (isActive) {
            syncAll()
            delay(every)
        }
    }

    /** Sends until nothing is pending or a send fails. Returns true when everything went. */
    suspend fun syncAll(): Boolean = withContext(Dispatchers.IO) {
        try {
            while (true) {
                val blobs = store.pendingBlobs(BLOBS)
                blobs.forEach { b ->
                    if (!has(b.hash)) put(b)
                    store.blobSent(b.hash)
                }
                val rows = store.pending(ROWS)
                if (rows.isNotEmpty()) {
                    ingest(rows)
                    store.sent(rows)
                    sentRows += rows.size
                }
                if (blobs.isEmpty() && rows.isEmpty()) break
            }
            lastError = null
            true
        } catch (e: IOException) {
            lastError = e.message ?: e.toString()
            false
        }
    }

    private fun has(hash: String): Boolean {
        val c = open("/v1/blobs/$hash", "HEAD")
        return when (val code = c.responseCode) {
            200 -> true
            404 -> false
            else -> throw IOException("HEAD blob: HTTP $code")
        }.also { c.disconnect() }
    }

    private fun put(b: RunStore.PendingBlob) {
        val c = open("/v1/blobs/${b.hash}", "PUT")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/octet-stream")
        c.setRequestProperty("X-Blob-Encoding", "gzip")
        c.setRequestProperty("X-Blob-Media-Type", b.mediaType)
        c.outputStream.use { it.write(b.gzipped) }
        check(c, "PUT blob")
    }

    private fun ingest(rows: List<RunStore.Pending>) {
        val body = JsonObject(
            mapOf(
                "client" to JsonPrimitive(client),
                "rows" to JsonArray(rows.map { p -> JsonObject(p.json + ("_rev" to JsonPrimitive(p.rev))) }),
            ),
        ).toString().toByteArray()
        val c = open("/v1/ingest", "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Content-Encoding", "gzip")
        c.outputStream.use { it.write(Blobs.gzip(body)) }
        check(c, "ingest")
    }

    private fun open(path: String, method: String): HttpURLConnection =
        (URL(target.url + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer ${target.token}")
        }

    private fun check(c: HttpURLConnection, what: String) {
        val code = c.responseCode
        if (code !in 200..299) {
            val why = runCatching { c.errorStream?.bufferedReader()?.readText() }.getOrNull().orEmpty().take(300)
            c.disconnect()
            throw IOException("$what: HTTP $code $why")
        }
        c.inputStream.use { it.readBytes() }
        c.disconnect()
    }

    private companion object {
        const val ROWS = 500
        const val BLOBS = 100
    }
}
