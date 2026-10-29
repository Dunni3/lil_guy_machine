package dev.lilguy.app

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.Disposable
import com.badlogic.gdx.utils.viewport.FitViewport
import dev.lilguy.agent.ActionExamples
import dev.lilguy.sim.EntitySnapshot
import dev.lilguy.sim.Intent
import dev.lilguy.sim.IntentStatus
import dev.lilguy.sim.Terrain
import dev.lilguy.sim.WorldSnapshot
import kotlinx.serialization.json.Json
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * Temporary 2D dev view: the room from above on the left, the agent's state on the right. A
 * stand-in until the 3D renderer (milestone 2). Laid out on a fixed 1920x1080 canvas and scaled
 * to fit. Fill rate stays low for the Streamer: one clear, then small shapes and text; no
 * full-screen layers.
 */
class DevView : Disposable {
    private val viewport = FitViewport(WIDTH, HEIGHT)
    private val shapes = ShapeRenderer()
    private val batch = SpriteBatch()
    private val font = BitmapFont().apply {
        region.texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
        setUseIntegerPositions(false)
    }
    private val glyphs = GlyphLayout()
    private val json = Json

    /** Back toggles between the full panel and a big-type summary. */
    var detail = true
    /** How many events the list is scrolled back from the newest. */
    var scroll = 0

    // Map placement, recomputed per frame from the map size.
    private var tile = 0f
    private var mapX = 0f
    private var mapTop = 0f

    fun resize(width: Int, height: Int) = viewport.update(width, height, true)

    fun render(session: SimSession, fps: Int) {
        val now = session.current
        val before = session.previous
        val agent = now.agents.firstOrNull()
        val guy = now.entities.first { it.kind == "guy" }
        val guyBefore = before.entities.firstOrNull { it.id == guy.id } ?: guy
        val gx = MathUtils.lerp(guyBefore.x, guy.x, session.alpha)
        val gy = MathUtils.lerp(guyBefore.y, guy.y, session.alpha)
        val bubble = agent?.saying ?: if (session.thinkingFor != null) "..." else null

        layoutMap(now)
        viewport.apply()
        shapes.projectionMatrix = viewport.camera.combined
        batch.projectionMatrix = viewport.camera.combined

        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
        shapes.begin(ShapeRenderer.ShapeType.Filled)
        drawTerrain(now)
        agent?.path?.forEach { shapes.color = PATH; shapes.circle(sx(it.x + 0.5f), sy(it.y + 0.5f), tile * 0.09f, 12) }
        now.entities.forEach { if (it.carriedBy == null) drawEntity(it) }
        drawGuy(gx, gy, guy.facingDegrees, carrying = agent?.inventory ?: emptyList())
        bubble?.let { bubbleShape(it, gx, gy) }
        banner(session)?.let { bannerShape(it) }
        shapes.color = RULE
        shapes.rect(PANEL_X - 24f, MARGIN, 2f, HEIGHT - 2 * MARGIN)
        shapes.end()

        batch.begin()
        bubble?.let { bubbleText(it, gx, gy) }
        banner(session)?.let { bannerText(it) }
        drawPanel(session, now, fps)
        batch.end()
    }

    // --- map -----------------------------------------------------------------------------------

    private fun layoutMap(s: WorldSnapshot) {
        tile = floor(min(MAP_WIDTH / s.map.width, (HEIGHT - 2 * MARGIN) / s.map.height))
        mapX = MARGIN + (MAP_WIDTH - tile * s.map.width) / 2
        mapTop = HEIGHT - (HEIGHT - tile * s.map.height) / 2
    }

    /** Grid to canvas. Grid y grows south; canvas y grows up. */
    private fun sx(gx: Float) = mapX + gx * tile
    private fun sy(gy: Float) = mapTop - gy * tile

