package dev.lilguy.app

import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.utils.Disposable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The hand-drawn look's geometry and shaders, ported from the workshop (`tools/workshop/guy.html`,
 * look A): lumpy spheres, rounded boxes and cylinders with smooth normals, an inverted-hull ink
 * outline per part (three of them, each a little different, cycled so the line boils), and a shader
 * with two tones, pencil hatching, crayon streaks and paper tooth. Seeds and numbers match the
 * workshop, so the guy comes out the same. [CrayonModel] puts parts together; [CrayonRenderer] draws them.
 */
class PartGeometry(val pos: FloatArray, val uv: FloatArray, val idx: ShortArray, val normal: FloatArray = FloatArray(pos.size)) {
    val count get() = pos.size / 3

    /** Normals averaged across every vertex at the same position, so seams don't crack the hull. */
    fun smoothNormals(): PartGeometry {
        val acc = HashMap<Long, FloatArray>()
        val keys = LongArray(count) { key(it) }
        for (t in 0 until idx.size / 3) {
            val a = idx[3 * t].toInt()
            val b = idx[3 * t + 1].toInt()
            val c = idx[3 * t + 2].toInt()
            val ux = pos[3 * b] - pos[3 * a]; val uy = pos[3 * b + 1] - pos[3 * a + 1]; val uz = pos[3 * b + 2] - pos[3 * a + 2]
            val vx = pos[3 * c] - pos[3 * a]; val vy = pos[3 * c + 1] - pos[3 * a + 1]; val vz = pos[3 * c + 2] - pos[3 * a + 2]
            val nx = uy * vz - uz * vy
            val ny = uz * vx - ux * vz
            val nz = ux * vy - uy * vx
            for (i in intArrayOf(a, b, c)) acc.getOrPut(keys[i]) { FloatArray(3) }.let { it[0] += nx; it[1] += ny; it[2] += nz }
        }
        for (i in 0 until count) {
            val n = acc.getValue(keys[i])
            val l = sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]).takeIf { it > 0f } ?: 1f
            normal[3 * i] = n[0] / l; normal[3 * i + 1] = n[1] / l; normal[3 * i + 2] = n[2] / l
        }
        return this
    }

    /**
     * The inverted-hull outline's positions: this part pushed out along its smooth normals by
     * [thickness], given each vertex's position and normal.
     */
    fun hull(thickness: (x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float) -> Float): FloatArray {
        val v = FloatArray(count * 3)
        for (i in 0 until count) {
            val k = thickness(pos[3 * i], pos[3 * i + 1], pos[3 * i + 2], normal[3 * i], normal[3 * i + 1], normal[3 * i + 2])
            for (j in 0..2) v[3 * i + j] = pos[3 * i + j] + normal[3 * i + j] * k
        }
        return v
    }

    /** Pushes each vertex [amp] along its normal, times smooth noise over the part ([seed]); normals stay as they were. */
    fun lump(amp: Float, seed: Int): PartGeometry {
        if (amp == 0f) return this
        var reach = 1e-4f
        for (v in pos) reach = max(reach, abs(v))
        val nz = Noise3(seed, 5, 3.5f)
        for (i in 0 until count) {
            val k = amp * nz(pos[3 * i] / reach, pos[3 * i + 1] / reach, pos[3 * i + 2] / reach)
            for (j in 0..2) pos[3 * i + j] += normal[3 * i + j] * k
        }
        return this
    }

    /**
     * Where the rig hangs off a body: its extent below and above centre, arm height and reach, and how
     * far forward the feet go so they peek out from under a wide bottom.
     */
    fun measure(): BodyMeasure {
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (i in 0 until count) { lo = minOf(lo, pos[3 * i + 1]); hi = max(hi, pos[3 * i + 1]) }
        val armY = lo + 0.42f * (hi - lo)
        var armX = 0f
        var front = 0f
        for (i in 0 until count) {
            val x = abs(pos[3 * i])
            val y = pos[3 * i + 1]
            if (abs(y - armY) < 0.025f) armX = max(armX, x)
            if (y < lo + 0.05f && x < 0.15f) front = max(front, pos[3 * i + 2])
        }
        return BodyMeasure(bottom = -lo, top = hi, armY = armY, armX = armX, footZ = max(0.08f, front - 0.03f))
    }

    private fun key(i: Int): Long {
        val x = (pos[3 * i] * 1e4f).roundToInt().toLong() and 0x1fffff
        val y = (pos[3 * i + 1] * 1e4f).roundToInt().toLong() and 0x1fffff
        val z = (pos[3 * i + 2] * 1e4f).roundToInt().toLong() and 0x1fffff
        return (x shl 42) or (y shl 21) or z
    }
}

