package dev.lilguy.sim

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Effects with reach: something gives off an effect (light, warmth, sound, water, and the lures that
 * draw visitors) that the sim spreads over the map's tiles, and perception, verb conditions and
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

    /** Degrees: the weather's (or the house's) temperature on every tile, plus what heaters and fires add. */
    Warmth("warmth"),

    /** How loud it is, 0 to 10: a record playing, a wind chime. Walls muffle it rather than stop it, and it carries out of a room. */
    Sound("sound"),

    /** How wet it is, 0 to 10: the rain outdoors (millimetres in the hour gone), plus a sprinkler's spray. */
    Water("water"),

    /** What draws a kind of visitor (birds, moths, fireflies) to a spot: a feeder, a lamp, the pond ([VisitorSpec]). */
    Lure("lure"),
    ;

    /** The most it can be on a tile, however many emitters add to it: light is never brighter than noon. */
    val most: Double get() = when (this) {
        Warmth -> 40.0
        else -> 10.0
    }

    /** Whether a tile drawn as [letter] on the [Layout]'s rows stops this effect. */
    fun blocks(letter: Char): Boolean = passes(letter) == 0.0

    /**
     * How much of this effect gets through a tile drawn as [letter], 0 (stops it) to 1: walls and
     * trees stop light, walls stop warmth, water and lures, and sound gets through a wall muffled.
     */
    fun passes(letter: Char): Double = when (this) {
        Light -> if (letter == '#' || letter == 'T') 0.0 else 1.0
        Sound -> if (letter == '#') SOUND_THROUGH_WALL else 1.0
        else -> if (letter == '#') 0.0 else 1.0
    }

    /** False when an emitter's effect stays on its side of the walls (a room's lamp lights only that room); sound carries. */
    val keepsToItsSide: Boolean get() = this != Sound

    companion object {
        fun of(id: String): EffectKind? = entries.firstOrNull { it.id == id }

        /** The share of a sound that gets through one tile of wall. */
        const val SOUND_THROUGH_WALL = 0.35
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
class Effects(private val keep: Int = KEPT) {
    /** The last few fields by their inputs: a kind can be asked for in more than one way (light with his lantern, and without). */
    private val kept = object : LinkedHashMap<Any, Field>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Any, Field>?) = size > keep
    }

    /** How many times a field has been worked out: for tests and for watching the cost. */
    var computed = 0
        private set

    /**
     * [kind] over [layout]: [baseline] on each tile (by its place), plus [emitters]. [key] is what
     * else the baseline depends on (the hour, for daylight); the field is recomputed only when the
     * layout, the emitters or [key] differ from last time.
     */
    fun field(kind: EffectKind, layout: Layout, emitters: List<Emitter>, key: Any, baseline: (Cell, Place?) -> Double): Field {
        val inputs = listOf(kind, System.identityHashCode(layout), emitters, key)
        kept[inputs]?.let { return it }
        return spread(kind, layout, emitters, baseline).also {
            computed++
            kept[inputs] = it
        }
    }

    companion object {
        private const val KEPT = 4

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
                    val v = reach(e, layout, Cell(x, y), home)
                    if (v <= 0) continue
                    val at = y * w + x
                    levels[at] += v
                    if (v > best[at]) {
                        best[at] = v
                        strongest[at] = i
                    }
                }
            }
            // Where the baseline gives more than any one emitter, the baseline is what lights it.
            for (i in levels.indices) {
                if (best[i] < base[i]) strongest[i] = -1
                levels[i] = levels[i].coerceAtMost(kind.most)
            }
            return Field(kind, w, h, levels, strongest, emitters)
        }

        /**
         * How much of [e] reaches [c]: its strength, falling with the square of the distance to nothing a
         * tile past its radius, times what gets through on the straight line there; 0 on the other side
         * of a wall for a kind that keeps to its side. [home]: the place [e] stands in, when known.
         */
        fun reach(e: Emitter, layout: Layout, c: Cell, home: Place? = layout.placeOf(e.cell)): Double {
            if (e.strength <= 0 || e.radius < 0) return 0.0
            if (e.kind.keepsToItsSide && !sameSide(home, layout.placeOf(c))) return 0.0
            val d = hypot((c.x - e.cell.x).toDouble(), (c.y - e.cell.y).toDouble()) / (e.radius + 1)
            val v = e.strength * (1 - d * d)
            if (v <= 0) return 0.0
            return v * through(e.kind, layout, e.cell, c)
        }

        /** An emitter reaches a tile in its own room, or, outdoors, any tile outdoors. */
        private fun sameSide(home: Place?, there: Place?): Boolean =
            if (home?.kind?.indoors == true) there == home else there?.kind?.indoors != true

        /** True when nothing between [a] and [b] (neither end counted) blocks [kind]: a straight line, tile by tile. */
        fun clear(kind: EffectKind, layout: Layout, a: Cell, b: Cell): Boolean = through(kind, layout, a, b) > 0

        /** The share of [kind] that gets from [a] to [b] along a straight line: each tile between (neither end counted) lets [EffectKind.passes] of it through. */
        fun through(kind: EffectKind, layout: Layout, a: Cell, b: Cell): Double {
            var share = 1.0
            var x = a.x
            var y = a.y
            val dx = abs(b.x - a.x)
            val dy = -abs(b.y - a.y)
            val sx = if (a.x < b.x) 1 else -1
            val sy = if (a.y < b.y) 1 else -1
            var err = dx + dy
            while (true) {
                if (x == b.x && y == b.y) return share
                if (x != a.x || y != a.y) {
                    share *= kind.passes(layout.rows[y][x])
                    if (share <= 0) return 0.0
                }
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
