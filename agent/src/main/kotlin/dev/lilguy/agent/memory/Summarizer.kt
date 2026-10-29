package dev.lilguy.agent.memory

import dev.lilguy.decision.ActionSchema
import dev.lilguy.decision.DecisionModel
import dev.lilguy.decision.DecisionRequest
import dev.lilguy.decision.Message
import dev.lilguy.decision.SchemaHints
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@Serializable
@SerialName("memory")
data class MemoryReply(val memory: String)

/**
 * The second layer: reads a stretch of the event stream, has a language model write it up as a
 * memory, and stores that. Each job starts where the last one stopped, so every event is covered
 * once. A failed job stores nothing and is retried from the same place next time.
 *
 * [model] must write free text, so a closed-choice model (TypeSafe's Jev) can't be the writer.
 */
class Summarizer(
    private val model: DecisionModel,
    private val store: MemoryStore,
    private val run: String,
    /** Most events one memory covers. */
    val batchSize: Int = 40,
    private val timeout: Duration = 60.seconds,
    private val onFailure: (Throwable) -> Unit = {},
) {
    init {
        require(!model.capabilities.closedChoice) { "the summarizer needs a model that writes text; '${model.id}' only picks from options" }
    }

    private val schema = ActionSchema.of(
        "memory",
        MemoryReply.serializer(),
        SchemaHints(descriptions = mapOf("memory.memory" to "The memory, in the first person, two to four sentences.")),
    ) { null }

    /** The writing model's id. */
    val writer: String get() = model.id

    /** Events not yet covered by a level-1 memory. */
    fun pending(): Int = store.countEventsAfter(run, store.coveredThrough(run, 1))

    /** Writes one memory from the next batch of events. Returns null if there was nothing to write or the model failed. */
    suspend fun summarizeOnce(): StoredMemory? {
        val events = store.eventsAfter(run, store.coveredThrough(run, 1), batchSize)
        if (events.isEmpty()) return null
        val text = try {
            withTimeout(timeout) { model.decide(request(events), schema) }.value.memory.trim()
        } catch (e: CancellationException) {
            if (e is kotlinx.coroutines.TimeoutCancellationException) { onFailure(e); return null }
            throw e
        } catch (e: Exception) {
            onFailure(e)
            return null
        }
        if (text.isEmpty()) return null
        val first = events.first()
        val last = events.last()
        val span = Span(first.day, first.time, last.day, last.time)
        val id = store.addMemory(run, 1, first.id, last.id, span, text, model.id)
        return StoredMemory(id, run, 1, first.id, last.id, span, text, model.id)
    }

    /** Summarizes full batches until fewer than [batchSize] events are left, or one job fails. */
    suspend fun catchUp() {
        while (pending() >= batchSize) summarizeOnce() ?: return
    }

    /** Summarizes everything left, a short last batch included, at the end of a run. */
    suspend fun flush() {
        while (pending() > 0) summarizeOnce() ?: return
    }

    private fun request(events: List<MemoryEvent>) = DecisionRequest(
        system = SYSTEM,
        messages = listOf(
            Message(
                Message.Role.User,
                events.joinToString("\n", prefix = "Log:\n", postfix = "\n\nWrite the memory.") { "[${it.stamp}] ${if (it.kind == EventKind.Observation) "I saw" else "I did"}: ${it.text}" },
            ),
        ),
        temperature = 0.3,
        timeout = timeout,
    )

    companion object {
        val SYSTEM = """
            |You write the memories of a little guy who lives alone in a bedroom. You are given a log of what he saw and did, oldest first: "I saw" lines are how things stood when he had to choose, "I did" lines are what he chose.
            |Write one memory of this stretch, in the first person, as he would remember it: what he did, what changed (money, needs, things he got), what worked or didn't, and anything he should keep in mind next time.
            |Say only what the log shows. Keep it to two to four sentences and do not repeat the log line by line.
            |Reply with a JSON object {"memory": ...}.
        """.trimMargin()
    }
}
