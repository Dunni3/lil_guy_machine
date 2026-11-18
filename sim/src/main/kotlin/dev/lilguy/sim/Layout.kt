package dev.lilguy.sim

/** A named region of the map he can be in: a room (its walls included) or a part of the land. */
data class Place(val id: String, val name: String, val kind: PlaceKind, val rects: List<Rect>, val says: String = "") {
    /** The smallest rect holding all of it. */
    val bounds: Rect = rects.reduce(Rect::union)
}

/** A doorway: a floor tile in a wall, between the room [inside] and the place [outside] (another room, or the land). */
data class Door(val id: String, val cell: Cell, val inside: String, val outside: String?)

/**
 * The world stitched into one map: the lot's ground, with the bedroom's rows laid on it and its
 * front door cut through its wall. [places] are the rooms first, then the content's outdoor places
 * in order; every tile belongs to the first that holds it, or to none. Without a world in the
 * content, the bedroom is the whole map and its only place.
 */
class Layout private constructor(
    val map: TileMap,
    /** The map as letters: '#' wall, '.' floor, '+' a doorway, and the lot's [Ground] letters. */
    val rows: List<String>,
    val places: List<Place>,
    private val index: IntArray,
    /** Slot name to its tile on the map. */
    val slotCells: Map<String, Cell>,
    /** Where he starts: the room's '@'. */
    val start: Cell,
    val doors: List<Door>,
    /** The bedroom's top-left tile on the map: (0, 0) without a world. */
    val roomOrigin: Cell,
) {
    private val byId = places.associateBy { it.id }

    fun place(id: String): Place? = byId[id]

    fun placeOf(c: Cell): Place? = if (map.inBounds(c.x, c.y)) index[c.y * map.width + c.x].takeIf { it >= 0 }?.let(places::get) else null

    fun placeOf(x: Float, y: Float): Place? = placeOf(Cell(x.toInt(), y.toInt()))

    /** True when the content has land around the house. */
    val outdoors: Boolean get() = places.any { it.kind == PlaceKind.Outdoor }

    /**
     * Where he goes to be in [place]: its walkable tile nearest its middle (for a room, nearest the
     * middle of its floor). Null when it has none.
     */
    fun spot(place: Place): Cell? = spots[place.id]

    private val spots: Map<String, Cell?> by lazy {
        places.associate { place ->
            val (cx, cy) = place.bounds.centre
            var best: Cell? = null
            var bestD = Float.MAX_VALUE
            for (r in place.rects) for (y in r.y0..r.y1) for (x in r.x0..r.x1) {
                val c = Cell(x, y)
                if (!map.isFloor(x, y) || placeOf(c) != place || doors.any { it.cell == c }) continue
                val d = (x + 0.5f - cx) * (x + 0.5f - cx) + (y + 0.5f - cy) * (y + 0.5f - cy)
                if (d < bestD) {
                    bestD = d
                    best = c
                }
            }
            place.id to best
        }
    }

    /** Tiles walked from [from] to [to] round walls, water and trees (not furniture), or null when there's no way. */
    fun distance(from: Cell, to: Cell): Float? = Pathfinder.find(map.width, map.height, from, to, map::isFloor)?.cost

    /** The rows of [rect], clipped to the map. */
    fun rowsOf(rect: Rect): List<String> = (maxOf(rect.y0, 0)..minOf(rect.y1, map.height - 1)).map { y -> rows[y].substring(maxOf(rect.x0, 0), minOf(rect.x1, map.width - 1) + 1) }

    companion object {
        /** The map with the bedroom, and each room of [house] (room id to site id) built on its site. */
        fun of(content: BedroomContent, house: Map<String, String> = emptyMap()): Layout {
            val room = content.room
            val roomRows = room.rows
            require(roomRows.isNotEmpty() && roomRows.all { it.length == roomRows[0].length }) { "room rows must all be the same length" }
            val world = content.world
            val rw = roomRows[0].length
            val rh = roomRows.size
            val width = world?.width ?: rw
            val height = world?.height ?: rh
            val origin = world?.house?.at?.let { Cell(it[0], it[1]) } ?: Cell(0, 0)
            val terrain = Array(width * height) { Terrain.Void }
            val letters = Array(height) { y -> CharArray(width) { x -> world?.lot?.get(y)?.get(x) ?: ' ' } }
            if (world != null) for (y in 0 until height) for (x in 0 until width) {
                val g = Ground.of(letters[y][x]) ?: error("world.lot has '${letters[y][x]}' at ($x, $y)")
                terrain[y * width + x] = when {
                    g.walkable -> Terrain.Floor
                    g == Ground.Sea -> Terrain.Void
                    else -> Terrain.Wall
                }
            }
            var start: Cell? = null
            val slotCells = mutableMapOf<String, Cell>()
            for (ry in 0 until rh) for (rx in 0 until rw) {
                val x = origin.x + rx
                val y = origin.y + ry
                val (t, letter) = when (val c = roomRows[ry][rx]) {
                    '#' -> Terrain.Wall to '#'
                    ' ' -> Terrain.Void to ' '
                    '.' -> Terrain.Floor to '.'
                    '@' -> {
                        start = Cell(x, y)
                        Terrain.Floor to '.'
                    }
                    else -> {
                        val slot = room.slots[c.toString()] ?: error("unknown room tile '$c' at ($rx, $ry)")
                        require(slot !in slotCells) { "slot '$slot' appears twice in the room" }
                        slotCells[slot] = Cell(x, y)
                        if (slot in room.wallSlots) Terrain.Wall to '#' else Terrain.Floor to '.'
                    }
                }
                terrain[y * width + x] = t
                letters[y][x] = letter
            }
            world?.slots?.forEach { (name, at) -> slotCells[name] = Cell(at[0], at[1]) }
            // Rooms built onto the house: their rows over the lot, sharing the wall they meet at.
            val built = house.map { (roomId, siteId) ->
                val r = requireNotNull(world?.room(roomId)) { "no room '$roomId'" }
                val s = requireNotNull(world.site(siteId)) { "no site '$siteId'" }
                r.rows.forEachIndexed { ry, row ->
                    row.forEachIndexed { rx, c ->
                        val x = s.at[0] + rx
                        val y = s.at[1] + ry
                        val slot = r.slots[c.toString()]
                        if (slot != null) slotCells[slot] = Cell(x, y)
                        terrain[y * width + x] = if (c == '#') Terrain.Wall else Terrain.Floor
                        letters[y][x] = if (c == '#') '#' else '.'
                    }
                }
                r to s
            }
            val missing = room.slots.values.toSet() - slotCells.keys
            require(missing.isEmpty()) { "slots $missing aren't in the room" }

            val roomId = world?.house?.room ?: "bedroom"
            val roomRect = Rect(origin.x, origin.y, origin.x + rw - 1, origin.y + rh - 1)
            val places = mutableListOf(Place(roomId, world?.house?.name ?: "your bedroom", PlaceKind.Room, listOf(roomRect), "where you live"))
            built.forEach { (r, s) -> places += Place(r.id, r.name, PlaceKind.Room, listOf(Rect(s.at[0], s.at[1], s.at[0] + r.rows[0].length - 1, s.at[1] + r.rows.size - 1)), r.says) }
            world?.places?.forEach { p -> places += Place(p.id, p.name, PlaceKind.Outdoor, p.rects.map { Rect(it[0], it[1], it[2], it[3]) }, p.says) }
            val index = IntArray(width * height) { -1 }
            for (y in 0 until height) for (x in 0 until width) {
                if (terrain[y * width + x] == Terrain.Void && letters[y][x] == ' ') continue
                index[y * width + x] = places.indexOfFirst { p -> p.rects.any { Cell(x, y) in it } }
            }

            val doors = mutableListOf<Door>()
            world?.house?.door?.let { (dx, dy) ->
                val cell = Cell(origin.x + dx, origin.y + dy)
                terrain[cell.y * width + cell.x] = Terrain.Floor
                letters[cell.y][cell.x] = '+'
                // The tile the door opens onto, one step out of the room.
                val out = when {
                    dy == rh - 1 -> Cell(cell.x, cell.y + 1)
                    dy == 0 -> Cell(cell.x, cell.y - 1)
                    dx == 0 -> Cell(cell.x - 1, cell.y)
                    else -> Cell(cell.x + 1, cell.y)
                }
                val outside = places.drop(1).firstOrNull { p -> p.rects.any { out in it } }?.id
                doors += Door("front_door", cell, roomId, outside)
            }
            built.forEach { (r, s) ->
                val cell = Cell(s.door[0], s.door[1])
                terrain[cell.y * width + cell.x] = Terrain.Floor
                letters[cell.y][cell.x] = '+'
                val joins = s.nextTo?.let { n -> built.firstOrNull { it.second.id == n }?.first?.id } ?: roomId
                doors += Door("door_${r.id}", cell, r.id, joins)
            }
            // Things stand in slots and most block, so a slot beside a doorway could shut a room off
            // once he owns what stands there (a fern pot did, the lab and the sunroom behind it).
            for (d in doors) for ((dx, dy) in listOf(0 to 1, 0 to -1, 1 to 0, -1 to 0)) {
                val beside = Cell(d.cell.x + dx, d.cell.y + dy)
                slotCells.entries.firstOrNull { it.value == beside }?.let { error("slot '${it.key}' is beside the doorway ${d.id} at (${d.cell.x}, ${d.cell.y})") }
            }
            return Layout(
                TileMap(width, height, terrain), letters.map { String(it) }, places, index, slotCells,
                start ?: error("room has no '@'"), doors, origin,
            )
        }
    }
}
