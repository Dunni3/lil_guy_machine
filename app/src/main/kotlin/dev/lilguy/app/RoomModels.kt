package dev.lilguy.app

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.VertexAttributes.Usage
import com.badlogic.gdx.graphics.g3d.Material
import com.badlogic.gdx.graphics.g3d.Model
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder
import com.badlogic.gdx.graphics.g3d.utils.shapebuilders.BoxShapeBuilder
import com.badlogic.gdx.graphics.g3d.utils.shapebuilders.CylinderShapeBuilder
import com.badlogic.gdx.graphics.g3d.utils.shapebuilders.SphereShapeBuilder
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.utils.Disposable
import dev.lilguy.sim.Terrain
import dev.lilguy.sim.TileMap

/**
 * The 3D room's geometry, built in code from boxes, spheres and cylinders: no asset files yet.
 *
 * Conventions every model here follows, so a Blender or kit glTF can replace one later as a drop-in:
 * 1 unit = 1 meter = 1 tile; pivot at the bottom centre of the item's tile; the front faces +Z
 * (toward the camera). Wall items ([wallItem]) instead have their back at z = 0 and are placed
 * against whichever wall face looks into the room. Models are keyed by item id ([item]), with a
 * plain box for ids nothing here knows. Positions come from the world snapshot, never from here.
 *
 * Every model is one vertex-coloured mesh part (one draw call), lit by the view's environment.
 */
class RoomModels : Disposable {
    private val builder = ModelBuilder()
    private val lit = Material(ColorAttribute.createDiffuse(Color.WHITE))
    private val items = HashMap<String, Model>()
    private var roomFor: TileMap? = null
    private var roomModel: Model? = null

    /** Floor and walls of [map], with the front wall cut down to a lip. Rebuilt when the map changes. */
    fun room(map: TileMap): Model {
        roomModel?.takeIf { roomFor === map }?.let { return it }
        roomModel?.dispose()
        roomFor = map
        return build { buildRoom(map) }.also { roomModel = it }
    }

    /** The model for item [id] (of [kind], for the fallback), built once. */
    fun item(id: String, kind: String): Model = items.getOrPut("$kind/$id") { build { buildItem(id, kind) } }

    /** Items that hang in a wall rather than stand on the floor: their back is at z = 0. */
    fun wallItem(id: String, kind: String): Boolean = id == "poster" || kind == "door" || id == "door"

    private fun build(body: Shapes.() -> Unit): Model {
        builder.begin()
        val part = builder.part("p", GL20.GL_TRIANGLES, (Usage.Position or Usage.Normal or Usage.ColorPacked).toLong(), lit)
        Shapes(part).body()
        return builder.end()
    }

    // --- the room ------------------------------------------------------------------------------

    private fun Shapes.buildRoom(map: TileMap) {
        val front = map.height - 1
        for (y in 0 until map.height) for (x in 0 until map.width) {
            when (map[x, y]) {
                Terrain.Floor -> floorTile(x, y)
                Terrain.Wall -> wallTile(map, x, y, if (y == front) LIP else WALL_HEIGHT)
                Terrain.Void -> Unit
            }
        }
        // A window in the back wall: the frame here, the glass is the view's (its colour follows the clock).
        val wx = window(map)
        box(TRIM, wx, WINDOW_BOTTOM - 0.06f, 1.0f, WINDOW_WIDTH + 0.16f, 0.08f, 0.1f)
        box(TRIM, wx, WINDOW_TOP, 1.0f, WINDOW_WIDTH + 0.12f, 0.06f, 0.04f)
        box(TRIM, wx - WINDOW_WIDTH / 2 - 0.03f, WINDOW_BOTTOM, 1.0f, 0.06f, WINDOW_TOP - WINDOW_BOTTOM, 0.04f)
        box(TRIM, wx + WINDOW_WIDTH / 2 + 0.03f, WINDOW_BOTTOM, 1.0f, 0.06f, WINDOW_TOP - WINDOW_BOTTOM, 0.04f)
        box(TRIM, wx, WINDOW_BOTTOM, 1.0f, 0.04f, WINDOW_TOP - WINDOW_BOTTOM, 0.03f)
        box(TRIM, wx, (WINDOW_BOTTOM + WINDOW_TOP) / 2, 1.0f, WINDOW_WIDTH, 0.04f, 0.03f)
        // Curtains, tied back, and a rod.
        for (side in listOf(-1f, 1f)) {
            box(CURTAIN, wx + side * (WINDOW_WIDTH / 2 + 0.1f), WINDOW_BOTTOM - 0.25f, 1.06f, 0.26f, WINDOW_TOP - WINDOW_BOTTOM + 0.38f, 0.05f)
            box(CURTAIN_TIE, wx + side * (WINDOW_WIDTH / 2 + 0.1f), WINDOW_BOTTOM + 0.15f, 1.09f, 0.28f, 0.05f, 0.03f)
        }
        cylinder(WOOD_DARK, wx - WINDOW_WIDTH / 2 - 0.35f, WINDOW_TOP + 0.12f, 1.07f, 0.03f, WINDOW_WIDTH + 0.7f, 0.03f, rotZ = -90f)
        // A wall clock; its hands are the view's ([hand]).
        cylinder(WOOD_DARK, clockX(map), CLOCK_Y, 1.0f, 0.42f, 0.04f, 0.42f, divisions = 24, rotX = 90f)
        cylinder(TRIM, clockX(map), CLOCK_Y, 1.03f, 0.36f, 0.02f, 0.36f, divisions = 24, rotX = 90f)
    }

