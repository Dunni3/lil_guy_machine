package dev.lilguy.app

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Mesh
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.VertexAttribute
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.utils.Disposable
import dev.lilguy.sim.Terrain
import dev.lilguy.sim.TileMap
import kotlin.math.hypot
import kotlin.math.max

/**
 * The 3D room in the hand-drawn look: furniture as part lists ([PartList], drawn by
 * [CrayonRenderer]), and the floor and walls as flat meshes carrying painted textures ([RoomPaint]),
 * drawn with the cheap paper shader. No asset files.
 *
 * Conventions every model here follows, so another part list (or a model file) can replace one as
 * a drop-in: 1 unit = 1 meter = 1 tile; pivot at the bottom centre of the item's tile; the front faces
 * +Z (toward the camera). Wall items ([wallItem]) instead have their back at z = 0 and are placed
 * against whichever wall face looks into the room. Models are keyed by item id ([item]), with a
 * plain box for ids nothing here knows. Positions come from the world snapshot, never from here.
 */
class RoomModels : Disposable {
    val paint = RoomPaint()
    private val items = HashMap<String, CrayonModel>()
    private var roomFor: TileMap? = null
    private var built: Room? = null

    /**
     * A room's fixed parts: painted [surfaces] (each a mesh and its texture), crayon [fixtures]
     * (window frame, curtains, clock), and the window's [glass], whose colour the view sets.
     */
    class Room(val surfaces: List<Pair<Mesh, Texture>>, val fixtures: CrayonModel, val glass: Mesh) : Disposable {
        override fun dispose() {
            surfaces.forEach { it.first.dispose() }
            fixtures.dispose()
            glass.dispose()
        }
    }

    /**
     * Floor and walls of [map], with the front wall cut down to a lip, each side shaded once for the
     * world-space [light] it faces. Rebuilt when the map changes.
     */
    fun room(map: TileMap, light: Vector3): Room {
        built?.takeIf { roomFor === map }?.let { return it }
        built?.dispose()
        roomFor = map
        return buildRoom(map, light).also { built = it }
    }

    /** The model for item [id] (of [kind], for the fallback), built once. */
    fun item(id: String, kind: String): CrayonModel = items.getOrPut("$kind/$id") { model { buildItem(id, kind) } }

    /** Items that hang in a wall rather than stand on the floor: their back is at z = 0. */
    fun wallItem(id: String, kind: String): Boolean = id in WALL_ITEMS || kind == "door"

    /** Items that lie flat and cast no shadow of their own. */
    fun flat(id: String, kind: String): Boolean = id == "rug" || kind == "exit"

    /**
     * An empty picture frame, pivot at its bottom centre, back at z = 0, [FRAME] square; the view
     * puts a piece's texture on its mat ([FRAME_ART] square, [FRAME_ART_Z] out from the wall).
     */
    fun frame(): CrayonModel = items.getOrPut("frame") {
        model {
            box(WOOD_DARK, 0f, 0f, 0.015f, FRAME, FRAME, 0.03f, round = 0.012f)
            box(PAPER, 0f, 0.03f, 0.03f, FRAME - 0.06f, FRAME - 0.06f, 0.01f, round = 0.004f, outline = 0f)
            sphere(KNOB, 0f, FRAME + 0.065f, 0.01f, 0.035f, 0.035f, 0.025f)
        }
    }

    /** A blank canvas, pivot at the easel's, standing on its ledge; its face is [CANVAS_Z] out and [CANVAS_Y] up at its centre. */
    fun canvas(): CrayonModel = items.getOrPut("canvas") {
        model { box(CANVAS, 0f, 0.47f, 0.12f, 0.5f, 0.56f, 0.03f, round = 0.008f) }
    }

    /** One clock hand, pivot at its base, pointing up (+Y) and facing +Z. */
    fun hand(length: Float): CrayonModel = items.getOrPut("hand/$length") {
        model { box(HAND, 0f, -0.02f, 0f, 0.025f, length, 0.01f, round = 0.005f, outline = 0f) }
    }

    /** A square of a picture, [size] wide, facing +Z, or lying face up when [flat], for the paper shader. */
    fun quad(size: Float, flat: Boolean): Mesh {
        val h = size / 2
        return PaperMesh().apply {
            if (flat) rect(floatArrayOf(-h, 0f, h, h, 0f, h, h, 0f, -h, -h, 0f, -h), FULL_UV, Color.WHITE)
            else rect(floatArrayOf(-h, -h, 0f, h, -h, 0f, h, h, 0f, -h, h, 0f), FULL_UV, Color.WHITE)
        }.mesh()
    }