/** [armY] is relative to the body's centre, like [bottom] and [top] (below centre is negative). */
class BodyMeasure(val bottom: Float, val top: Float, val armY: Float, val armX: Float, val footZ: Float)

/**
 * A UV sphere laid out like three.js's SphereGeometry ([ws] around, [hs] down; u = 0.25 faces +Z,
 * v = 0 at the top), each unit-sphere point moved by [place], which writes x, y, z into its last argument.
 */
fun sphereGeometry(ws: Int, hs: Int, place: (x: Float, y: Float, z: Float, out: FloatArray) -> Unit): PartGeometry {
    val n = (ws + 1) * (hs + 1)
    val pos = FloatArray(3 * n)
    val uv = FloatArray(2 * n)
    val out = FloatArray(3)
    for (iy in 0..hs) {
        val v = iy.toFloat() / hs
        val uOffset = if (iy == 0) 0.5f / ws else if (iy == hs) -0.5f / ws else 0f
        for (ix in 0..ws) {
            val u = ix.toFloat() / ws
            val i = iy * (ws + 1) + ix
            val st = sin(v * PI.toFloat())
            val x = -cos(u * 2 * PI.toFloat()) * st
            val y = cos(v * PI.toFloat())
            val z = sin(u * 2 * PI.toFloat()) * st
            val l = sqrt(x * x + y * y + z * z)
            place(x / l, y / l, z / l, out)
            System.arraycopy(out, 0, pos, 3 * i, 3)
            uv[2 * i] = u + uOffset
            uv[2 * i + 1] = v
        }
    }
    val idx = ArrayList<Short>()
    for (iy in 0 until hs) for (ix in 0 until ws) {
        val a = iy * (ws + 1) + ix + 1
        val b = iy * (ws + 1) + ix
        val c = (iy + 1) * (ws + 1) + ix
        val d = (iy + 1) * (ws + 1) + ix + 1
        if (iy != 0) { idx += a.toShort(); idx += b.toShort(); idx += d.toShort() }
        if (iy != hs - 1) { idx += b.toShort(); idx += c.toShort(); idx += d.toShort() }
    }
    return PartGeometry(pos, uv, idx.toShortArray()).smoothNormals()
}

/** A sphere with lumps, scaled to [rx], [ry], [rz]; lumps are relative to the radius. */
fun lumpySphere(rx: Float, ry: Float, rz: Float, ws: Int, hs: Int, seed: Int, amp: Float): PartGeometry {
    val nz = Noise3(seed, 5, 3.5f)
    return sphereGeometry(ws, hs) { x, y, z, out ->
        val k = 1 + amp * nz(x, y, z)
        out[0] = x * k * rx; out[1] = y * k * ry; out[2] = z * k * rz
    }
}

/** The tater: wide, a little squat and lumpy, [radius] across before the lumps. */
fun taterBody(radius: Float): PartGeometry {
    val nz = Noise3(11, 5, 3.5f)
    val big = Noise3(12, 3, 2f)
    return sphereGeometry(56, 40) { x, y, z, out ->
        val k = radius * (1 + 0.06f * nz(x, y, z) + 0.07f * big(x, y, z))
        out[0] = x * 1.14f * k; out[1] = y * 0.9f * k; out[2] = z * 0.96f * k
    }
}

/**
 * A box [w] x [h] x [d] centred on the origin with its edges and corners rounded to [r]: each face a
 * grid whose outer rows bend round onto a quarter circle ([arc] steps), so normals are exact and
 * faces meet seam to seam for the hull. Long faces get extra rows every [step], so lumps and the
 * boiling outline have vertices to move. UVs run 0..1 across each face, v = 0 at the top.
 */
