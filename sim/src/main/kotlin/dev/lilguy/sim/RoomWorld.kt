package dev.lilguy.sim

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Milestone 1 world: one room, one character, a locked door, its key and a few distractors.
 *
 * Threading: call [step] from one thread only, the sim thread. Everything else is safe from any
 * thread. [observe] and [snapshot] return values published at the end of the last tick, and
 * [submit] queues the intent for the start of the next one.
 */
class RoomWorld private constructor(
    val map: TileMap,
    private val guy: Character,
    private val items: List<Item>,
    private val doors: List<Door>,
    private val exit: Cell?,
) : World {
    private var ticks = 0L
    private val inbox = ConcurrentLinkedQueue<Handle>()
    private val events = ArrayDeque<WorldEvent>()
    private var running: Running? = null
    private var last: Handle? = null
    private var saying: String? = null
    private var sayUntil = 0L
    private var completed = false

    // Decision points. `pending` counts submitted intents the sim hasn't drained yet; the flag
    // only goes up when the character is idle with nothing queued, and only after the tick's
    // observation is published, so an agent woken on another thread never reads a stale one.
    private val lock = Any()
    private var pending = 0
    private val idle = MutableStateFlow(true)
    private var finishedThisTick = false

    @Volatile private var published: Published = publish()

    override val tick: Long get() = published.snapshot.tick

    val agentId: EntityId get() = guy.id

    /** True when the character has nothing to do and nothing queued: the agent should decide. */
    fun awaitingDecision(agentId: EntityId): Boolean {
        requireAgent(agentId)
        return idle.value
    }

    override fun step() {
        ticks++
        drainInbox()
        running?.let { advance(it) }
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
        event("${describe(run.handle.intent)} was interrupted")
    }

    private fun finish(handle: Handle, status: IntentStatus) {
        handle.status = status
        if (running?.handle === handle) running = null
        if (status is IntentStatus.Failed) event("${describe(handle.intent)} failed: ${status.reason}")
        finishedThisTick = true
    }

    private fun advance(run: Running) {
        val handle = run.handle
        if (handle.status == IntentStatus.Validated) handle.status = IntentStatus.Executing
        when (val work = run.work) {
            is Work.Walk -> {
                walk(work.path)
                if (work.path.isEmpty()) finish(handle, IntentStatus.Succeeded)
            }
            is Work.Timed -> {
                work.ticksLeft--
                if (work.ticksLeft <= 0) {
                    val failure = work.finish()
                    finish(handle, if (failure == null) IntentStatus.Succeeded else IntentStatus.Failed(failure))
                }
            }
        }
    }

    /** Validates [intent] against the current state and returns the work that carries it out. */
    private fun plan(intent: Intent): Work = when (intent) {
        is Intent.MoveTo -> {
            val goal = when (val target = intent.target) {
                is Target.Tile -> {
                    val cell = Cell(target.x, target.y)
                    if (!map.inBounds(cell.x, cell.y)) reject("(${cell.x}, ${cell.y}) is outside the map")
                    if (!passable(cell.x, cell.y)) {
                        reject(if (doorAt(cell) != null) "the door at (${cell.x}, ${cell.y}) is closed" else "(${cell.x}, ${cell.y}) is a wall")
                    }
                    cell
                }
                is Target.Entity -> {
                    if (target.id == guy.id.value) reject("you can't move to yourself")
                    if (target.id in guy.inventory) reject("you are carrying ${target.id}")
                    cellOf(target.id) ?: reject("there is nothing called '${target.id}'")
                }
            }
            val path = pathToward(goal) ?: reject("there is no way to reach ${describe(intent.target)}")
            Work.Walk(ArrayDeque(path.cells))
        }

        is Intent.PickUp -> {
            val item = items.find { it.id == intent.itemId }
                ?: reject(if (cellOf(intent.itemId) != null) "${intent.itemId} can't be picked up" else "there is no item called '${intent.itemId}'")
            val cell = item.cell ?: reject("you are already holding ${item.id}")
            val distance = chebyshev(guy.cell, cell)
            if (distance > REACH) reject("${item.id} is $distance tiles away; move next to it first")
            Work.Timed(PICK_UP_TICKS) {
                val at = item.cell
                if (at == null || chebyshev(guy.cell, at) > REACH) {
                    "${item.id} is out of reach"
                } else {
                    item.cell = null
                    guy.inventory += item.id
                    event("${guy.id.value} picked up the ${item.kind}")
                    null
                }
            }
        }

        is Intent.Use -> {
            val item = items.find { it.id == intent.itemId && it.id in guy.inventory }
                ?: reject("you aren't holding '${intent.itemId}'")
            val on = intent.on
            if (on == null) {
                if (item.kind != "apple") reject("${item.id} does nothing on its own; use it on something")
                Work.Timed(USE_TICKS) {
                    guy.inventory -= item.id
                    event("${guy.id.value} ate the apple")
                    null
                }
            } else {
                val cell = cellOf(on) ?: reject("there is nothing called '$on'")
                val distance = chebyshev(guy.cell, cell)
                if (distance > REACH) reject("$on is $distance tiles away; move next to it first")
                val door = doors.find { it.id == on } ?: reject("using ${item.id} on $on does nothing")
                if (door.keyId != item.id) reject("${item.id} doesn't fit the ${door.id}")
                if (!door.locked) reject("the ${door.id} is already unlocked")
                Work.Timed(USE_TICKS) {
                    door.locked = false
                    event("${guy.id.value} unlocked the ${door.id} with the ${item.kind}")
                    null
                }
            }
        }

        is Intent.Say -> {
            val text = intent.text.trim()
            if (text.isEmpty()) reject("say needs some text")
            if (text.length > MAX_SAY) reject("say is limited to $MAX_SAY characters")
            val duration = (text.length * 2).coerceIn(MIN_SAY_TICKS, MAX_SAY_TICKS)
            saying = text
            sayUntil = ticks + duration
            event("${guy.id.value} says \"$text\"")
            Work.Timed(duration) { null }
        }

        is Intent.Wait -> {
            if (intent.ticks !in 1..MAX_WAIT) reject("wait ticks must be between 1 and $MAX_WAIT")
            Work.Timed(intent.ticks) { null }
        }
    }

    // --- movement ----------------------------------------------------------------------------

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
                if (next == exit && !completed) {
                    completed = true
                    event("${guy.id.value} walked out of the room")
                }
            } else {
                guy.x += dx / d * budget
                guy.y += dy / d * budget
                budget = 0f
            }
        }
    }

    /** Doors block movement until unlocked; items never do. */
    fun passable(x: Int, y: Int): Boolean = map.isFloor(x, y) && doors.none { it.locked && it.cell.x == x && it.cell.y == y }

    /** A path onto [goal], or, when [goal] blocks (a locked door), onto the nearest tile beside it. */
    private fun pathToward(goal: Cell): Pathfinder.Path? {
        val find = { to: Cell -> Pathfinder.find(map.width, map.height, guy.cell, to, ::passable) }
        if (passable(goal.x, goal.y)) return find(goal)
        return NEIGHBOURS.mapNotNull { (dx, dy) -> find(Cell(goal.x + dx, goal.y + dy)) }.minByOrNull { it.cost }
    }

    // --- lookup ------------------------------------------------------------------------------

    private fun cellOf(id: String): Cell? = when {
        id == guy.id.value -> guy.cell
        id == EXIT_ID -> exit
        else -> items.find { it.id == id }?.cell ?: doors.find { it.id == id }?.cell
    }

    private fun doorAt(cell: Cell) = doors.find { it.cell == cell }

    private fun describe(intent: Intent): String = when (intent) {
        is Intent.MoveTo -> "move_to ${describe(intent.target)}"
        is Intent.PickUp -> "pick_up ${intent.itemId}"
        is Intent.Use -> "use ${intent.itemId}" + (intent.on?.let { " on $it" } ?: "")
        is Intent.Say -> "say"
        is Intent.Wait -> "wait ${intent.ticks}"
    }

    private fun describe(target: Target): String = when (target) {
        is Target.Tile -> "(${target.x}, ${target.y})"
        is Target.Entity -> target.id
    }

    private fun event(text: String) {
        events.addLast(WorldEvent(ticks, text))
        while (events.size > MAX_EVENTS) events.removeFirst()
    }

    // --- publishing --------------------------------------------------------------------------

    private class Published(val snapshot: WorldSnapshot, val observation: Observation)

    private fun publish(): Published {
        val entities = buildList {
            add(EntitySnapshot(guy.id, "guy", guy.x, guy.y, 1f, guy.facing.degrees))
            doors.forEach { add(EntitySnapshot(EntityId(it.id), "door", it.cell.x + 0.5f, it.cell.y + 0.5f, 1f, 0f, it.state)) }
            exit?.let { add(EntitySnapshot(EntityId(EXIT_ID), EXIT_ID, it.x + 0.5f, it.y + 0.5f, 0f, 0f)) }
            items.forEach { item ->
                val cell = item.cell
                if (cell != null) {
                    add(EntitySnapshot(EntityId(item.id), item.kind, cell.x + 0.5f, cell.y + 0.5f, 0.3f, 0f))
                } else if (item.id in guy.inventory) {
                    add(EntitySnapshot(EntityId(item.id), item.kind, guy.x, guy.y, 0.3f, 0f, carriedBy = guy.id))
                }
            }
        }
        val run = running
        val agent = AgentSnapshot(
            id = guy.id,
            intent = last?.intent,
            status = last?.status,
            inventory = guy.inventory.toList(),
            path = (run?.work as? Work.Walk)?.path?.toList() ?: emptyList(),
            saying = saying,
        )
        val snapshot = WorldSnapshot(ticks, map, entities, listOf(agent), events.toList(), completed)
        return Published(snapshot, observe(entities))
    }

    private fun observe(entities: List<EntitySnapshot>): Observation {
        val nearby = entities
            .filter { it.id != guy.id && it.carriedBy == null }
            .map { e ->
                val dx = e.x - guy.x
                val dy = e.y - guy.y
                val cell = Cell(e.x.toInt(), e.y.toInt())
                SeenEntity(e.id.value, e.kind, cell.x, cell.y, Direction.of(dx, dy), round(hypot(dx, dy), 1), e.state, chebyshev(guy.cell, cell) <= REACH)
            }
            .filter { it.distance <= VIEW_RADIUS }
            .sortedBy { it.distance }
        return Observation(
            tick = ticks,
            self = SelfState(guy.id, round(guy.x, 2), round(guy.y, 2), guy.facing, guy.inventory.toList()),
            nearby = nearby,
            localMap = localMap(),
            lastIntent = last?.intent,
            lastResult = last?.status,
            recentEvents = events.takeLast(OBSERVED_EVENTS).map { "t${it.tick}: ${it.text}" },
        )
    }

    /** Rows of [LOCAL_RADIUS]*2+1 characters centered on the character; legend in [MAP_LEGEND]. */
    private fun localMap(): List<String> {
        val center = guy.cell
        return (center.y - LOCAL_RADIUS..center.y + LOCAL_RADIUS).map { y ->
            buildString {
                for (x in center.x - LOCAL_RADIUS..center.x + LOCAL_RADIUS) {
                    val cell = Cell(x, y)
                    val door = doorAt(cell)
                    append(
                        when {
                            cell == center -> '@'
                            door != null -> if (door.locked) 'D' else '/'
                            cell == exit -> 'E'
                            else -> items.find { it.cell == cell }?.kind?.first()
                                ?: when (map[x, y]) { Terrain.Floor -> '.'; Terrain.Wall -> '#'; Terrain.Void -> ' ' }
                        },
                    )
                }
            }
        }
    }

    // --- internals ---------------------------------------------------------------------------

    private class Character(val id: EntityId, var x: Float, var y: Float) {
        var facing = Direction.S
        val inventory = mutableListOf<String>()
        val cell: Cell get() = Cell(x.toInt(), y.toInt())
    }

    /** [cell] is null while carried. */
    private class Item(val id: String, val kind: String, var cell: Cell?)

    private class Door(val id: String, val cell: Cell, val keyId: String) {
        var locked = true
        val state: String get() = if (locked) "locked" else "open"
    }

    private class Handle(override val intent: Intent) : IntentHandle {
        @Volatile override var status: IntentStatus = IntentStatus.Submitted
    }

    private class Running(val handle: Handle, val work: Work)

    private sealed interface Work {
        class Walk(val path: ArrayDeque<Cell>) : Work

        /** Runs for [ticksLeft] ticks, then [finish] applies the effect: null or a failure reason. */
        class Timed(var ticksLeft: Int, val finish: () -> String?) : Work
    }

    private class Rejected(val reason: String) : Exception(reason, null, false, false)

    private fun reject(reason: String): Nothing = throw Rejected(reason)

    companion object {
        const val EXIT_ID = "exit"
        /** Tiles per tick: 4 tiles a second at 20 Hz. */
        const val SPEED = 0.2f
        const val PICK_UP_TICKS = 6
        const val USE_TICKS = 10
        const val MAX_WAIT = 200
        const val MAX_SAY = 200
        const val MIN_SAY_TICKS = 20
        const val MAX_SAY_TICKS = 80
        /** Chebyshev tiles: picking up and using need the target on or beside your tile. */
        const val REACH = 1
        /** Perception radius in tiles. Walls don't block sight yet. */
        const val VIEW_RADIUS = 12f
        const val LOCAL_RADIUS = 4
        const val MAX_EVENTS = 50
        const val OBSERVED_EVENTS = 5

        const val MAP_LEGEND = "@ you, # wall, . floor, D locked door, / open door, E exit, " +
            "k key, a apple, b book, blank = outside"

        private val NEIGHBOURS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1, 1 to 1, 1 to -1, -1 to 1, -1 to -1)

        fun create(spec: RoomSpec = Rooms.KeyAndDoor, characterId: String = "guy"): RoomWorld {
            val height = spec.rows.size
            val width = spec.rows[0].length
            var guy: Character? = null
            var exit: Cell? = null
            val items = mutableListOf<Item>()
            val doors = mutableListOf<Door>()
            val terrain = Array(width * height) { i ->
                val x = i % width
                val y = i / width
                val cell = Cell(x, y)
                when (val c = spec.rows[y][x]) {
                    '#' -> Terrain.Wall
                    ' ' -> Terrain.Void
                    '.' -> Terrain.Floor
                    '@' -> Terrain.Floor.also { guy = Character(EntityId(characterId), x + 0.5f, y + 0.5f) }
                    'K' -> Terrain.Floor.also { items += Item("key", "key", cell) }
                    'A' -> Terrain.Floor.also { items += Item("apple", "apple", cell) }
                    'B' -> Terrain.Floor.also { items += Item("book", "book", cell) }
                    'D' -> Terrain.Floor.also { doors += Door("door", cell, keyId = "key") }
                    'E' -> Terrain.Floor.also { exit = cell }
                    else -> error("unknown room tile '$c' at ($x, $y)")
                }
            }
            return RoomWorld(TileMap(width, height, terrain), guy ?: error("room has no '@'"), items, doors, exit)
        }

        private fun chebyshev(a: Cell, b: Cell) = max(abs(a.x - b.x), abs(a.y - b.y))

        private fun round(value: Float, places: Int): Float {
            val scale = if (places == 1) 10f else 100f
            return (value * scale).roundToInt() / scale
        }
    }
}