    /**
     * A wall shelf of what he owns to read and play, back at z = 0 like [wallItem]s: [books] as
     * spines on the top board and [records] as sleeves leaning on the bottom one, each item id its
     * own colour. Built once per set of contents.
     */
    fun shelf(books: List<String>, records: List<String>): CrayonModel = items.getOrPut("shelf/${books.joinToString(",")}/${records.joinToString(",")}") {
        model {
            for (y in listOf(SHELF_LOW, SHELF_HIGH)) {
                box(WOOD, 0f, y, SHELF_DEPTH / 2, SHELF_WIDTH, 0.035f, SHELF_DEPTH, round = 0.012f)
                for (side in listOf(-1f, 1f)) box(WOOD_DARK, side * (SHELF_WIDTH / 2 - 0.1f), y - 0.1f, 0.05f, 0.035f, 0.1f, 0.1f, round = 0.012f)
            }
            lump = 0.002f
            var x = -SHELF_WIDTH / 2 + 0.06f
            for (id in books.take(SHELF_BOOKS)) {
                val k = id.hashCode().mod(SPINES.size)
                val w = 0.055f + 0.012f * (k % 3)
                val h = 0.2f + 0.025f * (k % 4)
                box(SPINES[k], x + w / 2, SHELF_HIGH + 0.035f, 0.1f, w, h, 0.15f, round = 0.008f)
                box(PAPER, x + w / 2, SHELF_HIGH + 0.035f + h * 0.62f, 0.176f, w * 0.7f, 0.022f, 0.004f, round = 0.002f, outline = 0f)
                x += w + 0.006f
            }
            records.take(SHELF_RECORDS).forEachIndexed { i, id ->
                val k = id.hashCode().mod(SLEEVES.size)
                val sx = -SHELF_WIDTH / 2 + 0.2f + i * 0.27f
                box(SLEEVES[k], sx, SHELF_LOW + 0.035f, 0.09f + i * 0.012f, SLEEVE, SLEEVE, 0.014f, rotX = -9f, round = 0.006f)
                cylinder(SPINES[(k + 2) % SPINES.size], sx, SHELF_LOW + 0.035f + SLEEVE * 0.32f, 0.105f + i * 0.012f, 0.12f, 0.004f, 0.12f, rotX = 81f, round = 0.002f, outline = 0.005f)
            }
        }
    }

    /** His guitar, hanging on a peg; back at z = 0 like the [shelf]. */
    fun guitar(): CrayonModel = items.getOrPut("guitar") {
        model {
            lump = 0.002f
            sphere(GUITAR, 0f, 0.82f, 0.06f, 0.4f, 0.36f, 0.09f)
            sphere(GUITAR, 0f, 1.08f, 0.06f, 0.3f, 0.27f, 0.09f)
            cylinder(VINYL, 0f, 1.05f, 0.1f, 0.1f, 0.01f, 0.1f, rotX = 90f, round = 0.002f, outline = 0.004f)
            box(WOOD_DARK, 0f, 0.86f, 0.106f, 0.12f, 0.025f, 0.012f, round = 0.004f, outline = 0.004f)
            box(WOOD_DARK, 0f, 1.2f, 0.07f, 0.055f, 0.42f, 0.035f, round = 0.01f)
            box(WOOD_DARK, 0f, 1.6f, 0.07f, 0.085f, 0.12f, 0.03f, round = 0.012f)
            capsule(KNOB, 0f, 1.62f, 0.02f, 0.035f, 0.05f, 0.035f, rotX = 90f, outline = 0.005f)
        }
    }

    // --- props he holds or works with, in his own units (the guy is scaled up from them) --------------

    /** The colour of book [id]'s spine on the [shelf], so the one in his hands matches; the plain book colour without one. */
    fun bookColor(id: String?): String = id?.let { SPINES[it.hashCode().mod(SPINES.size)] } ?: BOOK

    /** A book held up in both hands, cover out (+Z), the page block showing along the top; pivot at its bottom centre. */
    fun heldBook(color: String): CrayonModel = items.getOrPut("held_book/$color") {
        model {
            box(color, 0f, 0f, 0f, 0.26f, 0.32f, 0.06f, round = 0.012f)
            box(PAPER, 0f, 0.012f, -0.004f, 0.24f, 0.3f, 0.045f, round = 0.006f, outline = 0f)
        }
    }

    /** A book lying open, face up, spine along z at x = 0; pivot at its bottom centre. */
    fun openBook(color: String): CrayonModel = items.getOrPut("open_book/$color") {
        model {
            lump = 0.001f
            box(color, 0f, 0f, 0f, 0.27f, 0.012f, 0.19f, round = 0.004f)
            for (side in listOf(-1f, 1f)) box(PAPER, side * 0.064f, 0.01f, 0f, 0.125f, 0.014f, 0.175f, rotZ = side * 4f, round = 0.003f, outline = 0.004f)
        }
    }

    /** One page of an [openBook], lying flat to the spine's right; the view turns it about z to the left. */
    fun page(): CrayonModel = items.getOrPut("page") {
        model { box(PAPER, 0.062f, 0f, 0f, 0.124f, 0.003f, 0.17f, round = 0.001f, outline = 0.004f, lump = 0f) }
    }

