package dev.lilguy.agent.memory

import dev.lilguy.agent.authored.AuthorCall
import dev.lilguy.agent.authored.AuthorHandler
import dev.lilguy.sim.AuthoredResult
import dev.lilguy.sim.AuthoredView
import dev.lilguy.sim.Observation
import dev.lilguy.sim.ProgressOp
import dev.lilguy.sim.WriteOp

/**
 * `write: { with: thought }`: a [Thinker] writes one thought from the decision prompt he was just
 * shown. Kept, it goes into the event stream, and with `shows_as: plan` it becomes his plan
 * ([onPlan]), shown in every later prompt. [onThought] gets every thought as it's written, kept or not;
 * [onStored] each kept one with its event id, so it can be recalled later.
 */
class ThoughtHandler(
    private val thinker: Thinker,
    private val store: MemoryStore,
    private val run: String,
    private val onThought: (Thought) -> Unit,
    private val onPlan: (Thought) -> Unit,
    private val onStored: (Long, Thought) -> Unit = { _, _ -> },
) : AuthorHandler<Thought> {
    override val name = "thought"
    override val writer: String get() = thinker.thinker

    override suspend fun author(call: AuthorCall) =
        thinker.think(call.system, call.situation, call.obs.tick, call.obs.clock?.day, call.obs.clock?.time).also(onThought)

    override fun keep(result: Thought, call: AuthorCall, ended: Observation, view: AuthoredView?, deliver: (AuthoredResult) -> Unit) {
        val text = result.text ?: return
        val op = call.op as WriteOp
        if ("events" in op.into) onStored(store.appendEvent(run, result.tick, result.day, result.time, EventKind.Thought, text), result)
        if ("short_term" in op.into && op.showsAs == "plan") onPlan(result)
        deliver(AuthoredResult.Written(text))
    }
}

/**
 * `write: { with: reading }`: a [Reader] writes what stuck with him from the chapter he read (the
 * book bound to the verb's `progress` op, and the chapter the observation says is next). Kept, it
 * goes into the event stream as a [EventKind.Reading] event. [onRead] gets every one as it's
 * written, kept or not.
 */
class ReadingHandler(
    private val reader: Reader,
    private val store: MemoryStore,
    private val run: String,
    private val onRead: (Thought) -> Unit = {},
) : AuthorHandler<Pair<String, Thought>> {
    override val name = "reading"
    override val writer: String get() = reader.reader

    override suspend fun author(call: AuthorCall): Pair<String, Thought> {
        val progress = call.verb.ops<ProgressOp>().firstOrNull()
        val id = progress?.let { if (it.book == "what") call.intent.what else call.intent.on }
            ?: call.obs.books.orEmpty().firstOrNull()?.id
        val book = call.obs.books.orEmpty().firstOrNull { it.id == id }
        val title = book?.title ?: id ?: "a book"
        val thought = reader.read(call.persona, title, book?.next, progress?.study == true, call.obs.tick, call.obs.clock?.day, call.obs.clock?.time)
        onRead(thought)
        return title to thought
    }

    override fun keep(result: Pair<String, Thought>, call: AuthorCall, ended: Observation, view: AuthoredView?, deliver: (AuthoredResult) -> Unit) {
        val (title, thought) = result
        val text = thought.text ?: return
        if ("events" in call.op.into) store.appendEvent(run, ended.tick, ended.clock?.day, ended.clock?.time, EventKind.Reading, "from \"$title\": $text")
        deliver(AuthoredResult.Written(text))
    }
}