    /** One clock hand, pivot at its base, pointing up (+Y) and facing +Z. */
    fun hand(length: Float): Model = items.getOrPut("hand/$length") { build { box(HAND, 0f, -0.02f, 0f, 0.025f, length, 0.01f) } }

    /** Centre x of the back-wall clock, left of centre. */
    fun clockX(map: TileMap): Float = map.width * 0.27f

    /** Planks running east-west, four to a tile, each a slightly different wood, staggered so seams don't line up. */
    private fun Shapes.floorTile(x: Int, y: Int) {
        val g = GAP / 2
        for (i in 0 until PLANKS) {
            val row = y * PLANKS + i
            val z0 = y + i / PLANKS.toFloat()
            val z1 = z0 + 1f / PLANKS
            val wood = tmp.set(FLOOR_A).lerp(FLOOR_B, ((x * 7 + row * 13) % 5) / 4f)
            topQuad(FLOOR_GAP, x.toFloat(), z1 - g, x + 1f, z1, 0f)
            topQuad(FLOOR_GAP, x.toFloat(), z0, x + 1f, z0 + g, 0f)
            if (row % 2 == 0) {
                topQuad(wood, x.toFloat(), z0 + g, x + 1f, z1 - g, 0f)
            } else {
                topQuad(wood, x.toFloat(), z0 + g, x + 0.5f - g, z1 - g, 0f)
                topQuad(FLOOR_GAP, x + 0.5f - g, z0 + g, x + 0.5f + g, z1 - g, 0f)
                topQuad(wood, x + 0.5f + g, z0 + g, x + 1f, z1 - g, 0f)
            }
        }
    }

    /**
     * A wall tile: a thin slab along each side that faces the floor, so the room reads as a cutaway
     * dollhouse. The face into the room gets a baseboard, wainscot, a rail and wallpaper (or plain
     * trim on the low front lip); the top and the other faces are the dark cut section. Slabs run
     * [THICK] past both ends of the tile, so they meet at the corners.
     */
    private fun Shapes.wallTile(map: TileMap, x: Int, y: Int, h: Float) {
        for ((dx, dy) in SIDES) {
            if (map[x + dx, y + dy] != Terrain.Floor) continue
            slab(x, y, dx, dy, -THICK, 1 + THICK, THICK, h)
            if (h < WAINSCOT) {
                sideSpan(x, y, dx, dy, -THICK, 1 + THICK, 0f, h, TRIM)
                continue
            }
            sideSpan(x, y, dx, dy, -THICK, 1 + THICK, 0f, BASEBOARD, BASE)
            sideSpan(x, y, dx, dy, -THICK, 1 + THICK, BASEBOARD, WAINSCOT, WAINSCOT_COLOR)
            sideSpan(x, y, dx, dy, -THICK, 1 + THICK, WAINSCOT, WAINSCOT + RAIL, TRIM)
            // Wallpaper with two faint stripes a tile.
            var from = -THICK
            for ((i, to) in STRIPES.withIndex()) {
                sideSpan(x, y, dx, dy, from, if (i == STRIPES.lastIndex) 1 + THICK else to, WAINSCOT + RAIL, h, if (i % 2 == 0) WALLPAPER else WALLPAPER_STRIPE)
                from = to
            }
        }
    }

