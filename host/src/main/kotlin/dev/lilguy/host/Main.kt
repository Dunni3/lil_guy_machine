package dev.lilguy.host

import dev.lilguy.agent.BedroomPolicy
import dev.lilguy.agent.live.ModelSetup
import dev.lilguy.agent.live.RunDatabase
import dev.lilguy.decision.RandomPicker
import dev.lilguy.runlog.SyncTarget
import dev.lilguy.runlog.SyncWorker
import dev.lilguy.runlog.jdbc.JdbcSqlDriver
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.InetAddress
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

/**
 * The sim host: runs one life (`main`) in real time and serves its state stream, the command
 * endpoint and the debug page. Configured from the environment:
 * - `LILGUY_HOST_PORT` (8790) and `LILGUY_HOST_BIND` (127.0.0.1; 0.0.0.0 on a server);
 * - `LILGUY_ADMIN_PASSWORD`: the admin password commands must carry (at least [AdminPassword.MIN_LENGTH]
 *   characters); unset, every command is refused;
 * - `LILGUY_ENDPOINT` names the deciding endpoint in `LILGUY_CONFIG` (config/endpoints.yaml), with
 *   `LILGUY_MEMORY_ENDPOINT`, `LILGUY_WRITER_ENDPOINT`, `LILGUY_RECALL_ENDPOINT` and `LILGUY_BEAM` as for
 *   the desktop; unset, the scripted policy runs and nothing calls a model;
 * - `LILGUY_PAINTER=<seconds>` draws his art with random picks at that latency, so he paints with no endpoint;
 * - `LILGUY_START_ITEMS` and `LILGUY_SCRIPT`, as for the desktop's screenshots: what he starts with, and verbs the scripted policy does first;
 * - `LILGUY_DAYS` (0 for no end), `LILGUY_AUTOSTART=1` to start the first run at once;
 * - `LILGUY_DB` (runs/lilguy.db; "none" for off), `LILGUY_RESUME` (a run id or "latest"), and
 *   `LILGUY_SYNC_URL` / `LILGUY_SYNC_TOKEN` to sync the store to the server, one worker for the process.
 */
fun main() {
    val env: (String) -> String? = { System.getenv(it) }
    fun get(name: String) = env(name)?.takeIf { it.isNotBlank() }
    val port = get("LILGUY_HOST_PORT")?.toInt() ?: 8790
    val bind = get("LILGUY_HOST_BIND") ?: "127.0.0.1"
    val days = get("LILGUY_DAYS")?.toIntOrNull().let { if (it == null) ModelSetup.DEFAULT_DAYS else it.takeIf { d -> d > 0 } }
    val endpoint = get("LILGUY_ENDPOINT")
    val setup = (if (endpoint == null) ModelSetup.scripted(days = days) else ModelSetup.fromConfig(
        configFile = File(get("LILGUY_CONFIG") ?: "config/endpoints.yaml"),
        endpoint = endpoint,
        env = env,
        logRoot = null,
        memoryEndpoint = get("LILGUY_MEMORY_ENDPOINT"),
        writerEndpoint = get("LILGUY_WRITER_ENDPOINT"),
        recallEndpoint = get("LILGUY_RECALL_ENDPOINT"),
        beam = get("LILGUY_BEAM")?.toIntOrNull(),
        days = days,
    )).let { s -> get("LILGUY_PAINTER")?.toDoubleOrNull()?.let { secs -> s.copy(painter = { RandomPicker(secs.seconds, seed = System.nanoTime()) }) } ?: s }.let { s ->
        val extra = get("LILGUY_START_ITEMS").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            .associate { it.substringBefore('=') to (it.substringAfter('=', "1").toIntOrNull() ?: 1) }
        if (extra.isEmpty()) s else s.copy(startItems = extra)
    }.let { s ->
        val script = get("LILGUY_SCRIPT").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (script.isEmpty()) s else s.copy(create = { BedroomPolicy(ModelSetup.THINK_TIME, script = script) })
    }
    val hostName = runCatching { InetAddress.getLocalHost().hostName }.getOrNull()
    val store = (get("LILGUY_DB") ?: "runs/lilguy.db").takeIf { it != "none" }?.let { JdbcSqlDriver.store(File(it)) }
    // One sync worker for the whole process; the sessions record but don't sync.
    val (gitSha, gitDirty) = gitVersion()
    val db = store?.let { RunDatabase(it, sync = null, platform = "host", host = hostName, gitSha = gitSha, gitDirty = gitDirty, resume = get("LILGUY_RESUME")) }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val sync = store?.let { s -> SyncTarget.fromEnv(env)?.let { SyncWorker(s, it, client = "host@${hostName ?: "?"}") } }
    sync?.start(scope)

    val password = get("LILGUY_ADMIN_PASSWORD")?.trim()
    if (password != null && password.length < AdminPassword.MIN_LENGTH) {
        System.err.println("lil guy host: LILGUY_ADMIN_PASSWORD must be at least ${AdminPassword.MIN_LENGTH} characters: openssl rand -hex 24")
        exitProcess(2)
    }
    val auth = AdminPassword(password)
    val life = Life("main", setup, db)
    runBlocking { life.start(scope, autostart = get("LILGUY_AUTOSTART") == "1") }
    println(
        "lil guy host on http://$bind:$port/ (protocol ${dev.lilguy.protocol.PROTOCOL_VERSION}): life 'main', model ${setup.notice ?: if (endpoint == null) "scripted" else endpoint}" +
            "${if (setup.painter != null) ", painting with random picks" else ""}; " +
            "run database ${if (store == null) "off" else if (sync == null) "local only" else "syncing"}; commands ${if (auth.enabled) "need the admin password" else "refused (no LILGUY_ADMIN_PASSWORD)"}",
    )
    Runtime.getRuntime().addShutdownHook(Thread {
        life.stop()
        if (sync != null) runBlocking { withTimeoutOrNull(10.seconds) { sync.syncAll() } }
    })
    embeddedServer(CIO, port = port, host = bind) { host(mapOf(life.id to life), auth) }.start(wait = true)
}

/**
 * The commit this host was built from and whether that checkout had changes: from git when it runs in a checkout,
 * else from the `git-version` file in the working directory that `server/deploy/stage-host.sh` writes into the
 * server's image, which has no git (its sha, then `dirty` or `clean`).
 */
private fun gitVersion(): Pair<String?, Boolean?> {
    git("rev-parse", "HEAD")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it to git("status", "--porcelain")?.isNotBlank() }
    val lines = runCatching { File("git-version").readLines().map { it.trim() } }.getOrNull() ?: return null to null
    return lines.getOrNull(0)?.takeIf { it.isNotEmpty() } to when (lines.getOrNull(1)) { "dirty" -> true; "clean" -> false; else -> null }
}

private fun git(vararg args: String): String? = runCatching {
    val p = ProcessBuilder(listOf("git") + args).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText()
    if (p.waitFor() == 0) out else null
}.getOrNull()
