package dev.lilguy.agent.art

/** One crayon: its [name], as the options and the state say it, and its color as 0xRRGGBB. */
data class Crayon(val name: String, val rgb: Int)

/** A box of crayons for one piece. The first is the paper: the canvas starts filled with it. */
class Palette(val id: String, val description: String, val crayons: List<Crayon>) {
    init {
        require(crayons.size >= 2) { "palette $id needs the paper and at least one crayon" }
        require(crayons.distinctBy { it.name }.size == crayons.size) { "palette $id names a crayon twice" }
    }

    val rgb: List<Int> get() = crayons.map { it.rgb }
}

/**
 * The palettes a piece can be drawn in. A picking model can't write a hex color, so the boxes
 * are fixed here and named, and he picks a box, then crayons from it by name. Sketch boxes are
 * four crayons (paper plus three); the painting box is sixteen.
 */
object Palettes {
    val SKETCH: List<Palette> = listOf(
        Palette("night", "a night sky: navy paper, pale yellow, white and grey", listOf(Crayon("navy", 0x1b2340), Crayon("pale yellow", 0xf6e7a1), Crayon("white", 0xf8f8f4), Crayon("grey", 0x8a8f9c))),
        Palette("day", "a sunny day: sky blue paper, sun yellow, grass green and white", listOf(Crayon("sky blue", 0x9ed3f0), Crayon("sun yellow", 0xf9d44c), Crayon("grass green", 0x5fae4a), Crayon("white", 0xfbfbf7))),
        Palette("warm", "warm: cream paper, red, orange and brown", listOf(Crayon("cream", 0xf7edd6), Crayon("red", 0xd9463e), Crayon("orange", 0xf29b3c), Crayon("brown", 0x6b4426))),
        Palette("cool", "cool: pale blue paper, teal, deep blue and white", listOf(Crayon("pale blue", 0xdbeaf5), Crayon("teal", 0x3f9c9b), Crayon("deep blue", 0x25468a), Crayon("white", 0xffffff))),
        Palette("earth", "earthy: tan paper, brown, dark green and cream", listOf(Crayon("tan", 0xd9c39a), Crayon("brown", 0x7a5230), Crayon("dark green", 0x3d6b3a), Crayon("cream", 0xf6f0e1))),
        Palette("rose", "soft: blush paper, plum, cream and leaf green", listOf(Crayon("blush", 0xf6d9dc), Crayon("plum", 0x6d2e5c), Crayon("cream", 0xfaf3e6), Crayon("leaf green", 0x7fae6a))),
        Palette("ink", "ink: white paper, black, grey and red", listOf(Crayon("white", 0xfbfbf7), Crayon("black", 0x1e1e1e), Crayon("grey", 0x9a9a9a), Crayon("red", 0xcf3b2f))),
        Palette("sea", "the sea: deep blue paper, sea green, sand and white", listOf(Crayon("deep blue", 0x1f3f73), Crayon("sea green", 0x4fa58e), Crayon("sand", 0xe8d9a8), Crayon("white", 0xf7f7f2))),
        Palette("dusk", "dusk: lavender paper, orange, plum and pale yellow", listOf(Crayon("lavender", 0xcdbfe3), Crayon("orange", 0xf0874a), Crayon("plum", 0x4b2a5c), Crayon("pale yellow", 0xf9e8a6))),
    )

    val PAINT: List<Palette> = listOf(
        Palette(
            "full_box", "the full box of sixteen crayons on white paper",
            listOf(
                Crayon("white", 0xfbfbf7), Crayon("black", 0x1e1e1e), Crayon("grey", 0x9a9a9a), Crayon("dark grey", 0x4a4a4a),
                Crayon("red", 0xd9463e), Crayon("orange", 0xf29b3c), Crayon("yellow", 0xf9d44c), Crayon("cream", 0xf7edd6),
                Crayon("green", 0x5fae4a), Crayon("dark green", 0x3d6b3a), Crayon("sky blue", 0x9ed3f0), Crayon("blue", 0x3a6fc4),
                Crayon("navy", 0x1b2340), Crayon("purple", 0x6d2e9c), Crayon("pink", 0xf4a3c0), Crayon("brown", 0x7a5230),
            ),
        ),
    )

    /**
     * The boxes for a canvas of up to [colors] colors: every palette with at least that many
     * crayons, cut down to it. When none is big enough, the biggest, as it is (fewer colors than
     * the canvas allows is fine).
     */
    fun fitting(colors: Int): List<Palette> {
        val all = SKETCH + PAINT
        val fit = all.filter { it.crayons.size >= colors }.map { p -> if (p.crayons.size == colors) p else Palette(p.id, p.description, p.crayons.take(colors)) }
        return fit.ifEmpty { listOf(all.maxBy { it.crayons.size }) }
    }
}
