package dev.lilguy.runlog

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.atomic.AtomicLong

/**
 * One session of one run writing to the [store]. [record] takes a row and returns at once: the
 * row goes on a queue that a coroutine on the IO dispatcher writes in batches, so the sim, render
 * and agent threads never wait on the disk. [blob] hashes a body where it's called (so the row can
 * name it) and leaves the compressing and writing to the queue.
 *
 * Every fact row takes its `seq` from [next], one counter for the run across tables, continuing
 * from the store's highest when a session resumes a run. With [bodies] off, prompts, replies and
 * observations aren't kept (long batches); the rows still are.
 */
class RunRecorder(
    val store: RunStore,
    val runId: String,
    val sessionId: String,
    val bodies: Boolean = true,
) : AutoCloseable {
    private val seq = AtomicLong(store.maxSeq(runId))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<Any>(Channel.UNLIMITED)

    private class Body(val hash: String, val mediaType: String, val bytes: ByteArray)
    private class Flush(val done: CompletableDeferred<Unit>)

    /** Hashes already queued this session, so a repeated system prompt isn't compressed again. */
    private val seen = object : LinkedHashMap<String, Unit>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > SEEN
    }

    /** The latest failure to write, if any; rows in that batch are lost, the run goes on. */
    @Volatile var lastError: String? = null
        private set

    init {
        scope.launch {
            val batch = mutableListOf<Row>()
            for (first in queue) {
                var item: Any? = first
                val flushes = mutableListOf<Flush>()
                while (item != null) {
                    when (item) {
                        is Row -> batch += item
                        is Body -> runCatching { store.putBlob(item.hash, item.mediaType, Blobs.gzip(item.bytes), item.bytes.size) }.onFailure { lastError = "blob: ${it.message}" }
                        is Flush -> flushes += item
                    }
                    item = if (batch.size >= BATCH) null else queue.tryReceive().getOrNull()
                }
                if (batch.isNotEmpty()) {
                    runCatching { store.write(batch) }.onFailure { lastError = it.message ?: it.toString() }
                    batch.clear()
                }
                flushes.forEach { it.done.complete(Unit) }
            }
        }
    }

    /** The next `seq` in this run. */
    fun next(): Long = seq.incrementAndGet()

    /** The run's latest `seq` so far. */
    fun last(): Long = seq.get()

    fun stamp(tick: Long, day: Int?, minuteOfDay: Int?) = Stamp(runId, sessionId, next(), tick, day, minuteOfDay)

    fun record(row: Row) {
        queue.trySend(row)
    }

    /** Queues [bytes] as a blob and returns its hash; null when [bodies] are off. */
    fun blob(bytes: ByteArray, mediaType: String): String? {
        if (!bodies) return null
        val hash = Blobs.sha256(bytes)
        val fresh = synchronized(seen) { seen.put(hash, Unit) == null }
        if (fresh) queue.trySend(Body(hash, mediaType, bytes))
        return hash
    }

    fun blob(text: String, mediaType: String = "text/plain"): String? = blob(text.toByteArray(), mediaType)

    fun blob(json: JsonElement): String? = blob(json.toString().toByteArray(), "application/json")

    /** Waits until everything recorded so far is in the store. */
    suspend fun flush() {
        val f = Flush(CompletableDeferred())
        queue.send(f)
        f.done.await()
    }

    /** Stops the writer; call [flush] first to keep what's queued. */
    override fun close() {
        queue.close()
        scope.cancel()
    }

    private companion object {
        const val BATCH = 200
        const val SEEN = 512
    }
}