    /** His guitar held to play: the body at the origin facing +Z, the neck out along -x. */
    fun heldGuitar(): CrayonModel = items.getOrPut("held_guitar") {
        model {
            lump = 0.002f
            sphere(GUITAR, 0.04f, -0.15f, 0f, 0.3f, 0.27f, 0.07f)
            sphere(GUITAR, -0.13f, -0.11f, 0f, 0.22f, 0.2f, 0.07f)
            cylinder(VINYL, -0.06f, -0.05f, 0.03f, 0.075f, 0.01f, 0.075f, rotX = 90f, round = 0.002f, outline = 0.004f)
            box(WOOD_DARK, 0.06f, -0.06f, 0.04f, 0.02f, 0.09f, 0.01f, round = 0.004f, outline = 0.004f)
            box(WOOD_DARK, -0.4f, -0.04f, 0.02f, 0.34f, 0.04f, 0.026f, round = 0.008f)
            box(WOOD_DARK, -0.62f, -0.055f, 0.02f, 0.1f, 0.065f, 0.024f, round = 0.01f)
        }
    }

    /** A music note, its head at the origin, facing +Z. */
    fun note(color: String): CrayonModel = items.getOrPut("note/$color") {
        model {
            lump = 0f
            sphere(color, 0f, -0.03f, 0f, 0.075f, 0.06f, 0.03f, outline = 0.005f)
            box(color, 0.03f, -0.005f, 0f, 0.016f, 0.15f, 0.016f, round = 0.004f, outline = 0.005f)
            box(color, 0.055f, 0.12f, 0f, 0.05f, 0.022f, 0.014f, rotZ = -30f, round = 0.004f, outline = 0.005f)
        }
    }

    /** The record on the player, centred on its spindle, with a stripe on the label so it shows turning. */
    fun disc(): CrayonModel = items.getOrPut("disc") {
        model {
            cylinder(VINYL, 0f, 0f, 0f, 0.3f, 0.02f, 0.3f, round = 0.006f)
            cylinder(RECORD_LABEL, 0f, 0.015f, 0f, 0.1f, 0.012f, 0.1f, round = 0.004f, outline = 0.005f)
            box(PAPER, 0.025f, 0.026f, 0f, 0.045f, 0.004f, 0.014f, round = 0.002f, outline = 0f, lump = 0f)
        }
    }

    /** Centre x of the back-wall shelf, right of the window above the record player's place, and of the guitar, between the clock and the poster. */
    fun shelfX(map: TileMap): Float = map.width * 0.85f
    fun guitarX(map: TileMap): Float = map.width * 0.37f

    /** Centre x of the back-wall clock, left of centre. */
    fun clockX(map: TileMap): Float = map.width * 0.27f

    /** Centre x of the back-wall window: the widest stretch of back wall with nothing in it, right of centre. */
    fun window(map: TileMap): Float = map.width * 0.7f

    private fun model(body: PartList.() -> Unit): CrayonModel = CrayonModel.of(PartList().apply(body).parts, paint::texture)

    // --- the room ------------------------------------------------------------------------------

    private fun buildRoom(map: TileMap, light: Vector3): Room {
        val floor = PaperMesh()
        val walls = PaperMesh()
        val section = PaperMesh()
        val front = map.height - 1
        for (y in 0 until map.height) for (x in 0 until map.width) {
            when (map[x, y]) {
                Terrain.Floor -> floorTile(floor, map, x, y, faceShade(0f, 1f, 0f, light))
                Terrain.Wall -> wallTile(walls, section, map, x, y, if (y == front) LIP else WALL_HEIGHT, light)
                Terrain.Void -> Unit
            }
        }
        val glass = PaperMesh().apply {
            val w = WINDOW_WIDTH / 2
            val h = (WINDOW_TOP - WINDOW_BOTTOM) / 2
            rect(floatArrayOf(-w, -h, 0f, w, -h, 0f, w, h, 0f, -w, h, 0f), FULL_UV, Color.WHITE)
        }.mesh()
        return Room(
            listOf(
                floor.mesh() to paint.texture(RoomPaint.FLOOR)!!,
                walls.mesh() to paint.texture(RoomPaint.WALL)!!,
                section.mesh() to paint.texture(RoomPaint.SECTION)!!,
            ),
            model { fixtures(map) },
            glass,
        )
    }

