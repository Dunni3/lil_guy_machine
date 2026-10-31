package dev.lilguy.agent.memory

import dev.lilguy.agent.authored.AuthorCall
import dev.lilguy.agent.authored.AuthorHandler
import dev.lilguy.sim.AuthoredResult
import dev.lilguy.sim.AuthoredView
import dev.lilguy.sim.Observation
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