    /** Centre x of the back-wall window: the widest stretch of back wall with nothing in it, right of centre. */
    fun window(map: TileMap): Float = map.width * 0.7f

    // --- items ---------------------------------------------------------------------------------

    private fun Shapes.buildItem(id: String, kind: String) {
        when (id) {
            "bed" -> {
                for (sx in listOf(-0.4f, 0.4f)) for (sz in listOf(-0.42f, 0.42f)) box(WOOD_DARK, sx, 0f, sz, 0.08f, 0.16f, 0.08f)
                box(WOOD, 0f, 0.12f, 0f, 0.92f, 0.1f, 0.96f)
                box(SHEET, 0f, 0.22f, 0.01f, 0.86f, 0.1f, 0.9f)
                box(BLANKET, 0f, 0.24f, 0.17f, 0.9f, 0.1f, 0.6f)
                box(BLANKET_STRIPE, 0f, 0.245f, -0.1f, 0.92f, 0.12f, 0.06f)
                sphere(PILLOW, 0f, 0.3f, -0.3f, 0.56f, 0.12f, 0.24f)
                box(WOOD, 0f, 0f, -0.47f, 0.96f, 0.7f, 0.06f)
                box(WOOD_LIGHT, 0f, 0.7f, -0.47f, 1.0f, 0.05f, 0.08f)
            }
            "desk" -> {
                for (sx in listOf(-0.42f, 0.42f)) for (sz in listOf(-0.24f, 0.24f)) box(WOOD_DARK, sx, 0f, sz, 0.06f, 0.5f, 0.06f)
                box(WOOD, 0f, 0.5f, 0f, 0.96f, 0.06f, 0.6f)
                box(WOOD_LIGHT, 0.24f, 0.32f, 0f, 0.4f, 0.18f, 0.54f)
                box(PAPER, -0.12f, 0.56f, 0.04f, 0.28f, 0.01f, 0.2f)
                cylinder(MUG, 0.18f, 0.56f, 0.12f, 0.09f, 0.1f, 0.09f)
                // A little lamp.
                cylinder(WOOD_DARK, -0.34f, 0.56f, -0.18f, 0.14f, 0.03f, 0.14f)
                cylinder(WOOD_DARK, -0.34f, 0.56f, -0.18f, 0.025f, 0.3f, 0.025f)
                cylinder(LAMPSHADE, -0.34f, 0.8f, -0.18f, 0.22f, 0.14f, 0.22f)
            }
            "chair" -> {
                for (sx in listOf(-0.16f, 0.16f)) for (sz in listOf(-0.16f, 0.16f)) box(WOOD_DARK, sx, 0f, sz, 0.05f, 0.3f, 0.05f)
                box(WOOD, 0f, 0.3f, 0f, 0.42f, 0.05f, 0.42f)
                sphere(CUSHION, 0f, 0.33f, 0f, 0.36f, 0.08f, 0.36f)
                box(WOOD, -0.19f, 0.3f, 0f, 0.05f, 0.42f, 0.42f)
            }
            "easel" -> {
                box(WOOD, -0.22f, 0f, 0.08f, 0.05f, 1.15f, 0.05f, rotZ = -8f)
                box(WOOD, 0.22f, 0f, 0.08f, 0.05f, 1.15f, 0.05f, rotZ = 8f)
                box(WOOD_DARK, 0f, 0f, -0.2f, 0.05f, 1.1f, 0.05f, rotX = -14f)
                box(WOOD, 0f, 0.42f, 0.12f, 0.56f, 0.05f, 0.08f)
                box(CANVAS, 0f, 0.47f, 0.12f, 0.5f, 0.56f, 0.03f)
                sphere(POSTER_SUN, -0.08f, 0.72f, 0.14f, 0.16f, 0.16f, 0.02f)
                box(LEAF, 0.05f, 0.5f, 0.14f, 0.38f, 0.14f, 0.02f)
            }
            "record_player" -> {
                box(WOOD_DARK, 0f, 0f, 0f, 0.8f, 0.5f, 0.46f)
                box(WOOD, 0f, 0.08f, 0.235f, 0.7f, 0.34f, 0.01f)
                box(WOOD_LIGHT, 0f, 0.5f, 0f, 0.6f, 0.06f, 0.4f)
                cylinder(VINYL, -0.06f, 0.56f, 0f, 0.3f, 0.02f, 0.3f)
                cylinder(RECORD_LABEL, -0.06f, 0.575f, 0f, 0.1f, 0.01f, 0.1f)
                box(TRIM, 0.2f, 0.56f, -0.08f, 0.03f, 0.03f, 0.22f)
            }
            "plant" -> {
                cylinder(POT, 0f, 0f, 0f, 0.32f, 0.28f, 0.32f)
                cylinder(POT_RIM, 0f, 0.26f, 0f, 0.36f, 0.06f, 0.36f)
                sphere(LEAF_DARK, 0f, 0.28f, 0f, 0.4f, 0.32f, 0.4f)
                sphere(LEAF, -0.1f, 0.42f, 0.04f, 0.26f, 0.26f, 0.26f)
                sphere(LEAF, 0.12f, 0.46f, -0.02f, 0.24f, 0.28f, 0.24f)
                sphere(LEAF_LIGHT, 0f, 0.58f, 0.03f, 0.2f, 0.22f, 0.2f)
            }
            "rug" -> {
                cylinder(RUG, 0f, 0f, 0f, 1.9f, 0.015f, 1.3f, divisions = 32)
                cylinder(RUG_INNER, 0f, 0.01f, 0f, 1.5f, 0.015f, 0.95f, divisions = 32)
                cylinder(RUG, 0f, 0.02f, 0f, 1.0f, 0.015f, 0.6f, divisions = 32)
            }
            "poster" -> {
                box(POSTER, 0f, 0.95f, 0.01f, 0.62f, 0.82f, 0.02f)
                sphere(POSTER_SUN, 0.1f, 1.38f, 0.025f, 0.22f, 0.22f, 0.02f)
                box(POSTER_HILL, 0f, 0.99f, 0.025f, 0.54f, 0.22f, 0.02f)
                box(TRIM, 0f, 1.71f, 0.03f, 0.05f, 0.05f, 0.02f)
            }
            else -> when (kind) {
                "door", "fixture" -> {
                    box(TRIM, 0f, 0f, 0.02f, 0.92f, 1.66f, 0.04f)
                    box(DOOR, 0f, 0f, 0.05f, 0.8f, 1.6f, 0.05f)
                    box(WOOD_DARK, 0f, 0.92f, 0.08f, 0.6f, 0.5f, 0.02f)
                    box(WOOD_DARK, 0f, 0.22f, 0.08f, 0.6f, 0.55f, 0.02f)
                    sphere(KNOB, 0.3f, 0.78f, 0.1f, 0.07f, 0.07f, 0.07f)
                }
                "key" -> {
                    cylinder(KNOB, -0.08f, 0.02f, 0f, 0.16f, 0.04f, 0.16f)
                    box(KNOB, 0.07f, 0.02f, 0f, 0.2f, 0.04f, 0.05f)
                    box(KNOB, 0.14f, 0.02f, 0.04f, 0.04f, 0.04f, 0.06f)
                }
                "apple" -> {
                    sphere(APPLE, 0f, 0f, 0f, 0.24f, 0.22f, 0.24f)
                    box(LEAF, 0.04f, 0.22f, 0f, 0.08f, 0.03f, 0.05f)
                }
                "book" -> {
                    box(BOOK, 0f, 0f, 0f, 0.3f, 0.07f, 0.4f)
                    box(PAPER, 0.02f, 0.01f, 0f, 0.27f, 0.05f, 0.38f)
                }
                "exit" -> topQuad(EXIT, -0.45f, -0.45f, 0.45f, 0.45f, 0.01f)
                else -> box(UNKNOWN, 0f, 0f, 0f, 0.4f, 0.4f, 0.4f)
            }
        }
    }

