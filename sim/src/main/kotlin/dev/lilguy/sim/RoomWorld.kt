package dev.lilguy.sim

import kotlin.math.hypot

/**
 * Milestone 1 world: one room, one character, a locked door, its key and a few distractors.
 * The intent lifecycle, walking and publishing live in [GridWorld].
 */
class RoomWorld private constructor(
    map: TileMap,
    guy: Character,
    private val items: List<Item>,
    private val doors: List<Door>,
    private val exit: Cell?,
) : GridWorld(map, guy) {
    private var completed = false

    init {
        publishFirst()
    }

    /** Validates [intent] against the current state and returns the work that carries it out. */
    override fun plan(intent: Intent): Work = when (intent) {
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
            walkTo(goal, describe(intent.target))
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
                    outcome("${guy.id.value} picked up the ${item.kind}")
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
                    outcome("${guy.id.value} ate the apple")
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
                    outcome("${guy.id.value} unlocked the ${door.id} with the ${item.kind}")
                    null
                }
            }
        }

        is Intent.Say -> say(intent)
        is Intent.Wait -> waitFor(intent)
        is Intent.Do -> reject("there are no activities in this room")
        is Intent.Buy -> reject("there is no shop in this room")
        Intent.Think -> Work.Timed(World.TICKS_PER_SECOND) { null }
    }

    // --- movement ----------------------------------------------------------------------------

    override fun entered(cell: Cell) {
        if (cell == exit && !completed) {
            completed = true
            event("${guy.id.value} walked out of the room")
        }
    }

    /** Doors block movement until unlocked; items never do. */
    override fun passable(x: Int, y: Int): Boolean = map.isFloor(x, y) && doors.none { it.locked && it.cell.x == x && it.cell.y == y }

    // --- lookup ------------------------------------------------------------------------------

    private fun cellOf(id: String): Cell? = when {
        id == guy.id.value -> guy.cell
        id == EXIT_ID -> exit
        else -> items.find { it.id == id }?.cell ?: doors.find { it.id == id }?.cell
    }

    private fun doorAt(cell: Cell) = doors.find { it.cell == cell }

    // --- publishing --------------------------------------------------------------------------

    override fun publish(): Published {
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
        val snapshot = WorldSnapshot(ticks, map, entities, listOf(agentSnapshot()), eventLog(), completed)
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
            recentEvents = recentEvents(),
            outcome = last?.outcome,
            news = news(),
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

    /** [cell] is null while carried. */
    private class Item(val id: String, val kind: String, var cell: Cell?)

    private class Door(val id: String, val cell: Cell, val keyId: String) {
        var locked = true
        val state: String get() = if (locked) "locked" else "open"
    }

    companion object {
        const val EXIT_ID = "exit"
        const val PICK_UP_TICKS = 6
        const val USE_TICKS = 10
        /** Perception radius in tiles. Walls don't block sight yet. */
        const val VIEW_RADIUS = 12f
        const val LOCAL_RADIUS = 4

        const val MAP_LEGEND = "@ you, # wall, . floor, D locked door, / open door, E exit, " +
            "k key, a apple, b book, blank = outside"

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
    }
}