    /** The back wall's window frame, curtains and rod, and the clock (its hands are the view's, [hand]). */
    private fun PartList.fixtures(map: TileMap) {
        val wx = window(map)
        val tall = WINDOW_TOP - WINDOW_BOTTOM
        box(TRIM, wx, WINDOW_BOTTOM - 0.07f, 1.0f, WINDOW_WIDTH + 0.18f, 0.08f, 0.12f)
        box(TRIM, wx, WINDOW_TOP, 1.0f, WINDOW_WIDTH + 0.12f, 0.07f, 0.05f)
        box(TRIM, wx - WINDOW_WIDTH / 2 - 0.03f, WINDOW_BOTTOM, 1.0f, 0.07f, tall, 0.05f)
        box(TRIM, wx + WINDOW_WIDTH / 2 + 0.03f, WINDOW_BOTTOM, 1.0f, 0.07f, tall, 0.05f)
        box(TRIM, wx, WINDOW_BOTTOM, 1.0f, 0.045f, tall, 0.035f, outline = 0.008f)
        box(TRIM, wx, WINDOW_BOTTOM + tall / 2 - 0.02f, 1.0f, WINDOW_WIDTH, 0.045f, 0.035f, outline = 0.008f)
        // Curtains, gathered and tied back, and a rod with knobs on.
        lump = 0.01f
        for (side in listOf(-1f, 1f)) {
            val cx = wx + side * (WINDOW_WIDTH / 2 + 0.1f)
            box(CURTAIN, cx, WINDOW_BOTTOM - 0.25f, 1.07f, 0.28f, tall + 0.36f, 0.07f, round = 0.03f)
            capsule(CURTAIN_TIE, cx - 0.16f, WINDOW_BOTTOM + 0.15f, 1.11f, 0.07f, 0.32f, 0.05f, rotZ = -90f)
        }
        lump = 0f
        cylinder(WOOD_DARK, wx - WINDOW_WIDTH / 2 - 0.35f, WINDOW_TOP + 0.13f, 1.08f, 0.035f, WINDOW_WIDTH + 0.7f, 0.035f, rotZ = -90f)
        for (side in listOf(-1f, 1f)) sphere(WOOD_DARK, wx + side * (WINDOW_WIDTH / 2 + 0.37f), WINDOW_TOP + 0.1f, 1.08f, 0.07f, 0.07f, 0.07f)
        // The clock: a round box whose front cap is the painted dial.
        cylinder("#ffffff", clockX(map), CLOCK_Y, 1.0f, 0.42f, 0.05f, 0.42f, rotX = 90f, round = 0.015f, texture = "clock")
    }

    /** A floor tile, on a quarter-metre grid so the shade where floor meets wall can be laid on per vertex. */
    private fun floorTile(m: PaperMesh, map: TileMap, x: Int, y: Int, lit: Color) {
        val n = FLOOR_GRID
        val c = Array(4) { Color() }
        val p = FloatArray(12)
        val uv = FloatArray(8)
        for (j in 0 until n) for (i in 0 until n) {
            val x0 = x + i.toFloat() / n
            val x1 = x + (i + 1f) / n
            val z0 = y + j.toFloat() / n
            val z1 = y + (j + 1f) / n
            // Corners in the order the face wants them: (x0, z1), (x1, z1), (x1, z0), (x0, z0).
            val xs = floatArrayOf(x0, x1, x1, x0)
            val zs = floatArrayOf(z1, z1, z0, z0)
            for (k in 0..3) {
                p[3 * k] = xs[k]; p[3 * k + 1] = 0f; p[3 * k + 2] = zs[k]
                uv[2 * k] = xs[k] / RoomPaint.FLOOR_METRES; uv[2 * k + 1] = zs[k] / RoomPaint.FLOOR_METRES
                val near = MathUtils.clamp(nearestWall(map, xs[k], zs[k]) / CORNER_SHADE_REACH, 0f, 1f)
                c[k].set(CORNER_SHADE).lerp(Color.WHITE, near * near * (3 - 2 * near)).mul(lit)
            }
            m.rect(p, uv, c)
        }
    }

    /** How far point ([px], [pz]) is from the nearest tile that isn't floor, looking one tile around. */
    private fun nearestWall(map: TileMap, px: Float, pz: Float): Float {
        var best = Float.MAX_VALUE
        val cx = px.toInt()
        val cz = pz.toInt()
        for (tz in cz - 1..cz + 1) for (tx in cx - 1..cx + 1) {
            if (map[tx, tz] == Terrain.Floor) continue
            val dx = max(max(tx - px, 0f), px - (tx + 1))
            val dz = max(max(tz - pz, 0f), pz - (tz + 1))
            best = minOf(best, hypot(dx, dz))
        }
        return best
    }

    /**
     * A wall tile: a thin slab along each side that faces the floor, so the room reads as a cutaway
     * dollhouse. The face into the room carries the painted wall (or its bottom strip, on the low
     * front lip); the top and the other faces are the cut section. Slabs run [THICK] past both
     * ends of the tile, so they meet at the corners.
     */
    private fun wallTile(walls: PaperMesh, section: PaperMesh, map: TileMap, x: Int, y: Int, h: Float, light: Vector3) {
        for ((dx, dy) in SIDES) {
            if (map[x + dx, y + dy] != Terrain.Floor) continue
            slab(section, x, y, dx, dy, -THICK, 1 + THICK, THICK, h, light)
            val f = Face(x, y, dx, dy)
            val a0 = f.along(-THICK)
            val a1 = f.along(1 + THICK)
            val u0 = (if (dx != 0) f.pz(-THICK, 0f) else f.px(-THICK, 0f)) / RoomPaint.WALL_METRES
            val u1 = (if (dx != 0) f.pz(1 + THICK, 0f) else f.px(1 + THICK, 0f)) / RoomPaint.WALL_METRES
            val v0 = 1f
            val v1 = 1 - h / WALL_HEIGHT
            walls.rect(
                floatArrayOf(a0[0], 0f, a0[1], a1[0], 0f, a1[1], a1[0], h, a1[1], a0[0], h, a0[1]),
                floatArrayOf(u0, v0, u1, v0, u1, v1, u0, v1),
                faceShade(dx.toFloat(), 0f, dy.toFloat(), light),
            )
        }
    }

