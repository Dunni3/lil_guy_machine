package dev.lilguy.app

import dev.lilguy.agent.AgentRuntime
import dev.lilguy.agent.DecisionLog
import dev.lilguy.agent.keyDoorScenario
import dev.lilguy.decision.DecisionModel
import dev.lilguy.sim.RoomWorld
import dev.lilguy.sim.World
import dev.lilguy.sim.WorldSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * One running episode of the key-and-door room, ticked at a fixed 20 Hz from the render thread
 * (which makes the render thread the sim thread). The agent runs in [scope], off that thread.
 * Keeps the last two snapshots so the view can interpolate between them. After the guy walks out
 * it waits [resetDelay] seconds and starts a new episode.
 */
class SimSession(
    private val scope: CoroutineScope,
    val setup: ModelSetup,
    val resetDelay: Float = 3f,
) {
    lateinit var world: RoomWorld
        private set
    lateinit var runtime: AgentRuntime
        private set
    lateinit var previous: WorldSnapshot
        private set
    lateinit var current: WorldSnapshot
        private set

    var episode = 0
        private set
    var paused = false
    /** Seconds since the room was solved, or null while it isn't. */
    var solvedFor: Float? = null
        private set

    private var agent: Job? = null
    private var model: DecisionModel? = null
    private var log: DecisionLog? = null
    private var accumulator = 0f

    init {
        reset()
    }

    fun reset() {
        stopAgent()
        episode++
        val m = setup.create()
        val episodeLog = setup.logFor(episode, m.id)
        model = m
        log = episodeLog
        val (w, r) = keyDoorScenario(m, setup.timeout) { episodeLog?.write(it) }
        world = w
        runtime = r
        previous = w.snapshot()
        current = previous
        accumulator = 0f
        solvedFor = null
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

    /** Steps [ticks] ticks; only while paused, so the clock can't run away. */
    fun step(ticks: Int) {
        if (!paused) return
        repeat(ticks) { if (solvedFor == null) tick() }
    }

    /** How far the clock is between [previous] and [current], 0..1. */
    val alpha: Float get() = if (paused || solvedFor != null) 1f else accumulator / TICK

    private fun tick() {
        previous = current
        world.step()
        current = world.snapshot()
        if (current.completed) solvedFor = 0f
    }

    fun dispose() = stopAgent()

    private fun stopAgent() {
        agent?.cancel()
        (model as? AutoCloseable)?.close()
        log?.close()
        agent = null
        model = null
        log = null
    }

    private companion object {
        const val TICK = 1f / World.TICKS_PER_SECOND
        /** Don't try to catch up more than this after a stall (a GC, the app in the background). */
        const val MAX_FRAME = 0.25f
    }
}
