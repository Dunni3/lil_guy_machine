package dev.lilguy.agent.art

import dev.lilguy.agent.memory.EventKind
import dev.lilguy.agent.memory.MemoryStore
import dev.lilguy.sim.ArtNote
import dev.lilguy.sim.BedroomContent
import dev.lilguy.sim.BedroomWorld
import dev.lilguy.sim.CanvasSpec
import dev.lilguy.sim.Intent
import dev.lilguy.sim.IntentStatus
import dev.lilguy.sim.Observation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Where his art gets made, the way [dev.lilguy.agent.memory.AgentMemory] handles the journal: when
 * he starts an activity that makes a drawn item (one with a canvas), the [Painter] draws it, in
 * the background with a [scope] and inline without. At the next decision point, if the activity
 * succeeded, the drawing is matched to the sim's new piece, saved as a PNG in [dir], put in the
 * event stream, and handed to the world ([BedroomWorld.annotate]) so the sim knows its title and
 * tags; if it failed or was interrupted, the drawing is thrown away. Each day's commission is also
 * worded once, in the customer's voice, when there's a [voice] (a text model) to do it.
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
) {
    private class Pending(val intent: Intent, val item: String, val tick: Long) {
        @Volatile var result: Drawing? = null
        var job: Job? = null
    }

    private var content: BedroomContent? = null
    private var sink: (ArtNote) -> Unit = {}
    @Volatile private var pending: Pending? = null
    @Volatile private var committing: Job? = null
    private val worded = mutableSetOf<Int>()
    private val claimed = mutableSetOf<Int>()
    private val drawn = CopyOnWriteArrayList<Drawing>()

    val artist: String get() = painter.artist

    /** True while a piece is being drawn or waiting to be matched to its piece. */
    val drawing: Boolean get() = pending?.job?.isActive == true || committing?.isActive == true

    /** True while a piece's activity has ended but its drawing hasn't been handed to the world yet. */
    val finishing: Boolean get() = committing?.isActive == true

    /** The latest drawing failure, if any. */
    @Volatile var lastError: String? = null
        private set

    /** Pieces drawn and kept, oldest first, each with its [Drawing.piece] and artwork. Readable from any thread. */
    fun pieces(): List<Drawing> = drawn.toList()

    /** The drawing for the activity under way, once the model has sent it; not kept yet. Readable from any thread. */
    fun inProgress(): Drawing? = pending?.result

    /** Draws for [world]: its content says what each activity makes, and drawings go back to it. */
    fun connect(world: BedroomWorld) {
        content = world.content
        sink = world::annotate
    }

    /** At a decision point: keeps or drops a drawing whose activity has ended. */
    fun observed(obs: Observation) {
        val p = pending ?: return
        if (obs.tick <= p.tick) return
        pending = null
        if (obs.lastIntent != p.intent || obs.lastResult != IntentStatus.Succeeded) {
            p.job?.cancel()
            return
        }
        // The piece the activity just made: the newest undrawn one of its item.
        val piece = obs.art.orEmpty().filter { it.item == p.item && it.title == null && it.id !in claimed }.maxOfOrNull { it.id }
        piece?.let { claimed += it }
        val done = p.result
        if (done != null) commit(done, piece, obs)
        else committing = scope?.launch {
            p.job?.join()
            p.result?.let { commit(it, piece, obs) }
        }
    }

    /**
     * He chose [intent] at [obs]: words a new commission, and starts drawing if the intent makes
     * art and isn't refused at once. [persona] and [situation] are what the piece is drawn from.
     */
    suspend fun acted(obs: Observation, intent: Intent, persona: String, situation: String) {
        word(obs)
        val c = content ?: return
        val activity = (intent as? Intent.Do)?.let { c.activity(it.activity) } ?: return
        if (obs.activities.orEmpty().any { it.id == activity.id && it.locked != null }) return
        val made = activity.produces.keys.mapNotNull { c.item(it) }.firstOrNull { it.canvas != null } ?: return
        val canvas: CanvasSpec = made.canvas!!
        pending?.job?.cancel()
        val p = Pending(intent, made.id, obs.tick)
        pending = p
        val subjects = Painter.Subjects.of(c.commissions)
        val draw = suspend { p.result = painter.draw(persona, situation, made.name, canvas, obs.commission, subjects, obs.tick, obs.clock?.day, obs.clock?.time) }
        if (scope == null) draw() else p.job = scope.launch { draw() }
    }

    /** Waits for a drawing being matched to its piece. Call at the end of a run. */
    suspend fun flush() {
        committing?.join()
    }

    private suspend fun word(obs: Observation) {
        val v = voice ?: return
        val c = obs.commission?.takeIf { it.request == null && it.id !in worded } ?: return
        worded += c.id
        val write = suspend { v.word(c)?.let { sink(ArtNote.CommissionWorded(c.id, it)) } }
        if (scope == null) write() else scope.launch { write() }
    }

    private fun commit(d: Drawing, piece: Int?, ended: Observation) {
        val drawing = d.copy(piece = piece)
        val art = drawing.artwork
        if (art != null && piece != null) {
            dir?.let { runCatching { it.mkdirs(); File(it, "piece-%03d.png".format(piece)).writeBytes(art.png()) } }
            drawn += drawing
            sink(ArtNote.PieceDrawn(piece, drawing.title!!, drawing.tags))
            val tags = if (drawing.tags.isEmpty()) "" else " (${drawing.tags.joinToString()})"
            store.appendEvent(run, ended.tick, ended.clock?.day, ended.clock?.time, EventKind.Art, "made ${drawing.item} $piece, \"${drawing.title}\"$tags")
        }
        drawing.error?.let { lastError = it }
        onDrawing(drawing)
    }
}
