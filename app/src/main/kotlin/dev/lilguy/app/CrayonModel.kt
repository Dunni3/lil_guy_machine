package dev.lilguy.app

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Mesh
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.VertexAttribute
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.utils.Disposable
import kotlin.math.ceil
import kotlin.math.pow

/**
 * A model is a part list (`docs/decisions/2026-10-31-models-are-crayon-part-lists.md`): simple
 * shapes, each with a size, a place, optional lumps, a colour or a painted texture, and an ink
 * outline. Kept as plain data so a model can one day write it as JSON.
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
    /** A painted texture by name ([CrayonModel.of]'s lookup), or none. */
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

    /** This part's geometry, centred on the origin, before it's placed. */
    fun geometry(): PartGeometry {
        val small = minOf(w, h, d)
        val soft = if (round >= 0f) round else minOf(MAX_ROUND, small * 0.3f)
        val geo = when (shape) {
            Shape.Sphere -> {
                val ws = (maxOf(w, h, d) * 48).toInt().coerceIn(12, 32)
                sphereGeometry(ws, ws * 3 / 4) { px, py, pz, out -> out[0] = px * w / 2; out[1] = py * h / 2; out[2] = pz * d / 2 }
            }
            Shape.Box -> roundedBoxGeometry(w, h, d, soft)
            Shape.Cylinder -> cylinderGeometry(w, h, d, soft, taper, segments(maxOf(w, d)))
            Shape.Capsule -> cylinderGeometry(w, h, d, minOf(w, d) / 2, taper, segments(maxOf(w, d)))
            Shape.Panel -> panelGeometry(w, h)
        }
        return geo.lump(lump, seed)
    }

    /** Where the geometry goes in the model: to the bottom centre, turned, then up by half its height. */
    fun placement(out: Matrix4): Matrix4 {
        out.setToTranslation(x, y, z)
        if (rotY != 0f) out.rotate(Vector3.Y, rotY)
        if (rotX != 0f) out.rotate(Vector3.X, rotX)
        if (rotZ != 0f) out.rotate(Vector3.Z, rotZ)
        return out.translate(0f, h / 2, 0f)
    }

    private fun segments(size: Float) = ceil(size * 40).toInt().coerceIn(14, 40)

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

/** How a piece's outline boils: [base] thick plus up to [vary] of noise ([seed]), over its normals and, by [wander] per metre, its position. */
class Outline(val base: Float, val vary: Float, val seed: Int, val wander: Float = 0f)

/** A part ready to merge: geometry, where it goes in the model, a linear colour, a texture, an outline. */
class Piece(val geo: PartGeometry, val color: Color, val texture: Texture? = null, val outline: Outline? = null, val place: Matrix4 = Matrix4())

/**
 * Pieces merged for drawing: one mesh per texture (colour per vertex), and one outline hull per boil
 * frame, so a whole item costs a draw call or two plus one for its ink. [hatchScale] scales the
 * hatching's object space (furniture is in metres; the guy's parts are drawn 1.25 times bigger).
 */
class CrayonModel(pieces: List<Piece>, val hatchScale: Float = 1f) : Disposable {
    class Batch(val mesh: Mesh, val texture: Texture?)