    /**
     * The cut section of a wall slab behind the face of tile (x, y) toward (dx, dy): its top,
     * back and two ends, [thick] deep, spanning [s0]..[s1] along the face.
     */
    private fun slab(m: PaperMesh, x: Int, y: Int, dx: Int, dy: Int, s0: Float, s1: Float, thick: Float, h: Float, light: Vector3) {
        val f = Face(x, y, dx, dy)
        val nx = dx.toFloat()
        val nz = dy.toFloat()
        val k = 1 / RoomPaint.SECTION_METRES
        fun px(s: Float, d: Float) = f.px(s, d)
        fun pz(s: Float, d: Float) = f.pz(s, d)
        fun top(s: Float, d: Float) = floatArrayOf(px(s, d) * k, pz(s, d) * k)
        val t00 = top(s0, 0f); val t10 = top(s1, 0f); val t11 = top(s1, thick); val t01 = top(s0, thick)
        m.rect(
            floatArrayOf(px(s0, 0f), h, pz(s0, 0f), px(s1, 0f), h, pz(s1, 0f), px(s1, thick), h, pz(s1, thick), px(s0, thick), h, pz(s0, thick)),
            floatArrayOf(t00[0], t00[1], t10[0], t10[1], t11[0], t11[1], t01[0], t01[1]),
            faceShade(0f, 1f, 0f, light),
        )
        val len = (s1 - s0) * k
        val hk = h * k
        val tk = thick * k
        m.rect(
            floatArrayOf(px(s1, thick), 0f, pz(s1, thick), px(s0, thick), 0f, pz(s0, thick), px(s0, thick), h, pz(s0, thick), px(s1, thick), h, pz(s1, thick)),
            floatArrayOf(0f, hk, len, hk, len, 0f, 0f, 0f),
            faceShade(-nx, 0f, -nz, light),
        )
        m.rect(
            floatArrayOf(px(s1, 0f), 0f, pz(s1, 0f), px(s1, thick), 0f, pz(s1, thick), px(s1, thick), h, pz(s1, thick), px(s1, 0f), h, pz(s1, 0f)),
            floatArrayOf(0f, hk, tk, hk, tk, 0f, 0f, 0f),
            faceShade(f.rx, 0f, f.rz, light),
        )
        m.rect(
            floatArrayOf(px(s0, thick), 0f, pz(s0, thick), px(s0, 0f), 0f, pz(s0, 0f), px(s0, 0f), h, pz(s0, 0f), px(s0, thick), h, pz(s0, thick)),
            floatArrayOf(0f, hk, tk, hk, tk, 0f, 0f, 0f),
            faceShade(-f.rx, 0f, -f.rz, light),
        )
    }

    /** The face of wall tile (x, y) toward (dx, dy): points along it (s, 0..1 left to right from the room) and back into the wall (d). */
    private class Face(x: Int, y: Int, dx: Int, dy: Int) {
        val nx = dx.toFloat()
        val nz = dy.toFloat()
        val cx = x + 0.5f + nx * 0.5f
        val cz = y + 0.5f + nz * 0.5f
        val rx = nz
        val rz = -nx
        fun px(s: Float, d: Float) = cx + rx * (s - 0.5f) - nx * d
        fun pz(s: Float, d: Float) = cz + rz * (s - 0.5f) - nz * d
        fun along(s: Float) = floatArrayOf(px(s, 0f), pz(s, 0f))
    }

    /**
     * The shade a side facing (nx, ny, nz) gets from the world [light]: the crayon shader's two tones,
     * worked out once per side, since the paper shader does no lighting of its own.
     */
    private fun faceShade(nx: Float, ny: Float, nz: Float, light: Vector3): Color {
        val ndl = nx * light.x + ny * light.y + nz * light.z
        val t = MathUtils.clamp((ndl + 0.12f + 0.03f) / 0.06f, 0f, 1f)
        return Color(SHADE).lerp(Color.WHITE, t * t * (3 - 2 * t))
    }

    // --- items ---------------------------------------------------------------------------------