    override fun dispose() {
        roomModel?.dispose()
        items.values.forEach { it.dispose() }
        items.clear()
    }

    /**
     * Shapes added to one mesh part, each in its own colour. Positions are the bottom centre of the
     * shape; rotations (degrees) are about that point.
     */
    private class Shapes(private val b: MeshPartBuilder) {
        private val t = Matrix4()
        val tmp = Color()

        fun box(c: Color, x: Float, y: Float, z: Float, w: Float, h: Float, d: Float, rotX: Float = 0f, rotZ: Float = 0f) {
            place(c, x, y, z, rotX, rotZ, h)
            BoxShapeBuilder.build(b, 0f, 0f, 0f, w, h, d)
        }

        fun sphere(c: Color, x: Float, y: Float, z: Float, w: Float, h: Float, d: Float) {
            place(c, x, y, z, 0f, 0f, h)
            SphereShapeBuilder.build(b, w, h, d, 14, 10)
        }

        fun cylinder(c: Color, x: Float, y: Float, z: Float, w: Float, h: Float, d: Float, divisions: Int = 16, rotX: Float = 0f, rotZ: Float = 0f) {
            place(c, x, y, z, rotX, rotZ, h)
            CylinderShapeBuilder.build(b, w, h, d, divisions)
        }

        /** An upward-facing rectangle at height [y]. */
        fun topQuad(c: Color, x0: Float, z0: Float, x1: Float, z1: Float, y: Float) {
            b.setColor(c)
            b.setVertexTransform(t.idt())
            b.rect(x0, y, z1, x1, y, z1, x1, y, z0, x0, y, z0, 0f, 1f, 0f)
        }