    private fun drawTerrain(s: WorldSnapshot) {
        val exit = s.entities.firstOrNull { it.kind == "exit" }
        for (y in 0 until s.map.height) for (x in 0 until s.map.width) {
            val x0 = sx(x.toFloat())
            val y0 = sy(y + 1f)
            when (s.map[x, y]) {
                Terrain.Floor -> {
                    val isExit = exit != null && exit.x.toInt() == x && exit.y.toInt() == y
                    shapes.color = if (isExit) EXIT else FLOOR
                    shapes.rect(x0 + 1, y0 + 1, tile - 2, tile - 2)
                }
                Terrain.Wall -> {
                    shapes.color = WALL
                    shapes.rect(x0, y0, tile, tile)
                    shapes.color = WALL_TOP
                    shapes.rect(x0 + 4, y0 + 4, tile - 8, tile - 8)
                }
                Terrain.Void -> Unit
            }
        }
    }

    private fun drawEntity(e: EntitySnapshot) {
        val cx = sx(e.x)
        val cy = sy(e.y)
        val t = tile
        when (e.kind) {
            "door" -> if (e.state == "locked") {
                shapes.color = DOOR_LOCKED
                shapes.rect(cx - t / 2 + 3, cy - t / 2 + 3, t - 6, t - 6)
                shapes.color = DOOR_DARK
                shapes.circle(cx, cy + t * 0.06f, t * 0.09f, 16)
                shapes.rect(cx - t * 0.04f, cy - t * 0.2f, t * 0.08f, t * 0.22f)
            } else {
                shapes.color = DOOR_OPEN
                val h = t / 2 - 4
                shapes.rectLine(cx - h, cy - h, cx + h, cy - h, 5f)
                shapes.rectLine(cx - h, cy + h, cx + h, cy + h, 5f)
            }
            "exit" -> {
                shapes.color = DOOR_OPEN
                shapes.triangle(cx - t * 0.15f, cy + t * 0.22f, cx - t * 0.15f, cy - t * 0.22f, cx + t * 0.22f, cy)
            }
            "key" -> drawKey(cx, cy, t)
            "apple" -> {
                shapes.color = APPLE
                shapes.circle(cx, cy - t * 0.03f, t * 0.2f, 20)
                shapes.color = LEAF
                shapes.rect(cx, cy + t * 0.12f, t * 0.12f, t * 0.08f)
            }
            "book" -> {
                shapes.color = BOOK
                shapes.rect(cx - t * 0.2f, cy - t * 0.15f, t * 0.4f, t * 0.3f)
                shapes.color = BOOK_SPINE
                shapes.rect(cx - t * 0.2f, cy - t * 0.15f, t * 0.07f, t * 0.3f)
            }
            else -> {
                shapes.color = Color.MAGENTA
                shapes.circle(cx, cy, t * 0.15f, 12)
            }
        }
    }

    private fun drawKey(cx: Float, cy: Float, size: Float) {
        shapes.color = KEY
        shapes.circle(cx - size * 0.16f, cy, size * 0.15f, 18)
        shapes.rect(cx - size * 0.05f, cy - size * 0.04f, size * 0.32f, size * 0.08f)
        shapes.rect(cx + size * 0.14f, cy - size * 0.14f, size * 0.06f, size * 0.1f)
        shapes.rect(cx + size * 0.22f, cy - size * 0.14f, size * 0.05f, size * 0.1f)
        shapes.color = FLOOR
        shapes.circle(cx - size * 0.16f, cy, size * 0.06f, 12)
    }

