package dev.lilguy.app

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Mesh
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.VertexAttribute
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.math.Matrix3
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.utils.Disposable

/**
 * Draws everything in the hand-drawn look, a frame at a time: [begin], then queue [model]s, painted
 * [surface]s and hatched [shadow]s, then [end] draws them grouped by program so state changes stay
 * few. Surfaces go first with the cheap paper shader, then the crayon parts, then every outline
 * hull in ink (one boil frame for all, cycled at [BOIL_FPS]), then the shadows, blended.
 *
 * One light for everything: the workshop's light, which sits up and to the left of the camera, turned
 * into world space. The camera doesn't move, so the guy is lit exactly as he was with the light fixed
 * to the camera, and the furniture agrees with him. The room's light only tints ([begin]'s tint).
 */
class CrayonRenderer : Disposable {
    /** The guy's face, swapped into his body's texture inside [rect] (u, v, width, height of the texture). */
    class Overlay(var texture: Texture?, val rect: FloatArray)

    private class Entry {
        var model: CrayonModel? = null
        var overlay: Overlay? = null
        var mesh: Mesh? = null
        var texture: Texture? = null
        val color = Color()
        val transform = Matrix4()
    }

    private val shaders = CrayonShaders()
    private val solids = Pool()
    private val surfaces = Pool()
    private val shadows = Pool()
    private val bound = HashSet<ShaderProgram>()
    private val normalMatrix = Matrix3()
    private val light = Vector3()
    private val tint = Color(Color.WHITE)
    private var camera: Camera? = null
    private var time = 0f

    /** Which boil frame this frame draws, for anything (the guy's face) painted per frame. */
    var frame = 0
        private set

    /** The hatched lavender patch laid under things, and a unit square (x and z -0.5..0.5) to lay it on. */
    private val shadowTexture: Texture = CrayonCanvas(128, 100, 0.5f).also { paintShadow(it) }.texture()
    private val shadowMesh = Mesh(true, 4, 6, VertexAttribute.Position(), VertexAttribute.TexCoords(0)).apply {
        setVertices(floatArrayOf(-0.5f, 0f, -0.5f, 0f, 0f, 0.5f, 0f, -0.5f, 1f, 0f, 0.5f, 0f, 0.5f, 1f, 1f, -0.5f, 0f, 0.5f, 0f, 1f))
        setIndices(shortArrayOf(0, 2, 1, 0, 3, 2))
    }

    /** Starts a frame seen by [camera], in the room's light [tint] (white at midday), [delta] seconds after the last. */
    fun begin(camera: Camera, tint: Color, delta: Float) {
        this.camera = camera
        this.tint.set(tint)
        time += delta.coerceAtMost(0.1f)
        frame = (time * BOIL_FPS).toInt() % CrayonModel.BOIL_FRAMES
        worldLight(camera, light)
        solids.clear(); surfaces.clear(); shadows.clear()
    }

    fun model(model: CrayonModel, transform: Matrix4, overlay: Overlay? = null) {
        solids.next().also { it.model = model; it.overlay = overlay; it.transform.set(transform) }
    }

    /** A paper-shaded mesh (position, uv, colour) with its painted [texture], in the room's light or in [light] when given. */
    fun surface(mesh: Mesh, texture: Texture, transform: Matrix4, light: Color? = null) {
        surfaces.next().also { it.mesh = mesh; it.texture = texture; it.transform.set(transform); it.color.set(light ?: tint) }
    }

    /** A hatched shadow on the unit square, placed (and stretched) by [transform]. */
    fun shadow(transform: Matrix4) {
        shadows.next().also { it.transform.set(transform) }
    }

