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
import dev.lilguy.agent.TokenReport
import dev.lilguy.agent.memory.Describe
import dev.lilguy.agent.memory.StoredMemory
import dev.lilguy.app.SimSession.RunState
import dev.lilguy.sim.EntitySnapshot
import dev.lilguy.sim.GameClock
import dev.lilguy.sim.Intent
import dev.lilguy.sim.IntentStatus
import dev.lilguy.sim.LifeSnapshot
import dev.lilguy.sim.Terrain
import dev.lilguy.sim.WorldSnapshot
import kotlinx.serialization.json.Json
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Temporary 2D dev view: the room from above on the left, and on the right a header (run state,
 * model, clock) over one of the pages the remote flips between. The first page, [Page.Room], is
 * not drawn here: it is the 3D room ([RoomView]), full screen.
 *
 * - [Page.Now]: needs, what he's doing, the latest decision, recent events.
 * - [Page.Memory]: short-term memory (recent actions, in every prompt), his self-model and its
 *   last change, his thoughts, and the long-term memories (stretches and whole days) written so
 *   far, marking those recalled at the latest decision.
 * - [Page.Art]: his drawn pieces: a strip of them, and the picked one large with its title,
 *   tags, the request it was made for, and whether it's held, hung or sold. UP/DOWN steps through
 *   them; it follows the newest until you move.
 * - [Page.Tokens]: model tokens spent this run, by game day and by what they were for.
 * - [Page.History]: every decision this run; picking an older one puts the map back to the
 *   moment it was made.
 * - [Page.Log]: money and the sim's event log.
 * - [Page.Run]: the run's settings (length, speed, how long the clock waits on the model, clock
 *   resolution), restart and replay. DOWN from the tabs moves into the rows, where LEFT/RIGHT
 *   change the picked one and OK runs an action; UP past the first row goes back to the tabs.
 *
 * A stand-in until the 3D renderer (milestone 2). Laid out on a fixed 1920x1080 canvas and scaled
 * to fit. Fill rate stays low for the Streamer: one clear, then small shapes and text; no
 * full-screen layers.
 */
class DevView(private val art: ArtTextures) : Disposable {
    enum class Page(val title: String) { Room("ROOM"), Now("NOW"), Memory("MEMORY"), Art("ART"), Tokens("TOKENS"), History("HISTORY"), Log("LOG"), Run("RUN") }

    /** The Run page's rows. */
    private enum class RunRow(val label: String) {
        Days("Length"), Speed("Speed"), DecideCap("While he decides"), Resolution("Clock resolution"), Restart("Restart"), Replay("Replay");

        val isAction: Boolean get() = this == Restart || this == Replay
    }

    private val viewport = FitViewport(WIDTH, HEIGHT)
    private val shapes = ShapeRenderer()
    private val batch = SpriteBatch()
    private val font = BitmapFont().apply {
        region.texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
        setUseIntegerPositions(false)
    }
    private val glyphs = GlyphLayout()
    private val json = Json

    var page = Page.Room
        private set

    /** History only: the decision shown, as an index into [SimSession.history]; null follows the newest and shows the live world. */
    var pinned: Int? = null
        private set

    /** Art only: the piece shown, as an index into the drawn pieces oldest first; null follows the newest. */
    private var artPick: Int? = null

    /** Rows scrolled down from the top, per page. */
    private val scroll = IntArray(Page.entries.size)

    /** Set by the first BACK: a second BACK restarts, any other key cancels. */
    var confirmRestart = false

    /** Run page: the picked row, or null while the tabs have the focus. */
    private var runRow: RunRow? = null

    /** Run page: the run Replay would start again (1-based); null for this one. */
    private var replayPick: Int? = null

    /** Set by the first OK on Restart or Replay: a second OK does it, any other key cancels. */
    var confirmAction = false

    /** LEFT (-1) and RIGHT (+1): the previous or next page. Leaving History returns the map to live. */
    fun flip(by: Int) {
        page = Page.entries[(page.ordinal + by).mod(Page.entries.size)]
        pinned = null
    }

    /** UP (-1) and DOWN (+1): in History a newer or older decision, in Art a newer or older piece, elsewhere scroll. */
    fun move(by: Int, session: SimSession) {
        if (page == Page.Run) {
            val rows = RunRow.entries
            val next = (runRow?.ordinal ?: -1) + by
            runRow = if (next < 0) null else rows[next.coerceAtMost(rows.lastIndex)]
            return
        }
        if (page == Page.Art) {
            val last = session.memory.studio?.pieces()?.lastIndex ?: -1
            if (last < 0) return
            val next = (artPick ?: last) - by
            artPick = if (next >= last) null else next.coerceAtLeast(0)
            return
        }
        if (page != Page.History) {
            scroll[page.ordinal] = (scroll[page.ordinal] + by).coerceAtLeast(0)
            return
        }
        val last = session.history.lastIndex
        if (last < 0) return
        val next = (pinned ?: last) - by
        pinned = if (next >= last) null else next.coerceAtLeast(0)
    }

    /** LEFT (-1) and RIGHT (+1) on a Run page row: the previous or next value. False when the tabs have the focus, so the key flips pages. */
    fun adjust(by: Int, session: SimSession): Boolean {
        if (page != Page.Run) return false
        val row = runRow ?: return false
        val s = session.settings
        when (row) {
            RunRow.Days -> {
                val days = when (val d = s.days) {
                    null -> if (by < 0) RunSettings.MAX_DAYS.coerceAtLeast(session.minDays) else null
                    else -> if (by > 0 && d >= RunSettings.MAX_DAYS) null else d + by
                }
                session.configure(s.copy(days = days?.coerceAtLeast(1)))
            }
            RunRow.Speed -> session.configure(s.copy(speed = RunSettings.SPEEDS.step(s.speed, by)))
            RunRow.DecideCap -> session.configure(s.copy(decideCap = RunSettings.DECIDE_CAPS.step(s.decideCap, by)))
            RunRow.Resolution -> session.configure(s.copy(ticksPerMinute = RunSettings.RESOLUTIONS.step(s.ticksPerMinute, by)))
            RunRow.Replay -> replayPick = ((replayPick ?: session.run) + by).coerceIn(1, session.run).takeIf { it != session.run }
            RunRow.Restart -> Unit
        }
        return true
    }

