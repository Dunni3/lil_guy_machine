package dev.lilguy.app

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.VertexAttributes.Usage
import com.badlogic.gdx.graphics.g3d.Environment
import com.badlogic.gdx.graphics.g3d.Material
import com.badlogic.gdx.graphics.g3d.Model
import com.badlogic.gdx.graphics.g3d.ModelBatch
import com.badlogic.gdx.graphics.g3d.ModelInstance
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute
import com.badlogic.gdx.graphics.g3d.attributes.DepthTestAttribute
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder
import com.badlogic.gdx.graphics.g3d.utils.shapebuilders.CylinderShapeBuilder
import com.badlogic.gdx.graphics.g3d.utils.shapebuilders.SphereShapeBuilder
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.utils.Disposable
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The little guy in 3D: an orange blob with stubby arms and feet and a sprout on top, animated
 * entirely in code (no skeleton). His face is a texture wrapped on the body sphere, one per
 * expression, swapped as his state changes, so a hand-painted set can replace [paintFace] later.
 *
 * What drives him is sim state only ([Pose]): needs pick the expression, eyelid and sprout droop and
 * how he breathes; walking hops him with squash and stretch; the activity under way picks a pose.
 */
class Blob : Disposable {
    enum class Face { Neutral, Happy, Sad, Tired, Thinking, Asleep }

    /** Everything about him the view reads from the snapshot, each frame. */
    class Pose {
        var x = 0f
        var z = 0f
        /** Compass degrees, as in the sim: 0 east, 90 north (away from the camera). */
        var facing = 270f
        var moving = false
        var doing: String? = null
        var energy = 70.0
        var mood = 60.0
        var thinking = false
        var music = false
        /** Height of what he stands or lies on: the floor, or the bed. */
        var standY = 0f
    }

    private val builder = ModelBuilder()
    private val faces = HashMap<Face, Texture>()
    private val blinks = HashMap<Face, Texture>()
    private val bodyModel: Model
    private val limbModel: Model
    private val footModel: Model
    private val sproutModel: Model
    private val shadowModel: Model
    private val shadowTexture: Texture

    private val body: ModelInstance
    private val arms: List<ModelInstance>
    private val feet: List<ModelInstance>
    private val sprout: ModelInstance
    private val shadow: ModelInstance
    /** The body instance's own texture (instances copy their model's materials). */
    private val faceAttribute: TextureAttribute

    private var time = 0f
    private var yaw = 0f
    private var walkPhase = 0f
    private var wasMoving = false
    private var bounce = 0f
    private var bounceVelocity = 0f
    private var blinkIn = 3f
    private var blinkLeft = 0f
    private var lie = 0f

    /** The current expression, after blinking. */
    var face = Face.Neutral
        private set

    /** The top of his head in world space, for anchoring a bubble. */
    val headTop = Vector3()

    private val root = Matrix4()
    private val tmp = Matrix4()

    init {
        for (f in Face.entries) {
            faces[f] = faceTexture(f, blink = false)
            blinks[f] = faceTexture(f, blink = true)
        }
        bodyModel = part(Material(TextureAttribute.createDiffuse(faces.getValue(Face.Neutral))), Usage.Position or Usage.Normal or Usage.TextureCoordinates) {
            SphereShapeBuilder.build(it, 2 * RADIUS, 2 * RADIUS, 2 * RADIUS, 28, 20)
        }
        limbModel = part(Material(ColorAttribute.createDiffuse(LIMB))) { SphereShapeBuilder.build(it, 0.13f, 0.15f, 0.13f, 12, 8) }
        footModel = part(Material(ColorAttribute.createDiffuse(FOOT))) { SphereShapeBuilder.build(it, 0.15f, 0.09f, 0.19f, 12, 8) }
        sproutModel = part(Material(ColorAttribute.createDiffuse(Color.WHITE)), Usage.Position or Usage.Normal or Usage.ColorPacked) {
            it.setColor(STEM)
            it.setVertexTransform(tmp.setToTranslation(0f, 0.07f, 0f))
            CylinderShapeBuilder.build(it, 0.025f, 0.14f, 0.025f, 8)
            it.setColor(LEAF)
            it.setVertexTransform(tmp.setToTranslation(0.05f, 0.15f, 0f).rotate(Vector3.Z, -25f))
            SphereShapeBuilder.build(it, 0.13f, 0.035f, 0.08f, 10, 6)
            it.setVertexTransform(tmp.setToTranslation(-0.04f, 0.13f, 0f).rotate(Vector3.Z, 30f))
            SphereShapeBuilder.build(it, 0.09f, 0.03f, 0.06f, 10, 6)
        }
        shadowTexture = shadowTexture()
        shadowModel = part(
            Material(
                TextureAttribute.createDiffuse(shadowTexture),
                ColorAttribute.createDiffuse(Color.BLACK),
                BlendingAttribute(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA, 0.35f),
                DepthTestAttribute(GL20.GL_LEQUAL, false),
            ),
            Usage.Position or Usage.Normal or Usage.TextureCoordinates,
        ) {
            it.rect(-0.36f, 0f, 0.3f, 0.36f, 0f, 0.3f, 0.36f, 0f, -0.3f, -0.36f, 0f, -0.3f, 0f, 1f, 0f)
        }
        body = ModelInstance(bodyModel)
        faceAttribute = body.materials.first().get(TextureAttribute.Diffuse) as TextureAttribute
        arms = List(2) { ModelInstance(limbModel) }
        feet = List(2) { ModelInstance(footModel) }
        sprout = ModelInstance(sproutModel)
        shadow = ModelInstance(shadowModel)
    }

