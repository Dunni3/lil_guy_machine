package dev.lilguy.app

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.PerspectiveCamera
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g3d.Environment
import com.badlogic.gdx.graphics.g3d.Material
import com.badlogic.gdx.graphics.g3d.Model
import com.badlogic.gdx.graphics.g3d.ModelBatch
import com.badlogic.gdx.graphics.g3d.ModelInstance
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder
import com.badlogic.gdx.graphics.VertexAttributes.Usage
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.Disposable
import com.badlogic.gdx.utils.viewport.FitViewport
import dev.lilguy.agent.art.Drawing
import dev.lilguy.sim.EntitySnapshot
import dev.lilguy.sim.Intent
import dev.lilguy.sim.IntentStatus
import dev.lilguy.sim.LifeSnapshot
import dev.lilguy.sim.Terrain
import dev.lilguy.sim.TileMap
import dev.lilguy.sim.WorldSnapshot
import kotlin.math.min
import kotlin.math.tan

/**
 * The room in 3D, the spike of `research_notes/2026-10-29_2053_3d-room-and-character-plan.md`: a
 * fixed-camera cutaway of the room (no front wall, no ceiling), code-built furniture placed where
 * the snapshot says ([RoomModels]), the guy as an animated [Blob], light and the window's sky
 * following the game clock, and a small HUD with a bubble over his head. His art shows too: hung
 * pieces in frames on the picture rail, and his newest unsold piece on the desk (a sketch) or the
 * easel (a painting), each a small texture from [ArtTextures].
 *
 * Shown as the dev view's first page ([DevView.Page.Room]); the other pages are the 2D dev view.
 * Fill rate stays low for the Streamer: one clear, opaque near-flat-lit geometry, one directional
 * light plus ambient, no shadow maps or post-processing; the only blended things are the blob
 * shadow and the HUD's small rectangles.
 */
class RoomView(private val art: ArtTextures) : Disposable {
    private val viewport = FitViewport(DevView.WIDTH, DevView.HEIGHT)
    private val camera = PerspectiveCamera(FOV, DevView.WIDTH, DevView.HEIGHT).apply {
        near = 1f
        far = 60f
    }
    private val models = RoomModels()
    private val blob = Blob()
    private val pose = Blob.Pose()
    private val modelBatch = ModelBatch()
    private val ambient = ColorAttribute(ColorAttribute.AmbientLight, 0.7f, 0.7f, 0.7f, 1f)
    private val sun = DirectionalLight().set(0.4f, 0.4f, 0.4f, SUN_DIRECTION)
    private val env = Environment().apply {
        set(ambient)
        add(sun)
    }
    private val glassModel: Model
    private val glass: ModelInstance
    /** The glass instance's own emissive colour (instances copy their model's materials). */
    private val glassColor: ColorAttribute
    private val hourHand = ModelInstance(models.hand(0.1f))
    private val minuteHand = ModelInstance(models.hand(0.15f))
    private var clockX = 0f
    private val instances = HashMap<String, ModelInstance>()
    private var room: ModelInstance? = null

    /** Frames on the rail, one per wall place, and a textured quad per piece shown (by "piece/flat"), for [quadsRun]. */
    private val frames = List(RoomModels.FRAME_XS.size) { ModelInstance(models.frame()) }
    private val quads = HashMap<String, Pair<Model, ModelInstance>>()
    private var quadsRun = -1
    private val railAt = Matrix4()
    private var framed: TileMap? = null

    private val shapes = ShapeRenderer()
    private val batch = SpriteBatch()
    private val font = BitmapFont().apply {
        region.texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
        setUseIntegerPositions(false)
    }
    private val glyphs = GlyphLayout()
    private val head = Vector3()
    /** What's beyond the room, dimmed with the light; the game clears to it before [render] (one frame behind). */
    val background = Color(0.15f, 0.13f, 0.16f, 1f)
    private val light = Light()
    private val blobTint = Color(Color.WHITE)

    /** The newest thought, and how long it has been on show. */
    private var thoughtCount = 0
    private var thought: String? = null
    private var thoughtAge = 0f