    private fun drawGuy(gx: Float, gy: Float, facingDegrees: Float, carrying: List<String>) {
        val cx = sx(gx)
        val cy = sy(gy)
        val r = tile * 0.36f
        shapes.color = GUY_OUTLINE
        shapes.circle(cx, cy, r + 3, 28)
        shapes.color = GUY
        shapes.circle(cx, cy, r, 28)
        // Eyes look the way he faces (compass degrees: 0 east, 90 north, which is canvas up).
        val rad = facingDegrees * MathUtils.degreesToRadians
        val fx = cos(rad)
        val fy = sin(rad)
        shapes.color = GUY_OUTLINE
        for (side in listOf(-1f, 1f)) {
            shapes.circle(cx + fx * r * 0.45f - fy * side * r * 0.35f, cy + fy * r * 0.45f + fx * side * r * 0.35f, r * 0.16f, 10)
        }
        if ("key" in carrying) drawKey(cx + r * 0.9f, cy - r * 0.9f, tile * 0.6f)
    }

    // --- speech bubble and banners -------------------------------------------------------------

    private fun bubbleLayout(text: String): GlyphLayout {
        font.data.setScale(BUBBLE_SCALE)
        glyphs.setText(font, text, BUBBLE_INK, BUBBLE_MAX_WIDTH, Align.center, true)
        return glyphs
    }

    /** Bubble rectangle as (x, y, w, h), kept inside the map. */
    private fun bubbleRect(text: String, gx: Float, gy: Float): FloatArray {
        val l = bubbleLayout(text)
        val w = l.width + 2 * BUBBLE_PAD
        val h = l.height + 2 * BUBBLE_PAD
        val x = (sx(gx) - w / 2).coerceIn(mapX, mapX + MAP_WIDTH - w)
        val y = min(sy(gy) + tile * 0.6f, HEIGHT - MARGIN - h)
        return floatArrayOf(x, y, w, h)
    }

    private fun bubbleShape(text: String, gx: Float, gy: Float) {
        val (x, y, w, h) = bubbleRect(text, gx, gy)
        val cx = sx(gx)
        shapes.color = BUBBLE
        shapes.rect(x, y, w, h)
        shapes.triangle(cx - 10, y, cx + 10, y, cx, y - 12)
    }

    private fun bubbleText(text: String, gx: Float, gy: Float) {
        val (x, y, w, h) = bubbleRect(text, gx, gy)
        bubbleLayout(text)
        font.draw(batch, glyphs, x + w / 2 - glyphs.width / 2, y + h - BUBBLE_PAD)
    }

    private fun banner(session: SimSession): String? {
        val solved = session.solvedFor
        return when {
            solved != null -> "ESCAPED in ${session.current.tick} ticks · next episode in ${(session.resetDelay - solved).toInt() + 1} s"
            session.paused -> if (session.viewBack > 0) "PAUSED · ${session.viewBack} ticks back · LEFT/RIGHT step · OK resumes live"
            else "PAUSED · LEFT steps back a tick, RIGHT forward"
            else -> null
        }
    }

    private fun bannerShape(text: String) {
        font.data.setScale(BANNER_SCALE)
        glyphs.setText(font, text)
        shapes.color = BANNER_BG
        shapes.rect(mapX + MAP_WIDTH / 2 - glyphs.width / 2 - 24, MARGIN, glyphs.width + 48, glyphs.height + 32)
    }

    private fun bannerText(text: String) {
        font.data.setScale(BANNER_SCALE)
        font.color = TEXT
        glyphs.setText(font, text)
        font.draw(batch, text, mapX + MAP_WIDTH / 2 - glyphs.width / 2, MARGIN + glyphs.height + 16)
    }

    // --- panel ---------------------------------------------------------------------------------

    private var cursor = 0f

    private fun line(text: String, scale: Float, color: Color, maxLines: Int = 3, gapAfter: Float = 0.25f) {
        font.data.setScale(scale)
        font.color = color
        // Wrap, then cut to maxLines by truncating the source; BitmapFont can only truncate one line.
        glyphs.setText(font, text, color, PANEL_WIDTH, Align.left, true)
        var shown = text
        if (glyphs.runs.size > maxLines) {
            val keep = (text.length * maxLines / glyphs.runs.size).coerceAtLeast(1)
            shown = text.take(keep - 3) + "..."
            glyphs.setText(font, shown, color, PANEL_WIDTH, Align.left, true)
        }
        font.draw(batch, glyphs, PANEL_X, cursor)
        cursor -= font.lineHeight * (glyphs.runs.size.coerceAtLeast(1) + gapAfter)
    }

