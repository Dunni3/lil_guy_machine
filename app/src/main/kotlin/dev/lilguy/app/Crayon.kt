package dev.lilguy.app

import com.badlogic.gdx.graphics.Mesh
import com.badlogic.gdx.graphics.VertexAttribute
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
 * look A): lumpy spheres with smooth normals, an inverted-hull ink outline per part (three of them,
 * each a little different, cycled so the line boils), and a shader with two tones, pencil hatching,
 * crayon streaks and paper tooth. Seeds and numbers match the workshop, so he comes out the same.
 */
class PartGeometry(val pos: FloatArray, val uv: FloatArray, val idx: ShortArray) {
    val count get() = pos.size / 3
    val normal = FloatArray(pos.size)

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

    fun mesh(): Mesh {
        val v = FloatArray(count * 8)
        for (i in 0 until count) {
            System.arraycopy(pos, 3 * i, v, 8 * i, 3)
            System.arraycopy(normal, 3 * i, v, 8 * i + 3, 3)
            System.arraycopy(uv, 2 * i, v, 8 * i + 6, 2)
        }
        return Mesh(true, count, idx.size, VertexAttribute.Position(), VertexAttribute.Normal(), VertexAttribute.TexCoords(0)).apply {
            setVertices(v)
            setIndices(idx)
        }
    }

    /** The inverted-hull outline: this part pushed out along its smooth normals by [thickness] (of the normal). */
    fun hull(thickness: (nx: Float, ny: Float, nz: Float) -> Float): Mesh {
        val v = FloatArray(count * 3)
        for (i in 0 until count) {
            val k = thickness(normal[3 * i], normal[3 * i + 1], normal[3 * i + 2])
            for (j in 0..2) v[3 * i + j] = pos[3 * i + j] + normal[3 * i + j] * k
        }
        return Mesh(true, count, idx.size, VertexAttribute.Position()).apply {
            setVertices(v)
            setIndices(idx)
        }
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

/** The two programs: [crayon] for the parts, [flat] for ink hulls and the shadow. */
class CrayonShaders : Disposable {
    val crayon = compile(CRAYON_VERTEX, CRAYON_FRAGMENT)
    val flat = compile(FLAT_VERTEX, FLAT_FRAGMENT)

    override fun dispose() {
        crayon.dispose()
        flat.dispose()
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

        // Normals in view space and the light fixed to the camera, as in the workshop, so he is lit the
        // same way wherever he stands; the room's light only tints him ([u_tint]).
        const val CRAYON_VERTEX = """
            attribute vec3 a_position;
            attribute vec3 a_normal;
            attribute vec2 a_texCoord0;
            uniform mat4 u_projViewTrans;
            uniform mat4 u_worldTrans;
            uniform mat3 u_normalMatrix;
            varying vec3 v_n;
            varying vec2 v_uv;
            varying vec3 v_p;
            void main() {
                v_n = normalize(u_normalMatrix * a_normal);
                v_uv = a_texCoord0;
                v_p = a_position;
                gl_Position = u_projViewTrans * u_worldTrans * vec4(a_position, 1.0);
            }
        """

        // Two tones with a wobbly terminator; pencil hatching that starts just inside the light, thickens
        // in shadow and cross-hatches in the deepest part, with breaks so it reads drawn; crayon streaks;
        // paper tooth. Worked in linear light like three.js, so the mixes match the workshop. The body's
        // texture is the crayon grain, with the face from its own small texture inside [u_faceRect].
        const val CRAYON_FRAGMENT = PRECISION + """
            uniform vec3 u_color;
            uniform float u_useMap;
            uniform sampler2D u_grain;
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
            void main() {
                vec3 base = u_color;
                if (u_useMap > 0.5) {
                    vec2 f = (v_uv - u_faceRect.xy) / u_faceRect.zw;
                    float inside = step(0.0, f.x) * step(f.x, 1.0) * step(0.0, f.y) * step(f.y, 1.0);
                    vec3 grain = texture2D(u_grain, v_uv).rgb;
                    vec3 face = texture2D(u_face, clamp(f, 0.0, 1.0)).rgb;
                    base = pow(mix(grain, face, inside), vec3(2.2));
                }
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
    }
}