fun roundedBoxGeometry(w: Float, h: Float, d: Float, r: Float, arc: Int = 3, step: Float = 0.15f): PartGeometry {
    val e = floatArrayOf(w / 2, h / 2, d / 2)
    val rr = r.coerceIn(1e-4f, minOf(e[0], e[1], e[2]))
    val inner = FloatArray(3) { e[it] - rr }
    val samples = Array(3) { axisSamples(e[it], rr, arc, step) }
    val pos = ArrayList<Float>()
    val nrm = ArrayList<Float>()
    val uv = ArrayList<Float>()
    val idx = ArrayList<Short>()
    val p = FloatArray(3)
    for (f in BOX_FACES) {
        val (na, ns, ua, us, va, vs) = f
        val su = samples[ua]
        val sv = samples[va]
        val base = pos.size / 3
        for (j in sv.indices) for (i in su.indices) {
            p[na] = ns * e[na]; p[ua] = us * su[i]; p[va] = vs * sv[j]
            var ox = 0f; var oy = 0f; var oz = 0f
            for (k in 0..2) {
                val c = p[k].coerceIn(-inner[k], inner[k])
                val o = p[k] - c
                p[k] = c
                when (k) { 0 -> ox = o; 1 -> oy = o; else -> oz = o }
            }
            val l = sqrt(ox * ox + oy * oy + oz * oz)
            ox /= l; oy /= l; oz /= l
            pos += p[0] + ox * rr; pos += p[1] + oy * rr; pos += p[2] + oz * rr
            nrm += ox; nrm += oy; nrm += oz
            uv += (su[i] / e[ua] + 1) / 2; uv += 1 - (sv[j] / e[va] + 1) / 2
        }
        val nu = su.size
        for (j in 0 until sv.size - 1) for (i in 0 until nu - 1) {
            val a = base + j * nu + i
            val b = a + 1
            val c = a + nu + 1
            val dd = a + nu
            idx += a.toShort(); idx += b.toShort(); idx += c.toShort()
            idx += a.toShort(); idx += c.toShort(); idx += dd.toShort()
        }
    }
    return PartGeometry(pos.toFloatArray(), uv.toFloatArray(), idx.toShortArray(), nrm.toFloatArray())
}

/** One face of a box: its normal's axis and sign, then the axes and signs of u and v (u x v = normal). */
private data class BoxFace(val na: Int, val ns: Float, val ua: Int, val us: Float, val va: Int, val vs: Float)

private val BOX_FACES = listOf(
    BoxFace(0, 1f, 2, -1f, 1, 1f), BoxFace(0, -1f, 2, 1f, 1, 1f),
    BoxFace(1, 1f, 0, 1f, 2, -1f), BoxFace(1, -1f, 0, 1f, 2, 1f),
    BoxFace(2, 1f, 0, 1f, 1, 1f), BoxFace(2, -1f, 0, -1f, 1, 1f),
)

/** Where a rounded box's grid lines fall along an axis of half-length [e]: even angles round each edge, even steps between. */
private fun axisSamples(e: Float, r: Float, arc: Int, step: Float): FloatArray {
    val inner = e - r
    val out = ArrayList<Float>()
    for (k in arc downTo 1) out += -inner - r * kotlin.math.tan(k.toFloat() / arc * PI.toFloat() / 4)
    val n = max(1, kotlin.math.ceil(2 * inner / step).toInt())
    for (i in 0..n) out += -inner + 2 * inner * i / n
    for (k in 1..arc) out += inner + r * kotlin.math.tan(k.toFloat() / arc * PI.toFloat() / 4)
    return out.toFloatArray()
}

/**
 * A cylinder [w] across, [h] tall and [d] deep, centred on the origin, its rims rounded to [r]
 * and its top [taper] times the bottom's width. [r] as big as the radius makes a capsule. Made by
 * turning a profile round Y in [segs] steps; UVs are a top-down projection, so a texture lies on the caps.
 */
