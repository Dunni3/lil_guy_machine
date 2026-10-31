package dev.lilguy.sim

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The machinery every room shares: one character on a tile map, the intent lifecycle
 * (inbox, validate, run, finish), A* walking, speech, events and publishing. A room supplies
 * [plan] (what an intent means there) and [publish] (what the renderer and the agent see).
 *
 * Threading: call [step] from one thread only, the sim thread. Everything else is safe from any
 * thread. [observe] and [snapshot] return values published at the end of the last tick, and
 * [submit] queues the intent for the start of the next one. A subclass calls [publishFirst] at
 * the end of its constructor, once its own state exists.
 */
abstract class GridWorld protected constructor(
    val map: TileMap,
    protected val guy: Character,
) : World {
    protected var ticks = 0L
        private set
    private val inbox = ConcurrentLinkedQueue<Handle>()
    private val events = ArrayDeque<WorldEvent>()
    protected var running: Running? = null
        private set
    protected var last: Handle? = null
        private set
    protected var saying: String? = null
        private set
    private var sayUntil = 0L

    // Decision points. `pending` counts submitted intents the sim hasn't drained yet; the flag
    // only goes up when the character is idle with nothing queued, and only after the tick's
    // observation is published, so an agent woken on another thread never reads a stale one.
    private val lock = Any()
    private var pending = 0
    private val idle = MutableStateFlow(true)
    private var finishedThisTick = false

    @Volatile private lateinit var published: Published

    override val tick: Long get() = published.snapshot.tick

    val agentId: EntityId get() = guy.id

    protected fun publishFirst() {
        published = publish()
    }

    /** True when the character has nothing to do and nothing queued: the agent should decide. */
    fun awaitingDecision(agentId: EntityId): Boolean {
        requireAgent(agentId)
        return idle.value
    }

    override fun step() {
        ticks++
        drainInbox()
        running?.let { advance(it) }
        afterWork()
        if (saying != null && ticks >= sayUntil) saying = null
        published = publish()
        if (finishedThisTick) {
            finishedThisTick = false
            synchronized(lock) { if (pending == 0) idle.value = true }
        }
    }

    override fun observe(agentId: EntityId): Observation {
        requireAgent(agentId)
        return published.observation
    }

    override fun submit(agentId: EntityId, intent: Intent): IntentHandle {
        requireAgent(agentId)
        val handle = Handle(intent)
        synchronized(lock) {
            pending++
            idle.value = false
        }
        inbox.add(handle)
        return handle
    }

    override fun snapshot(): WorldSnapshot = published.snapshot

    override suspend fun awaitDecisionPoint(agentId: EntityId) {
        requireAgent(agentId)
        idle.first { it }
    }

    private fun requireAgent(agentId: EntityId) = require(agentId == guy.id) { "no character '${agentId.value}'" }

    // --- what a room supplies --------------------------------------------------------------------

    /** Validates [intent] against the current state and returns the work that carries it out; [reject] otherwise. */
    protected abstract fun plan(intent: Intent): Work

    /** The snapshot and observation for the tick that just ran. */
    protected abstract fun publish(): Published

    /** Runs every tick after the intent's work, before publishing: clocks, needs. */
    protected open fun afterWork() {}

    /** Called when running work ends for any reason (success, failure, interruption). */
    protected open fun workEnded(handle: Handle) {}

    /** Whether the character can stand on (x, y). */
    open fun passable(x: Int, y: Int): Boolean = map.isFloor(x, y)

    /** How an event's time is shown to the agent. */
    protected open fun stamp(tick: Long): String = "t$tick"

    open fun describe(intent: Intent): String = when (intent) {
        is Intent.MoveTo -> "move_to ${describe(intent.target)}"
        is Intent.PickUp -> "pick_up ${intent.itemId}"
        is Intent.Use -> "use ${intent.itemId}" + (intent.on?.let { " on $it" } ?: "")
        is Intent.Say -> "say"
        is Intent.Wait -> "wait ${intent.ticks}"
        is Intent.Do -> "do ${intent.activity}" + listOfNotNull(intent.what, intent.on?.let { "on $it" }).joinToString("") { " $it" }
        is Intent.Buy -> "buy ${intent.item}"
        Intent.Think -> "think"
    }

    protected fun describe(target: Target): String = when (target) {
        is Target.Tile -> "(${target.x}, ${target.y})"
        is Target.Entity -> target.id
    }

    // --- intent lifecycle --------------------------------------------------------------------

    private fun drainInbox() {
        while (true) {
            val handle = inbox.poll() ?: break
            synchronized(lock) { pending-- }
            running?.let { interrupt(it) }
            last = handle
            try {
                val work = plan(handle.intent)
                handle.status = IntentStatus.Validated
                running = Running(handle, work)
            } catch (e: Rejected) {
                finish(handle, IntentStatus.Failed(e.reason))
            }
        }
    }

    private fun interrupt(run: Running) {
        run.handle.status = IntentStatus.Interrupted
        running = null
        workEnded(run.handle)
        outcome(run.handle, "${describe(run.handle.intent)} was interrupted")
    }

    private fun finish(handle: Handle, status: IntentStatus) {
        handle.status = status
        if (running?.handle === handle) {
            running = null
            workEnded(handle)
        }
        if (status is IntentStatus.Failed) outcome(handle, "${describe(handle.intent)} failed: ${status.reason}")
        finishedThisTick = true
    }

    private fun advance(run: Running) {
        val handle = run.handle
        if (handle.status == IntentStatus.Validated) handle.status = IntentStatus.Executing
        stepWork(run.work)?.let { finish(handle, it) }
    }

    /** One tick of [work]: null while it runs, then Succeeded or Failed. */
    private fun stepWork(work: Work): IntentStatus? = when (work) {
        is Work.Walk -> {
            walk(work.path)
            if (work.path.isEmpty()) IntentStatus.Succeeded else null
        }
        is Work.Timed -> {
            work.ticksLeft--
            if (work.ticksLeft <= 0) outcome(work.finish()) else null
        }
        is Work.Until -> {
            if (!work.started) {
                work.started = true
                work.start()
            }
            if (work.done()) outcome(work.finish()) else null
        }
        is Work.Steps -> {
            val status = stepWork(work.steps.first())
            when {
                status == null -> null
                status is IntentStatus.Failed -> status
                else -> {
                    work.steps.removeFirst()
                    if (work.steps.isEmpty()) IntentStatus.Succeeded else null
                }
            }
        }
    }

    private fun outcome(failure: String?): IntentStatus = if (failure == null) IntentStatus.Succeeded else IntentStatus.Failed(failure)

    /** Starts a speech bubble and returns the work that waits it out; [reject]s bad text. */
    protected fun say(intent: Intent.Say): Work {
        val text = intent.text.trim()
        if (text.isEmpty()) reject("say needs some text")
        if (text.length > MAX_SAY) reject("say is limited to $MAX_SAY characters")
        val duration = (text.length * 2).coerceIn(MIN_SAY_TICKS, MAX_SAY_TICKS)
        saying = text
        sayUntil = ticks + duration
        event("${guy.id.value} says \"$text\"")
        return Work.Timed(duration) { null }
    }

    protected fun waitFor(intent: Intent.Wait): Work {
        if (intent.ticks !in 1..MAX_WAIT) reject("wait ticks must be between 1 and $MAX_WAIT")
        return Work.Timed(intent.ticks) { null }
    }

    // --- movement ----------------------------------------------------------------------------

    /** Called when the character steps onto [cell]. */
    protected open fun entered(cell: Cell) {}

    private fun walk(path: ArrayDeque<Cell>) {
        var budget = SPEED
        while (budget > 0f && path.isNotEmpty()) {
            val next = path.first()
            val tx = next.x + 0.5f
            val ty = next.y + 0.5f
            val dx = tx - guy.x
            val dy = ty - guy.y
            val d = hypot(dx, dy)
            if (d > 1e-4f) guy.facing = Direction.of(dx, dy)
            if (d <= budget) {
                guy.x = tx
                guy.y = ty
                budget -= d
                path.removeFirst()
                entered(next)
            } else {
                guy.x += dx / d * budget
                guy.y += dy / d * budget
                budget = 0f
            }
        }
    }

    /** A path onto [goal], or, when [goal] blocks (a locked door, a desk), onto the nearest tile beside it. */
    protected fun pathToward(goal: Cell): Pathfinder.Path? {
        val find = { to: Cell -> Pathfinder.find(map.width, map.height, guy.cell, to, ::passable) }
        if (passable(goal.x, goal.y)) return find(goal)
        return NEIGHBOURS.mapNotNull { (dx, dy) -> find(Cell(goal.x + dx, goal.y + dy)) }.minByOrNull { it.cost }
    }

    protected fun walkTo(goal: Cell, what: String): Work.Walk {
        val path = pathToward(goal) ?: reject("there is no way to reach $what")
        return Work.Walk(ArrayDeque(path.cells))
    }

    protected fun event(text: String) = addEvent(WorldEvent(ticks, text))

    /**
     * An event that says how the running intent ended: what it did when it finished (its ops'
     * results), a purchase, a failure. It's also kept on the intent, as [Observation.outcome].
     */
    protected fun outcome(text: String) {
        running?.handle?.let { return outcome(it, text) }
        event(text)
    }

    private fun outcome(handle: Handle, text: String) {
        handle.outcome = text
        addEvent(WorldEvent(ticks, text, outcome = true))
    }

    private fun addEvent(e: WorldEvent) {
        events.addLast(e)
        while (events.size > MAX_EVENTS) events.removeFirst()
    }

    protected fun eventLog(): List<WorldEvent> = events.toList()

    protected fun recentEvents(): List<String> = events.takeLast(OBSERVED_EVENTS).map { "${stamp(it.tick)}: ${it.text}" }

    /** [recentEvents] without the ones that are an intent's outcome: what happened around him. */
    protected fun news(): List<String> = events.takeLast(OBSERVED_EVENTS).filter { !it.outcome }.map { "${stamp(it.tick)}: ${it.text}" }

    protected fun agentSnapshot(): AgentSnapshot = AgentSnapshot(
        id = guy.id,
        intent = last?.intent,
        status = last?.status,
        inventory = guy.inventory.toList(),
        path = (running?.work?.let(::currentWalk))?.path?.toList() ?: emptyList(),
        saying = saying,
    )

    private fun currentWalk(work: Work): Work.Walk? = when (work) {
        is Work.Walk -> work
        is Work.Steps -> work.steps.firstOrNull()?.let(::currentWalk)
        else -> null
    }

    // --- internals ---------------------------------------------------------------------------

    protected class Character(val id: EntityId, var x: Float, var y: Float) {
        var facing = Direction.S
        val inventory = mutableListOf<String>()
        val cell: Cell get() = Cell(x.toInt(), y.toInt())
    }

    protected class Handle(override val intent: Intent) : IntentHandle {
        @Volatile override var status: IntentStatus = IntentStatus.Submitted

        /** How it ended, in the sim's words ([outcome]); null while it runs, or when nothing was said. */
        @Volatile var outcome: String? = null
    }

    protected class Running(val handle: Handle, val work: Work)

    protected class Published(val snapshot: WorldSnapshot, val observation: Observation)

    protected sealed interface Work {
        class Walk(val path: ArrayDeque<Cell>) : Work

        /** Runs for [ticksLeft] ticks, then [finish] applies the effect: null or a failure reason. */
        class Timed(var ticksLeft: Int, val finish: () -> String?) : Work

        /** [start] once on its first tick, then runs until [done], then [finish] as for [Timed]. */
        class Until(val start: () -> Unit, val done: () -> Boolean, val finish: () -> String?) : Work {
            var started = false
        }

        /** Runs each step in turn; the first failure ends it. */
        class Steps(val steps: ArrayDeque<Work>) : Work {
            constructor(vararg steps: Work) : this(ArrayDeque(steps.toList()))
        }
    }

    protected class Rejected(val reason: String) : Exception(reason, null, false, false)

    protected fun reject(reason: String): Nothing = throw Rejected(reason)

    companion object {
        /** Tiles per tick: 4 tiles a second at 20 Hz. */
        const val SPEED = 0.2f
        const val MAX_WAIT = 200
        const val MAX_SAY = 200
        const val MIN_SAY_TICKS = 20
        const val MAX_SAY_TICKS = 80
        /** Chebyshev tiles: picking up and using need the target on or beside your tile. */
        const val REACH = 1
        const val MAX_EVENTS = 50
        const val OBSERVED_EVENTS = 5

        private val NEIGHBOURS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1, 1 to 1, 1 to -1, -1 to 1, -1 to -1)

        fun chebyshev(a: Cell, b: Cell) = max(abs(a.x - b.x), abs(a.y - b.y))

        fun round(value: Float, places: Int): Float {
            val scale = if (places == 1) 10f else 100f
            return (value * scale).roundToInt() / scale
        }
    }
}