    val batches: List<Batch>
    /** One per boil frame; empty when nothing has an outline. */
    val hulls: List<Mesh>
    /** What it takes up: min x, y, z, then max x, y, z. */
    val bounds = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)

    init {
        val normalMatrix = com.badlogic.gdx.math.Matrix3()
        val v = Vector3()
        batches = pieces.groupBy { it.texture }.map { (texture, group) ->
            val verts = FloatArray(group.sumOf { it.geo.count } * STRIDE)
            val idx = ShortArray(group.sumOf { it.geo.idx.size })
            var vo = 0
            var io = 0
            for (p in group) {
                val g = p.geo
                normalMatrix.set(p.place).inv().transpose()
                val base = vo
                for (i in 0 until g.count) {
                    val o = (vo + i) * STRIDE
                    v.set(g.pos[3 * i], g.pos[3 * i + 1], g.pos[3 * i + 2]).mul(p.place)
                    verts[o] = v.x; verts[o + 1] = v.y; verts[o + 2] = v.z
                    for (k in 0..2) {
                        bounds[k] = minOf(bounds[k], verts[o + k])
                        bounds[k + 3] = maxOf(bounds[k + 3], verts[o + k])
                    }
                    v.set(g.normal[3 * i], g.normal[3 * i + 1], g.normal[3 * i + 2]).mul(normalMatrix).nor()
                    verts[o + 3] = v.x; verts[o + 4] = v.y; verts[o + 5] = v.z
                    verts[o + 6] = g.uv[2 * i]; verts[o + 7] = g.uv[2 * i + 1]
                    verts[o + 8] = p.color.r; verts[o + 9] = p.color.g; verts[o + 10] = p.color.b; verts[o + 11] = 1f
                }
                for (k in g.idx) idx[io++] = ((k.toInt() and 0xffff) + base).toShort()
                vo += g.count
            }
            check(vo <= 0xffff) { "a crayon model's batch has $vo vertices, more than 16-bit indices reach" }
            Batch(mesh(verts, vo, idx, VertexAttribute.Position(), VertexAttribute.Normal(), VertexAttribute.TexCoords(0), VertexAttribute.ColorUnpacked()), texture)
        }
        val inked = pieces.filter { it.outline != null }
        hulls = if (inked.isEmpty()) emptyList() else List(BOIL_FRAMES) { k ->
            val verts = FloatArray(inked.sumOf { it.geo.count } * 3)
            val idx = ShortArray(inked.sumOf { it.geo.idx.size })
            var vo = 0
            var io = 0
            for (p in inked) {
                val o = p.outline!!
                val nz = Noise3(o.seed * 31 + k * 7, 4, 4f)
                val w = o.wander
                val pushed = p.geo.hull { x, y, z, nx, ny, nz2 -> o.base + o.vary * nz(nx + x * w, ny + y * w, nz2 + z * w) }
                for (i in 0 until p.geo.count) {
                    v.set(pushed[3 * i], pushed[3 * i + 1], pushed[3 * i + 2]).mul(p.place)
                    verts[3 * (vo + i)] = v.x; verts[3 * (vo + i) + 1] = v.y; verts[3 * (vo + i) + 2] = v.z
                }
                for (q in p.geo.idx) idx[io++] = ((q.toInt() and 0xffff) + vo).toShort()
                vo += p.geo.count
            }
            check(vo <= 0xffff) { "a crayon model's outline has $vo vertices, more than 16-bit indices reach" }
            mesh(verts, vo, idx, VertexAttribute.Position())
        }
    }

    override fun dispose() {
        batches.forEach { it.mesh.dispose() }
        hulls.forEach { it.dispose() }
    }

    private fun mesh(verts: FloatArray, count: Int, idx: ShortArray, vararg attributes: VertexAttribute) =
        Mesh(true, count, idx.size, *attributes).apply {
            setVertices(verts)
            setIndices(idx)
        }

    companion object {
        /** The outline and hatching redraw a few times a second, cycling through this many frames. */
        const val BOIL_FRAMES = 3
        private const val STRIDE = 12

        /** A model from a part list; [texture] finds a painted texture by name. */
        fun of(parts: List<Part>, texture: (String) -> Texture? = { null }, hatchScale: Float = HATCH_METRES): CrayonModel {
            val pieces = parts.map { p ->
                Piece(
                    geo = p.geometry(),
                    color = linear(Color.valueOf(p.color)),
                    texture = p.texture?.let(texture),
                    outline = if (p.outline > 0f) Outline(p.outline, p.outline * 0.45f, p.seed, wander = 2.5f) else null,
                    place = p.placement(Matrix4()),
                )
            }
            return CrayonModel(pieces, hatchScale)
        }

        /** The guy's parts are drawn 1.25 times their size, so furniture in metres hatches at this to match him. */
        const val HATCH_METRES = 0.8f

        /** sRGB to linear, as the crayon shader works. */
        fun linear(c: Color) = Color(c.r.pow(2.2f), c.g.pow(2.2f), c.b.pow(2.2f), c.a)
    }
}