    /** OK on a Run page action: asks first, then restarts or replays. False when OK isn't on an action, so it starts or pauses. */
    fun activate(session: SimSession, confirmed: Boolean): Boolean {
        val row = runRow?.takeIf { page == Page.Run && it.isAction } ?: return false
        if (!confirmed) {
            confirmAction = true
            return true
        }
        if (row == RunRow.Replay) session.replay(replayPick ?: session.run) else session.restart()
        reset()
        return true
    }

    /** The value [by] steps on from [current] in this list, stopping at either end. */
    private fun <T> List<T>.step(current: T, by: Int): T = this[(indexOf(current).coerceAtLeast(0) + by).coerceIn(0, lastIndex)]

    /** After a restart: the live world, every page scrolled to the top. */
    fun reset() {
        pinned = null
        replayPick = null
        artPick = null
        scroll.fill(0)
        confirmRestart = false
        confirmAction = false
    }

    // Map placement, recomputed per frame from the map size.
    private var tile = 0f
    private var mapX = 0f
    private var mapTop = 0f

    fun resize(width: Int, height: Int) = viewport.update(width, height, true)

    fun render(session: SimSession, fps: Int) {
        val moment = pinned?.takeIf { page == Page.History }?.let { session.history.getOrNull(it) }
        val now = moment?.snapshot ?: session.current
        val before = if (moment != null) now else session.previous
        val alpha = if (moment != null) 1f else session.alpha
        val agent = now.agents.firstOrNull()
        val guy = now.entities.first { it.kind == "guy" }
        val guyBefore = before.entities.firstOrNull { it.id == guy.id } ?: guy
        val gx = MathUtils.lerp(guyBefore.x, guy.x, alpha)
        val gy = MathUtils.lerp(guyBefore.y, guy.y, alpha)
        val thinking = moment == null && session.thinkingFor != null
        val bubble = agent?.saying ?: if (thinking) "..." else if (now.life?.doing == "sleep") "z z z" else null
        val bottomBanner = banner(session)
        val topBanner = moment?.let { historyBanner(session, it) }

        layoutMap(now)
        viewport.apply()
        shapes.projectionMatrix = viewport.camera.combined
        batch.projectionMatrix = viewport.camera.combined

        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
        shapes.begin(ShapeRenderer.ShapeType.Filled)
        drawTerrain(now)
        agent?.path?.forEach { shapes.color = PATH; shapes.circle(sx(it.x + 0.5f), sy(it.y + 0.5f), tile * 0.09f, 12) }
        now.entities.forEach { if (it.carriedBy == null && it.kind != "guy") drawEntity(it) }
        shapes.color = RULE
        shapes.rect(PANEL_X - 24f, MARGIN, 2f, HEIGHT - 2 * MARGIN)
        drawPanelShapes(session)
        shapes.end()

        batch.begin()
        if (now.life != null) now.entities.forEach { labelFurniture(it) }
        batch.end()

        shapes.begin(ShapeRenderer.ShapeType.Filled)
        drawGuy(gx, gy, guy.facingDegrees, carrying = agent?.inventory ?: emptyList())
        bubble?.let { bubbleShape(it, gx, gy) }
        bottomBanner?.let { bannerShape(it, top = false) }
        topBanner?.let { bannerShape(it, top = true) }
        shapes.end()

        batch.begin()
        bubble?.let { bubbleText(it, gx, gy) }
        bottomBanner?.let { bannerText(it, top = false) }
        topBanner?.let { bannerText(it, top = true) }
        drawPanel(session, fps)
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
        if (e.kind in FURNISHING) return drawFurniture(e, cx, cy, t)
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

    /** Bedroom furniture and decor, by item id. Simple blocks; a label is drawn under each. */
    private fun drawFurniture(e: EntitySnapshot, cx: Float, cy: Float, t: Float) {
        val h = t / 2
        when (e.id.value) {
            "bed" -> {
                shapes.color = BED
                shapes.rect(cx - h + 4, cy - h + 4, t - 8, t - 8)
                shapes.color = PILLOW
                shapes.rect(cx - h + 10, cy + h * 0.35f, t - 20, h * 0.45f)
            }
            "desk" -> {
                shapes.color = WOOD
                shapes.rect(cx - h + 3, cy - h * 0.7f, t - 6, h * 1.4f)
                shapes.color = PAPER
                shapes.rect(cx - h * 0.4f, cy - h * 0.3f, h * 0.7f, h * 0.5f)
            }
            "chair" -> {
                shapes.color = WOOD_DARK
                shapes.rect(cx - h * 0.5f, cy - h * 0.5f, h, h)
                shapes.rect(cx - h * 0.5f, cy + h * 0.4f, h, h * 0.2f)
            }
            "easel" -> {
                shapes.color = WOOD
                shapes.rectLine(cx - h * 0.6f, cy - h * 0.8f, cx, cy + h * 0.8f, 5f)
                shapes.rectLine(cx + h * 0.6f, cy - h * 0.8f, cx, cy + h * 0.8f, 5f)
                shapes.color = PAPER
                shapes.rect(cx - h * 0.45f, cy - h * 0.1f, h * 0.9f, h * 0.7f)
            }
            "record_player" -> {
                shapes.color = WOOD_DARK
                shapes.rect(cx - h + 6, cy - h + 6, t - 12, t - 12)
                shapes.color = if (e.state != null) ACCENT else VINYL
                shapes.circle(cx, cy, h * 0.6f, 24)
                shapes.color = WOOD
                shapes.circle(cx, cy, h * 0.12f, 10)
            }
            "plant" -> {
                shapes.color = POT
                shapes.rect(cx - h * 0.35f, cy - h * 0.7f, h * 0.7f, h * 0.5f)
                shapes.color = LEAF
                shapes.circle(cx, cy + h * 0.15f, h * 0.5f, 18)
            }
            "poster" -> {
                shapes.color = POSTER
                shapes.rect(cx - h * 0.6f, cy - h * 0.7f, h * 1.2f, h * 1.4f)
            }
            "rug" -> {
                shapes.color = RUG
                shapes.rect(cx - t * 0.9f, cy - h * 0.8f, t * 1.8f, h * 1.6f)
            }
            "mail_hatch" -> {
                shapes.color = WOOD
                shapes.rect(cx - h * 0.6f, cy - h * 0.5f, h * 1.2f, h)
                shapes.color = KEY
                shapes.rect(cx - h * 0.4f, cy - h * 0.1f, h * 0.8f, h * 0.2f)
            }
            "picture_rail" -> {
                shapes.color = WOOD_DARK
                shapes.rect(cx - t * 0.75f, cy - h * 0.15f, t * 1.5f, h * 0.3f)
            }
            else -> {
                shapes.color = Color.MAGENTA
                shapes.circle(cx, cy, t * 0.15f, 12)
            }
        }
    }

    private fun labelFurniture(e: EntitySnapshot) {
        if (e.kind !in FURNISHING) return
        font.data.setScale(LABEL_SCALE)
        font.color = MUTED
        font.draw(batch, e.id.value.replace('_', ' '), sx(e.x) - tile, sy(e.y) - tile * 0.32f, tile * 2, Align.center, false)
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
        // Art he carries: one small card each, up to a handful.
        carrying.filter { it == "sketch" || it == "painting" }.take(6).forEachIndexed { i, art ->
            shapes.color = if (art == "painting") POSTER else PAPER
            shapes.rect(cx + r * 0.7f + i * 7f, cy - r * 1.1f, tile * 0.16f, tile * 0.2f)
        }
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
        // The layout centers each line within BUBBLE_MAX_WIDTH, so center that box on the bubble.
        font.draw(batch, glyphs, x + w / 2 - BUBBLE_MAX_WIDTH / 2, y + h - BUBBLE_PAD)
    }

    /** The run's state, across the bottom of the map whenever it isn't simply running. */
    fun banner(session: SimSession): String? = when {
        confirmRestart -> "Restart? BACK again for a fresh run · any other key cancels"
        confirmAction && runRow == RunRow.Replay -> "Replay run ${replayPick ?: session.run}? OK again · any other key cancels"
        confirmAction -> "Restart with these settings? OK again · any other key cancels"
        session.state == RunState.Ready -> "READY · press OK to start"
        session.state == RunState.Paused -> "PAUSED · OK resumes"
        session.state == RunState.Ended -> "ENDED · ${session.endedBecause} · BACK twice for a fresh run"
        else -> null
    }

    /** Across the top of the map while it shows a past decision instead of the live world. */
    private fun historyBanner(session: SimSession, moment: SimSession.Moment): String =
        "THE PAST · decision ${(pinned ?: 0) + 1} of ${session.history.size} · ${stamp(moment.decision.tick, moment.snapshot)}"

    private fun bannerY(top: Boolean): Float = if (top) HEIGHT - MARGIN - glyphs.height - 32 else MARGIN

    private fun bannerShape(text: String, top: Boolean) {
        font.data.setScale(BANNER_SCALE)
        glyphs.setText(font, text)
        shapes.color = if (top) PAST_BG else BANNER_BG
        shapes.rect(mapX + MAP_WIDTH / 2 - glyphs.width / 2 - 24, bannerY(top), glyphs.width + 48, glyphs.height + 32)
    }

    private fun bannerText(text: String, top: Boolean) {
        font.data.setScale(BANNER_SCALE)
        font.color = TEXT
        glyphs.setText(font, text)
        font.draw(batch, text, mapX + MAP_WIDTH / 2 - glyphs.width / 2, bannerY(top) + glyphs.height + 16)
    }

    // --- panel: text helpers -------------------------------------------------------------------

    private var cursor = 0f
    /** Page text stops above this; once a line doesn't fit, the rest of the page is skipped. */
    private var contentFloor = 0f
    private var clipped = false

    private fun line(raw: String, scale: Float, color: Color, maxLines: Int = 3, gapAfter: Float = 0.25f, indent: Float = 0f) {
        if (clipped) return
        val text = plain(raw)
        font.data.setScale(scale)
        font.color = color
        val width = PANEL_WIDTH - indent
        // Wrap, then cut to maxLines by truncating the source; BitmapFont can only truncate one line.
        glyphs.setText(font, text, color, width, Align.left, true)
        if (glyphs.runs.size > maxLines) {
            val keep = (text.length * maxLines / glyphs.runs.size).coerceAtLeast(4)
            glyphs.setText(font, text.take(keep - 3) + "...", color, width, Align.left, true)
        }
        if (cursor - glyphs.height < contentFloor) {
            clipped = true
            return
        }
        font.draw(batch, glyphs, PANEL_X + indent, cursor)
        cursor -= font.lineHeight * (glyphs.runs.size.coerceAtLeast(1) + gapAfter)
    }

    /** Model text in characters the bitmap font has: dashes, curly quotes and ellipses made plain. */
    private fun plain(text: String): String {
        if (text.none { it.code > 0x7e }) return text
        return text.replace("\u2014", " - ").replace('\u2013', '-').replace('\u2018', '\'').replace('\u2019', '\'')
            .replace('\u201c', '"').replace('\u201d', '"').replace("\u2026", "...")
    }

    private fun heading(text: String) {
        cursor -= 14f
        line(text, HEADING_SCALE, MUTED, maxLines = 1, gapAfter = 0.1f)
    }

    /** One line of text cut to [width] with "...", at [x] on the current line; doesn't move the cursor. */
    private fun cell(raw: String, x: Float, width: Float, scale: Float, color: Color, align: Int = Align.left) {
        val text = plain(raw)
        font.data.setScale(scale)
        glyphs.setText(font, text, 0, text.length, color, width, align, false, "...")
        font.draw(batch, glyphs, x, cursor)
    }

    /** "d2 14:30" in the bedroom, "t 140" in rooms without a clock. */
    private fun stamp(tick: Long, s: WorldSnapshot): String =
        s.life?.clockScale?.reading(tick)?.let { "d${it.day} ${it.time}" } ?: "t $tick"

    // --- panel ---------------------------------------------------------------------------------

    // Some shapes (need bars, the tab underline) are placed by the text pass and drawn by the next
    // frame's shape pass, which is the same layout unless the header above them changed height.
    private var barsTop = 0f
    private var tabRuleY = 0f
    private var tabX = 0f
    private var tabWidth = 0f

    private fun drawPanelShapes(session: SimSession) {
        if (tabRuleY > 0f) {
            shapes.color = RULE
            shapes.rect(PANEL_X, tabRuleY, PANEL_WIDTH, 2f)
            shapes.color = ACCENT
            shapes.rect(tabX, tabRuleY, tabWidth, 4f)
        }
        val life = session.current.life
        if (page == Page.Now && life != null && barsTop > 0f) drawNeedBars(life)
    }

    private fun drawPanel(session: SimSession, fps: Int) {
        val live = session.current
        cursor = HEIGHT - MARGIN
        contentFloor = FOOTER_Y + 40f
        clipped = false

        font.data.setScale(TITLE_SCALE)
        font.color = ACCENT
        font.draw(batch, "lil guy machine", PANEL_X, cursor)
        val titleHeight = font.lineHeight
        font.data.setScale(BODY_SCALE)
        font.color = stateColor(session.state)
        font.draw(batch, "run ${session.run} · ${session.state.name.uppercase()}${if (session.holding) " · HELD" else ""}", PANEL_X, cursor - 6, PANEL_WIDTH, Align.right, false)
        cursor -= titleHeight
        line("${session.runtime.modelId} · memory ${session.memory.writer ?: "off"} · writer ${session.memory.thoughtWriter ?: "off"} · recall ${session.memory.scorerId ?: "keywords"} · $fps fps", SMALL_SCALE, MUTED, maxLines = 1)
        session.setup.notice?.let { line(it, SMALL_SCALE, FAILED, maxLines = 2) }

        val life = live.life
        val ends = session.endTick?.let { "ends ${stamp(it, live)}" }
        if (ends != null) cell(ends, PANEL_X, PANEL_WIDTH, SMALL_SCALE, MUTED, Align.right)
        line(if (life != null) "day ${life.clock.day} · ${life.clock.time}     $${life.money}" else "tick ${live.tick}", CLOCK_SCALE, TEXT, maxLines = 1, gapAfter = 0.1f)

        drawTabs()
        when (page) {
            Page.Room, Page.Now -> drawNow(session, live)
            Page.Memory -> drawMemory(session, live)
            Page.Art -> drawArt(session)
            Page.Tokens -> drawTokens(session)
            Page.History -> drawHistory(session)
            Page.Log -> drawLog(live)
            Page.Run -> drawRun(session)
        }

        clipped = false
        contentFloor = 0f
        cursor = FOOTER_Y
        val ok = when (session.state) {
            RunState.Ready -> "OK start · "
            RunState.Running -> "OK pause · "
            RunState.Paused -> "OK resume · "
            RunState.Ended -> ""
        }
        val upDown = when (page) {
            Page.Room, Page.Now -> ""
            Page.History, Page.Art -> "UP newer · DOWN older · "
            Page.Run -> "DOWN settings · "
            else -> "UP/DOWN scroll · "
        }
        val row = runRow?.takeIf { page == Page.Run }
        val keys = when {
            row?.isAction == true -> "OK ${row.label.lowercase()} · UP/DOWN pick · "
            row != null -> "${ok}LEFT/RIGHT change · UP/DOWN pick · "
            else -> "${ok}LEFT/RIGHT pages · $upDown"
        }
        line("${keys}BACK twice restarts", SMALL_SCALE * 0.9f, MUTED, maxLines = 1)
    }

    private fun drawTabs() {
        cursor -= 22f
        font.data.setScale(TAB_SCALE)
        var x = PANEL_X
        Page.entries.forEach { p ->
            glyphs.setText(font, p.title)
            font.color = if (p == page) TEXT else MUTED
            font.draw(batch, p.title, x, cursor)
            if (p == page) {
                tabX = x
                tabWidth = glyphs.width
            }
            x += glyphs.width + TAB_GAP
        }
        tabRuleY = cursor - font.capHeight - 18f
        cursor = tabRuleY - 20f
    }

    // --- pages ---------------------------------------------------------------------------------

    private fun drawNow(session: SimSession, s: WorldSnapshot) {
        val agent = s.agents.firstOrNull()
        val life = s.life
        if (life != null) {
            heading("NEEDS")
            barsTop = cursor + 6f
            val levels = listOf("energy" to life.energy, "fullness" to life.fullness, "mood" to life.mood, "stimulation" to life.stimulation)
            levels.forEachIndexed { i, (name, value) ->
                font.data.setScale(SMALL_SCALE)
                font.color = if (value < LOW) FAILED else TEXT
                val y = barsTop - i * BAR_PITCH
                font.draw(batch, name, PANEL_X, y - 2)
                font.draw(batch, value.toInt().toString(), BAR_X + BAR_WIDTH + 16f, y - 2)
            }
            cursor = barsTop - levels.size * BAR_PITCH - 6f
            line("mood settles toward ${life.moodTarget.toInt()}", SMALL_SCALE * 0.9f, MUTED, maxLines = 1)

            heading("DOING")
            val doing = life.doing
            val now = when {
                doing != null -> doing + (life.progress?.let { " · ${(it * 100).toInt()}%" } ?: "")
                agent?.intent != null -> Describe.intent(agent.intent!!)
                else -> "(nothing yet)"
            }
            line(now, BODY_SCALE, if (doing != null) ACCENT else TEXT, maxLines = 2)
            if (doing == null && agent?.status is IntentStatus.Failed) line(statusText(agent.status), SMALL_SCALE, FAILED, maxLines = 2)
            life.music?.let { line("music: $it", SMALL_SCALE, CODE, maxLines = 1) }
            if (life.holding.isNotEmpty()) {
                line("holding " + life.holding.groupingBy { it }.eachCount().entries.joinToString { (k, v) -> if (v == 1) k else "$v $k" }, SMALL_SCALE * 0.9f, MUTED, maxLines = 2, gapAfter = 0.1f)
            }
            line("owns " + life.owns.entries.joinToString { (k, v) -> if (v == 1) k.replace('_', ' ') else "$v ${k.replace('_', ' ')}s" }, SMALL_SCALE * 0.9f, MUTED, maxLines = 2, gapAfter = 0.1f)
        } else {
            heading("INTENT")
            line(agent?.intent?.let { json.encodeToString(Intent.serializer(), it) } ?: "(none yet)", BODY_SCALE, TEXT)
            line(statusText(agent?.status), BODY_SCALE, statusColor(agent?.status), maxLines = 2)
            if (agent != null && agent.inventory.isNotEmpty()) line("holding ${agent.inventory.joinToString()}", SMALL_SCALE, MUTED)
        }

        heading("LATEST DECISION")
        val deciding = session.thinkingFor
        val decision = session.decision
        if (deciding != null) line("thinking... %.1f s".format(deciding.inWholeMilliseconds / 1000f), BODY_SCALE, ACCENT, maxLines = 1)
        if (decision == null) {
            if (deciding == null) line(if (session.state == RunState.Ready) "(none: the run starts when you press OK)" else "(none yet)", BODY_SCALE * 0.9f, MUTED)
        } else {
            line(decision.rationale ?: "fallback: ${decision.error}", BODY_SCALE * 0.9f, if (decision.fellBack) FAILED else TEXT, maxLines = 3)
            decision.thought?.let { line("thought: " + (it.text ?: "none (${it.error})"), BODY_SCALE * 0.85f, if (it.text == null) FAILED else ACCENT, maxLines = 4) }
            val recalled = if (decision.recalled.isNotEmpty()) " · ${decision.recalled.size} recalled" else ""
            line("${Describe.intent(decision.intent)} · ${decision.latency.inWholeMilliseconds} ms$recalled", SMALL_SCALE * 0.9f, CODE, maxLines = 1)
        }

        heading("RECENT EVENTS")
        if (s.events.isEmpty()) line("(none yet)", SMALL_SCALE, MUTED)
        s.events.asReversed().take(NOW_EVENT_ROWS).forEach {
            line("${stamp(it.tick, s)}  ${it.text}", SMALL_SCALE * 0.95f, TEXT, maxLines = 1, gapAfter = 0f)
        }
    }

    private fun drawMemory(session: SimSession, s: WorldSnapshot) {
        val mem = session.memory
        val recent = session.runtime.recent
        heading("SHORT-TERM · recent actions, sent with every prompt")
        if (recent.isEmpty()) line("(nothing yet)", SMALL_SCALE, MUTED)
        recent.asReversed().forEach { e ->
            val result = e.result?.let { statusText(it) } ?: "in progress"
            line("${stamp(e.tick, s)}  ${Describe.intent(e.intent)}  ·  $result", SMALL_SCALE * 0.9f, statusColor(e.result).takeIf { e.result is IntentStatus.Failed } ?: TEXT, maxLines = 1, gapAfter = 0f)
        }

        mem.selfText()?.let { self ->
            val rev = mem.lastRevision
            val version = mem.selfModel()
            heading("SELF-MODEL · his own words, revised each night, in every prompt")
            line(self, MEMORY_SCALE * 0.9f, TEXT, maxLines = 8, gapAfter = 0.2f)
            val note = when {
                rev?.error != null -> "last night's revision failed: ${rev.error}"
                rev != null && !rev.changed -> "unchanged last night: ${rev.note}"
                version != null -> "last change: ${version.note}"
                else -> "not revised yet: the first night revises it"
            }
            line(note, SMALL_SCALE * 0.85f, if (rev?.error != null) FAILED else MUTED, maxLines = 3, gapAfter = 0.3f)
        }

        val thoughts = mem.thoughts()
        if (mem.thinking) {
            heading("THOUGHTS · the latest is his plan, sent with every prompt")
            if (thoughts.isEmpty()) line("(none yet)", SMALL_SCALE, MUTED)
            thoughts.asReversed().take(THOUGHT_ROWS).forEachIndexed { i, t ->
                val label = stamp(t.tick, s) + if (i == 0 && t.text != null) "  · his plan for now" else ""
                line(label, SMALL_SCALE * 0.9f, if (i == 0) ACCENT else MUTED, maxLines = 1, gapAfter = 0.05f)
                line(t.text ?: "no thought: ${t.error}", MEMORY_SCALE, if (t.text == null) FAILED else TEXT, maxLines = 5, gapAfter = 0.4f)
            }
        }

        heading("LONG-TERM")
        val writer = mem.writer
        val batchSize = mem.batchSize
        val events = mem.eventCount()
        val status = when {
            writer == null || batchSize == null -> "no memory writer configured: $events events recorded, none written up"
            mem.summarizing -> "$writer is writing a memory..."
            session.state == RunState.Ended && mem.pending() == 0 -> "$events events recorded, all written up · by $writer"
            else -> "$events events recorded · " + (mem.pendingSignificance()?.let { (sum, of) -> "significance %.1f of %.1f toward the next memory".format(sum, of) }
                ?: "next memory after ${(batchSize - mem.pending()).coerceAtLeast(0)} more") + " · by $writer"
        }
        line(status, SMALL_SCALE * 0.9f, MUTED, maxLines = 2)
        session.memoryError?.let { line("last write failed: $it", SMALL_SCALE * 0.9f, FAILED, maxLines = 2) }
        session.writerError?.let { line("last failure: $it", SMALL_SCALE * 0.9f, FAILED, maxLines = 2) }
        if (mem.lastRecalledThoughts.isNotEmpty()) {
            line("${mem.lastRecalledThoughts.size} earlier thought${if (mem.lastRecalledThoughts.size == 1) "" else "s"} recalled at the latest decision", SMALL_SCALE * 0.9f, ACCENT, maxLines = 1)
        }

        // Stretches and whole days together, newest first by when they end.
        fun end(m: StoredMemory) = (m.span.toDay ?: 0) * GameClock.MINUTES_PER_DAY + (m.span.toTime?.let(GameClock::parseHhmm) ?: 0) + m.level
        val all = (mem.memories() + mem.days()).sortedWith(compareByDescending<StoredMemory> { end(it) }.thenByDescending { it.id })
        if (all.isEmpty()) return line("(no memories yet)", BODY_SCALE * 0.8f, MUTED)
        val recalled = mem.lastRecalled
        val skip = scroll[Page.Memory.ordinal].coerceAtMost(all.lastIndex).also { scroll[Page.Memory.ordinal] = it }
        val days = all.count { it.level == 2 }
        val count = "${all.size - days} memories and $days day${if (days == 1) "" else "s"}, newest first"
        line(if (skip > 0) "$count · $skip newer above" else count, SMALL_SCALE * 0.9f, MUTED, maxLines = 1, gapAfter = 0.5f)
        all.drop(skip).forEach { m ->
            val isRecalled = m.id in recalled
            val what = if (m.level == 2) "THE WHOLE DAY · ${m.span.label}" else m.span.label.ifEmpty { "events ${m.fromId}-${m.toId}" }
            val importance = m.importance?.let { " · importance %.2f".format(it) }.orEmpty()
            val label = what + importance + if (isRecalled) "  · recalled at the latest decision" else ""
            line(label, SMALL_SCALE * 0.9f, if (isRecalled) ACCENT else MUTED, maxLines = 1, gapAfter = 0.05f)
            line(m.text, MEMORY_SCALE, TEXT, maxLines = 6, gapAfter = 0.6f)
        }
    }

    private fun drawArt(session: SimSession) {
        val studio = session.memory.studio
        val pieces = studio?.pieces().orEmpty()
        val status = when {
            studio == null -> "the scripted policy doesn't draw: his pieces stay untitled"
            session.memory.drawing -> "${studio.artist} is drawing..."
            else -> "${pieces.size} drawn · by ${studio.artist}"
        }
        val sel = (artPick ?: pieces.lastIndex).coerceIn(0, max(0, pieces.lastIndex))
        heading(if (pieces.isEmpty()) "ART" else if (artPick == null) "ART · ${pieces.size} · following the newest" else "ART · ${sel + 1} of ${pieces.size}")
        line(status, SMALL_SCALE * 0.9f, MUTED, maxLines = 2)
        studio?.lastError?.let { line("last drawing failed: $it", SMALL_SCALE * 0.9f, FAILED, maxLines = 2) }
        session.current.life?.commission?.let { c ->
            line("request: ${c.request ?: c.wants} (+$${c.reward} until ${c.until})", SMALL_SCALE * 0.9f, CODE, maxLines = 2)
        }
        if (pieces.isEmpty()) return line("(nothing drawn yet)", BODY_SCALE * 0.8f, MUTED)
        art.sync(session)

        // A strip of pieces, newest first, keeping the pick in view.
        val perRow = ((PANEL_WIDTH + THUMB_GAP) / (THUMB + THUMB_GAP)).toInt()
        val pos = pieces.lastIndex - sel
        val first = (pos - perRow / 2).coerceIn(0, max(0, pieces.size - perRow))
        cursor -= 8f
        val top = cursor
        for (k in first until min(pieces.size, first + perRow)) {
            val i = pieces.lastIndex - k
            val x = PANEL_X + (k - first) * (THUMB + THUMB_GAP)
            art.of(pieces[i])?.let { batch.draw(it, x, top - THUMB, THUMB, THUMB) }
            cursor = top - THUMB - 8f
            cell((if (i == sel) "> " else "") + "#${pieces[i].piece}", x, THUMB, SMALL_SCALE * 0.85f, if (i == sel) ACCENT else MUTED, Align.center)
        }
        font.data.setScale(SMALL_SCALE * 0.85f)
        cursor -= font.lineHeight + 12f

        // The picked piece large, its details beside it.
        val d = pieces[sel]
        val big = min(ART_SIZE, cursor - contentFloor)
        if (big < THUMB) return
        val imageTop = cursor
        art.of(d)?.let { batch.draw(it, PANEL_X, imageTop - big, big, big) }
        val indent = big + 24f
        val shown = session.current.life?.art?.firstOrNull { it.id == d.piece }
        val where = when {
            shown == null -> "sold"
            shown.hung -> "on the wall"
            else -> "held, sells for $${shown.sellsFor}"
        }
        line(d.title ?: "untitled", BODY_SCALE, TEXT, maxLines = 3, gapAfter = 0.2f, indent = indent)
        line("${d.item} ${d.piece} · ${if (d.day != null) "day ${d.day} ${d.time}" else "t ${d.tick}"} · $where", SMALL_SCALE * 0.9f, MUTED, maxLines = 2, gapAfter = 0.3f, indent = indent)
        shown?.commission?.let { line(it, SMALL_SCALE * 0.9f, CODE, maxLines = 2, gapAfter = 0.3f, indent = indent) }
        if (d.tags.isNotEmpty()) line("tags: ${d.tags.joinToString()}", SMALL_SCALE * 0.9f, TEXT, maxLines = 3, gapAfter = 0.3f, indent = indent)
        d.commission?.let { line("asked: $it", SMALL_SCALE * 0.85f, MUTED, maxLines = 4, gapAfter = 0.3f, indent = indent) }
        line("${d.ops} ops in ${d.calls} picks · ${d.artist}", SMALL_SCALE * 0.85f, MUTED, maxLines = 2, indent = indent)
        cursor = min(cursor, imageTop - big - 12f)
    }

    private fun drawHistory(session: SimSession) {
        val h = session.history
        if (h.isEmpty()) {
            heading("DECISIONS")
            return line("(none yet)", BODY_SCALE * 0.9f, MUTED)
        }
        val sel = (pinned ?: h.lastIndex).coerceIn(0, h.lastIndex)
        heading(if (pinned == null) "DECISIONS · ${h.size} · following the newest" else "DECISIONS · ${sel + 1} of ${h.size}")

        // Newest first, a window of rows that keeps the selection in view.
        val pos = h.lastIndex - sel
        val first = (pos - HISTORY_ROWS / 2).coerceIn(0, max(0, h.size - HISTORY_ROWS))
        font.data.setScale(SMALL_SCALE * 0.95f)
        val rowHeight = font.lineHeight
        for (k in first until min(h.size, first + HISTORY_ROWS)) {
            val i = h.lastIndex - k
            val m = h[i]
            val outcome = outcomeOf(session, i)
            val selected = i == sel
            val color = if (selected) TEXT else MUTED
            cell(if (selected) ">" else "", PANEL_X, 20f, SMALL_SCALE * 0.95f, ACCENT)
            cell(stamp(m.decision.tick, m.snapshot), PANEL_X + 24f, 130f, SMALL_SCALE * 0.95f, color)
            cell(Describe.intent(m.decision.intent), PANEL_X + 160f, PANEL_WIDTH - 330f, SMALL_SCALE * 0.95f, if (m.decision.fellBack) FAILED else color)
            cell(shortStatus(outcome), PANEL_X + PANEL_WIDTH - 160f, 160f, SMALL_SCALE * 0.95f, if (selected) statusColor(outcome) else MUTED, Align.right)
            cursor -= rowHeight
        }

        val m = h[sel]
        val d = m.decision
        heading("DECISION ${sel + 1}")
        val tokens = d.usage.let { if (it.promptTokens + it.completionTokens > 0) " · ${it.promptTokens}+${it.completionTokens} tok" else "" }
        val tries = if (d.attempts.size > 1) " · ${d.attempts.size} tries" else ""
        line("${stamp(d.tick, m.snapshot)} · tick ${d.tick} · ${d.latency.inWholeMilliseconds} ms$tokens$tries", SMALL_SCALE * 0.9f, MUTED, maxLines = 1)
        line(Describe.intent(d.intent), BODY_SCALE * 0.9f, CODE, maxLines = 2, gapAfter = 0.15f)
        line(d.rationale ?: "fallback: ${d.error}", BODY_SCALE * 0.8f, if (d.fellBack) FAILED else TEXT, maxLines = 4)
        d.thought?.let { line("thought: " + (it.text ?: "none (${it.error})"), BODY_SCALE * 0.8f, if (it.text == null) FAILED else ACCENT, maxLines = 5) }
        val outcome = outcomeOf(session, sel)
        line("result: ${outcome?.let { statusText(it) } ?: "still going"}", SMALL_SCALE * 0.9f, statusColor(outcome), maxLines = 2)
        d.observation?.let { line("saw: " + Describe.state(it), SMALL_SCALE * 0.85f, MUTED, maxLines = 3) }
        if (d.recalled.isNotEmpty()) {
            line("recalled ${d.recalled.size}:", SMALL_SCALE * 0.9f, ACCENT, maxLines = 1, gapAfter = 0.05f)
            d.recalled.asReversed().forEach { line(it, SMALL_SCALE * 0.85f, MUTED, maxLines = 2, gapAfter = 0.15f, indent = 16f) }
        }
        if (d.fellBack) line("raw: ${d.raw ?: "(none)"}", SMALL_SCALE * 0.85f, FAILED, maxLines = 2)
    }

    /** How decision [i] ended: told by the next decision's observation, or the live status for the newest. */
    private fun outcomeOf(session: SimSession, i: Int): IntentStatus? {
        val d = session.history[i].decision
        val next = session.history.getOrNull(i + 1)?.decision?.observation
        if (next != null) return next.lastResult.takeIf { next.lastIntent == d.intent }
        val agent = session.current.agents.firstOrNull() ?: return null
        return agent.status.takeIf { agent.intent == d.intent }
    }

    private fun drawTokens(session: SimSession) {
        heading("TOKENS · every model call this run, by game day")
        TokenReport.format(session.tokens, session.setup.ticksPerMinute, session.current.tick).lines().forEach {
            line(it.trim(), SMALL_SCALE * 0.95f, TEXT, maxLines = 2, gapAfter = 0.15f)
        }
    }

    private fun drawLog(s: WorldSnapshot) {
        val life = s.life
        if (life != null) {
            heading("MONEY")
            if (life.transactions.isEmpty()) line("(no transactions yet)", SMALL_SCALE, MUTED)
            life.transactions.takeLast(MONEY_ROWS).asReversed().forEach { t ->
                val amount = if (t.amount > 0) "+$${t.amount}" else "-$${-t.amount}"
                line("d${t.day} ${t.time}  $amount  ${t.reason}  ($${t.balance})", SMALL_SCALE * 0.95f, if (t.amount > 0) SUCCEEDED else TEXT, maxLines = 1, gapAfter = 0f)
            }
        }
        val events = s.events.asReversed()
        val skip = scroll[Page.Log.ordinal].coerceAtMost((events.size - 1).coerceAtLeast(0)).also { scroll[Page.Log.ordinal] = it }
        heading(if (skip > 0) "EVENTS · ${events.size} · $skip newer above" else "EVENTS · ${events.size}, newest first")
        if (events.isEmpty()) line("(none yet)", SMALL_SCALE, MUTED)
        events.drop(skip).forEach { line("${stamp(it.tick, s)}  ${it.text}", SMALL_SCALE * 0.95f, TEXT, maxLines = 2, gapAfter = 0.1f) }
    }

    private fun drawRun(session: SimSession) {
        val s = session.settings
        heading(if (runRow == null) "SETTINGS · DOWN to change them" else "SETTINGS")
        font.data.setScale(SMALL_SCALE)
        val rowHeight = font.lineHeight * 1.25f
        RunRow.entries.forEach { row ->
            val selected = row == runRow
            cell(if (selected) ">" else "", PANEL_X, 20f, SMALL_SCALE, ACCENT)
            cell(row.label, PANEL_X + 24f, 220f, SMALL_SCALE, if (selected) TEXT else MUTED)
            cell(runValue(row, session), PANEL_X + 250f, PANEL_WIDTH - 250f, SMALL_SCALE, if (selected) ACCENT else TEXT)
            cursor -= rowHeight
        }
        cursor -= 10f
        runRow?.let { line(runHelp(it, session), SMALL_SCALE * 0.9f, MUTED, maxLines = 6) }

        heading("CLOCK")
        val holdsAfter = s.decideCap?.let { "holds the clock after ${seconds(it * 60f / s.speed)} s" } ?: "never holds the clock"
        line("tick ${session.current.tick} · ${s.speed * session.ticksPerMinute} ticks/s · a decision $holdsAfter", SMALL_SCALE * 0.9f, TEXT, maxLines = 2)
        val deciding = session.thinkingFor
        when {
            session.holding && session.waitingOnAuthor.isNotEmpty() -> line("held: waiting on his ${session.waitingOnAuthor.joinToString(" and ") { if (it == "draw") "drawing" else it }}", SMALL_SCALE * 0.9f, INTERRUPTED, maxLines = 1)
            session.holding -> line("held: waiting on the model (%.1f s)".format((deciding?.inWholeMilliseconds ?: 0) / 1000f), SMALL_SCALE * 0.9f, INTERRUPTED, maxLines = 1)
            deciding != null -> line("the model is deciding (%.1f s); the clock runs".format(deciding.inWholeMilliseconds / 1000f), SMALL_SCALE * 0.9f, ACCENT, maxLines = 1)
        }

        heading("RUNS SINCE THE APP STARTED")
        session.runStarts.withIndex().toList().takeLast(RUN_ROWS).asReversed().forEach { (i, start) ->
            val n = i + 1
            val mark = when (n) {
                session.run -> " · this run"
                replayPick -> " · replay picks this"
                else -> ""
            }
            line("run $n: ${summary(start)}$mark", SMALL_SCALE * 0.9f, if (n == (replayPick ?: session.run)) TEXT else MUTED, maxLines = 1, gapAfter = 0.05f)
        }
    }

    private fun runValue(row: RunRow, session: SimSession): String {
        val s = session.settings
        return when (row) {
            RunRow.Days -> s.days?.let { d -> plural(d, "day") + (session.endTick?.let { " · ends ${stamp(it, session.current)}" } ?: "") } ?: "no end"
            RunRow.Speed -> "${s.speed} game min/s · a day in ${RunSettings.realTime(s.secondsPerDay)}"
            RunRow.DecideCap -> capText(s.decideCap)
            RunRow.Resolution -> "${s.ticksPerMinute} ticks/game min" + if (s.ticksPerMinute != session.ticksPerMinute) " · next run (now ${session.ticksPerMinute})" else ""
            RunRow.Restart -> "a fresh run with these settings"
            RunRow.Replay -> "run ${replayPick ?: session.run} as it started" + if (session.run > 1) " · LEFT/RIGHT picks the run" else ""
        }
    }

    private fun runHelp(row: RunRow, session: SimSession): String {
        val s = session.settings
        return when (row) {
            RunRow.Days -> "Game days the run lasts, and the next run's. Raising it extends this run; an ended run reopens, paused. It can't go below the days he has lived."
            RunRow.Speed -> "How fast his day passes on screen. Everything speeds up together; ticks per second = speed x resolution, now ${s.speed * session.ticksPerMinute}."
            RunRow.DecideCap -> "A model's reply takes real seconds whatever the speed, and he stands still meanwhile. The clock runs up to this much game time while a decision is out, " +
                "then holds until it comes back. Waits: thinking costs no game time (lockstep). Runs: it costs whatever the latency is at this speed." +
                (s.decideCap?.takeIf { it > 0 }?.let { " Now: ${seconds(it * 60f / s.speed)} s of model time." } ?: "")
            RunRow.Resolution -> "Ticks per game minute: how finely walking and timing are cut. Walking is in tiles per tick, so fewer ticks make walks cheaper in game time. His prompt states it, so it applies from the next run."
            RunRow.Restart -> "Throws this run away for a fresh one with the settings above. OK, then OK again."
            RunRow.Replay -> "A fresh run set up as the picked run started (length, speed, wait, resolution), to watch the same start play out again. A model may not decide the same way twice. OK, then OK again."
        }
    }

    private fun summary(s: RunSettings): String =
        "${s.days?.let { plural(it, "day") } ?: "no end"} · ${s.speed} min/s · ${s.ticksPerMinute} ticks/min · ${capText(s.decideCap)}"

    private fun capText(cap: Int?): String = when (cap) {
        null -> "clock runs"
        0 -> "clock waits"
        else -> "clock runs ${GameClock.duration(cap)}, then waits"
    }

    private fun plural(n: Int, what: String) = if (n == 1) "1 $what" else "$n ${what}s"

    private fun seconds(s: Float): String = if (s < 10) "%.1f".format(s) else s.toInt().toString()

    private fun drawNeedBars(life: LifeSnapshot) {
        val needs = listOf(life.energy to ENERGY, life.fullness to FULLNESS, life.mood to MOOD, life.stimulation to STIMULATION)
        needs.forEachIndexed { i, (value, color) ->
            val y = barsTop - i * BAR_PITCH - BAR_HEIGHT
            shapes.color = BAR_BG
            shapes.rect(BAR_X, y, BAR_WIDTH, BAR_HEIGHT)
            shapes.color = if (value < LOW) FAILED else color
            shapes.rect(BAR_X, y, BAR_WIDTH * (value / 100.0).toFloat().coerceIn(0f, 1f), BAR_HEIGHT)
        }
        // Where mood drifts to.
        val moodY = barsTop - 2 * BAR_PITCH - BAR_HEIGHT
        shapes.color = TEXT
        shapes.rect(BAR_X + BAR_WIDTH * (life.moodTarget / 100.0).toFloat() - 1.5f, moodY - 4, 3f, BAR_HEIGHT + 8)
    }

    private fun shortStatus(status: IntentStatus?): String = when (status) {
        IntentStatus.Succeeded -> "done"
        IntentStatus.Interrupted -> "interrupted"
        is IntentStatus.Failed -> "failed"
        null -> ""
        else -> "going"
    }

    private fun stateColor(state: RunState): Color = when (state) {
        RunState.Ready -> TEXT
        RunState.Running -> SUCCEEDED
        RunState.Paused -> INTERRUPTED
        RunState.Ended -> ACCENT
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
        private const val FOOTER_Y = MARGIN + 30f
        private const val NOW_EVENT_ROWS = 5
        private const val THOUGHT_ROWS = 2
        private const val HISTORY_ROWS = 7
        private const val THUMB = 96f
        private const val THUMB_GAP = 16f
        private const val ART_SIZE = 320f
        private const val MONEY_ROWS = 5
        private const val RUN_ROWS = 6
        private const val TAB_GAP = 16f
        private const val LABEL_SCALE = 1.1f
        private const val BAR_X = PANEL_X + 130f
        private const val BAR_WIDTH = 440f
        private const val BAR_HEIGHT = 18f
        private const val BAR_PITCH = 34f
        private const val LOW = 15.0
        private val FURNISHING = setOf("furniture", "decor", "fixture")

        private const val TITLE_SCALE = 2.4f
        private const val CLOCK_SCALE = 1.9f
        private const val TAB_SCALE = 1.3f
        private const val MEMORY_SCALE = 1.45f
        private const val HEADING_SCALE = 1.35f
        private const val BODY_SCALE = 1.75f
        private const val SMALL_SCALE = 1.4f
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
        private val PAST_BG = Color.valueOf("3a2a5cdd")
        private val RULE = Color.valueOf("2c313bff")
        private val BED = Color.valueOf("3f5f9eff")
        private val PILLOW = Color.valueOf("dfe6f2ff")
        private val WOOD = Color.valueOf("a0703fff")
        private val WOOD_DARK = Color.valueOf("6b4826ff")
        private val PAPER = Color.valueOf("f1ead8ff")
        private val VINYL = Color.valueOf("17181cff")
        private val POT = Color.valueOf("b5583aff")
        private val POSTER = Color.valueOf("d36fb0ff")
        private val RUG = Color.valueOf("6d3f5a99")
        private val BAR_BG = Color.valueOf("2c313bff")
        private val ENERGY = Color.valueOf("f5c542ff")
        private val FULLNESS = Color.valueOf("4cd18aff")
        private val MOOD = Color.valueOf("5fd3e8ff")
        private val STIMULATION = Color.valueOf("c58ae0ff")

        private val TEXT = Color.valueOf("eef0f4ff")
        private val MUTED = Color.valueOf("8d95a5ff")
        private val ACCENT = Color.valueOf("5fd3e8ff")
        private val CODE = Color.valueOf("c6e6a0ff")
        private val SUCCEEDED = Color.valueOf("4cd18aff")
        private val INTERRUPTED = Color.valueOf("f5c542ff")
        private val FAILED = Color.valueOf("ff7a66ff")
    }
}