        /** The side of wall tile (x, y) facing (dx, dy), from height [y0] to [y1]. */
        fun sideQuad(x: Int, y: Int, dx: Int, dy: Int, y0: Float, y1: Float, c: Color) = sideSpan(x, y, dx, dy, 0f, 1f, y0, y1, c)

        /**
         * The cut section of a wall slab behind the face of tile (x, y) toward (dx, dy): its top,
         * back and two ends, [thick] deep, spanning [s0]..[s1] along the face.
         */
        fun slab(x: Int, y: Int, dx: Int, dy: Int, s0: Float, s1: Float, thick: Float, h: Float) {
            b.setColor(SECTION)
            b.setVertexTransform(t.idt())
            val nx = dx.toFloat()
            val nz = dy.toFloat()
            val cx = x + 0.5f + nx * 0.5f
            val cz = y + 0.5f + nz * 0.5f
            val rx = nz
            val rz = -nx
            // Points along the face (s) and back into the wall (d).
            fun px(s: Float, d: Float) = cx + rx * (s - 0.5f) - nx * d
            fun pz(s: Float, d: Float) = cz + rz * (s - 0.5f) - nz * d
            b.rect(px(s0, 0f), h, pz(s0, 0f), px(s1, 0f), h, pz(s1, 0f), px(s1, thick), h, pz(s1, thick), px(s0, thick), h, pz(s0, thick), 0f, 1f, 0f)
            b.rect(px(s1, thick), 0f, pz(s1, thick), px(s0, thick), 0f, pz(s0, thick), px(s0, thick), h, pz(s0, thick), px(s1, thick), h, pz(s1, thick), -nx, 0f, -nz)
            b.rect(px(s1, 0f), 0f, pz(s1, 0f), px(s1, thick), 0f, pz(s1, thick), px(s1, thick), h, pz(s1, thick), px(s1, 0f), h, pz(s1, 0f), rx, 0f, rz)
            b.rect(px(s0, thick), 0f, pz(s0, thick), px(s0, 0f), 0f, pz(s0, 0f), px(s0, 0f), h, pz(s0, 0f), px(s0, thick), h, pz(s0, thick), -rx, 0f, -rz)
        }

        /** Part [s0]..[s1] (0..1 along the face, left to right as seen from the room; may run past either end) of a wall face. */
        fun sideSpan(x: Int, y: Int, dx: Int, dy: Int, s0: Float, s1: Float, y0: Float, y1: Float, c: Color) {
            b.setColor(c)
            b.setVertexTransform(t.idt())
            // The face's plane and its left-to-right direction, as seen from the neighbouring tile.
            val nx = dx.toFloat()
            val nz = dy.toFloat()
            val cx = x + 0.5f + nx * 0.5f
            val cz = y + 0.5f + nz * 0.5f
            val rx = nz
            val rz = -nx
            val ax = cx + rx * (s0 - 0.5f)
            val az = cz + rz * (s0 - 0.5f)
            val bx = cx + rx * (s1 - 0.5f)
            val bz = cz + rz * (s1 - 0.5f)
            b.rect(ax, y0, az, bx, y0, bz, bx, y1, bz, ax, y1, az, nx, 0f, nz)
        }

