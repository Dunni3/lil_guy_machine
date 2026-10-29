package dev.lilguy.app

import dev.lilguy.agent.AgentRuntime
import dev.lilguy.agent.KeyDoorPolicy
import dev.lilguy.agent.keyDoorScenario
import dev.lilguy.sim.RoomWorld
import dev.lilguy.sim.World
import dev.lilguy.sim.WorldSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.time.Duration

/**
 * One running episode of the key-and-door room, ticked at a fixed 20 Hz from the render thread
 * (which makes the render thread the sim thread). The agent runs in [scope], off that thread.
 * Keeps a rolling history of snapshots so the view can interpolate between the last two and,
 * while paused, scrub back through past ticks. After the guy walks out
 * it waits [resetDelay] seconds and starts a new episode.
 */
class SimSession(
    private val scope: CoroutineScope,
    private val thinkTime: Duration,
    val resetDelay: Float = 3f,
) {
    lateinit var world: RoomWorld
        private set
    lateinit var runtime: AgentRuntime
        private set

    /** Rolling record of every tick this episode, oldest first; the last entry is the live world. */
    private val history = ArrayDeque<Frame>()

    /** A tick's snapshot plus the agent's decision state at that moment, so scrubbing back shows both. */
    private class Frame(val snapshot: WorldSnapshot, val decision: AgentRuntime.DecisionRecord?, val thinkingFor: Duration?)
    /** How many ticks the view is scrubbed back from the live world (0 = live). Only while paused. */
    var viewBack = 0
        private set

    /** What the view shows: the live snapshot, or a past one while scrubbed back. */
    val current: WorldSnapshot get() = history[history.lastIndex - viewBack].snapshot
    val previous: WorldSnapshot get() = history[(history.lastIndex - viewBack - 1).coerceAtLeast(0)].snapshot

    /** The agent's latest decision as of the viewed tick: live at the head, recorded when scrubbed back. */
    val decision: AgentRuntime.DecisionRecord?
        get() = if (viewBack == 0) runtime.lastDecision else history[history.lastIndex - viewBack].decision

    /** How long the agent had been waiting on the model as of the viewed tick, or null if it wasn't. */
    val thinkingFor: Duration?
        get() = if (viewBack == 0) runtime.decidingSince?.elapsedNow() else history[history.lastIndex - viewBack].thinkingFor

    var episode = 0
        private set
    /** Resuming always returns to the live world: the sim can't run from a past tick. */
    var paused = false
        set(value) {
            field = value
            if (!value) viewBack = 0
        }
    /** Seconds since the room was solved, or null while it isn't. */
    var solvedFor: Float? = null
        private set

    private var agent: Job? = null
    private var accumulator = 0f

    init {
        reset()
    }

    fun reset() {
        agent?.cancel()
        val (w, r) = keyDoorScenario(KeyDoorPolicy(thinkTime))
        world = w
        runtime = r
        history.clear()
        history.addLast(frame(w.snapshot()))
        viewBack = 0
        accumulator = 0f
        solvedFor = null
        episode++
        agent = scope.launch { r.run() }
    }

    /** Advances real time by [delta] seconds: runs whole ticks, or counts down to the next episode. */
    fun update(delta: Float) {
        solvedFor?.let {
            solvedFor = it + delta
            if (it + delta >= resetDelay) reset()
            return
        }
        if (paused) return
        accumulator += min(delta, MAX_FRAME)
        while (accumulator >= TICK && solvedFor == null) {
            tick()
            accumulator -= TICK
        }
    }

    /**
     * Steps the view by [ticks] ticks, forward when positive and back when negative; only while
     * paused, so the clock can't run away. Forward through a scrubbed-back view replays recorded
     * ticks; at the live tick it advances the world.
     */
    fun step(ticks: Int) {
        if (!paused) return
        if (ticks < 0) {
            viewBack = (viewBack - ticks).coerceAtMost(history.lastIndex)
            return
        }
        repeat(ticks) {
            if (viewBack > 0) viewBack-- else if (solvedFor == null) tick()
        }
    }

    /** How far the clock is between [previous] and [current], 0..1. */
    val alpha: Float get() = if (paused || solvedFor != null) 1f else accumulator / TICK

    private fun frame(snapshot: WorldSnapshot) =
        Frame(snapshot, runtime.lastDecision, runtime.decidingSince?.elapsedNow())

    private fun tick() {
        world.step()
        val next = world.snapshot()
        history.addLast(frame(next))
        if (history.size > MAX_HISTORY) history.removeFirst()
        if (next.completed) solvedFor = 0f
    }

    fun dispose() {
        agent?.cancel()
    }

    private companion object {
        const val TICK = 1f / World.TICKS_PER_SECOND
        /** Don't try to catch up more than this after a stall (a GC, the app in the background). */
        const val MAX_FRAME = 0.25f
        /** Two minutes of ticks to scrub back through. */
        const val MAX_HISTORY = 120 * World.TICKS_PER_SECOND
    }
}