fun cylinderGeometry(w: Float, h: Float, d: Float, r: Float, taper: Float = 1f, segs: Int = 24, step: Float = 0.15f): PartGeometry {
    val hh = h / 2
    val rb = w / 2
    val rt = rb * taper
    // The profile, bottom centre round the outside to top centre: r, y, and the outward normal.
    val prof = ArrayList<FloatArray>()
    fun line(r0: Float, y0: Float, r1: Float, y1: Float, endInclusive: Boolean) {
        val len = sqrt((r1 - r0) * (r1 - r0) + (y1 - y0) * (y1 - y0))
        if (len < 1e-6f) return
        val nr = (y1 - y0) / len
        val ny = -(r1 - r0) / len
        val n = max(1, kotlin.math.ceil(len / step).toInt())
        for (i in 0 until (if (endInclusive) n + 1 else n)) {
            val t = i.toFloat() / n
            prof += floatArrayOf(r0 + (r1 - r0) * t, y0 + (y1 - y0) * t, nr, ny)
        }
    }
    val corners = arrayOf(floatArrayOf(0f, -hh), floatArrayOf(rb, -hh), floatArrayOf(rt, hh), floatArrayOf(0f, hh))
    // Each outer corner's fillet: where it starts and ends, its centre and radius.
    val fillets = (1..2).map { c -> fillet(corners[c - 1], corners[c], corners[c + 1], r.coerceAtLeast(1e-4f)) }
    line(0f, -hh, fillets[0][0], fillets[0][1], false)
    arcPoints(fillets[0], prof)
    line(fillets[0][2], fillets[0][3], fillets[1][0], fillets[1][1], false)
    arcPoints(fillets[1], prof)
    line(fillets[1][2], fillets[1][3], 0f, hh, true)

    val ring = segs + 1
    val pos = FloatArray(prof.size * ring * 3)
    val nrm = FloatArray(pos.size)
    val uv = FloatArray(prof.size * ring * 2)
    val sz = d / w
    for ((j, q) in prof.withIndex()) for (i in 0..segs) {
        val a = i.toFloat() / segs * 2 * PI.toFloat()
        val s = sin(a)
        val c = cos(a)
        val v = j * ring + i
        val x = q[0] * s
        val z = q[0] * c * sz
        pos[3 * v] = x; pos[3 * v + 1] = q[1]; pos[3 * v + 2] = z
        var nx = q[2] * s
        var ny = q[3]
        var nz = q[2] * c / sz
        val l = sqrt(nx * nx + ny * ny + nz * nz)
        nx /= l; ny /= l; nz /= l
        nrm[3 * v] = nx; nrm[3 * v + 1] = ny; nrm[3 * v + 2] = nz
        uv[2 * v] = x / w + 0.5f; uv[2 * v + 1] = z / d + 0.5f
    }
    val idx = ArrayList<Short>()
    for (j in 0 until prof.size - 1) for (i in 0 until segs) {
        val a = j * ring + i
        val b = a + 1
        val c = a + ring + 1
        val dd = a + ring
        idx += a.toShort(); idx += b.toShort(); idx += c.toShort()
        idx += a.toShort(); idx += c.toShort(); idx += dd.toShort()
    }
    return PartGeometry(pos, uv, idx.toShortArray(), nrm)
}

/**
 * The fillet of radius up to [r] at corner [p] between [prev] and [next] (2D, convex): start point,
 * end point, centre and the radius that fits, as r0, y0, r1, y1, cr, cy, radius.
 */
private fun fillet(prev: FloatArray, p: FloatArray, next: FloatArray, r: Float): FloatArray {
    val ax = p[0] - prev[0]; val ay = p[1] - prev[1]
    val bx = next[0] - p[0]; val by = next[1] - p[1]
    val la = sqrt(ax * ax + ay * ay)
    val lb = sqrt(bx * bx + by * by)
    val dax = ax / la; val day = ay / la
    val dbx = bx / lb; val dby = by / lb
    val theta = kotlin.math.acos((-dax * dbx - day * dby).coerceIn(-1f, 1f))
    val half = kotlin.math.tan(theta / 2)
    // The side is shared with the other rim's fillet, so each may take half of it; a cap's radius is all its own.
    val t = minOf(r / half, la * (if (prev[0] == 0f) 1f else 0.5f), lb * (if (next[0] == 0f) 1f else 0.5f))
    val rad = t * half
    val bisx = -dax + dbx; val bisy = -day + dby
    val bl = sqrt(bisx * bisx + bisy * bisy)
    val k = rad / sin(theta / 2)
    return floatArrayOf(p[0] - dax * t, p[1] - day * t, p[0] + dbx * t, p[1] + dby * t, p[0] + bisx / bl * k, p[1] + bisy / bl * k, rad)
}