    init {
        glassModel = ModelBuilder().createBox(
            RoomModels.WINDOW_WIDTH, RoomModels.WINDOW_TOP - RoomModels.WINDOW_BOTTOM, 0.01f,
            Material(ColorAttribute.createDiffuse(Color.BLACK), ColorAttribute.createEmissive(Color.SKY)),
            (Usage.Position or Usage.Normal).toLong(),
        )
        glass = ModelInstance(glassModel)
        glassColor = glass.materials.first().get(ColorAttribute.Emissive) as ColorAttribute
    }

    fun resize(width: Int, height: Int) = viewport.update(width, height, true)

    /** One frame of the live world; [banner] is the run-state line the dev view would show, if any. */
    fun render(session: SimSession, banner: String?, delta: Float) {
        val now = session.current
        val before = session.previous
        val life = now.life
        val agent = now.agents.firstOrNull()
        val guy = now.entities.first { it.kind == "guy" }
        val guyBefore = before.entities.firstOrNull { it.id == guy.id } ?: guy
        val alpha = session.alpha

        frame(now.map)
        lightFor(life)
        viewport.apply()

        pose.x = MathUtils.lerp(guyBefore.x, guy.x, alpha)
        pose.z = MathUtils.lerp(guyBefore.y, guy.y, alpha)
        pose.facing = guy.facingDegrees
        pose.moving = guyBefore.x != guy.x || guyBefore.y != guy.y
        pose.doing = life?.doing
        pose.energy = life?.energy ?: 70.0
        pose.mood = life?.mood ?: 60.0
        pose.music = life?.music != null
        pose.thinking = session.thinkingFor != null ||
            (agent?.intent == Intent.Think && agent.status.let { it == IntentStatus.Executing || it == IntentStatus.Validated || it == IntentStatus.Submitted })
        val asleep = pose.doing == "sleep" && !pose.moving
        // On the bed's tile he is on the mattress; standing still on a chair's tile, he sits on it.
        val under = now.entities.firstOrNull { it.id.value in SURFACES && it.x.toInt() == guy.x.toInt() && it.y.toInt() == guy.y.toInt() }?.id?.value
        pose.standY = if (under == "chair" && pose.moving) 0f else SURFACES[under] ?: 0f
        blob.update(delta, pose)

        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST)
        modelBatch.begin(camera)
        room?.let { modelBatch.render(it, env) }
        modelBatch.render(glass, env)
        modelBatch.render(hourHand, env)
        modelBatch.render(minuteHand, env)
        for (e in now.entities) {
            if (e.carriedBy != null || e.kind == "guy") continue
            if (e.kind == "door" && e.state == "open") continue
            modelBatch.render(placed(e, now.map), env)
        }
        renderArt(session, now)
        modelBatch.end()
        blob.render(camera, blobTint, asleep)
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST)

        updateThought(session, delta)
        val bubble = agent?.saying ?: when {
            asleep -> "z z z"
            thought != null -> thought
            pose.thinking -> "..."
            else -> null
        }
        camera.project(head.set(blob.headTop), 0f, 0f, DevView.WIDTH, DevView.HEIGHT)
        drawHud(session, life, bubble, banner)
    }

    // --- 3D --------------------------------------------------------------------------------------

    /** Points the camera at [map]'s room (once per map): a front view from above, the whole room in frame. */
    private fun frame(map: TileMap) {
        if (framed === map) return
        framed = map
        room = ModelInstance(models.room(map))
        instances.clear()
        clockX = models.clockX(map)
        glass.transform.setToTranslation(models.window(map), (RoomModels.WINDOW_BOTTOM + RoomModels.WINDOW_TOP) / 2, 1.012f)
        val halfFovX = tan(FOV / 2 * MathUtils.degreesToRadians) * DevView.WIDTH / DevView.HEIGHT
        val distance = (map.width / 2f + SIDE_MARGIN) / halfFovX
        val target = Vector3(map.width / 2f, 1.1f, map.height / 2f + 0.2f)
        camera.position.set(target).add(0f, MathUtils.sinDeg(PITCH) * distance, MathUtils.cosDeg(PITCH) * distance)
        camera.up.set(Vector3.Y)
        camera.lookAt(target)
        camera.update()
    }

    /** An item's instance, placed at its tile; wall items sit against the wall face that looks into the room. */
    private fun placed(e: EntitySnapshot, map: TileMap): ModelInstance {
        val id = e.id.value
        val instance = instances.getOrPut(id) { ModelInstance(models.item(id, e.kind)) }
        val tx = e.x.toInt()
        val ty = e.y.toInt()
        if (map[tx, ty] == Terrain.Wall && models.wallItem(id, e.kind)) {
            val (dx, dy) = INWARD.firstOrNull { (dx, dy) -> map[tx + dx, ty + dy] == Terrain.Floor } ?: (0 to 1)
            val yaw = when (dx to dy) { 1 to 0 -> 90f; -1 to 0 -> -90f; 0 to -1 -> 180f; else -> 0f }
            instance.transform.setToTranslation(tx + 0.5f + dx * 0.5f, 0f, ty + 0.5f + dy * 0.5f).rotate(Vector3.Y, yaw)
        } else {
            instance.transform.setToTranslation(e.x, 0f, e.y)
        }
        return instance
    }

    /**
     * Hung pieces in frames along the picture rail, oldest at the left; then the newest unsold
     * piece of each drawn item on its stand ([STANDS]). Pieces not drawn yet show as an empty frame or nothing.
     */
    private fun renderArt(session: SimSession, now: WorldSnapshot) {
        val pieces = now.life?.art?.takeIf { it.isNotEmpty() } ?: return
        art.sync(session)
        if (quadsRun != art.run) {
            quads.values.forEach { it.first.dispose() }
            quads.clear()
            quadsRun = art.run
        }
        val drawings = art.drawings(session)
        val rail = instances["picture_rail"]
        if (rail != null && now.entities.any { it.id.value == "picture_rail" }) {
            pieces.filter { it.hung }.take(frames.size).forEachIndexed { i, p ->
                railAt.set(rail.transform).translate(RoomModels.FRAME_XS[i], RoomModels.FRAME_BOTTOM, 0f)
                frames[i].transform.set(railAt)
                modelBatch.render(frames[i], env)
                quad(drawings[p.id], RoomModels.FRAME_ART, flat = false)?.let { q ->
                    q.transform.set(railAt).translate(0f, RoomModels.FRAME / 2, RoomModels.FRAME_ART_Z)
                    modelBatch.render(q, env)
                }
            }
        }
        for ((item, stand) in STANDS) {
            val p = pieces.lastOrNull { !it.hung && it.item == item } ?: continue
            val e = now.entities.firstOrNull { it.id.value == stand.id } ?: continue
            val q = quad(drawings[p.id], stand.size, stand.flat) ?: continue
            q.transform.setToTranslation(e.x + stand.x, stand.y, e.y + stand.z)
            modelBatch.render(q, env)
        }
    }

    /** A square of the piece's picture, [size] wide, facing +Z, or lying face up when [flat]; null until it's drawn. */
    private fun quad(drawing: Drawing?, size: Float, flat: Boolean): ModelInstance? {
        val d = drawing ?: return null
        val texture = art.of(d) ?: return null
        return quads.getOrPut("${d.piece}/$flat") {
            val material = Material(TextureAttribute.createDiffuse(texture))
            val attributes = (Usage.Position or Usage.Normal or Usage.TextureCoordinates).toLong()
            val h = size / 2
            val model = if (flat) {
                ModelBuilder().createRect(-h, 0f, h, h, 0f, h, h, 0f, -h, -h, 0f, -h, 0f, 1f, 0f, material, attributes)
            } else {
                ModelBuilder().createRect(-h, -h, 0f, h, -h, 0f, h, h, 0f, -h, h, 0f, 0f, 0f, 1f, material, attributes)
            }
            model to ModelInstance(model)
        }.second
    }

    /** Ambient, sun, sky, background and the wall clock for the time of day; the bedroom's clock, or noon without one. */
    private fun lightFor(life: LifeSnapshot?) {
        val hour = life?.clock?.time?.let { t ->
            val (h, m) = t.split(':').map { it.toIntOrNull() ?: 0 }
            h + m / 60f
        } ?: 12f
        light.at(hour)
        hourHand.transform.setToTranslation(clockX, RoomModels.CLOCK_Y, 1.06f).rotate(Vector3.Z, -30f * hour)
        minuteHand.transform.setToTranslation(clockX, RoomModels.CLOCK_Y, 1.07f).rotate(Vector3.Z, -360f * hour)
        ambient.color.set(light.ambient)
        sun.color.set(light.sun)
        // The guy shades himself; the room's light only tints him, white at midday.
        val a = light.ambient
        val s = light.sun
        blobTint.set((a.r + s.r) / DAY_LIGHT[0], (a.g + s.g) / DAY_LIGHT[1], (a.b + s.b) / DAY_LIGHT[2], 1f)
        glassColor.color.set(light.sky)
        background.set(light.ambient).mul(0.22f, 0.2f, 0.24f, 1f)
    }

    // --- HUD -------------------------------------------------------------------------------------

    private fun updateThought(session: SimSession, delta: Float) {
        val thoughts = session.memory.thoughts()
        if (thoughts.size < thoughtCount) thoughtCount = 0
        if (thoughts.size > thoughtCount) {
            thoughtCount = thoughts.size
            thought = thoughts.last().text?.let { if (it.length > THOUGHT_CHARS) it.take(THOUGHT_CHARS).trimEnd() + "..." else it }
            thoughtAge = 0f
        }
        thoughtAge += delta
        if (thoughtAge > THOUGHT_SECONDS) thought = null
    }

    private fun drawHud(session: SimSession, life: LifeSnapshot?, bubble: String?, banner: String?) {
        shapes.projectionMatrix = viewport.camera.combined
        batch.projectionMatrix = viewport.camera.combined
        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)

        shapes.begin(ShapeRenderer.ShapeType.Filled)
        life?.let { cardShape(it) }
        bubble?.let { bubbleShape(it) }
        banner?.let { bannerShape(it) }
        shapes.end()

        batch.begin()
        life?.let { cardText(session, it) }
        bubble?.let { bubbleText(it) }
        banner?.let { bannerText(it) }
        font.data.setScale(HINT_SCALE)
        font.color = HINT
        font.draw(batch, "ROOM · LEFT/RIGHT for the dev pages", DevView.WIDTH - MARGIN - 600f, DevView.HEIGHT - MARGIN, 600f, Align.right, false)
        batch.end()
    }

    private fun cardShape(life: LifeSnapshot) {
        shapes.color = CARD
        roundRect(MARGIN, DevView.HEIGHT - MARGIN - CARD_HEIGHT, CARD_WIDTH, CARD_HEIGHT, 18f)
        val needs = listOf(life.energy to ENERGY, life.fullness to FULLNESS, life.mood to MOOD)
        for ((i, need) in needs.withIndex()) {
            val y = DevView.HEIGHT - MARGIN - 86f - i * BAR_PITCH
            shapes.color = BAR_BG
            roundRect(BAR_X, y, BAR_WIDTH, BAR_HEIGHT, BAR_HEIGHT / 2)
            shapes.color = need.second
            val w = (BAR_WIDTH * (need.first / 100.0).toFloat().coerceIn(0f, 1f)).coerceAtLeast(BAR_HEIGHT)
            roundRect(BAR_X, y, w, BAR_HEIGHT, BAR_HEIGHT / 2)
        }
    }

    /** Clock and money, the need bars' labels, and what he's doing. */
    private fun cardText(session: SimSession, life: LifeSnapshot) {
        val left = MARGIN + 28f
        font.data.setScale(CLOCK_SCALE)
        font.color = INK
        font.draw(batch, "Day ${life.clock.day} · ${life.clock.time}   $${life.money}", left, DevView.HEIGHT - MARGIN - 22f)
        font.data.setScale(LABEL_SCALE)
        font.color = MUTED
        for ((i, label) in listOf("energy", "food", "mood").withIndex()) {
            font.draw(batch, label, left, DevView.HEIGHT - MARGIN - 86f - i * BAR_PITCH + BAR_HEIGHT - 1f)
        }
        font.data.setScale(DOING_SCALE)
        font.color = INK
        val doing = life.doing?.let { d -> d.replace('_', ' ') + (life.progress?.let { " · ${(it * 100).toInt()}%" } ?: "") }
        font.draw(batch, doing ?: if (session.thinkingFor != null) "deciding..." else "idle", left, DevView.HEIGHT - MARGIN - CARD_HEIGHT + 40f)
    }

    private fun bubbleLayout(text: String): GlyphLayout {
        font.data.setScale(BUBBLE_SCALE)
        glyphs.setText(font, text, INK, BUBBLE_MAX_WIDTH, Align.center, true)
        return glyphs
    }

    /** Bubble rectangle as (x, y, w, h): above his head, kept on screen. */
    private fun bubbleRect(text: String): FloatArray {
        val l = bubbleLayout(text)
        val w = l.width + 2 * BUBBLE_PAD
        val h = l.height + 2 * BUBBLE_PAD
        val x = (head.x - w / 2).coerceIn(MARGIN, DevView.WIDTH - MARGIN - w)
        val y = min(head.y + 24f, DevView.HEIGHT - MARGIN - h)
        return floatArrayOf(x, y, w, h)
    }

    private fun bubbleShape(text: String) {
        val (x, y, w, h) = bubbleRect(text)
        shapes.color = BUBBLE
        roundRect(x, y, w, h, 16f)
        val tip = head.x.coerceIn(x + 20f, x + w - 20f)
        shapes.triangle(tip - 12f, y + 1f, tip + 12f, y + 1f, head.x, y - 16f)
    }

    private fun bubbleText(text: String) {
        val (x, y, w, h) = bubbleRect(text)
        bubbleLayout(text)
        font.draw(batch, glyphs, x + w / 2 - BUBBLE_MAX_WIDTH / 2, y + h - BUBBLE_PAD)
    }

    private fun bannerShape(text: String) {
        font.data.setScale(BANNER_SCALE)
        glyphs.setText(font, text)
        shapes.color = BANNER_BG
        roundRect(DevView.WIDTH / 2 - glyphs.width / 2 - 28, MARGIN, glyphs.width + 56, glyphs.height + 36, 14f)
    }

    private fun bannerText(text: String) {
        font.data.setScale(BANNER_SCALE)
        font.color = Color.WHITE
        glyphs.setText(font, text)
        font.draw(batch, text, DevView.WIDTH / 2 - glyphs.width / 2, MARGIN + glyphs.height + 18)
    }

    private fun roundRect(x: Float, y: Float, w: Float, h: Float, r: Float) {
        val rr = min(r, min(w, h) / 2)
        shapes.rect(x + rr, y, w - 2 * rr, h)
        shapes.rect(x, y + rr, rr, h - 2 * rr)
        shapes.rect(x + w - rr, y + rr, rr, h - 2 * rr)
        shapes.circle(x + rr, y + rr, rr, 12)
        shapes.circle(x + w - rr, y + rr, rr, 12)
        shapes.circle(x + rr, y + h - rr, rr, 12)
        shapes.circle(x + w - rr, y + h - rr, rr, 12)
    }

    override fun dispose() {
        quads.values.forEach { it.first.dispose() }
        models.dispose()
        blob.dispose()
        glassModel.dispose()
        modelBatch.dispose()
        shapes.dispose()
        batch.dispose()
        font.dispose()
    }

    /**
     * Light through the day, interpolated between [KEYS]: cool and dim at night (a warm lamp's worth
     * of ambient), warm at dawn and dusk, bright and near-flat by day.
     */
    private class Light {
        val ambient = Color()
        val sun = Color()
        val sky = Color()

        fun at(hour: Float) {
            val h = hour.mod(24f)
            val i = KEYS.indexOfLast { it.hour <= h }.coerceAtLeast(0)
            val a = KEYS[i]
            val b = KEYS[(i + 1) % KEYS.size]
            val span = (b.hour - a.hour).let { if (it <= 0f) it + 24f else it }
            val t = ((h - a.hour) / span).coerceIn(0f, 1f)
            ambient.set(a.ambient).lerp(b.ambient, t)
            sun.set(a.sun).lerp(b.sun, t)
            sky.set(a.sky).lerp(b.sky, t)
        }

        class Key(val hour: Float, ambient: String, sun: String, sky: String) {
            val ambient: Color = Color.valueOf(ambient)
            val sun: Color = Color.valueOf(sun)
            val sky: Color = Color.valueOf(sky)
        }

        companion object {
            val KEYS = listOf(
                Key(0f, "6a6488", "1c2038", "141a38"),
                Key(5.5f, "6a6488", "1c2038", "1c2448"),
                Key(7f, "c8aaa0", "8a6048", "f6b48c"),
                // A pale gold between dawn and day, so the sky doesn't pass through grey.
                Key(8.2f, "c8b8ac", "7a6450", "f8e0a8"),
                Key(9.5f, "c8c4b8", "6a665e", "9fd4f5"),
                Key(16.5f, "c8c4b8", "6a665e", "9fd4f5"),
                Key(19f, "c8a08c", "8a5030", "f08a5d"),
                Key(21f, "8c7678", "302028", "2a2850"),
            )
        }
    }

    private companion object {
        const val FOV = 30f
        /** Degrees the camera looks down. */
        const val PITCH = 40f
        /** Room beyond the side walls, in tiles, so the walls don't touch the screen edge. */
        const val SIDE_MARGIN = 0.9f
        /** Where an item's newest unsold piece shows: on which stand, offset from its tile centre, how big, and lying flat or upright. */
        class Stand(val id: String, val x: Float, val y: Float, val z: Float, val size: Float, val flat: Boolean)

        val STANDS = mapOf(
            "sketch" to Stand("desk", -0.12f, 0.567f, 0.04f, 0.2f, flat = true),
            "painting" to Stand("easel", 0f, 0.75f, 0.16f, 0.44f, flat = false),
        )

        /** What he stands, sits or lies on, by item id, and how high its top is. */
        val SURFACES = mapOf("bed" to 0.3f, "chair" to 0.38f)
        val SUN_DIRECTION = Vector3(0.45f, -1f, -0.55f)
        /** Ambient plus sun at midday (the 9:30 to 16:30 key), so the guy's tint is white then. */
        val DAY_LIGHT = Color.valueOf("c8c4b8").let { a ->
            Color.valueOf("6a665e").let { s -> floatArrayOf(a.r + s.r, a.g + s.g, a.b + s.b) }
        }
        /** (dx, dy) toward the room, from a wall tile. */
        val INWARD = listOf(0 to 1, 1 to 0, -1 to 0, 0 to -1)

        const val THOUGHT_SECONDS = 9f
        const val THOUGHT_CHARS = 150

        const val MARGIN = 36f
        const val CARD_WIDTH = 440f
        const val CARD_HEIGHT = 236f
        const val BAR_X = MARGIN + 120f
        const val BAR_WIDTH = 280f
        const val BAR_HEIGHT = 18f
        const val BAR_PITCH = 34f
        const val CLOCK_SCALE = 2.0f
        const val LABEL_SCALE = 1.3f
        const val DOING_SCALE = 1.6f
        const val HINT_SCALE = 1.2f
        const val BUBBLE_SCALE = 1.6f
        const val BANNER_SCALE = 1.8f
        const val BUBBLE_PAD = 16f
        const val BUBBLE_MAX_WIDTH = 440f

        val CARD = Color.valueOf("fff8eeff")
        val INK = Color.valueOf("3a2a20ff")
        val MUTED = Color.valueOf("8a7464ff")
        val HINT = Color.valueOf("ffffff88")
        val BAR_BG = Color.valueOf("e8dccbff")
        val ENERGY = Color.valueOf("f5b942ff")
        val FULLNESS = Color.valueOf("6cc070ff")
        val MOOD = Color.valueOf("6fa8e6ff")
        val BUBBLE = Color.valueOf("fffdf6ff")
        val BANNER_BG = Color.valueOf("2a1e18ff")
    }
}
