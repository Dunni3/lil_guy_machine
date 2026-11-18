package dev.lilguy.parts

/**
 * A model is a part list (`docs/decisions/2026-10-31-models-are-crayon-part-lists.md`): simple
 * shapes, each with a size, a place, optional lumps, a colour or a painted texture, and an ink
 * outline. Plain data, so a renderer in any language can draw it ([PartExport]) and a model can one
 * day write it as JSON.
 *
 * Units and pivot follow the room's asset conventions: 1 unit = 1 m = 1 tile, the model's pivot at
 * the bottom centre, the front facing +Z. Each part's ([x], [y], [z]) is the bottom centre of its
 * shape, and its rotations (degrees; Y, then X, then Z) turn it about that point.
 */
data class Part(
    val shape: Shape,
    /** Full width (x), height (y) and depth (z), before rotation. */
    val w: Float,
    val h: Float,
    val d: Float,
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f,
    val rotX: Float = 0f,
    val rotY: Float = 0f,
    val rotZ: Float = 0f,
    /** sRGB hex, as in the content files; with a texture, it multiplies it. */
    val color: String = "#ffffff",
    /** A painted texture by name, or none. */
    val texture: String? = null,
    /** How far edges are rounded, in metres; negative picks a soft default from the size. */
    val round: Float = -1f,
    /** Lump height, in metres, and the seed of their noise. */
    val lump: Float = 0f,
    val seed: Int = 0,
    /** Ink outline thickness, in metres; 0 for none. */
    val outline: Float = OUTLINE,
    /** Cylinders only: the top's width as a fraction of the bottom's. */
    val taper: Float = 1f,
    // The fields below came with clay (`docs/decisions/2026-11-15-clay-replaces-crayon-and-models-stay-part-lists.md`).
    // The crayon renderers ignore them, so `:app` draws what it always did.
    /** Spheres only: the part of the height that is there, from the top; 0.5 is a dome, flat at the bottom, [h] being its own height. */
    val cut: Float = 1f,
    /** Cylinders only: the hole's width as a fraction of the outside's, for a ring or a tube; rounded by half its wall, a torus. */
    val hole: Float = 0f,
    /** Cylinders only: the degrees of the turn that are there, centred on the front (+Z) before rotation; under 360 for an arc. */
    val arc: Float = 360f,
    /** How brightly it glows at night (a shade, a bulb, a lit pane): 1 is a lamp's; 0 for not at all. */
    val glow: Float = 0f,
    /** 1 for solid; less for glass. */
    val opacity: Float = 1f,
) {
    enum class Shape { Sphere, Box, Cylinder, Capsule, Panel }

    companion object {
        /** The default ink line for room things, in metres (the guy's is about 0.011 m). */
        const val OUTLINE = 0.011f
        const val MAX_ROUND = 0.035f
    }
}

/**
 * A part list being written in code: each call adds a [Part] with the shape's arguments in the
 * order the old g3d builders took them (colour, bottom centre, size), so models port line for line.
 * [lump] and [outline] are the defaults for the parts that follow; seeds count up per part.
 */
class PartList {
    val parts = ArrayList<Part>()
    var lump = 0f
    var outline = Part.OUTLINE
    private var seed = 1

    fun box(color: String, x: Float, y: Float, z: Float, w: Float, h: Float, d: Float, rotX: Float = 0f, rotZ: Float = 0f, rotY: Float = 0f, round: Float = -1f, texture: String? = null, outline: Float = this.outline, lump: Float = this.lump, glow: Float = 0f, opacity: Float = 1f) =
        add(Part(Part.Shape.Box, w, h, d, x, y, z, rotX, rotY, rotZ, color, texture, round, lump, seed, outline, glow = glow, opacity = opacity))

    fun cylinder(color: String, x: Float, y: Float, z: Float, w: Float, h: Float, d: Float, rotX: Float = 0f, rotZ: Float = 0f, taper: Float = 1f, round: Float = -1f, texture: String? = null, outline: Float = this.outline, lump: Float = this.lump, rotY: Float = 0f, hole: Float = 0f, arc: Float = 360f, glow: Float = 0f, opacity: Float = 1f) =
        add(Part(Part.Shape.Cylinder, w, h, d, x, y, z, rotX, rotY, rotZ, color, texture, round, lump, seed, outline, taper, hole = hole, arc = arc, glow = glow, opacity = opacity))

    /**
     * A ring standing up and facing +Z, [w] x [h] across the outside and [d] thick, its bottom centre
     * at ([x], [y], [z]): a cylinder turned on its back. [hole] is the inside's width as a fraction of
     * the outside's, [round] (half the wall for a torus) its edges, and [arc] the degrees of it that are
     * there, centred on the bottom (180 is a smile). Rotations turn it about the middle of its back.
     */
    fun ring(color: String, x: Float, y: Float, z: Float, w: Float, h: Float, d: Float, hole: Float, arc: Float = 360f, rotX: Float = 0f, rotY: Float = 0f, rotZ: Float = 0f, round: Float = -1f, glow: Float = 0f) =
        add(Part(Part.Shape.Cylinder, w, d, h, x, y + h / 2, z - d / 2, rotX + 90f, rotY, rotZ, color, null, round, 0f, seed, 0f, hole = hole, arc = arc, glow = glow))

    /** The top [cut] of a sphere [w] x [h] x [d] (by default its upper half, a dome [h] / 2 tall), the cut flat at ([x], [y], [z]). */
    fun dome(color: String, x: Float, y: Float, z: Float, w: Float, h: Float, d: Float, cut: Float = 0.5f, rotX: Float = 0f, rotY: Float = 0f, rotZ: Float = 0f, glow: Float = 0f, opacity: Float = 1f) =
        add(Part(Part.Shape.Sphere, w, h * cut, d, x, y, z, rotX, rotY, rotZ, color, null, -1f, 0f, seed, 0f, cut = cut, glow = glow, opacity = opacity))

    /** Upright; turned on its side ([rotZ] -90) it runs from ([x], [y]) along +X, with [y] its axis. */
    fun capsule(color: String, x: Float, y: Float, z: Float, w: Float, h: Float, d: Float, rotX: Float = 0f, rotZ: Float = 0f, outline: Float = this.outline, lump: Float = this.lump) =
        add(Part(Part.Shape.Capsule, w, h, d, x, y, z, rotX, 0f, rotZ, color, null, -1f, lump, seed, outline))

    fun sphere(color: String, x: Float, y: Float, z: Float, w: Float, h: Float, d: Float, outline: Float = this.outline, lump: Float = this.lump, rotX: Float = 0f, rotY: Float = 0f, rotZ: Float = 0f, texture: String? = null, glow: Float = 0f, opacity: Float = 1f) =
        add(Part(Part.Shape.Sphere, w, h, d, x, y, z, rotX, rotY, rotZ, color, texture, -1f, lump, seed, outline, glow = glow, opacity = opacity))

    /** A flat picture facing +Z, [w] x [h], its bottom centre at ([x], [y], [z]); no outline unless asked. */
    fun panel(texture: String?, x: Float, y: Float, z: Float, w: Float, h: Float, rotX: Float = 0f, color: String = "#ffffff", outline: Float = 0f) =
        add(Part(Part.Shape.Panel, w, h, 0f, x, y, z, rotX, 0f, 0f, color, texture, 0f, 0f, seed, outline))

    /** [p] as it is but for this list's lump, outline and next seed: for a model kept as data, as an invention's look is ([InventedParts]). */
    fun part(p: Part) = add(p.copy(lump = lump, seed = seed, outline = outline))

    private fun add(p: Part) { parts += p; seed++ }
}
