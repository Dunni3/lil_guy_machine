package dev.lilguy.app

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.utils.Disposable
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The little guy in 3D: a butter-yellow tater with stubby arms and feet, drawn in the hand-drawn look
 * of the workshop (`tools/workshop/guy.html`, gallery v3): crayon shading and hatching ([CrayonShaders]),
 * a boiling ink outline, and a face painted in pen on the body's texture. Animated entirely in code
 * (no skeleton). The face is one small texture per expression, blink and boil frame, swapped as his
 * state changes.
 *
 * What drives him is sim state only ([Pose]): needs pick the expression and how he breathes; walking
 * hops him with squash and stretch; the activity under way picks a pose.
 *
 * His parts are [CrayonModel]s from his own geometry (the tater body, lumpy limbs), queued on the
 * room's [CrayonRenderer] each frame ([render]) with his face as the body's overlay.
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
        /** Where his working hand goes, in world space, while he draws, writes or paints; null otherwise. */
        var workAt: Vector3? = null
        /** The work lies flat (a page on the desk) rather than standing up (a canvas facing +Z). */
        var workFlat = true
        /** How far he steps or slides toward the work, in metres. */
        var workSlide = 0f
    }

    /** One moving part: its model and where it is this frame. */
    private class Limb(val model: CrayonModel) {
        val transform = Matrix4()
    }

    private val measure: BodyMeasure
    private val body: Limb
    private val arms: List<Limb>
    private val feet: List<Limb>
    private val parts: List<Limb>
    private val grain: Texture
    /** By face, then blink (0 open, 1 shut), then boil frame. */
    private val faces: List<List<List<Texture>>>
    private val face = CrayonRenderer.Overlay(null, floatArrayOf(FACE_X / DESIGN_W, FACE_Y / DESIGN_H, FACE_W / DESIGN_W, FACE_H / DESIGN_H))
    private val shadowTransform = Matrix4()
    private val shadowWidth: Float

    private var time = 0f
    private var yaw = 0f
    private var walkPhase = 0f
    private var wasMoving = false
    private var bounce = 0f
    private var bounceVelocity = 0f
    private var blinkIn = 3f
    private var blinkLeft = 0f
    private var lie = 0f
    /** 0..1, how far he has settled in to the work: slid up to it and leaning over it. */
    private var settle = 0f
    /** Toward the work, flat, and the axis he leans on to bend toward it. */
    private val workDir = Vector3()
    private val leanAxis = Vector3()
    private var slideTo = 0f

    /** The current expression, after blinking. */
    var expression = Face.Neutral
        private set
    private var shut = false

    /** The top of his head in world space, for anchoring a bubble. */
    val headTop = Vector3()

    private val root = Matrix4()
    private val inverse = Matrix4()
    private val hold = Vector3()

    init {
        val grainCanvas = CrayonCanvas(BODY_W, BODY_H, TEX_SCALE).also { paintGrain(it) }
        grain = grainCanvas.texture()

        // Hull seeds count up per part in the workshop's order: body, then each side's arm and foot.
        var seed = 1
        fun part(geo: PartGeometry, color: Color, base: Float, vary: Float, texture: Texture? = null): Limb {
            val piece = Piece(geo, CrayonModel.linear(color), texture, Outline(base, vary, seed++))
            return Limb(CrayonModel(listOf(piece)))
        }
        val bodyGeo = taterBody(RADIUS)
        measure = bodyGeo.measure()
        // The body's colour is all in its texture.
        body = part(bodyGeo, Color.WHITE, 0.009f, 0.006f, grain)
        val armList = ArrayList<Limb>()
        val footList = ArrayList<Limb>()
        for (i in SIDES.indices) {
            armList += part(lumpySphere(0.065f, 0.075f, 0.065f, 16, 12, 20 + i, 0.07f), LIMB, 0.008f, 0.004f)
            footList += part(lumpySphere(0.075f, 0.045f, 0.095f, 16, 12, 30 + i, 0.07f), FOOT, 0.008f, 0.004f)
        }
        arms = armList
        feet = footList
        parts = listOf(body) + arms + feet

        faces = Face.entries.map { f ->
            List(2) { b ->
                List(BOIL_FRAMES) { k ->
                    CrayonCanvas((FACE_W * TEX_SCALE).toInt(), (FACE_H * TEX_SCALE).toInt(), TEX_SCALE, FACE_X, FACE_Y).run {
                        copy(grainCanvas, (FACE_X * TEX_SCALE).toInt(), (FACE_Y * TEX_SCALE).toInt())
                        paintFace(this, f, b == 1, Rng(1000 + k * 97 + f.ordinal * 13 + b * 5))
                        texture()
                    }
                }
            }
        }
        shadowWidth = maxOf(measure.armX, 0.25f) / RADIUS
    }

    /** Advances his animation by [delta] seconds toward [pose]. */
    fun update(delta: Float, pose: Pose) {
        val dt = delta.coerceAtMost(0.1f)
        time += dt
        val asleep = pose.doing == "sleep" && !pose.moving
        val tired = ((40.0 - pose.energy) / 30.0).coerceIn(0.0, 1.0).toFloat()
        val glum = ((45.0 - pose.mood) / 30.0).coerceIn(0.0, 1.0).toFloat()

        // Turn toward where he faces, the short way round. Model +Z is compass 270 (south, the camera).
        // Standing still he turns partway toward the camera, so his face reads; less with his hands on his work.
        var target = pose.facing + 90f
        if (!pose.moving) target += angleDiff(0f, target) * if (pose.workAt != null) PLAY_AT_WORK else PLAY_TO_CAMERA
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
        expression = when {
            asleep -> Face.Asleep
            pose.thinking || pose.doing == "think" -> Face.Thinking
            pose.energy < 20 -> Face.Tired
            pose.mood < 30 -> Face.Sad
            pose.mood >= 65 || pose.music -> Face.Happy
            else -> Face.Neutral
        }
        shut = blinkLeft > 0f && expression != Face.Asleep

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

        // At work he slides up to it (off the chair's middle, toward the desk) and leans toward it, more over a page.
        val work = pose.workAt?.takeIf { !pose.moving && !asleep }
        settle = MathUtils.lerp(settle, if (work != null) 1f else 0f, (dt * 3f).coerceAtMost(1f))
        if (work != null) workDir.set(work.x - pose.x, 0f, work.z - pose.z).nor()
        val stroke = strokeAt(time)
        if (work != null) slideTo = pose.workSlide
        val slide = slideTo * settle
        val lean = settle * (if (pose.workFlat) LEAN_FLAT + 3f * stroke.nod else LEAN_UPRIGHT)

        root.setToTranslation(pose.x + workDir.x * slide, pose.standY + hop, pose.z + workDir.z * slide + lie * 0.08f)
            .rotate(leanAxis.set(workDir.z, 0f, -workDir.x), lean)
            .rotate(Vector3.Y, if (asleep) MathUtils.lerp(yaw, 0f, lie) else yaw)
            .rotate(Vector3.X, -38f * lie)
            .rotate(Vector3.Z, sway)
            .scale(SCALE, SCALE, SCALE)
        val m = measure
        body.transform.set(root).translate(0f, FEET + m.bottom * squash, 0f).scale(stretchXZ, squash, stretchXZ)

        // At work, the hand nearer the camera does it, so it shows.
        val hand = if (work == null) -1 else SIDES.indices.maxBy { i -> hold.set(SIDES[i], 0f, 0f).rot(root).z }
        for ((i, side) in SIDES.withIndex()) {
            val step = if (pose.moving) sin(walkPhase + i * MathUtils.PI) * 0.09f else 0f
            feet[i].transform.set(root).translate(side * 0.13f, 0.045f + (if (step > 0) step * 0.3f else 0f), m.footZ + step)

            // Arms: swing while walking; activity poses otherwise.
            var ax = side * (m.armX * stretchXZ + 0.015f)
            var ay = FEET + (m.bottom + m.armY) * squash
            var az = if (pose.moving) -step * 0.8f else 0f
            if (!pose.moving && !asleep) when (pose.doing) {
                "sketch", "journal", "paint" -> if (side > 0) { ax *= 0.75f; az = 0.24f; ay += 0.06f + 0.025f * sin(time * 15f) }
                "eat" -> if ((time * 2.5f).toInt() % 2 == i) { ax *= 0.6f; az = 0.26f; ay += 0.15f }
                "read" -> { ax *= 0.65f; az = 0.24f; ay += 0.1f }
                "sell_art" -> if (side > 0) { az = 0.22f; ay += 0.2f + 0.04f * sin(time * 6f) }
                else -> ay += if (pose.music) 0.03f * sin(time * 5.2f + i * MathUtils.PI) else 0.008f * sin(time * 2.3f + i)
            }
            if (work != null) {
                workHand(work, pose, stroke, side, i == hand)
                ax = MathUtils.lerp(ax, hold.x, settle)
                ay = MathUtils.lerp(ay, hold.y, settle)
                az = MathUtils.lerp(az, hold.z, settle)
            }
            arms[i].transform.set(root).translate(ax, ay, az)
        }

        val shrink = SCALE * (1f - hop * 2f)
        shadowTransform.setToTranslation(pose.x, pose.standY + SHADOW_LIFT, pose.z).scale(shrink * shadowWidth * SHADOW_W, 1f, shrink * SHADOW_D)
        headTop.set(0f, FEET + m.bottom + m.top + 0.2f, 0f).mul(root)
        if (asleep) headTop.set(pose.x, pose.standY + 0.6f, pose.z)
    }

    /** One stroke of the pen or brush: where in it he is (0..1), which stroke it is, and whether his hand is lifted. */
    private class Stroke {
        var t = 0f
        var n = 0
        var lifted = false
        /** His head dips with each stroke, -1..1. */
        var nod = 0f
    }

    private val strokeNow = Stroke()

    private fun strokeAt(time: Float): Stroke = strokeNow.apply {
        val cycle = time / STROKE
        n = cycle.toInt()
        val f = cycle - n
        lifted = f > STROKE_DOWN
        t = if (lifted) 1f else f / STROKE_DOWN
        nod = if (lifted) -1f else sin(t * MathUtils.PI)
    }

    /**
     * Sets [hold] to where hand [side] goes at [work], in his own (root) frame. The [working] hand
     * goes to the work: scribbling over a sketch, writing lines in the journal, or dabbing at the
     * canvas with a trip to the palette now and then. The other holds the page down, or the palette.
     */
    private fun workHand(work: Vector3, pose: Pose, stroke: Stroke, side: Float, working: Boolean) {
        val r = Rng(stroke.n * 7919 + 17)
        if (pose.workFlat) {
            if (working) {
                if (pose.doing == "journal") {
                    // Writing: along a line, left to right, a line at a time.
                    val line = (time / LINE).toInt()
                    val along = time / LINE - line
                    hold.set(work).add(-0.07f + 0.14f * along, 0f, -0.05f + 0.025f * (line % 5))
                    hold.z += 0.006f * sin(time * 31f)
                    if (along > 0.9f) hold.y += 0.03f
                } else {
                    // Sketching: a short loop somewhere on the page, then lift and move.
                    val a = stroke.t * MathUtils.PI2 * 1.3f + r() * MathUtils.PI2
                    hold.set(work).add((r() - 0.5f) * 0.12f + 0.025f * cos(a), 0f, (r() - 0.5f) * 0.08f + 0.018f * sin(1.7f * a))
                    if (stroke.lifted) hold.y += 0.035f
                }
                hold.y += 0.02f
            } else {
                // Holding the page down, at its corner nearer that hand.
                hold.set(work).add(0f, 0.02f, 0f)
                toLocal(hold)
                hold.x = side * 0.17f
                return reach(side)
            }
        } else {
            if (working) {
                if (stroke.n % PALETTE_EVERY == PALETTE_EVERY - 1) {
                    // A dip in the paint: his hands meet at the palette.
                    palette(-side)
                    hold.x += side * 0.08f
                    return reach(side)
                }
                // A dab: from a spot on the canvas, a short drag, then back off it.
                val angle = r() * MathUtils.PI2
                val drag = 0.07f * stroke.t
                hold.set(work).add((r() - 0.5f) * 0.3f + drag * cos(angle), (r() - 0.5f) * 0.34f + drag * sin(angle), 0f)
                if (stroke.lifted) hold.z += 0.07f
            } else {
                palette(side)
                return reach(side)
            }
        }
        toLocal(hold)
        reach(side)
    }

    /** Where hand [side] holds the palette, out to his side and low, in his own frame. */
    private fun palette(side: Float) {
        hold.set(side * (measure.armX + 0.05f), measure.bottom + measure.armY - 0.06f, 0.16f)
    }

    private fun toLocal(v: Vector3) {
        v.mul(inverse.set(root).inv())
    }

    /** Pulls [hold] back within arm's length of shoulder [side]. */
    private fun reach(side: Float) {
        val sx = side * measure.armX
        val sy = FEET + measure.bottom + measure.armY
        val dx = hold.x - sx
        val dy = hold.y - sy
        val dz = hold.z
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        if (d > REACH) hold.set(sx + dx / d * REACH, sy + dy / d * REACH, dz / d * REACH)
    }

    /** Queues him on [r], between its begin and end: his shadow (not while [asleep]), parts and face. */
    fun render(r: CrayonRenderer, asleep: Boolean) {
        if (!asleep) r.shadow(shadowTransform)
        face.texture = faces[expression.ordinal][if (shut) 1 else 0][r.frame]
        r.model(body.model, body.transform, face)
        for (p in parts) if (p !== body) r.model(p.model, p.transform)
    }

    override fun dispose() {
        for (p in parts) p.model.dispose()
        faces.flatten().flatten().forEach { it.dispose() }
        grain.dispose()
    }

    // --- textures, in the workshop's 1024 x 512 design units -----------------------------------------

    /** The body's colour, laid down in thousands of short crayon strokes a shade either side of it. */
    private fun paintGrain(x: CrayonCanvas) {
        val rand = Rng(4242)
        x.fill(Color.valueOf(BODY))
        val light = shade(BODY, 0.05f)
        val dark = shade(BODY, -0.06f, 0.03f)
        repeat(6500) {
            val px = rand() * DESIGN_W
            val py = rand() * DESIGN_H
            val len = 8 + rand() * 20
            val a = -0.7f + (rand() - 0.5f) * 0.5f
            val c = if (rand() < 0.5f) light else dark
            val alpha = 0.3f + rand() * 0.35f
            val width = 1.5f + rand() * 2.2f
            x.stroke(floatArrayOf(px, py, px + cos(a) * len, py + sin(a) * len), width, c, alpha)
        }
    }

    /**
     * Draws [face] in pen. The texture wraps once around him with the front at u = 0.25 (x = 256);
     * left in the image is his right, as you look at him.
     */
    private fun paintFace(x: CrayonCanvas, face: Face, blink: Boolean, rand: Rng) {
        val cx = 256f
        val ey = 216f
        val gap = 64f
        val er = 29f
        val my = ey + 66
        val happy = face == Face.Happy
        val br = if (happy) 30f else 25f
        for (s in SIDES) x.crayonFill(cx + s * 118, ey + 46, br, br * 0.75f, BLUSH, rand, if (happy) 0.85f else 0.6f)
        val shut = blink || face == Face.Asleep
        for (s in SIDES) {
            val ex = cx + s * gap
            when {
                shut -> x.penStroke(arcPts(ex, ey - 6, 24f, 20f, 160f, rand), 8f, INK, rand)
                happy -> x.penStroke(arcPts(ex, ey + 12, 24f, 200f, 340f, rand), 9f, INK, rand)
                face == Face.Thinking -> inkEye(x, ex + 8, ey - 12, er, rand)
                face == Face.Tired -> {
                    x.clipRect(ex - 50, ey - 2, 100f, 60f)
                    inkEye(x, ex, ey, er, rand)
                    x.unclip()
                    x.penStroke(linePts(ex - 33, ey - 2, ex + 33, ey - 2, rand, 2f), 7f, INK, rand)
                }
                face == Face.Sad -> {
                    inkEye(x, ex, ey + 6, er * 0.92f, rand)
                    x.penStroke(linePts(ex + s * 34, ey - 36, ex - s * 12, ey - 52, rand), 7f, INK, rand)
                }
                else -> inkEye(x, ex, ey, er, rand)
            }
        }
        when (face) {
            Face.Happy -> {
                val pts = arcPts(cx, my - 16, 30f, 0f, 180f, rand, 0.03f)
                x.fillPath(pts, MOUTH)
                x.clip(pts)
                x.fillPath(blobPath(cx, my + 14, 16f, 12f, rand), TONGUE)
                x.unclip()
                x.penStroke(pts + pts.copyOfRange(0, 2), 6f, INK, rand)
            }
            Face.Neutral -> x.penStroke(arcPts(cx, my - 24, 24f, 35f, 145f, rand), 7f, INK, rand)
            Face.Sad -> x.penStroke(arcPts(cx, my + 22, 24f, 215f, 325f, rand), 7f, INK, rand)
            Face.Tired -> x.penStroke(linePts(cx - 16, my, cx + 16, my + 4, rand), 6.5f, INK, rand)
            Face.Thinking -> x.penStroke(linePts(cx + 2, my, cx + 32, my - 9, rand), 6.5f, INK, rand)
            Face.Asleep -> {
                val p = blobPath(cx, my, 11f, 13f, rand)
                x.fillPath(p, MOUTH)
                x.stroke(p, 5f, INK)
            }
        }
    }

    private fun inkEye(x: CrayonCanvas, ex: Float, ey: Float, r: Float, rand: Rng) {
        x.fillPath(blobPath(ex, ey, r * 0.92f, r, rand, 0.04f), INK)
        x.fillPath(blobPath(ex - r * 0.3f, ey - r * 0.34f, r * 0.36f, r * 0.36f, rand, 0.08f), PAPER_WHITE)
        x.fillPath(blobPath(ex + r * 0.36f, ey + r * 0.38f, r * 0.13f, r * 0.13f, rand), PAPER_WHITE)
    }

    private fun angleDiff(target: Float, from: Float): Float = ((target - from + 540f) % 360f) - 180f

    private companion object {
        /** His size: the body is [RADIUS] across before this. */
        const val SCALE = 1.25f
        const val RADIUS = 0.3f
        /** How far the body sits above his feet' soles. */
        const val FEET = 0.03f
        const val SHADOW_LIFT = 0.045f
        /** His shadow's size, before it's widened to his arms and shrunk as he hops. */
        const val SHADOW_W = 0.62f
        const val SHADOW_D = 0.48f
        const val TURN_SPEED = 600f
        const val PLAY_TO_CAMERA = 0.4f
        /** At work: how far he leans (degrees), and his arm's reach (his own units). */
        const val LEAN_FLAT = 8f
        const val LEAN_UPRIGHT = 3f
        const val PLAY_AT_WORK = 0.25f
        const val REACH = 0.48f
        /** Seconds a stroke takes, and the part of it his hand is down. */
        const val STROKE = 0.85f
        const val STROKE_DOWN = 0.8f
        /** Seconds to write a line. */
        const val LINE = 1.6f
        /** Every this many strokes, he dips his brush in the palette. */
        const val PALETTE_EVERY = 6
        const val WALK_RATE = 13f
        const val SPRING = 160f
        const val DAMPING = 9f
        const val BLINK = 0.13f
        const val BOIL_FRAMES = CrayonModel.BOIL_FRAMES

        /** The workshop's texture size, in which the face and grain are drawn, and the scale they're kept at. */
        const val DESIGN_W = 1024f
        const val DESIGN_H = 512f
        const val TEX_SCALE = 0.5f
        val BODY_W = (DESIGN_W * TEX_SCALE).toInt()
        val BODY_H = (DESIGN_H * TEX_SCALE).toInt()
        /** Where the face's own texture sits on the body's, in design units; the shader swaps it in there. */
        const val FACE_X = 96f
        const val FACE_Y = 148f
        const val FACE_W = 320f
        const val FACE_H = 160f
        val SIDES = floatArrayOf(-1f, 1f)

        const val BODY = "#ffe3a1"
        val LIMB = shade(BODY, -0.02f)
        val FOOT = shade(BODY, -0.055f, 0.02f)
        val INK = CrayonRenderer.INK
        val PAPER_WHITE: Color = Color.valueOf("#fffdf8")
        val BLUSH: Color = Color.valueOf("#ff9fb4")
        val MOUTH: Color = Color.valueOf("#b05a6e")
        val TONGUE: Color = Color.valueOf("#ff9aaa")
    }
}
