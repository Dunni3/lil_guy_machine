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

    fun box(color: String, x: Float, y: Float, z: Float, w: Float, h: Float, d: Float, rotX: Float = 0f, rotZ: Float = 0f, rotY: Float = 0f, round: Float = -1f, texture: String? = null, outline: Float = this.outline, lump: Float = this.lump) =
        add(Part(Part.Shape.Box, w, h, d, x, y, z, rotX, rotY, rotZ, color, texture, round, lump, seed, outline))

    fun cylinder(color: String, x: Float, y: Float, z: Float, w: Float, h: Float, d: Float, rotX: Float = 0f, rotZ: Float = 0f, taper: Float = 1f, round: Float = -1f, texture: String? = null, outline: Float = this.outline, lump: Float = this.lump) =
        add(Part(Part.Shape.Cylinder, w, h, d, x, y, z, rotX, 0f, rotZ, color, texture, round, lump, seed, outline, taper))

    /** Upright; turned on its side ([rotZ] -90) it runs from ([x], [y]) along +X, with [y] its axis. */
    fun capsule(color: String, x: Float, y: Float, z: Float, w: Float, h: Float, d: Float, rotX: Float = 0f, rotZ: Float = 0f, outline: Float = this.outline, lump: Float = this.lump) =
        add(Part(Part.Shape.Capsule, w, h, d, x, y, z, rotX, 0f, rotZ, color, null, -1f, lump, seed, outline))

    fun sphere(color: String, x: Float, y: Float, z: Float, w: Float, h: Float, d: Float, outline: Float = this.outline, lump: Float = this.lump) =
        add(Part(Part.Shape.Sphere, w, h, d, x, y, z, color = color, lump = lump, seed = seed, outline = outline))

    /** A flat picture facing +Z, [w] x [h], its bottom centre at ([x], [y], [z]); no outline unless asked. */
    fun panel(texture: String?, x: Float, y: Float, z: Float, w: Float, h: Float, rotX: Float = 0f, color: String = "#ffffff", outline: Float = 0f) =
        add(Part(Part.Shape.Panel, w, h, 0f, x, y, z, rotX, 0f, 0f, color, texture, 0f, 0f, seed, outline))

    private fun add(p: Part) { parts += p; seed++ }
}
