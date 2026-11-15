package dev.lilguy.sim

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The land around the house (`world.yaml`): the lot as rows of ground letters, where the bedroom
 * stands on it and its front door, and the named places outdoors. Null in content without a
 * world: then the bedroom is the whole map, as before there was an outside.
 */
@Serializable
data class WorldContent(
    /** One string per row, north at the top; letters are [Ground]. */
    val lot: List<String>,
    val house: HouseSpec,
    /** Outdoor places, the first whose [PlaceSpec.rects] hold a tile winning; rooms come before all of them. */
    val places: List<PlaceSpec> = emptyList(),
) {
    val width: Int get() = lot.firstOrNull()?.length ?: 0
    val height: Int get() = lot.size

    fun problems(room: RoomContent): List<String> = buildList {
        if (lot.isEmpty() || lot.any { it.length != lot[0].length }) add("world.lot rows must all be the same length")
        lot.forEachIndexed { y, row -> row.forEachIndexed { x, c -> if (Ground.of(c) == null) add("world.lot has '$c' at ($x, $y), which isn't a ground letter (${Ground.entries.joinToString(" ") { "'${it.letter}'" }})") } }
        val w = room.rows.firstOrNull()?.length ?: 0
        val h = room.rows.size
        val (ax, ay) = house.at
        if (house.at.size != 2 || ax < 0 || ay < 0 || ax + w > width || ay + h > height) add("world.house.at ${house.at} doesn't fit the ${w}x$h bedroom on the ${width}x$height lot")
        val (dx, dy) = house.door
        val onEdge = house.door.size == 2 && (dx == 0 || dy == 0 || dx == w - 1 || dy == h - 1) && dx in 0 until w && dy in 0 until h
        if (!onEdge) add("world.house.door ${house.door} must be a tile on the bedroom's outer wall")
        else if (room.rows[dy][dx] != '#') add("world.house.door ${house.door} must be a plain wall tile of the bedroom, not '${room.rows[dy][dx]}'")
        val ids = places.map { it.id }
        if (ids.size != ids.toSet().size) add("world place ids must be unique")
        if (house.room in ids) add("world place '${house.room}' has the bedroom's id")
        places.forEach { p ->
            if (p.rects.isEmpty()) add("world place ${p.id} needs at least one rect")
            p.rects.forEach { r ->
                if (r.size != 4 || r[0] > r[2] || r[1] > r[3] || r[0] < 0 || r[1] < 0 || r[2] >= width || r[3] >= height) add("world place ${p.id}: rect $r must be [x0, y0, x1, y1] inside the lot")
            }
        }
    }
}

@Serializable
data class HouseSpec(
    /** The bedroom's place id. */
    val room: String = "bedroom",
    val name: String = "your bedroom",
    /** The bedroom's top-left tile (its corner wall) on the lot. */
    val at: List<Int>,
    /** The front door: a tile of the bedroom's outer wall, from its top-left, that opens onto the lot. */
    val door: List<Int>,
)

@Serializable
data class PlaceSpec(
    val id: String,
    val name: String,
    /** One line about it, for his map. */
    val says: String = "",
    /** [x0, y0, x1, y1], tiles inclusive. */
    val rects: List<List<Int>>,
)

/** What the lot's ground is, by its letter in `world.yaml` and in the map the renderer gets. */
enum class Ground(val letter: Char, val walkable: Boolean) {
    Sea(' ', false),
    Grass(',', true),
    Path('=', true),
    Bare(':', true),
    Flowers('*', true),
    Water('~', false),
    Tree('T', false),
    ;

    companion object {
        private val byLetter = entries.associateBy { it.letter }

        fun of(c: Char): Ground? = byLetter[c]
    }
}

/** Tiles (x0, y0) to (x1, y1), inclusive. */
@Serializable
data class Rect(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
    operator fun contains(c: Cell): Boolean = c.x in x0..x1 && c.y in y0..y1

    fun union(o: Rect) = Rect(minOf(x0, o.x0), minOf(y0, o.y0), maxOf(x1, o.x1), maxOf(y1, o.y1))

    val centre: Pair<Float, Float> get() = (x0 + x1 + 1) / 2f to (y0 + y1 + 1) / 2f
}

@Serializable
enum class PlaceKind {
    @SerialName("room") Room,
    @SerialName("outdoor") Outdoor,
}