        private fun place(c: Color, x: Float, y: Float, z: Float, rotX: Float, rotZ: Float, h: Float) {
            b.setColor(c)
            t.setToTranslation(x, y, z)
            if (rotX != 0f) t.rotate(Vector3.X, rotX)
            if (rotZ != 0f) t.rotate(Vector3.Z, rotZ)
            t.translate(0f, h / 2, 0f)
            b.setVertexTransform(t)
        }
    }

    companion object {
        const val WALL_HEIGHT = 2.1f
        const val LIP = 0.14f
        const val WINDOW_WIDTH = 1.3f
        const val WINDOW_BOTTOM = 1.0f
        const val WINDOW_TOP = 1.8f
        const val CLOCK_Y = 1.32f
        private const val THICK = 0.14f
        private const val PLANKS = 4
        private const val GAP = 0.012f
        /** Where the wallpaper switches between plain and stripe, along a tile face. */
        private val STRIPES = floatArrayOf(0.22f, 0.28f, 0.72f, 0.78f, 1f)
        private const val BASEBOARD = 0.1f
        private const val WAINSCOT = 0.75f
        private const val RAIL = 0.05f
        /** (dx, dy) to each neighbouring tile. */
        private val SIDES = listOf(0 to 1, 0 to -1, 1 to 0, -1 to 0)

        private val FLOOR_A = Color.valueOf("dcaa78ff")
        private val FLOOR_B = Color.valueOf("cf9a66ff")
        private val FLOOR_GAP = Color.valueOf("b98456ff")
        private val SECTION = Color.valueOf("5b4636ff")
        private val BASE = Color.valueOf("a8714aff")
        private val WAINSCOT_COLOR = Color.valueOf("9fcdb5ff")
        private val WALLPAPER = Color.valueOf("fbe6c4ff")
        private val WALLPAPER_STRIPE = Color.valueOf("f5d7aaff")
        val TRIM = Color.valueOf("fff8eeff")
        private val WOOD = Color.valueOf("c48a55ff")
        private val WOOD_LIGHT = Color.valueOf("dcaa74ff")
        private val WOOD_DARK = Color.valueOf("8c5a36ff")
        private val SHEET = Color.valueOf("fff6e6ff")
        private val BLANKET = Color.valueOf("6f9de6ff")
        private val BLANKET_STRIPE = Color.valueOf("f7c94bff")
        private val PILLOW = Color.valueOf("ffffffff")
        private val PAPER = Color.valueOf("fbf6eaff")
        private val MUG = Color.valueOf("e8685aff")
        private val LAMPSHADE = Color.valueOf("ffd98aff")
        private val CUSHION = Color.valueOf("e86f7aff")
        private val CANVAS = Color.valueOf("fbf6eaff")
        private val VINYL = Color.valueOf("26232bff")
        private val RECORD_LABEL = Color.valueOf("e8685aff")
        private val POT = Color.valueOf("d9774fff")
        private val POT_RIM = Color.valueOf("e8906aff")
        private val LEAF = Color.valueOf("6cc070ff")
        private val LEAF_DARK = Color.valueOf("4f9e5aff")
        private val LEAF_LIGHT = Color.valueOf("8ed884ff")
        private val RUG = Color.valueOf("e79aa8ff")
        private val RUG_INNER = Color.valueOf("fbe0b4ff")
        private val POSTER = Color.valueOf("86b6e8ff")
        private val POSTER_SUN = Color.valueOf("ffd45cff")
        private val POSTER_HILL = Color.valueOf("6cc070ff")
        private val DOOR = Color.valueOf("b77a48ff")
        private val KNOB = Color.valueOf("f5c542ff")
        private val APPLE = Color.valueOf("e04848ff")
        private val BOOK = Color.valueOf("4f7fdcff")
        private val EXIT = Color.valueOf("4cd18aff")
        private val CURTAIN = Color.valueOf("f29aa0ff")
        private val CURTAIN_TIE = Color.valueOf("fbe0b4ff")
        private val HAND = Color.valueOf("3a2a20ff")
        private val UNKNOWN = Color.valueOf("c78bd8ff")
    }
}