    private fun PartList.buildItem(id: String, kind: String) {
        lump = 0.003f
        when (id) {
            "bed" -> {
                for (sx in listOf(-0.4f, 0.4f)) for (sz in listOf(-0.42f, 0.42f)) box(WOOD_DARK, sx, 0f, sz, 0.085f, 0.16f, 0.085f, round = 0.03f)
                box(WOOD, 0f, 0.12f, 0f, 0.94f, 0.1f, 0.98f, round = 0.035f)
                box(SHEET, 0f, 0.2f, 0.01f, 0.88f, 0.12f, 0.92f, round = 0.05f)
                box(BLANKET, 0f, 0.225f, 0.17f, 0.93f, 0.1f, 0.62f, round = 0.045f, lump = 0.006f)
                box(BLANKET_STRIPE, 0f, 0.235f, -0.12f, 0.94f, 0.1f, 0.08f, round = 0.04f, lump = 0.006f)
                sphere(PILLOW, 0f, 0.27f, -0.3f, 0.6f, 0.15f, 0.27f, lump = 0.008f)
                box(WOOD, 0f, 0f, -0.47f, 0.98f, 0.7f, 0.07f, round = 0.03f)
                capsule(WOOD_LIGHT, -0.52f, 0.7f, -0.47f, 0.08f, 1.04f, 0.1f, rotZ = -90f)
            }
            "desk" -> {
                for (sx in listOf(-0.42f, 0.42f)) for (sz in listOf(-0.24f, 0.24f)) box(WOOD_DARK, sx, 0f, sz, 0.065f, 0.5f, 0.065f, round = 0.025f)
                box(WOOD, 0f, 0.5f, 0f, 0.98f, 0.065f, 0.62f, round = 0.03f)
                box(WOOD_LIGHT, 0.24f, 0.31f, 0f, 0.42f, 0.18f, 0.56f, round = 0.03f)
                sphere(KNOB, 0.24f, 0.38f, 0.28f, 0.045f, 0.045f, 0.03f)
                // His sketchbook's page, at the end by the chair; the view lays the sketch under way or his newest on it.
                box(PAPER, SKETCH_X, 0.562f, SKETCH_Z, 0.28f, 0.004f, 0.2f, round = 0.002f, outline = 0f, lump = 0f)
                cylinder(MUG, 0.18f, 0.565f, 0.12f, 0.09f, 0.1f, 0.09f, round = 0.015f)
                capsule(MUG, 0.235f, 0.585f, 0.12f, 0.025f, 0.06f, 0.025f)
                // A little lamp.
                cylinder(WOOD_DARK, -0.34f, 0.565f, -0.18f, 0.15f, 0.035f, 0.15f, round = 0.015f)
                cylinder(WOOD_DARK, -0.34f, 0.58f, -0.18f, 0.025f, 0.24f, 0.025f)
                cylinder(LAMPSHADE, -0.34f, 0.78f, -0.18f, 0.26f, 0.16f, 0.26f, taper = 0.62f, round = 0.025f)
            }
            "chair" -> {
                for (sx in listOf(-0.16f, 0.16f)) for (sz in listOf(-0.16f, 0.16f)) box(WOOD_DARK, sx, 0f, sz, 0.055f, 0.3f, 0.055f, round = 0.022f)
                box(WOOD, 0f, 0.29f, 0f, 0.44f, 0.05f, 0.44f, round = 0.02f)
                box(CUSHION, 0f, 0.32f, 0f, 0.38f, 0.07f, 0.38f, round = 0.035f, lump = 0.006f)
                box(WOOD, -0.195f, 0.3f, 0f, 0.05f, 0.44f, 0.42f, round = 0.022f)
            }
            "easel" -> {
                box(WOOD, -0.22f, 0f, 0.08f, 0.05f, 1.15f, 0.05f, rotZ = -8f, round = 0.02f)
                box(WOOD, 0.22f, 0f, 0.08f, 0.05f, 1.15f, 0.05f, rotZ = 8f, round = 0.02f)
                box(WOOD_DARK, 0f, 0f, -0.2f, 0.05f, 1.1f, 0.05f, rotX = -14f, round = 0.02f)
                // The ledge a canvas stands on ([canvas]); the easel is bare without one.
                box(WOOD, 0f, 0.42f, 0.12f, 0.58f, 0.05f, 0.09f, round = 0.02f)
            }
            "record_player" -> {
                box(WOOD_DARK, 0f, 0f, 0f, 0.8f, 0.5f, 0.46f, round = 0.035f)
                box(WOOD, 0f, 0.08f, 0.225f, 0.68f, 0.32f, 0.03f, round = 0.015f)
                box(WOOD_LIGHT, 0f, 0.5f, 0f, 0.62f, 0.06f, 0.4f, round = 0.025f)
                // The record is its own model ([disc], at [DISC_X], [DISC_Y]), so the view can turn it.
                capsule(TRIM, 0.2f, 0.575f, -0.19f, 0.03f, 0.22f, 0.03f, rotX = 90f)
            }
            "plant" -> {
                cylinder(POT, 0f, 0f, 0f, 0.28f, 0.28f, 0.28f, taper = 1.2f, round = 0.02f)
                cylinder(POT_RIM, 0f, 0.25f, 0f, 0.37f, 0.07f, 0.37f, round = 0.03f)
                lump = 0.02f
                sphere(LEAF_DARK, 0f, 0.27f, 0f, 0.42f, 0.32f, 0.42f)
                sphere(LEAF, -0.11f, 0.42f, 0.05f, 0.27f, 0.27f, 0.27f)
                sphere(LEAF, 0.12f, 0.45f, -0.02f, 0.25f, 0.29f, 0.25f)
                sphere(LEAF_LIGHT, 0f, 0.58f, 0.04f, 0.21f, 0.23f, 0.21f)
            }
            "rug" -> cylinder("#ffffff", 0f, 0f, 0f, 1.9f, 0.02f, 1.3f, round = 0.008f, texture = "rug", outline = 0.008f, lump = 0f)
            "mail_hatch" -> {
                // A brass flap in the wall at hand height, with a little shelf under it for parcels.
                box(TRIM, 0f, 0.62f, 0.02f, 0.64f, 0.52f, 0.05f, round = 0.02f)
                box(WOOD_DARK, 0f, 0.66f, 0.045f, 0.54f, 0.42f, 0.02f, round = 0.01f, outline = 0.007f)
                box(KNOB, 0f, 0.74f, 0.06f, 0.46f, 0.28f, 0.03f, round = 0.015f)
                capsule(WOOD_DARK, -0.17f, 0.95f, 0.08f, 0.03f, 0.34f, 0.025f, rotZ = -90f, outline = 0.006f)
                box(WOOD_DARK, 0f, 0.8f, 0.08f, 0.14f, 0.03f, 0.03f, round = 0.012f, outline = 0.006f)
                box(WOOD, 0f, 0.5f, 0.12f, 0.72f, 0.045f, 0.24f, round = 0.02f)
                box(WOOD_DARK, -0.28f, 0.36f, 0.04f, 0.045f, 0.14f, 0.08f, round = 0.018f)
                box(WOOD_DARK, 0.28f, 0.36f, 0.04f, 0.045f, 0.14f, 0.08f, round = 0.018f)
            }
            // A rail along the wall, a little wider than its tile; the view hangs frames from it.
            "picture_rail" -> capsule(WOOD_DARK, RAIL_X - RAIL_LENGTH / 2, RAIL_Y + 0.02f, 0.03f, 0.05f, RAIL_LENGTH, 0.05f, rotZ = -90f)
            "poster" -> {
                box("#ffffff", 0f, 0.95f, 0.01f, 0.62f, 0.82f, 0.02f, round = 0.006f, texture = "poster", lump = 0f)
                sphere(CURTAIN, 0f, 1.71f, 0.03f, 0.05f, 0.05f, 0.03f)
            }
            else -> when (kind) {
                "door", "fixture" -> {
                    box(TRIM, 0f, 0f, 0.02f, 0.94f, 1.68f, 0.05f, round = 0.02f)
                    box(DOOR, 0f, 0f, 0.05f, 0.8f, 1.6f, 0.05f, round = 0.02f)
                    box(WOOD_DARK, 0f, 0.92f, 0.08f, 0.6f, 0.5f, 0.02f, round = 0.015f, outline = 0.007f)
                    box(WOOD_DARK, 0f, 0.22f, 0.08f, 0.6f, 0.55f, 0.02f, round = 0.015f, outline = 0.007f)
                    sphere(KNOB, 0.3f, 0.78f, 0.1f, 0.075f, 0.075f, 0.075f)
                }
                "key" -> {
                    cylinder(KNOB, -0.08f, 0.02f, 0f, 0.16f, 0.04f, 0.16f)
                    box(KNOB, 0.07f, 0.02f, 0f, 0.2f, 0.04f, 0.05f)
                    box(KNOB, 0.14f, 0.02f, 0.04f, 0.04f, 0.04f, 0.06f)
                }
                "apple" -> {
                    sphere(APPLE, 0f, 0f, 0f, 0.24f, 0.22f, 0.24f, lump = 0.006f)
                    capsule(WOOD_DARK, 0f, 0.2f, 0f, 0.02f, 0.06f, 0.02f, outline = 0.005f)
                    sphere(LEAF, 0.05f, 0.23f, 0f, 0.09f, 0.025f, 0.05f, outline = 0.005f)
                }
                "book" -> {
                    box(BOOK, 0f, 0f, 0f, 0.3f, 0.07f, 0.4f, round = 0.015f)
                    box(PAPER, 0.02f, 0.01f, 0f, 0.27f, 0.05f, 0.38f, round = 0.01f, outline = 0f)
                }
                "exit" -> box(EXIT, 0f, 0f, 0f, 0.9f, 0.01f, 0.9f, round = 0.004f, outline = 0.006f, lump = 0f)
                else -> box(UNKNOWN, 0f, 0f, 0f, 0.4f, 0.4f, 0.4f, round = 0.05f)
            }
        }
    }

