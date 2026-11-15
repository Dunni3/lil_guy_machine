package dev.lilguy.sim

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Effects with reach: something gives off an effect (light first; warmth, sound, sun and water and
 * attraction later) that the sim spreads over the map's tiles, and perception, verb conditions and
 * needs read it where they happen. One [Field] per kind, each recomputed only when what goes into it
 * changes ([Effects.field]).
 *
 * How an effect spreads is the same for every kind: a [baseline] on every tile (daylight, for light),
 * plus each [Emitter], strongest on its own tile and fading (with the square of the distance) to nothing a
 * tile past its radius, where
 * it can reach in a straight line. What blocks a line is the kind's [EffectKind.blocks]: for light,
 * walls and trees. An emitter in a room reaches only that room (a wall lamp doesn't light the lawn
 * behind its wall); one outdoors reaches only the land.
 */
enum class EffectKind(val id: String) {
    Light("light"),
    ;

    /** Whether a tile drawn as [letter] on the [Layout]'s rows stops this effect. */
    fun blocks(letter: Char): Boolean = when (this) {
        Light -> letter == '#' || letter == 'T'
    }

    companion object {
        fun of(id: String): EffectKind? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Something giving off [kind] at [cell]: [strength] on its own tile, fading to nothing a tile past
 * [radius]. [name] is how the prompt says what's doing it ("the floor lamp", "the window").
 */
data class Emitter(val id: String, val name: String, val kind: EffectKind, val cell: Cell, val strength: Double, val radius: Double)

/** One effect's level on each tile, and which emitter gives the most of it there (-1 for the baseline). */
class Field(val kind: EffectKind, val width: Int, val height: Int, private val levels: DoubleArray, private val strongest: IntArray, val emitters: List<Emitter>) {
    operator fun get(c: Cell): Double = get(c.x, c.y)

    operator fun get(x: Int, y: Int): Double = if (x in 0 until width && y in 0 until height) levels[y * width + x] else 0.0

    /** What gives the most of it at [c], or null where it's the baseline's (or nothing's). */
    fun source(c: Cell): Emitter? = if (c.x in 0 until width && c.y in 0 until height) strongest[c.y * width + c.x].takeIf { it >= 0 }?.let(emitters::get) else null
}

/**
 * The effect fields of one map, kept until their inputs change. [field] takes everything a kind's
 * field depends on; asked again with equal inputs, it returns the same field without recomputing.
 */
class Effects {
    private val kept = mutableMapOf<EffectKind, Pair<Any, Field>>()

    /** How many times a field has been worked out: for tests and for watching the cost. */
    var computed = 0
        private set

    /**
     * [kind] over [layout]: [baseline] on each tile (by its place), plus [emitters]. [key] is what
     * else the baseline depends on (the hour, for daylight); the field is recomputed only when the
     * layout, the emitters or [key] differ from last time.
     */
    fun field(kind: EffectKind, layout: Layout, emitters: List<Emitter>, key: Any, baseline: (Cell, Place?) -> Double): Field {
        val inputs = Triple(System.identityHashCode(layout), emitters, key)
        kept[kind]?.let { (k, f) -> if (k == inputs) return f }
        return spread(kind, layout, emitters, baseline).also {
            computed++
            kept[kind] = inputs to it
        }
    }

    companion object {
        /** Works out one field: see [Effects]. */
        fun spread(kind: EffectKind, layout: Layout, emitters: List<Emitter>, baseline: (Cell, Place?) -> Double): Field {
            val w = layout.map.width
            val h = layout.map.height
            val levels = DoubleArray(w * h)
            val strongest = IntArray(w * h) { -1 }
            val best = DoubleArray(w * h)
            val base = DoubleArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                val c = Cell(x, y)
                base[y * w + x] = baseline(c, layout.placeOf(c))
                levels[y * w + x] = base[y * w + x]
            }
            emitters.forEachIndexed { i, e ->
                if (e.strength <= 0 || e.radius < 0) return@forEachIndexed
                val home = layout.placeOf(e.cell)
                val r = e.radius.toInt() + 1
                for (y in e.cell.y - r..e.cell.y + r) for (x in e.cell.x - r..e.cell.x + r) {
                    if (x !in 0 until w || y !in 0 until h) continue
                    val c = Cell(x, y)
                    val there = layout.placeOf(c)
                    if (!sameSide(home, there)) continue
                    val d = hypot((x - e.cell.x).toDouble(), (y - e.cell.y).toDouble()) / (e.radius + 1)
                    val v = e.strength * (1 - d * d)
                    if (v <= 0 || !clear(kind, layout, e.cell, c)) continue
                    val at = y * w + x
                    levels[at] += v
                    if (v > best[at]) {
                        best[at] = v
                        strongest[at] = i
                    }
                }
            }
            // Where the baseline gives more than any one emitter, the baseline is what lights it.
            for (i in levels.indices) if (best[i] < base[i]) strongest[i] = -1
            return Field(kind, w, h, levels, strongest, emitters)
        }

        /** An emitter reaches a tile in its own room, or, outdoors, any tile outdoors. */
        private fun sameSide(home: Place?, there: Place?): Boolean =
            if (home?.kind == PlaceKind.Room) there == home else there?.kind != PlaceKind.Room

        /** True when nothing between [a] and [b] (neither end counted) blocks [kind]: a straight line, tile by tile. */
        fun clear(kind: EffectKind, layout: Layout, a: Cell, b: Cell): Boolean {
            var x = a.x
            var y = a.y
            val dx = abs(b.x - a.x)
            val dy = -abs(b.y - a.y)
            val sx = if (a.x < b.x) 1 else -1
            val sy = if (a.y < b.y) 1 else -1
            var err = dx + dy
            while (true) {
                if (x == b.x && y == b.y) return true
                if ((x != a.x || y != a.y) && kind.blocks(layout.rows[y][x])) return false
                val e2 = 2 * err
                if (e2 >= dy) {
                    err += dy
                    x += sx
                }
                if (e2 <= dx) {
                    err += dx
                    y += sy
                }
            }
        }

        /** Tiles between two cells, as the crow flies. */
        fun distance(a: Cell, b: Cell): Double = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())
    }
}