    /** Advances his animation by [delta] seconds toward [pose]. */
    fun update(delta: Float, pose: Pose) {
        val dt = delta.coerceAtMost(0.1f)
        time += dt
        val asleep = pose.doing == "sleep" && !pose.moving
        val tired = ((40.0 - pose.energy) / 30.0).coerceIn(0.0, 1.0).toFloat()
        val glum = ((45.0 - pose.mood) / 30.0).coerceIn(0.0, 1.0).toFloat()

        // Turn toward where he faces, the short way round. Model +Z is compass 270 (south, the camera).
        // Standing still he turns partway toward the camera, so his face reads while he works.
        var target = pose.facing + 90f
        if (!pose.moving) target += angleDiff(0f, target) * PLAY_TO_CAMERA
        yaw += MathUtils.clamp(angleDiff(target, yaw), -TURN_SPEED * dt, TURN_SPEED * dt)

        // A little landing squash whenever he starts or stops walking.
        if (pose.moving != wasMoving) bounceVelocity -= 1.6f
        wasMoving = pose.moving
        bounceVelocity += (-SPRING * bounce - DAMPING * bounceVelocity) * dt
        bounce += bounceVelocity * dt
        if (pose.moving) walkPhase += dt * WALK_RATE else walkPhase = 0f

        // Blinking, every few seconds; not while asleep, when the eyes are shut anyway.
        blinkIn -= dt
        if (blinkIn <= 0f) {
            blinkLeft = BLINK
            blinkIn = MathUtils.random(2.2f, 5.5f)
        }
        blinkLeft -= dt
        val base = when {
            asleep -> Face.Asleep
            pose.thinking || pose.doing == "think" -> Face.Thinking
            pose.energy < 20 -> Face.Tired
            pose.mood < 30 -> Face.Sad
            pose.mood >= 65 || pose.music -> Face.Happy
            else -> Face.Neutral
        }
        face = base
        val texture = if (blinkLeft > 0f && base != Face.Asleep) blinks.getValue(base) else faces.getValue(base)
        faceAttribute.textureDescription.texture = texture

        // Squash and stretch: breathing, the walk cycle, chewing, and the landing spring.
        val breathRate = if (asleep) 1.1f else MathUtils.lerp(2.3f, 1.3f, tired)
        val breathAmp = if (asleep) 0.045f else 0.025f
        var squash = 1f + breathAmp * sin(time * breathRate) + bounce
        squash -= 0.06f * tired + 0.03f * glum
        if (pose.moving) squash -= 0.07f * cos(2 * walkPhase)
        if (pose.doing == "eat" && !pose.moving) squash += 0.04f * sin(time * 10f)
        val stretchXZ = 1f / sqrt(squash)
        val hop = if (pose.moving) abs(sin(walkPhase)) * 0.07f else 0f

        // He sleeps propped on his pillow, leaning back far enough that his face still shows.
        lie = MathUtils.lerp(lie, if (asleep) 1f else 0f, (dt * 4f).coerceAtMost(1f))
        // When music plays and he's idle, he sways.
        val sway = if (pose.music && !pose.moving && !asleep) 6f * sin(time * 2.6f) else 0f

        root.setToTranslation(pose.x, pose.standY + hop, pose.z + lie * 0.08f)
            .rotate(Vector3.Y, if (asleep) MathUtils.lerp(yaw, 0f, lie) else yaw)
            .rotate(Vector3.X, -38f * lie)
            .rotate(Vector3.Z, sway)
            .scale(SCALE, SCALE, SCALE)
        val bodyY = FEET + RADIUS * squash
        body.transform.set(root).translate(0f, bodyY, 0f).scale(stretchXZ, squash, stretchXZ)

        for ((i, side) in SIDES.withIndex()) {
            val step = if (pose.moving) sin(walkPhase + i * MathUtils.PI) * 0.09f else 0f
            feet[i].transform.set(root).translate(side * 0.13f, 0.045f + (if (step > 0) step * 0.3f else 0f), 0.08f + step)

            // Arms: swing while walking; activity poses otherwise.
            var ax = side * (RADIUS * stretchXZ + 0.015f)
            var ay = FEET + RADIUS * squash * 0.85f
            var az = if (pose.moving) -step * 0.8f else 0f
            if (!pose.moving && !asleep) when (pose.doing) {
                "sketch", "journal", "paint" -> if (side > 0) { ax *= 0.75f; az = 0.24f; ay += 0.06f + 0.025f * sin(time * 15f) }
                "eat" -> if ((time * 2.5f).toInt() % 2 == i) { ax *= 0.6f; az = 0.26f; ay += 0.15f }
                "read" -> { ax *= 0.65f; az = 0.24f; ay += 0.1f }
                "sell_art" -> if (side > 0) { az = 0.22f; ay += 0.2f + 0.04f * sin(time * 6f) }
                else -> if (pose.music) ay += 0.03f * sin(time * 5.2f + i * MathUtils.PI)
            }
            arms[i].transform.set(root).translate(ax, ay, az)
        }

        // The sprout droops when he's tired or low, and bobs with him.
        val droop = 10f + 55f * maxOf(tired, glum) + 5f * sin(time * 1.7f)
        sprout.transform.set(root).translate(0f, FEET + 2 * RADIUS * squash - 0.015f, 0f).rotate(Vector3.X, droop)

        val shrink = SCALE * (1f - hop * 2f)
        shadow.transform.setToTranslation(pose.x, pose.standY + SHADOW_LIFT, pose.z).scale(shrink, 1f, shrink)
        headTop.set(0f, FEET + 2 * RADIUS + 0.2f, 0f).mul(root)
        if (asleep) headTop.set(pose.x, pose.standY + 0.6f, pose.z)
    }

