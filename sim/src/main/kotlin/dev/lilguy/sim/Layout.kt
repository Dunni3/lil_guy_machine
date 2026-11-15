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

    /** The rows of [rect], clipped to the map. */
    fun rowsOf(rect: Rect): List<String> = (maxOf(rect.y0, 0)..minOf(rect.y1, map.height - 1)).map { y -> rows[y].substring(maxOf(rect.x0, 0), minOf(rect.x1, map.width - 1) + 1) }

    companion object {
        fun of(content: BedroomContent): Layout {
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
            val missing = room.slots.values.toSet() - slotCells.keys
            require(missing.isEmpty()) { "slots $missing aren't in the room" }

            val roomId = world?.house?.room ?: "bedroom"
            val roomRect = Rect(origin.x, origin.y, origin.x + rw - 1, origin.y + rh - 1)
            val places = mutableListOf(Place(roomId, world?.house?.name ?: "your bedroom", PlaceKind.Room, listOf(roomRect), "where you live"))
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
            return Layout(
                TileMap(width, height, terrain), letters.map { String(it) }, places, index, slotCells,
                start ?: error("room has no '@'"), doors, origin,
            )
        }
    }
}
