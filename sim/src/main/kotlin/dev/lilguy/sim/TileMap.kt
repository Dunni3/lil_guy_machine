package dev.lilguy.sim

import kotlinx.serialization.Serializable

/** Grid coordinates. x grows east, y grows south: row 0 is the north edge. */
@Serializable
data class Cell(val x: Int, val y: Int)

enum class Terrain { Floor, Wall, Void }

/** Static terrain, shared by every snapshot. Doors and items are entities on top of it. */
class TileMap(val width: Int, val height: Int, private val terrain: Array<Terrain>) {
    init {
        require(terrain.size == width * height) { "terrain has ${terrain.size} tiles, expected ${width * height}" }
    }

    fun inBounds(x: Int, y: Int) = x in 0 until width && y in 0 until height

    operator fun get(x: Int, y: Int): Terrain = if (inBounds(x, y)) terrain[y * width + x] else Terrain.Void

    fun isFloor(x: Int, y: Int) = this[x, y] == Terrain.Floor
}

/**
 * A room drawn as text, one string per row. Terrain: `#` wall, `.` floor, space void (outside,
 * not walkable). Entities stand on floor: `@` the character, `K` key, `D` locked door that the
 * key opens, `E` exit, `A` apple, `B` book.
 */
class RoomSpec(val rows: List<String>) {
    init {
        require(rows.isNotEmpty() && rows.all { it.length == rows[0].length }) { "room rows must all be the same length" }
    }
}

object Rooms {
    /** The MVP scenario: a 16x16 room (plus the tile outside its door) with a key behind a wall. */
    val KeyAndDoor = RoomSpec(
        listOf(
            "################ ",
            "#..............# ",
            "#..@...........# ",
            "#..............# ",
            "#.......#......# ",
            "#.......#..A...# ",
            "#.......#......# ",
            "#.......#......DE",
            "#.......#......# ",
            "#.......#####..# ",
            "#..............# ",
            "#..B...........# ",
            "#..........#...# ",
            "#.....K....#...# ",
            "#..........#...# ",
            "################ ",
        ),
    )
}