/** A fillet's arc into [prof], start included, end left for the next line. */
private fun arcPoints(f: FloatArray, prof: MutableList<FloatArray>, steps: Int = 4) {
    val rad = f[6]
    val a0 = kotlin.math.atan2(f[1] - f[5], f[0] - f[4])
    var a1 = kotlin.math.atan2(f[3] - f[5], f[2] - f[4])
    if (a1 < a0) a1 += 2 * PI.toFloat()
    for (i in 0 until steps) {
        val a = a0 + (a1 - a0) * i / steps
        prof += floatArrayOf(f[4] + cos(a) * rad, f[5] + sin(a) * rad, cos(a), sin(a))
    }
}

/** A flat [w] x [h] rectangle centred on the origin, facing +Z, UVs 0..1 with v = 0 at the top. */
fun panelGeometry(w: Float, h: Float): PartGeometry {
    val x = w / 2
    val y = h / 2
    return PartGeometry(
        floatArrayOf(-x, -y, 0f, x, -y, 0f, x, y, 0f, -x, y, 0f),
        floatArrayOf(0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f),
        shortArrayOf(0, 1, 2, 0, 2, 3),
        floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f),
    )
}

/**
 * The programs: [crayon] for the parts (with variants for a texture, and for the guy's face swapped
 * into his body's texture), [flat] for ink hulls and shadows, and [paper] for big surfaces, whose
 * crayon look is painted into their texture: one texture sample times the light, nothing per pixel.
 */
class CrayonShaders : Disposable {
    private val crayons = HashMap<Int, ShaderProgram>()
    val flat = compile(FLAT_VERTEX, FLAT_FRAGMENT)
    val paper = compile(PAPER_VERTEX, PAPER_FRAGMENT)

    /** The crayon program, sampling a texture when [map], with the face overlay when [face]. */
    fun crayon(map: Boolean, face: Boolean): ShaderProgram {
        val key = (if (map) 1 else 0) or (if (face) 2 else 0)
        return crayons.getOrPut(key) {
            val defines = (if (map) "#define MAP\n" else "") + (if (face) "#define FACE\n" else "")
            compile(CRAYON_VERTEX, defines + CRAYON_FRAGMENT)
        }
    }

    override fun dispose() {
        crayons.values.forEach { it.dispose() }
        flat.dispose()
        paper.dispose()
    }

    private fun compile(vertex: String, fragment: String) = ShaderProgram(vertex, fragment).also {
        check(it.isCompiled) { "crayon shader: ${it.log}" }
    }