    /** Draws the queue with depth testing against what's already drawn. Leaves depth testing on, blending and culling off. */
    fun end() {
        val camera = camera ?: return
        val gl = Gdx.gl
        bound.clear()
        gl.glEnable(GL20.GL_DEPTH_TEST)
        gl.glDepthFunc(GL20.GL_LEQUAL)
        gl.glEnable(GL20.GL_CULL_FACE)
        gl.glCullFace(GL20.GL_BACK)

        if (surfaces.size > 0) {
            val paper = shaders.paper
            paper.bind()
            paper.setUniformMatrix("u_projViewTrans", camera.combined)
            paper.setUniformi("u_texture", 0)
            gl.glActiveTexture(GL20.GL_TEXTURE0)
            surfaces.each { e ->
                paper.setUniformMatrix("u_worldTrans", e.transform)
                paper.setUniformf("u_tint", e.color.r, e.color.g, e.color.b)
                e.texture!!.bind(0)
                e.mesh!!.render(paper, GL20.GL_TRIANGLES)
            }
        }

        var current: ShaderProgram? = null
        solids.each { e ->
            val model = e.model!!
            for (b in model.batches) {
                val face = e.overlay?.takeIf { b.texture != null }
                val program = shaders.crayon(b.texture != null, face != null)
                if (program !== current) {
                    program.bind()
                    current = program
                    if (bound.add(program)) frameUniforms(program, camera)
                }
                program.setUniformMatrix("u_worldTrans", e.transform)
                program.setUniformMatrix("u_normalMatrix", normalMatrix.set(e.transform).inv().transpose())
                program.setUniformf("u_hatchScale", model.hatchScale)
                if (face != null) {
                    face.texture?.bind(1)
                    program.setUniformf("u_faceRect", face.rect[0], face.rect[1], face.rect[2], face.rect[3])
                }
                b.texture?.bind(0)
                b.mesh.render(program, GL20.GL_TRIANGLES)
            }
        }

        // The outlines: back faces of the pushed-out hulls, in ink.
        val flat = shaders.flat
        gl.glCullFace(GL20.GL_FRONT)
        flat.bind()
        flat.setUniformMatrix("u_projViewTrans", camera.combined)
        flat.setUniformf("u_color", INK.r * tint.r, INK.g * tint.g, INK.b * tint.b, 1f)
        flat.setUniformf("u_useMap", 0f)
        flat.setUniformi("u_texture", 0)
        solids.each { e ->
            val hulls = e.model!!.hulls
            if (hulls.isNotEmpty()) {
                flat.setUniformMatrix("u_worldTrans", e.transform)
                hulls[frame].render(flat, GL20.GL_TRIANGLES)
            }
        }
        gl.glCullFace(GL20.GL_BACK)
        gl.glDisable(GL20.GL_CULL_FACE)

        if (shadows.size > 0) {
            gl.glEnable(GL20.GL_BLEND)
            gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
            gl.glDepthMask(false)
            flat.setUniformf("u_color", tint.r, tint.g, tint.b, 1f)
            flat.setUniformf("u_useMap", 1f)
            shadowTexture.bind(0)
            shadows.each { e ->
                flat.setUniformMatrix("u_worldTrans", e.transform)
                shadowMesh.render(flat, GL20.GL_TRIANGLES)
            }
            gl.glDepthMask(true)
            gl.glDisable(GL20.GL_BLEND)
        }
    }

    /** What every crayon program shares in a frame, set the first time it's bound. */
    private fun frameUniforms(p: ShaderProgram, camera: Camera) {
        p.setUniformMatrix("u_projViewTrans", camera.combined)
        p.setUniformf("u_lightDir", light)
        p.setUniformf("u_shadeTint", SHADE_TINT.r, SHADE_TINT.g, SHADE_TINT.b)
        p.setUniformf("u_ink", INK_LINEAR.r, INK_LINEAR.g, INK_LINEAR.b)
        p.setUniformf("u_tint", tint.r, tint.g, tint.b)
        p.setUniformf("u_boil", frame.toFloat())
        // Only the variants that sample have these.
        if (p.hasUniform("u_map")) p.setUniformi("u_map", 0)
        if (p.hasUniform("u_face")) p.setUniformi("u_face", 1)
    }

    override fun dispose() {
        shaders.dispose()
        shadowTexture.dispose()
        shadowMesh.dispose()
    }

    /** Queue entries, reused frame to frame. */
    private class Pool {
        private val entries = ArrayList<Entry>()
        var size = 0
            private set

        fun next(): Entry {
            if (size == entries.size) entries += Entry()
            return entries[size++].also { it.overlay = null }
        }

        fun clear() { size = 0 }

        inline fun each(block: (Entry) -> Unit) { for (i in 0 until size) block(entries[i]) }
    }

    companion object {
        const val BOIL_FPS = 7f
        val INK: Color = Color.valueOf("#4a3a4f")
        val INK_LINEAR = CrayonModel.linear(INK)
        /** What the shaded side is multiplied by, a soft lavender (linear). */
        val SHADE_TINT = CrayonModel.linear(Color.valueOf("#e2d2ec"))
        /** From up, to the left and toward the camera, in view space (the workshop's). */
        val LIGHT: Vector3 = Vector3(-0.45f, 0.65f, 0.6f).nor()

        /** [LIGHT] turned from [camera]'s view into world space, into [out]. */
        fun worldLight(camera: Camera, out: Vector3): Vector3 = out.set(LIGHT).rot(Matrix4(camera.view).inv()).nor()
        private val SHADOW: Color = Color.valueOf("#9d8db0")

        /** A hatched lavender patch, 256 x 200 design units. */
        private fun paintShadow(x: CrayonCanvas) {
            val rand = Rng(9)
            x.clip(blobPath(128f, 100f, 110f, 80f, rand, 0.06f))
            var k = -260f
            while (k < 260f) {
                val ya = 190 + (rand() - 0.5f) * 8
                val yb = 10 + (rand() - 0.5f) * 8
                x.stroke(floatArrayOf(128 + k - 90, ya, 128 + k + 90, yb), 3.5f, SHADOW, 0.45f)
                k += 8f
            }
            x.unclip()
        }
    }
}
