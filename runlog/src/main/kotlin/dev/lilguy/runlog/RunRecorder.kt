package dev.lilguy.runlog

import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Where a run's rows go. [record] returns at once and never does I/O on the caller's thread, so
 * the sim and render threads can call it; a recorder that fails says so once and keeps the game
 * running.
 */
interface RunRecorder : AutoCloseable {
    fun record(row: Row)

    /** Writes what's queued and stops; rows recorded after this are dropped. */
    override fun close() {}

    companion object {
        val None: RunRecorder = object : RunRecorder {
            override fun record(row: Row) {}
        }
    }
}

/**
 * One JSON line per row in [file], appended and flushed in batches on its own thread, so a run
 * that's killed keeps all but the last moment. The local database replaces this as the store
 * (step 2 of the run-database plan); until then it's what a run leaves behind.
 */
class JsonlRecorder(val file: File) : RunRecorder {
    private val queue = LinkedBlockingQueue<Any>()
    @Volatile private var closed = false
    @Volatile private var failed = false
    private val thread = Thread(::drain, "runlog-${file.name}").apply {
        isDaemon = true
        start()
    }

    override fun record(row: Row) {
        if (!closed) queue.put(row)
    }

    override fun close() {
        if (closed) return
        closed = true
        queue.put(STOP)
        thread.join(TimeUnit.SECONDS.toMillis(10))
    }

    private fun drain() {
        val writer = runCatching {
            file.parentFile?.mkdirs()
            FileOutputStream(file, true).bufferedWriter(Charsets.UTF_8)
        }.getOrElse { e -> fail(e); return }
        writer.use { w ->
            val batch = ArrayList<Any>()
            while (true) {
                batch += queue.take()
                queue.drainTo(batch)
                var stop = false
                for (item in batch) {
                    if (item === STOP) { stop = true; continue }
                    runCatching {
                        w.write(Rows.encode(item as Row))
                        w.newLine()
                    }.onFailure(::fail)
                }
                batch.clear()
                runCatching { w.flush() }.onFailure(::fail)
                if (stop) return
            }
        }
    }

    private fun fail(e: Throwable) {
        if (!failed) System.err.println("runlog: writing ${file.path} failed: $e")
        failed = true
    }

    private companion object {
        val STOP = Any()
    }
}