    private companion object {
        const val PRECISION = """
            #ifdef GL_ES
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            #endif
        """

        // Normals in world space against one light for everything ([u_lightDir], the workshop's
        // camera-fixed light turned into the world by the renderer). Hatching runs in object space,
        // scaled by [u_hatchScale] so it spaces the same on the guy and on furniture.
        const val CRAYON_VERTEX = """
            attribute vec3 a_position;
            attribute vec3 a_normal;
            attribute vec2 a_texCoord0;
            attribute vec4 a_color;
            uniform mat4 u_projViewTrans;
            uniform mat4 u_worldTrans;
            uniform mat3 u_normalMatrix;
            uniform float u_hatchScale;
            varying vec3 v_n;
            varying vec2 v_uv;
            varying vec3 v_p;
            varying vec3 v_color;
            void main() {
                v_n = normalize(u_normalMatrix * a_normal);
                v_uv = a_texCoord0;
                v_p = a_position * u_hatchScale;
                v_color = a_color.rgb;
                gl_Position = u_projViewTrans * u_worldTrans * vec4(a_position, 1.0);
            }
        """

        // Two tones with a wobbly terminator; pencil hatching that starts just inside the light, thickens
        // in shadow and cross-hatches in the deepest part, with breaks so it reads drawn; crayon streaks;
        // paper tooth. Worked in linear light like three.js, so the mixes match the workshop. The colour
        // is per vertex (linear); with MAP it is multiplied by a texture, and with FACE the guy's face
        // is swapped into that texture inside [u_faceRect].
        const val CRAYON_FRAGMENT = PRECISION + """
            uniform sampler2D u_map;
            uniform sampler2D u_face;
            uniform vec4 u_faceRect;
            uniform vec3 u_lightDir;
            uniform vec3 u_shadeTint;
            uniform vec3 u_ink;
            uniform vec3 u_tint;
            uniform float u_boil;
            varying vec3 v_n;
            varying vec2 v_uv;
            varying vec3 v_p;
            varying vec3 v_color;
            void main() {
                vec3 base = v_color;
                #ifdef MAP
                vec3 tex = texture2D(u_map, v_uv).rgb;
                #ifdef FACE
                vec2 f = (v_uv - u_faceRect.xy) / u_faceRect.zw;
                float inside = step(0.0, f.x) * step(f.x, 1.0) * step(0.0, f.y) * step(f.y, 1.0);
                tex = mix(tex, texture2D(u_face, clamp(f, 0.0, 1.0)).rgb, inside);
                #endif
                base *= pow(tex, vec3(2.2));
                #endif
                float ndl = dot(normalize(v_n), u_lightDir);
                float boil = u_boil;
                vec3 vP = v_p;
                float wob = 0.08 * sin(vP.x * 47.0 + boil * 2.1) * sin(vP.y * 53.0 - boil * 1.3 + vP.z * 31.0);
                float lit = smoothstep(-0.03, 0.03, ndl + 0.12 + wob);
                vec3 c = mix(base * u_shadeTint, base, lit);
                float mid = 1.0 - smoothstep(-0.03, 0.03, ndl - 0.32 + wob);
                float deep = 1.0 - smoothstep(-0.03, 0.03, ndl + 0.3 + wob);
                float brk = 0.5 + 0.5 * sin(dot(vP, vec3(-0.6, 0.9, 0.4)) * 37.0 + boil);
                float h1 = smoothstep(0.3, 0.75, sin(dot(vP, vec3(1.0, 0.85, 0.35)) * 190.0 + boil * 2.0));
                float h2 = smoothstep(0.35, 0.8, sin(dot(vP, vec3(-0.9, 1.0, 0.2)) * 170.0 - boil * 1.7));
                float hatch = h1 * (0.22 * mid + 0.4 * (1.0 - lit)) + h2 * 0.45 * deep;
                c = mix(c, u_ink, clamp(hatch * (0.55 + 0.45 * brk), 0.0, 0.75));
                float streak = sin(dot(vP, vec3(0.7, -0.5, 0.5)) * 900.0 + 4.0 * sin(dot(vP, vec3(0.2, 0.9, -0.3)) * 70.0));
                float g = fract(sin(dot(floor(gl_FragCoord.xy / 1.5), vec2(12.9898, 78.233))) * 43758.5453);
                c *= 0.95 + 0.03 * streak + 0.065 * g;
                gl_FragColor = vec4(pow(max(c, vec3(0.0)), vec3(1.0 / 2.2)) * u_tint, 1.0);
            }
        """

        const val FLAT_VERTEX = """
            attribute vec3 a_position;
            attribute vec2 a_texCoord0;
            uniform mat4 u_projViewTrans;
            uniform mat4 u_worldTrans;
            varying vec2 v_uv;
            void main() {
                v_uv = a_texCoord0;
                gl_Position = u_projViewTrans * u_worldTrans * vec4(a_position, 1.0);
            }
        """

        const val FLAT_FRAGMENT = PRECISION + """
            uniform vec4 u_color;
            uniform float u_useMap;
            uniform sampler2D u_texture;
            varying vec2 v_uv;
            void main() {
                vec4 t = texture2D(u_texture, v_uv);
                gl_FragColor = u_color * mix(vec4(1.0), t, u_useMap);
            }
        """

        const val PAPER_VERTEX = """
            attribute vec3 a_position;
            attribute vec2 a_texCoord0;
            attribute vec4 a_color;
            uniform mat4 u_projViewTrans;
            uniform mat4 u_worldTrans;
            varying vec2 v_uv;
            varying vec3 v_color;
            void main() {
                v_uv = a_texCoord0;
                v_color = a_color.rgb;
                gl_Position = u_projViewTrans * u_worldTrans * vec4(a_position, 1.0);
            }
        """

        // Big surfaces: the painted texture, times the vertex colour (which carries the side's shade,
        // baked once), times the room's light. Kept in mediump on purpose: it covers most of the screen.
        const val PAPER_FRAGMENT = """
            #ifdef GL_ES
            precision mediump float;
            #endif
            uniform sampler2D u_texture;
            uniform vec3 u_tint;
            varying vec2 v_uv;
            varying vec3 v_color;
            void main() {
                gl_FragColor = vec4(texture2D(u_texture, v_uv).rgb * v_color * u_tint, 1.0);
            }
        """
    }
}
