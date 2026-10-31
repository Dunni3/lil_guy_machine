package dev.lilguy.agent.memory

import dev.lilguy.agent.authored.AuthorCall
import dev.lilguy.agent.authored.AuthorHandler
import dev.lilguy.decision.Usage
import dev.lilguy.sim.AuthoredResult
import dev.lilguy.sim.AuthoredView
import dev.lilguy.sim.Observation
import dev.lilguy.sim.WriteOp
import kotlin.time.Duration

/**
 * `write: { with: journal }`: a [JournalWriter] writes an entry from his state. Kept, it goes into
 * his journal and the event stream (as the op's `into` says). [onFinish] gets every session, kept
 * or not.
 */
class JournalHandler(
    private val journal: JournalWriter,
    private val store: MemoryStore,
    private val run: String,
    private val onFinish: (JournalSession) -> Unit,
) : AuthorHandler<JournalSession> {
    override val name = "journal"
    override val writer: String get() = journal.writer

    override suspend fun author(call: AuthorCall) = journal.write(call.persona, call.situation, call.obs.tick, call.obs.clock?.day, call.obs.clock?.time)

    override fun keep(result: JournalSession, call: AuthorCall, ended: Observation, view: AuthoredView?, deliver: (AuthoredResult) -> Unit) {
        val entry = result.entry ?: return onFinish(result)
        if ("journal" in call.op.into) store.addJournal(run, result.tick, result.day, result.time, entry, result.wants, result.writer)
        if ("events" in call.op.into) {
            val wants = if (result.wants.isEmpty()) "" else " and wrote down ${result.wants.size} want${if (result.wants.size == 1) "" else "s"}"
            store.appendEvent(run, ended.tick, ended.clock?.day, ended.clock?.time, EventKind.Journal, "wrote in my journal$wants")
        }
        deliver(AuthoredResult.Written(entry))
        onFinish(result.copy(status = JournalSession.Status.Stored))
    }

    override fun discard(result: JournalSession?, call: AuthorCall) = onFinish(
        when {
            result == null -> JournalSession(call.obs.tick, call.obs.clock?.day, call.obs.clock?.time, journal.writer, emptyList(), null, emptyList(), Usage(), Duration.ZERO, "the activity ended before the entry was written", JournalSession.Status.Discarded)
            result.status == JournalSession.Status.Written -> result.copy(status = JournalSession.Status.Discarded)
            else -> result
        },
    )
}

/**
 * `write: { with: thought }`: a [Thinker] writes one thought from the decision prompt he was just
 * shown. Kept, it goes into the event stream, and with `shows_as: plan` it becomes his plan
 * ([onPlan]), shown in every later prompt. [onThought] gets every thought as it's written, kept or not.
 */
class ThoughtHandler(
    private val thinker: Thinker,
    private val store: MemoryStore,
    private val run: String,
    private val onThought: (Thought) -> Unit,
    private val onPlan: (Thought) -> Unit,
) : AuthorHandler<Thought> {
    override val name = "thought"
    override val writer: String get() = thinker.thinker

    override suspend fun author(call: AuthorCall) =
        thinker.think(call.system, call.situation, call.obs.tick, call.obs.clock?.day, call.obs.clock?.time).also(onThought)

    override fun keep(result: Thought, call: AuthorCall, ended: Observation, view: AuthoredView?, deliver: (AuthoredResult) -> Unit) {
        val text = result.text ?: return
        val op = call.op as WriteOp
        if ("events" in op.into) store.appendEvent(run, result.tick, result.day, result.time, EventKind.Thought, text)
        if ("short_term" in op.into && op.showsAs == "plan") onPlan(result)
        deliver(AuthoredResult.Written(text))
    }
}