    override fun dispose() {
        built?.dispose()
        items.values.forEach { it.dispose() }
        items.clear()
        paint.dispose()
    }

    /** A flat-shaded mesh being built for the paper shader: position, uv and colour per vertex, quads only. */
    private class PaperMesh {
        private val v = ArrayList<Float>()
        private val idx = ArrayList<Short>()

        /** A quad of corners 00, 10, 11, 01 (counter-clockwise from the front) in [p], with [uv] and one colour. */
        fun rect(p: FloatArray, uv: FloatArray, c: Color) = rect(p, uv, Array(4) { c })

        fun rect(p: FloatArray, uv: FloatArray, c: Array<Color>) {
            val base = v.size / STRIDE
            for (k in 0..3) {
                v += p[3 * k]; v += p[3 * k + 1]; v += p[3 * k + 2]
                v += uv[2 * k]; v += uv[2 * k + 1]
                v += c[k].r; v += c[k].g; v += c[k].b; v += 1f
            }
            for (k in intArrayOf(0, 1, 2, 2, 3, 0)) idx += (base + k).toShort()
        }

        fun mesh(): Mesh {
            check(v.size / STRIDE <= 0xffff) { "a paper mesh has more vertices than 16-bit indices reach" }
            return Mesh(true, v.size / STRIDE, idx.size, VertexAttribute.Position(), VertexAttribute.TexCoords(0), VertexAttribute.ColorUnpacked()).apply {
                setVertices(v.toFloatArray())
                setIndices(idx.toShortArray())
            }
        }

