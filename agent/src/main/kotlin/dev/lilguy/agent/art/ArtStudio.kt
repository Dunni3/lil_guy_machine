package dev.lilguy.agent.art

import dev.lilguy.agent.authored.AuthorCall
import dev.lilguy.agent.authored.AuthorHandler
import dev.lilguy.agent.memory.EventKind
import dev.lilguy.agent.memory.MemoryStore
import dev.lilguy.sim.ArtNote
import dev.lilguy.sim.AuthoredResult
import dev.lilguy.sim.AuthoredView
import dev.lilguy.sim.BedroomContent
import dev.lilguy.sim.BedroomWorld
import dev.lilguy.sim.DrawOp
import dev.lilguy.sim.Observation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The `draw` handler: when he starts a verb with a `draw` op, the [Painter] draws the piece it will
 * make. The lifecycle around it (background call, keep only if the verb succeeds, the clock hold)
 * is [dev.lilguy.agent.authored.Authors]'. Kept, the drawing is saved as a PNG in [dir], put in the
 * event stream, and handed to the world for its piece ([BedroomWorld.deliver]) so the sim knows its
 * title and tags. Each day's commission is also worded once, in the customer's voice, when there's
 * a [voice] (a text model) to do it ([word]).
 *
 * [connect] it to the world before the run starts.
 */
class ArtStudio(
    private val painter: Painter,
    private val store: MemoryStore,
    private val run: String,
    private val scope: CoroutineScope? = null,
    /** One PNG per piece goes here; null to keep them in memory only. */
    private val dir: File? = null,
    private val voice: CommissionVoice? = null,
    /** Every finished drawing, kept or not; called from whichever coroutine finished it. */
    private val onDrawing: (Drawing) -> Unit = {},
) : AuthorHandler<Drawing> {
    private var content: BedroomContent? = null
    private var sink: (ArtNote) -> Unit = {}
    private val worded = mutableSetOf<Int>()
    private val drawn = CopyOnWriteArrayList<Drawing>()

    override val name = "draw"
    override val writer: String get() = artist

    val artist: String get() = painter.artist

    /** The latest drawing failure, if any. */
    @Volatile var lastError: String? = null
        private set

    /** Pieces drawn and kept, oldest first, each with its [Drawing.piece] and artwork. Readable from any thread. */
    fun pieces(): List<Drawing> = drawn.toList()

    /** Draws for [world]: its content says what each verb makes, and commission wordings go back to it. */
    fun connect(world: BedroomWorld) {
        content = world.content
        sink = world::annotate
    }

    override suspend fun author(call: AuthorCall): Drawing {
        val op = call.op as DrawOp
        val id = call.verb.args[op.what]?.let { slot -> if (op.what == "what") call.intent.what ?: slot else call.intent.on ?: slot } ?: op.what
        val c = content ?: error("draw: the studio isn't connected to a world")
        val made = c.withName(id).firstOrNull { it.canvas != null } ?: error("draw: no drawable '$id'")
        val obs = call.obs
        val subjects = Painter.Subjects.of(c.commissions)
        return painter.draw(call.persona, call.situation, made.name, made.canvas!!, obs.commission, subjects, obs.tick, obs.clock?.day, obs.clock?.time, drawn.mapNotNull { it.title })
            .also { d -> d.error?.let { lastError = it }; if (d.artwork == null) onDrawing(d) }
    }

    override fun keep(result: Drawing, call: AuthorCall, ended: Observation, view: AuthoredView?, deliver: (AuthoredResult) -> Unit) {
        val piece = view?.piece
        val drawing = result.copy(piece = piece)
        val art = drawing.artwork ?: return
        if (piece == null) return onDrawing(drawing)
        dir?.let { runCatching { it.mkdirs(); File(it, "piece-%03d.png".format(piece)).writeBytes(art.png()) } }
        drawn += drawing
        deliver(AuthoredResult.Drawn(drawing.title!!, drawing.tags))
        val tags = if (drawing.tags.isEmpty()) "" else " (${drawing.tags.joinToString()})"
        if ("events" in call.op.into) store.appendEvent(run, ended.tick, ended.clock?.day, ended.clock?.time, EventKind.Art, "made ${drawing.item} $piece, \"${drawing.title}\"$tags")
        onDrawing(drawing)
    }

    /** At a decision point: words a new commission, once, when there's a voice. */
    suspend fun word(obs: Observation) {
        val v = voice ?: return
        val c = obs.commission?.takeIf { it.request == null && it.id !in worded } ?: return
        worded += c.id
        val write = suspend { v.word(c)?.let { sink(ArtNote.CommissionWorded(c.id, it)) } }
        if (scope == null) write() else scope.launch { write() }
    }
}
