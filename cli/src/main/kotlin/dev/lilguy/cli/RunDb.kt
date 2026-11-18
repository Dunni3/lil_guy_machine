package dev.lilguy.cli

import dev.lilguy.agent.LifeRecorder
import dev.lilguy.runlog.Ids
import dev.lilguy.runlog.RunStore
import dev.lilguy.runlog.SessionRow
import dev.lilguy.runlog.SyncTarget
import dev.lilguy.runlog.SyncWorker
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import kotlin.time.Duration.Companion.seconds

/** What built this run: the commit, whether the tree had changes, and the machine. */
internal object Build {
    val gitSha: String? by lazy { git("rev-parse", "HEAD")?.trim()?.takeIf { it.isNotEmpty() } }
    val gitDirty: Boolean? by lazy { git("status", "--porcelain")?.let { it.isNotBlank() } }
    val host: String? by lazy { runCatching { InetAddress.getLocalHost().hostName }.getOrNull() }

    private fun git(vararg args: String): String? = runCatching {
        val p = ProcessBuilder(listOf("git") + args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (p.waitFor() == 0) out else null
    }.getOrNull()
}

internal fun sessionRow(id: String, runId: String, fromCheckpoint: String?, contentHash: String?) = SessionRow(
    id = id, runId = runId, fromCheckpoint = fromCheckpoint, gitSha = Build.gitSha, gitDirty = Build.gitDirty,
    contentHash = contentHash, platform = "cli", host = Build.host, startedAt = Ids.now(),
)

/** The sync worker for [store] when `LILGUY_SYNC_URL` and `LILGUY_SYNC_TOKEN` are set, else null. */
internal fun syncWorker(store: RunStore): SyncWorker? = SyncTarget.fromEnv()?.let { SyncWorker(store, it, client = "cli@${Build.host ?: "?"}") }

/** Sends what's left before the CLI exits, waiting up to a minute; says how it went. */
internal fun finalSync(store: RunStore, worker: SyncWorker?) {
    if (worker == null) {
        println("run database: ${store.unsent()} rows and blobs not synced (set LILGUY_SYNC_URL and LILGUY_SYNC_TOKEN to send them)")
        return
    }
    val ok = runBlocking { withTimeoutOrNull(60.seconds) { worker.syncAll() } }
    when (ok) {
        true -> println("run database: synced (${worker.sentRows} rows sent this process)")
        else -> println("run database: sync incomplete, ${store.unsent()} left for next time: ${worker.lastError ?: "timed out"}")
    }
}

/** The content bundle row, computed once per process. */
internal val contentRow by lazy { LifeRecorder.content() }