        private companion object {
            const val STRIDE = 9
        }
    }

    companion object {
        const val WALL_HEIGHT = 2.1f
        const val LIP = 0.14f
        const val WINDOW_WIDTH = 1.3f
        const val WINDOW_BOTTOM = 1.0f
        const val WINDOW_TOP = 1.8f
        const val CLOCK_Y = 1.32f
        /** Where the desk's page is, from the desk's pivot. */
        const val SKETCH_X = -0.24f
        const val SKETCH_Z = 0.06f
        const val CANVAS_Y = 0.75f
        const val CANVAS_Z = 0.135f
        /** The record's spindle on the player, from its pivot. */
        const val DISC_X = -0.06f
        const val DISC_Y = 0.555f
        /** Where the book he studies lies open on the desk, from its pivot: between the sketchbook and the mug. */
        const val STUDY_BOOK_X = 0.0f
        const val STUDY_BOOK_Y = 0.565f
        const val STUDY_BOOK_Z = -0.02f
        const val FRAME = 0.44f
        const val FRAME_ART = 0.34f
        const val FRAME_ART_Z = 0.04f
        /** Where the picture rail runs, from its tile's centre, and the frames' centres along it. */
        const val RAIL_X = 0f
        const val RAIL_Y = 1.86f
        const val RAIL_LENGTH = 1.5f
        val FRAME_XS = floatArrayOf(-0.46f, 0f, 0.46f)
        /** The shelf's boards, in metres up from the floor, and how much fits on them. */
        const val SHELF_LOW = 1.0f
        const val SHELF_HIGH = 1.4f
        private const val SHELF_WIDTH = 1.1f
        private const val SHELF_DEPTH = 0.2f
        private const val SLEEVE = 0.3f
        private const val SHELF_BOOKS = 12
        private const val SHELF_RECORDS = 3
        const val FRAME_BOTTOM = 1.2f
        /** The bands of the painted wall, in metres up from the floor. */
        const val BASEBOARD = 0.1f
        const val WAINSCOT = 0.75f
        const val RAIL_TOP = 0.8f
        private val WALL_ITEMS = setOf("poster", "door", "mail_hatch", "picture_rail")
        private const val THICK = 0.14f
        private const val FLOOR_GRID = 4
        /** How far from a wall the floor's corner shade fades out, and what it multiplies by at the wall. */
        private const val CORNER_SHADE_REACH = 0.4f
        private val CORNER_SHADE = Color.valueOf("d9c9dcff")
        /** The shaded side's tone, as the crayon shader's (sRGB). */
        private val SHADE = Color.valueOf("e2d2ecff")
        /** (dx, dy) to each neighbouring tile. */
        private val SIDES = listOf(0 to 1, 0 to -1, 1 to 0, -1 to 0)
        private val FULL_UV = floatArrayOf(0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f)

        // Pastels, a little softer than the old flat colours, since ink outlines do the separating now.
        const val TRIM = "#fff8ee"
        private const val WOOD = "#dba77c"
        private const val WOOD_LIGHT = "#edc49c"
        private const val WOOD_DARK = "#b47e5e"
        private const val SHEET = "#fff7ea"
        private const val BLANKET = "#9fc1ef"
        private const val BLANKET_STRIPE = "#ffe08a"
        private const val PILLOW = "#fffaf2"
        private const val PAPER = "#fdf9ef"
        private const val MUG = "#f2928a"
        private const val LAMPSHADE = "#ffe3a1"
        private const val CUSHION = "#f4a3ad"
        private const val CANVAS = "#fdf8ee"
        private const val VINYL = "#4a4255"
        private const val RECORD_LABEL = "#f2928a"
        private const val POT = "#e9a07c"
        private const val POT_RIM = "#f2b594"
        private const val LEAF = "#9fd49a"
        private const val LEAF_DARK = "#80c088"
        private const val LEAF_LIGHT = "#c2e6a8"
        private const val POSTER_SUN = "#ffd86a"
        private const val DOOR = "#d6a27a"
        private const val KNOB = "#f5cf6a"
        private const val APPLE = "#ef7b7b"
        private const val BOOK = "#8fb0ec"
        private const val EXIT = "#8fdcaa"
        private const val CURTAIN = "#f6b2b7"
        private const val CURTAIN_TIE = "#fde9c6"
        private const val HAND = "#4a3a4f"
        private const val UNKNOWN = "#d6aee6"
        private const val GUITAR = "#f0b46e"
        private val SPINES = listOf("#8fb0ec", "#f2928a", "#9fd49a", "#ffd86a", "#d6aee6", "#f6b2b7", "#9fd3d9")
        private val SLEEVES = listOf("#f4a3ad", "#9fc1ef", "#c2e6a8", "#ffe3a1", "#c9b2ec")
    }
}