    private fun heading(text: String) {
        cursor -= 14f
        line(text, HEADING_SCALE, MUTED, gapAfter = 0.1f)
    }

    private fun drawPanel(session: SimSession, s: WorldSnapshot, fps: Int) {
        val agent = s.agents.firstOrNull()
        val decision = session.decision
        val deciding = session.thinkingFor
        cursor = HEIGHT - MARGIN

        font.data.setScale(TITLE_SCALE)
        font.color = ACCENT
        font.draw(batch, "lil guy machine", PANEL_X, cursor)
        val titleHeight = font.lineHeight
        font.data.setScale(BODY_SCALE)
        font.color = TEXT
        font.draw(batch, "t ${s.tick} · $fps fps", PANEL_X, cursor - 6, PANEL_WIDTH, Align.right, false)
        cursor -= titleHeight
        line("model ${session.runtime.modelId} · episode ${session.episode}", SMALL_SCALE, MUTED)
        session.setup.notice?.let { line(it, SMALL_SCALE, FAILED, maxLines = 2) }

        val big = if (detail) 1f else 1.25f
        heading("INTENT")
        line(agent?.intent?.let { json.encodeToString(Intent.serializer(), it) } ?: "(none yet)", BODY_SCALE * big, TEXT)
        val status = agent?.status
        line(statusText(status), BODY_SCALE * big, statusColor(status), maxLines = 3)
        if (agent != null && agent.inventory.isNotEmpty()) line("holding ${agent.inventory.joinToString()}", SMALL_SCALE * big, MUTED)

        heading("LAST DECISION")
        if (deciding != null) {
            line("thinking... %.1f s".format(deciding.inWholeMilliseconds / 1000f), BODY_SCALE * big, ACCENT, maxLines = 1)
        }
        if (decision == null) {
            line("(none yet)", BODY_SCALE * big, MUTED)
        } else {
            line(decision.rationale ?: "fallback: ${decision.error}", BODY_SCALE * big, if (decision.error != null) FAILED else TEXT)
            if (detail) {
                val ms = decision.latency.inWholeMilliseconds
                val tokens = decision.usage.let { if (it.promptTokens + it.completionTokens > 0) " · ${it.promptTokens}+${it.completionTokens} tok" else "" }
                val tries = if (decision.attempts.size > 1) " · ${decision.attempts.size} tries" else ""
                line("t ${decision.tick} · $ms ms$tokens$tries · raw output:", SMALL_SCALE, MUTED, maxLines = 1, gapAfter = 0f)
                line(decision.raw ?: "(none)", SMALL_SCALE, CODE, maxLines = 2)
            }
        }

        if (!detail) {
            cursor = MARGIN + 30f
            line("BACK for details · OK to pause", SMALL_SCALE, MUTED, maxLines = 1)
            return
        }

        val events = s.events.asReversed()
        scroll = scroll.coerceIn(0, (events.size - EVENT_ROWS).coerceAtLeast(0))
        heading(if (scroll > 0) "EVENTS (scrolled back $scroll)" else "EVENTS")
        if (events.isEmpty()) line("(none yet)", SMALL_SCALE, MUTED)
        events.drop(scroll).take(EVENT_ROWS).forEach { line("t ${it.tick}  ${it.text}", EVENT_SCALE, TEXT, maxLines = 1, gapAfter = 0f) }

        heading("ACTIONS")
        // One example per action: the panel has room for five.
        ActionExamples.distinctBy { it.first::class }.forEach { (intent, what) ->
            line(json.encodeToString(Intent.serializer(), intent), SMALL_SCALE, CODE, maxLines = 1, gapAfter = 0f)
            line(what, SMALL_SCALE * 0.9f, MUTED, maxLines = 1, gapAfter = 0.2f)
        }

        cursor = MARGIN + 30f
        line("OK pause · BACK less · UP/DOWN events · LEFT -1 tick · RIGHT +1 tick", SMALL_SCALE * 0.9f, MUTED, maxLines = 1)
    }

