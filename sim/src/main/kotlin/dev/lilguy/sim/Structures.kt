package dev.lilguy.sim

import kotlinx.serialization.Serializable

/**
 * Something big he built on the land (`docs/decisions/2026-11-18-he-builds-big-things-*`): a thing he
 * invented with a footprint of [w] by [d] tiles, its top-left tile at [at]. With a [door] (the side it's
 * in: north, south, east or west) it's a building he can walk into: its edge tiles are walls but the
 * door, its inside is a place of its own ([PlaceKind.Building]) with free spots for his things, and
 * [windows] (sides) let daylight in. Without one it's solid: every tile of it blocks, as a tower or a
 * big machine does. The item is [id]; the place, when there is one, has the same id and is called [name].
 *
 * Its slots: [id] is where the thing itself stands, which verbs done at it walk to (just inside the
 * door, or for a solid one just outside its south side), and [spots] are the free spots inside.
 */
@Serializable
data class Structure(
    val id: String,
    val name: String,
    val at: List<Int>,
    val w: Int,
    val d: Int,
    val door: String? = null,
    val windows: List<String> = emptyList(),
    val says: String = "",
) {
    val enterable: Boolean get() = door != null

    val rect: Rect get() = Rect(at[0], at[1], at[0] + w - 1, at[1] + d - 1)

    /** The middle tile of [side]'s edge. */
    fun edge(side: String): Cell {
        val r = rect
        return when (side) {
            NORTH -> Cell(r.x0 + w / 2, r.y0)
            SOUTH -> Cell(r.x0 + w / 2, r.y1)
            WEST -> Cell(r.x0, r.y0 + d / 2)
            else -> Cell(r.x1, r.y0 + d / 2)
        }
    }

    /** One step out of [side]'s middle tile, and one step in. */
    fun outside(side: String): Cell = step(edge(side), side, 1)

    fun inside(side: String): Cell = step(edge(side), side, -1)

    private fun step(c: Cell, side: String, n: Int) = when (side) {
        NORTH -> Cell(c.x, c.y - n)
        SOUTH -> Cell(c.x, c.y + n)
        WEST -> Cell(c.x - n, c.y)
        else -> Cell(c.x + n, c.y)
    }

    /** The doorway's tile, or null for a solid one. */
    val doorCell: Cell? get() = door?.let(::edge)

    /** The windows' tiles, in the walls. */
    val windowCells: List<Cell> get() = if (enterable) windows.filter { it != door }.map(::edge) else emptyList()

    /** Where the thing itself stands: just inside its door, or just outside its south side. */
    val anchor: Cell get() = door?.let(::inside) ?: outside(SOUTH)

    /** Its free spots inside, by name: every other tile each way, none on the anchor or beside the doorway, so no thing set down can shut the rest off. */
    val spots: List<Pair<String, Cell>>
        get() {
            if (!enterable) return emptyList()
            val r = rect
            val door = doorCell!!
            val out = mutableListOf<Pair<String, Cell>>()
            for (y in r.y0 + 1 until r.y1) for (x in r.x0 + 1 until r.x1) {
                if ((x - r.x0 - 1) % 2 != 0 || (y - r.y0 - 1) % 2 != 0) continue
                val c = Cell(x, y)
                if (c == anchor || kotlin.math.abs(c.x - door.x) + kotlin.math.abs(c.y - door.y) <= 1) continue
                out += "${id}_spot_${out.size + 1}" to c
            }
            return out
        }

    /** Every slot name it brings: itself and its spots. */
    val slotNames: List<String> get() = listOf(id) + spots.map { it.first }

    /** Its middle, in tiles: where it's drawn from. */
    val centre: Pair<Float, Float> get() = at[0] + w / 2f to at[1] + d / 2f

    /** What's wrong with it as content, or null. */
    internal fun problem(): String? = when {
        at.size != 2 -> "structure $id: at is [x, y]"
        w < 1 || d < 1 -> "structure $id: w and d at least 1"
        door != null && door !in SIDES -> "structure $id: door is one of ${SIDES.joinToString()}"
        door != null && (w < MIN_INSIDE || d < MIN_INSIDE) -> "structure $id: one you can go into is at least $MIN_INSIDE by $MIN_INSIDE"
        windows.any { it !in SIDES } -> "structure $id: windows are on ${SIDES.joinToString()}"
        else -> null
    }

    companion object {
        const val NORTH = "north"
        const val SOUTH = "south"
        const val EAST = "east"
        const val WEST = "west"
        val SIDES = listOf(NORTH, SOUTH, EAST, WEST)

        /** A building is at least this many tiles each way, walls included, so it has an inside. */
        const val MIN_INSIDE = 3
    }
}