    fun render(batch: ModelBatch, env: Environment, asleep: Boolean) {
        if (!asleep) batch.render(shadow, env)
        batch.render(body, env)
        arms.forEach { batch.render(it, env) }
        feet.forEach { batch.render(it, env) }
        batch.render(sprout, env)
    }

    override fun dispose() {
        (faces.values + blinks.values).forEach { it.dispose() }
        shadowTexture.dispose()
        listOf(bodyModel, limbModel, footModel, sproutModel, shadowModel).forEach { it.dispose() }
    }

    private fun part(material: Material, usage: Int = Usage.Position or Usage.Normal, build: (com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder) -> Unit): Model {
        builder.begin()
        build(builder.part("p", GL20.GL_TRIANGLES, usage.toLong(), material))
        return builder.end()
    }

    // --- faces ---------------------------------------------------------------------------------

    private fun faceTexture(face: Face, blink: Boolean): Texture {
        val p = Pixmap(FACE_W, FACE_H, Pixmap.Format.RGBA8888)
        p.setColor(BODY)
        p.fill()
        paintFace(p, face, blink)
        return Texture(p).also {
            it.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
            p.dispose()
        }
    }

    /**
     * Draws [face] on the body's texture. The sphere's texture wraps once around him, so the front
     * of his face is at u = 0.75 (x = [FACE_X]); left in the image is his right, as you look at him.
     */
    private fun paintFace(p: Pixmap, face: Face, blink: Boolean) {
        val cx = FACE_X
        val ey = EYE_Y
        // Rosy cheeks, always a little, more when he's happy.
        p.setColor(if (face == Face.Happy) BLUSH_STRONG else BLUSH)
        for (s in SIDES) p.fillCircle((cx + s * 30).toInt(), (ey + 12).toInt(), 7)
        p.setColor(INK)
        val shut = blink || face == Face.Asleep
        for (s in SIDES) {
            val ex = cx + s * EYE_GAP
            when {
                shut -> arc(p, ex, ey - 2, 7f, 20f, 160f, 1.8f)
                face == Face.Happy -> arc(p, ex, ey + 3, 7f, 200f, 340f, 2f)
                face == Face.Thinking -> eye(p, ex + 2, ey - 3)
                face == Face.Tired -> {
                    eye(p, ex, ey)
                    p.setColor(BODY)
                    p.fillRectangle((ex - 10).toInt(), (ey - 12).toInt(), 20, 11)
                    p.setColor(LID)
                    thick(p, ex - 8, ey - 1, ex + 8, ey - 1, 1.6f)
                    p.setColor(INK)
                }
                face == Face.Sad -> {
                    eye(p, ex, ey + 1)
                    thick(p, ex - s * 8, ey - 9, ex + s * 3, ey - 13, 1.5f)
                }
                else -> eye(p, ex, ey)
            }
        }
        val my = ey + 16
        when (face) {
            Face.Happy -> {
                p.setColor(MOUTH)
                p.fillCircle(cx.toInt(), my.toInt() - 2, 8)
                p.setColor(BODY)
                p.fillRectangle((cx - 9).toInt(), (my - 11).toInt(), 19, 9)
                p.setColor(TONGUE)
                p.fillCircle(cx.toInt(), my.toInt() + 3, 3)
            }
            Face.Neutral -> arc(p, cx, my - 6, 6f, 35f, 145f, 1.6f)
            Face.Sad -> arc(p, cx, my + 6, 6f, 215f, 325f, 1.6f)
            Face.Tired -> thick(p, cx - 4, my, cx + 4, my + 1, 1.5f)
            Face.Thinking -> thick(p, cx + 1, my, cx + 8, my - 2, 1.5f)
            Face.Asleep -> {
                p.setColor(MOUTH)
                p.fillCircle(cx.toInt(), my.toInt(), 3)
            }
        }
    }