    private fun statusText(status: IntentStatus?): String = when (status) {
        null -> "idle"
        IntentStatus.Submitted -> "submitted"
        IntentStatus.Validated -> "validated"
        IntentStatus.Executing -> "executing"
        IntentStatus.Succeeded -> "succeeded"
        IntentStatus.Interrupted -> "interrupted"
        is IntentStatus.Failed -> "failed: ${status.reason}"
    }

    private fun statusColor(status: IntentStatus?): Color = when (status) {
        IntentStatus.Executing -> ACCENT
        IntentStatus.Succeeded -> SUCCEEDED
        IntentStatus.Interrupted -> INTERRUPTED
        is IntentStatus.Failed -> FAILED
        else -> MUTED
    }

    override fun dispose() {
        shapes.dispose()
        batch.dispose()
        font.dispose()
    }

    companion object {
        const val WIDTH = 1920f
        const val HEIGHT = 1080f
        private const val MARGIN = 40f
        private const val MAP_WIDTH = 1060f
        private const val PANEL_X = MARGIN + MAP_WIDTH + 64f
        private const val PANEL_WIDTH = WIDTH - PANEL_X - MARGIN
        private const val EVENT_ROWS = 6

        private const val TITLE_SCALE = 2.4f
        private const val HEADING_SCALE = 1.35f
        private const val BODY_SCALE = 1.75f
        private const val SMALL_SCALE = 1.4f
        private const val EVENT_SCALE = 1.5f
        private const val BUBBLE_SCALE = 1.6f
        private const val BANNER_SCALE = 1.8f
        private const val BUBBLE_PAD = 12f
        private const val BUBBLE_MAX_WIDTH = 420f

        val BACKGROUND = Color.valueOf("12141aff")
        private val FLOOR = Color.valueOf("23272fff")
        private val EXIT = Color.valueOf("1f3a2cff")
        private val WALL = Color.valueOf("4a5160ff")
        private val WALL_TOP = Color.valueOf("586072ff")
        private val DOOR_LOCKED = Color.valueOf("c0503cff")
        private val DOOR_DARK = Color.valueOf("3a1712ff")
        private val DOOR_OPEN = Color.valueOf("4cd18aff")
        private val KEY = Color.valueOf("f5c542ff")
        private val APPLE = Color.valueOf("e04848ff")
        private val LEAF = Color.valueOf("5cc65cff")
        private val BOOK = Color.valueOf("4f7fdcff")
        private val BOOK_SPINE = Color.valueOf("2f4f96ff")
        private val GUY = Color.valueOf("ff9a3dff")
        private val GUY_OUTLINE = Color.valueOf("2a1606ff")
        private val PATH = Color.valueOf("5fd3e899")
        private val BUBBLE = Color.valueOf("f4f1e8ff")
        private val BUBBLE_INK = Color.valueOf("15171cff")
        private val BANNER_BG = Color.valueOf("000000cc")
        private val RULE = Color.valueOf("2c313bff")

        private val TEXT = Color.valueOf("eef0f4ff")
        private val MUTED = Color.valueOf("8d95a5ff")
        private val ACCENT = Color.valueOf("5fd3e8ff")
        private val CODE = Color.valueOf("c6e6a0ff")
        private val SUCCEEDED = Color.valueOf("4cd18aff")
        private val INTERRUPTED = Color.valueOf("f5c542ff")
        private val FAILED = Color.valueOf("ff7a66ff")
    }
}
