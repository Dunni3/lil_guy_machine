package dev.lilguy.app

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Mesh
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.VertexAttribute
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.utils.Disposable
import dev.lilguy.parts.Part
import dev.lilguy.parts.RoomParts
import dev.lilguy.sim.Terrain
import dev.lilguy.sim.TileMap
import kotlin.math.hypot
import kotlin.math.max

/**
 * The 3D room in the hand-drawn look: furniture from its part lists ([RoomParts], drawn by
 * [CrayonRenderer]), each built into meshes once, and the floor and walls as flat meshes carrying
 * painted textures ([RoomPaint]), drawn with the cheap paper shader. No asset files.
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
    fun item(id: String, kind: String): CrayonModel = cached("$kind/$id") { RoomParts.item(id, kind) }

    fun wallItem(id: String, kind: String): Boolean = RoomParts.wallItem(id, kind)

    fun flat(id: String, kind: String): Boolean = RoomParts.flat(id, kind)

    fun frame(): CrayonModel = cached("frame") { RoomParts.frame() }

    fun canvas(): CrayonModel = cached("canvas") { RoomParts.canvas() }

    fun hand(length: Float): CrayonModel = cached("hand/$length") { RoomParts.hand(length) }

    /** A square of a picture, [size] wide, facing +Z, or lying face up when [flat], for the paper shader. */
    fun quad(size: Float, flat: Boolean): Mesh {
        val h = size / 2
        return PaperMesh().apply {
            if (flat) rect(floatArrayOf(-h, 0f, h, h, 0f, h, h, 0f, -h, -h, 0f, -h), FULL_UV, Color.WHITE)
            else rect(floatArrayOf(-h, -h, 0f, h, -h, 0f, h, h, 0f, -h, h, 0f), FULL_UV, Color.WHITE)
        }.mesh()
    }

    /** Built once per set of contents. */
    fun shelf(books: List<String>, records: List<String>): CrayonModel =
        cached("shelf/${books.joinToString(",")}/${records.joinToString(",")}") { RoomParts.shelf(books, records) }

    fun guitar(): CrayonModel = cached("guitar") { RoomParts.guitar() }

    // --- props he holds or works with ---------------------------------------------------------------

    fun bookColor(id: String?): String = RoomParts.bookColor(id)

    fun heldBook(color: String): CrayonModel = cached("held_book/$color") { RoomParts.heldBook(color) }

    fun heldGuitar(): CrayonModel = cached("held_guitar") { RoomParts.heldGuitar() }

    /** [color] is 0xRRGGBB. */
    fun tool(color: Int, brush: Boolean): CrayonModel = cached("tool/$brush/$color") { RoomParts.tool(hex(color), brush) }

    /** [colors] are 0xRRGGBB. */
    fun palette(colors: List<Int>): CrayonModel = cached("palette/${colors.joinToString(",")}") { RoomParts.palette(colors.map(::hex)) }

    /** [color] is 0xRRGGBB. */
    fun sparkle(color: Int): CrayonModel = cached("sparkle/$color") { RoomParts.sparkle(hex(color)) }

    private fun hex(rgb: Int) = "#%06x".format(rgb and 0xffffff)

    fun note(color: String): CrayonModel = cached("note/$color") { RoomParts.note(color) }

    fun disc(): CrayonModel = cached("disc") { RoomParts.disc() }

    /** Centre x of the back-wall shelf, right of the window above the record player's place, and of the guitar, between the clock and the poster. */
    fun shelfX(map: TileMap): Float = map.width * RoomParts.SHELF_AT
    fun guitarX(map: TileMap): Float = map.width * RoomParts.GUITAR_AT

    /** Centre x of the back-wall clock, left of centre. */
    fun clockX(map: TileMap): Float = map.width * RoomParts.CLOCK_AT

    /** Centre x of the back-wall window: the widest stretch of back wall with nothing in it, right of centre. */
    fun window(map: TileMap): Float = map.width * RoomParts.WINDOW_AT

    private fun cached(key: String, parts: () -> List<Part>): CrayonModel = items.getOrPut(key) { model(parts()) }

    private fun model(parts: List<Part>): CrayonModel = CrayonModel.of(parts, paint::texture)

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
            model(RoomParts.fixtures(window(map), clockX(map))),
            glass,
        )
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

    private companion object {
        const val WALL_HEIGHT = RoomParts.WALL_HEIGHT
        const val LIP = RoomParts.LIP
        const val WINDOW_WIDTH = RoomParts.WINDOW_WIDTH
        const val WINDOW_BOTTOM = RoomParts.WINDOW_BOTTOM
        const val WINDOW_TOP = RoomParts.WINDOW_TOP
        const val THICK = 0.14f
        const val FLOOR_GRID = 4
        /** How far from a wall the floor's corner shade fades out, and what it multiplies by at the wall. */
        const val CORNER_SHADE_REACH = 0.4f
        val CORNER_SHADE = Color.valueOf("d9c9dcff")
        /** The shaded side's tone, as the crayon shader's (sRGB). */
        val SHADE = Color.valueOf("e2d2ecff")
        /** (dx, dy) to each neighbouring tile. */
        val SIDES = listOf(0 to 1, 0 to -1, 1 to 0, -1 to 0)
        val FULL_UV = floatArrayOf(0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f)
    }
}