    private fun eye(p: Pixmap, x: Float, y: Float) {
        p.setColor(INK)
        p.fillCircle(x.toInt(), y.toInt(), 8)
        p.setColor(Color.WHITE)
        p.fillCircle((x - 3).toInt(), (y - 3).toInt(), 3)
        p.fillCircle((x + 3).toInt(), (y + 3).toInt(), 1)
        p.setColor(INK)
    }

    /** An arc of radius [r] around (x, y), from [from] to [to] degrees; image y is down, so 90 is the bottom. */
    private fun arc(p: Pixmap, x: Float, y: Float, r: Float, from: Float, to: Float, width: Float) {
        var a = from
        while (a <= to) {
            val rad = a * MathUtils.degreesToRadians
            p.fillCircle((x + r * cos(rad)).toInt(), (y + r * sin(rad)).toInt(), width.toInt())
            a += 6f
        }
    }

    private fun thick(p: Pixmap, x0: Float, y0: Float, x1: Float, y1: Float, width: Float) {
        for (i in 0..10) {
            val t = i / 10f
            p.fillCircle(MathUtils.lerp(x0, x1, t).toInt(), MathUtils.lerp(y0, y1, t).toInt(), width.toInt())
        }
    }

    /** A soft dark disc, for the blob shadow under him. */
    private fun shadowTexture(): Texture {
        val size = 64
        val p = Pixmap(size, size, Pixmap.Format.RGBA8888)
        p.blending = Pixmap.Blending.None
        for (y in 0 until size) for (x in 0 until size) {
            val dx = (x + 0.5f) / size * 2 - 1
            val dy = (y + 0.5f) / size * 2 - 1
            val a = (1f - sqrt(dx * dx + dy * dy)).coerceIn(0f, 1f)
            p.drawPixel(x, y, Color.rgba8888(1f, 1f, 1f, a * a * (3 - 2 * a)))
        }
        return Texture(p).also { p.dispose() }
    }

    private fun angleDiff(target: Float, from: Float): Float = ((target - from + 540f) % 360f) - 180f

    private companion object {
        /** His size: the body is [RADIUS] across before this. */
        const val SCALE = 1.25f
        const val RADIUS = 0.3f
        /** How far the body sits above his feet' soles. */
        const val FEET = 0.03f
        const val SHADOW_LIFT = 0.045f
        const val TURN_SPEED = 600f
        const val PLAY_TO_CAMERA = 0.4f
        const val WALK_RATE = 13f
        const val SPRING = 160f
        const val DAMPING = 9f
        const val BLINK = 0.13f
        const val FACE_W = 256
        const val FACE_H = 128
        const val FACE_X = FACE_W * 0.75f
        const val EYE_Y = FACE_H * 0.43f
        const val EYE_GAP = 16f
        val SIDES = floatArrayOf(-1f, 1f)

        val BODY = Color.valueOf("ff9a3dff")
        val LIMB = Color.valueOf("f68a30ff")
        val FOOT = Color.valueOf("d9692aff")
        val STEM = Color.valueOf("5a9e4aff")
        val LEAF = Color.valueOf("7ccf6aff")
        val INK = Color.valueOf("2a1606ff")
        val LID = Color.valueOf("a8551cff")
        val MOUTH = Color.valueOf("6b1f12ff")
        val TONGUE = Color.valueOf("ff6f6fff")
        val BLUSH = Color.valueOf("ff6f6f40")
        val BLUSH_STRONG = Color.valueOf("ff5f6f80")
    }
}
