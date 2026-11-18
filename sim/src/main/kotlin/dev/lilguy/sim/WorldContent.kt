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
    /** Slot name to its tile [x, y] on the lot: where outdoor things stand, as the room's slots. */
    val slots: Map<String, List<Int>> = emptyMap(),
    /** What grows on the land: a patch (an item on a slot) and what collecting there yields. */
    val finds: List<FindSpec> = emptyList(),
    /** Animals drawn to the things whose `lure` draws them, when the conditions there allow ([VisitorSpec]). */
    val visitors: List<VisitorSpec> = emptyList(),
    /** Rooms he can build onto the house. */
    val rooms: List<RoomModule> = emptyList(),
    /** Where a room can stand. */
    val sites: List<SiteSpec> = emptyList(),
    /** How long putting a thing somewhere else takes, besides the walks ([Intent.Put]). */
    val carry: CarrySpec = CarrySpec(),
) {
    fun room(id: String): RoomModule? = rooms.firstOrNull { it.id == id }

    fun roomFor(plan: String): RoomModule? = rooms.firstOrNull { it.plan == plan }

    fun site(id: String): SiteSpec? = sites.firstOrNull { it.id == id }

    fun find(patch: String): FindSpec? = finds.firstOrNull { it.patch == patch }

    fun visitor(id: String): VisitorSpec? = visitors.firstOrNull { it.id == id }

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
        slots.forEach { (name, at) ->
            if (name in room.slots.values) add("world slot '$name' is also a room slot")
            val ok = at.size == 2 && at[1] in lot.indices && at[0] in 0 until width && Ground.of(lot[at[1]][at[0]])?.walkable == true
            if (!ok) add("world slot '$name' at $at must be a walkable tile of the lot")
        }
        finds.forEach { f -> if (f.regrowHours <= 0) add("find ${f.patch}: regrow_hours must be above 0") }
        rooms.forEach { r ->
            if (r.rows.isEmpty() || r.rows.any { it.length != r.rows[0].length }) add("room ${r.id}: rows must all be the same length")
            r.rows.forEach { row -> row.forEach { c -> if (c != '#' && c != '.' && c.toString() !in r.slots) add("room ${r.id}: '$c' isn't '#', '.' or one of its slots") } }
            if (r.id == house.room || places.any { it.id == r.id }) add("room ${r.id} has the id of another place")
            sites.forEach { s ->
                val w = r.rows.firstOrNull()?.length ?: 0
                if (s.at.size != 2 || s.at[0] < 0 || s.at[1] < 0 || s.at[0] + w > width || s.at[1] + r.rows.size > height) add("room ${r.id} doesn't fit on ${s.id}")
            }
        }
        sites.forEach { s ->
            if (s.door.size != 2) add("site ${s.id}: door must be [x, y]")
            if (s.nextTo != null && site(s.nextTo) == null) add("site ${s.id} is next to '${s.nextTo}', which isn't a site")
        }
        if (finds.map { it.patch }.toSet().size != finds.size) add("each patch may have one find")
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

/** A room he can build: its [plan] (the item bought), its rows (walls included) and slots, what comes with it, and what it's like to him. */
@Serializable
data class RoomModule(
    val id: String,
    val plan: String,
    val name: String,
    val says: String = "",
    val qualities: Map<String, Double> = emptyMap(),
    val rows: List<String>,
    /** Map letter to slot name. */
    val slots: Map<String, String> = emptyMap(),
    /** Items that come with the room, standing in its slots. */
    val items: List<String> = emptyList(),
    /** Windows: wall tiles [x, y] from its top-left, where daylight comes in. */
    val windows: List<List<Int>> = emptyList(),
)

/** Where a room can stand: its top-left tile on the lot, the doorway into the room it joins, and the site whose room that is (null: the bedroom). */
@Serializable
data class SiteSpec(
    val id: String,
    val name: String,
    val at: List<Int>,
    val door: List<Int>,
    @SerialName("next_to") val nextTo: String? = null,
)

/** Game minutes to lift a thing where it stands and to set it down where it goes. */
@Serializable
data class CarrySpec(val lift: Int = 2, @SerialName("set_down") val setDown: Int = 3)

/** A patch ([patch], an item on a lot slot) that [yields] an item when he collects there, then is bare for [regrowHours]. */
@Serializable
data class FindSpec(val patch: String, val yields: String, @SerialName("regrow_hours") val regrowHours: Int)

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
