package dev.lilguy.app

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.PerspectiveCamera
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.Mesh
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.Disposable
import com.badlogic.gdx.utils.viewport.FitViewport
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
 * fixed-camera cutaway of the room (no front wall, no ceiling), furniture placed where the snapshot
 * says ([RoomModels]), the guy as an animated [Blob], light and the window's sky following the game
 * clock, and a small HUD with a bubble over his head. His art shows too: hung pieces in frames on
 * the picture rail, and on the desk (a sketch) or the easel (a painting) the piece he's making or
 * his newest unsold one, each a small texture from [ArtTextures]. What he's busy with shows: the book
 * in his hands or open on the desk, the guitar off its peg and in his arms, the record turning and
 * notes rising from it.
 *
 * Everything is drawn in the hand-drawn look by one [CrayonRenderer]: furniture and the guy with the
 * crayon shader and boiling ink outlines, the floor and walls as painted textures under the cheap
 * paper shader. Shown as the dev view's first page ([DevView.Page.Room]); the other pages are the 2D
 * dev view. DOWN opens the shop's catalogue over the room (UP closes it), and it opens by itself
 * while he's buying something, that item picked out. Fill rate stays low for the Streamer: one clear, opaque geometry, the per-pixel crayon
 * shader only on furniture and the guy, no shadow maps or post-processing; the only blended things
 * are the hatched shadows and the HUD's small rectangles.
 */
class RoomView(private val art: ArtTextures) : Disposable {
    private val viewport = FitViewport(DevView.WIDTH, DevView.HEIGHT)
    private val camera = PerspectiveCamera(FOV, DevView.WIDTH, DevView.HEIGHT).apply {
        near = 1f
        far = 60f
    }
    private val models = RoomModels()
    private val crayon = CrayonRenderer()
    private val blob = Blob()
    private val pose = Blob.Pose()
    private var room: RoomModels.Room? = null
    private val identity = Matrix4()
    private val glassAt = Matrix4()
    private val hourHand = models.hand(0.1f)
    private val minuteHand = models.hand(0.15f)
    private val hourAt = Matrix4()
    private val minuteAt = Matrix4()
    private var clockX = 0f
    /** Where each item stands, by id. */
    private val placedAt = HashMap<String, Matrix4>()
    private val worldLight = Vector3()

    /** A picture's quad, by "size/flat"; the piece's texture goes on at draw time. */
    private val quads = HashMap<String, Mesh>()
    private val railAt = Matrix4()
    private val shelfAt = Matrix4()
    private val guitarAt = Matrix4()
    private var shelvedFrom: List<dev.lilguy.sim.CatalogEntry>? = null
    private var shelf: CrayonModel? = null
    private var guitar: CrayonModel? = null
    private val at = Matrix4()
    private val workAt = Vector3()
    private var framed: TileMap? = null
    private var discTurn = 0f
    /** Notes rising from the record player and his guitar, and the seconds to each one's next. */
    private val notes = ArrayList<Note>()
    private var recordNoteIn = 0f
    private var guitarNoteIn = 0f
    private val source = Vector3()

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
    /** The room's light as a tint, white at midday. */
    private val tint = Color(Color.WHITE)

    /** The newest thought, and how long it has been on show. */
    private var thoughtCount = 0
    private var thought: String? = null
    private var thoughtAge = 0f

    fun resize(width: Int, height: Int) = viewport.update(width, height, true)

    /** One frame of the live world; [banner] is the run-state line the dev view would show, if any; [shopPage] shows that page of the catalogue. */
    fun render(session: SimSession, banner: String?, delta: Float, shopPage: Int? = null) {
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
            ((agent?.intent as? Intent.Do)?.activity == "think" && agent.status.let { it == IntentStatus.Executing || it == IntentStatus.Validated || it == IntentStatus.Submitted })
        val asleep = pose.doing == "sleep" && !pose.moving
        // On the bed's tile he is on the mattress; standing still on a chair's tile, he sits on it.
        val under = now.entities.firstOrNull { it.id.value in SURFACES && it.x.toInt() == guy.x.toInt() && it.y.toInt() == guy.y.toInt() }?.id?.value
        pose.standY = if (under == "chair" && pose.moving) 0f else SURFACES[under] ?: 0f
        // Drawing, writing or painting, his hand goes to the page or the canvas.
        val station = WORK[pose.doing]
        val stand = station?.let { st -> now.entities.firstOrNull { it.id.value == st.id } }
        pose.workAt = if (station != null && stand != null) workAt.set(stand.x + station.x, station.y, stand.y + station.z) else null
        pose.workFlat = station?.flat ?: true
        pose.workSlide = station?.slide ?: 0f
        blob.update(delta, pose)

        crayon.begin(camera, tint, delta)
        room?.let { r ->
            for ((mesh, texture) in r.surfaces) crayon.surface(mesh, texture, identity)
            crayon.surface(r.glass, models.paint.texture(RoomPaint.WHITE)!!, glassAt, light.sky)
            crayon.model(r.fixtures, identity)
        }
        crayon.model(hourHand, hourAt)
        crayon.model(minuteHand, minuteAt)
        for (e in now.entities) {
            if (e.carriedBy != null || e.kind == "guy") continue
            if (e.kind == "door" && e.state == "open") continue
            place(e, now.map)
        }
        renderArt(session, now)
        renderShelf(life, now.map)
        blob.render(crayon, asleep)
        val book = models.bookColor((agent?.intent as? Intent.Do)?.what)
        when (blob.held) {
            Blob.Held.Book -> crayon.model(models.heldBook(book), blob.heldAt)
            Blob.Held.Guitar -> crayon.model(models.heldGuitar(), blob.heldAt)
            Blob.Held.None -> {}
        }
        renderStudy(book)
        renderMusic(life, delta)
        crayon.end()
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST)

        updateThought(session, delta)
        val bubble = agent?.saying ?: when {
            asleep -> "z z z"
            thought != null -> thought
            pose.thinking -> "..."
            else -> null
        }
        camera.project(head.set(blob.headTop), 0f, 0f, DevView.WIDTH, DevView.HEIGHT)
        // While he's off to buy something, the catalogue opens by itself at that item.
        val buying = (agent?.intent as? Intent.Buy)?.item?.takeIf { agent.status.let { it == IntentStatus.Executing || it == IntentStatus.Validated || it == IntentStatus.Submitted } }
        drawHud(session, life, bubble, banner, if (shopPage != null || buying != null) buying ?: "" else null, shopPage ?: 0)
    }

    // --- 3D --------------------------------------------------------------------------------------

    /** Points the camera at [map]'s room and builds it (once per map): a front view from above, the whole room in frame. */
    private fun frame(map: TileMap) {
        if (framed === map) return
        framed = map
        placedAt.clear()
        clockX = models.clockX(map)
        glassAt.setToTranslation(models.window(map), (RoomModels.WINDOW_BOTTOM + RoomModels.WINDOW_TOP) / 2, 1.012f)
        val halfFovX = tan(FOV / 2 * MathUtils.degreesToRadians) * DevView.WIDTH / DevView.HEIGHT
        val distance = (map.width / 2f + SIDE_MARGIN) / halfFovX
        val target = Vector3(map.width / 2f, 1.1f, map.height / 2f + 0.2f)
        camera.position.set(target).add(0f, MathUtils.sinDeg(PITCH) * distance, MathUtils.cosDeg(PITCH) * distance)
        camera.up.set(Vector3.Y)
        camera.lookAt(target)
        camera.update()
        val start = System.nanoTime()
        room = models.room(map, CrayonRenderer.worldLight(camera, worldLight))
        Gdx.app.log(LilGuyGame.TAG, "room built in ${(System.nanoTime() - start) / 1_000_000} ms (painting ${models.paint.paintMillis} ms so far)")
    }

    /**
     * Queues an item at its tile; wall items sit against the wall face that looks into the room.
     * Things standing on the floor get a hatched shadow the size of their footprint.
     */
    private fun place(e: EntitySnapshot, map: TileMap) {
        val id = e.id.value
        val model = models.item(id, e.kind)
        val transform = placedAt.getOrPut(id) { Matrix4() }
        val tx = e.x.toInt()
        val ty = e.y.toInt()
        if (map[tx, ty] == Terrain.Wall && models.wallItem(id, e.kind)) {
            val (dx, dy) = INWARD.firstOrNull { (dx, dy) -> map[tx + dx, ty + dy] == Terrain.Floor } ?: (0 to 1)
            val yaw = when (dx to dy) { 1 to 0 -> 90f; -1 to 0 -> -90f; 0 to -1 -> 180f; else -> 0f }
            transform.setToTranslation(tx + 0.5f + dx * 0.5f, 0f, ty + 0.5f + dy * 0.5f).rotate(Vector3.Y, yaw)
        } else {
            transform.setToTranslation(e.x, 0f, e.y)
            if (!models.flat(id, e.kind)) {
                val b = model.bounds
                at.set(transform).translate((b[0] + b[3]) / 2, SHADOW_LIFT, (b[2] + b[5]) / 2)
                    .scale((b[3] - b[0]) * SHADOW_SPREAD, 1f, (b[5] - b[2]) * SHADOW_SPREAD)
                crayon.shadow(at)
            }
        }
        crayon.model(model, transform)
    }

    /**
     * Hung pieces in frames along the picture rail, oldest at the left; then each stand ([STANDS]):
     * the piece he's making there, filling in as he works, or else his newest unsold piece of its
     * item. The easel holds a blank canvas while he has one, is painting, or has a painting to show.
     * Pieces not drawn yet show as an empty frame, a blank page or a blank canvas.
     */
    private fun renderArt(session: SimSession, now: WorldSnapshot) {
        val life = now.life ?: return
        val pieces = life.art
        art.sync(session)
        val drawings = art.drawings(session)
        val rail = placedAt["picture_rail"]
        if (rail != null && now.entities.any { it.id.value == "picture_rail" }) {
            pieces.filter { it.hung }.take(RoomModels.FRAME_XS.size).forEachIndexed { i, p ->
                railAt.set(rail).translate(RoomModels.FRAME_XS[i], RoomModels.FRAME_BOTTOM, 0f)
                crayon.model(models.frame(), railAt)
                picture(drawings[p.id]?.let(art::of), RoomModels.FRAME_ART, flat = false, at.set(railAt).translate(0f, RoomModels.FRAME / 2, RoomModels.FRAME_ART_Z))
            }
        }
        for (stand in STANDS) {
            val e = now.entities.firstOrNull { it.id.value == stand.id } ?: continue
            val making = life.doing == stand.activity
            val texture = if (making) {
                session.memory.drawingInProgress()?.takeIf { it.item == stand.item }?.let { art.partial(it, life.progress ?: 0f, stand.blank) }
            } else if (life.doing == "study" && stand === SKETCH) {
                // A fresh page for his notes.
                null
            } else {
                pieces.lastOrNull { !it.hung && it.item == stand.item }?.let { p -> drawings[p.id]?.let(art::of) }
            }
            if (stand.canvas && (making || texture != null || (life.owns["canvas"] ?: 0) > 0)) {
                placedAt[stand.id]?.let { crayon.model(models.canvas(), it) }
            }
            picture(texture, stand.size, stand.flat, at.setToTranslation(e.x + stand.x, stand.y, e.y + stand.z))
        }
    }

    /** His books and records on the wall shelf, and his guitar on the wall, once he owns any. */
    private fun renderShelf(life: LifeSnapshot?, map: TileMap) {
        val catalog = life?.catalog ?: return
        // The world shares one catalog list until what he owns changes, so this runs a few times a run.
        if (catalog !== shelvedFrom) {
            shelvedFrom = catalog
            val owned = catalog.filter { it.owned > 0 }
            val books = owned.filter { it.group == "book" }.map { it.id }
            val records = owned.filter { it.group == "record" }.map { it.id }
            shelf = if (books.isEmpty() && records.isEmpty()) null else models.shelf(books, records)
            guitar = if (owned.any { it.id == "guitar" }) models.guitar() else null
        }
        shelf?.let { crayon.model(it, shelfAt.setToTranslation(models.shelfX(map), 0f, 1f)) }
        // Off its peg while he plays it.
        if (blob.held != Blob.Held.Guitar) guitar?.let { crayon.model(it, guitarAt.setToTranslation(models.guitarX(map), 0f, 1f)) }
    }

    /** Studying, the book lies open on the desk beside his notes, a page turning over now and then. */
    private fun renderStudy(color: String) {
        if (pose.doing != "study" || pose.moving) return
        val desk = placedAt["desk"] ?: return
        at.set(desk).translate(RoomModels.STUDY_BOOK_X, RoomModels.STUDY_BOOK_Y, RoomModels.STUDY_BOOK_Z)
        crayon.model(models.openBook(color), at)
        val t = (studyClock % STUDY_PAGE_EVERY) / STUDY_PAGE_TURN
        if (t < 1f) crayon.model(models.page(), at.translate(0f, 0.024f, 0f).rotate(Vector3.Z, 180f * MathUtils.sin(t * MathUtils.HALF_PI)))
    }

    private var studyClock = 0f

    /**
     * The record turns while music plays, and notes rise from the player; more from his guitar while
     * he plays it. Each drifts up, wavering, and shrinks away.
     */
    private fun renderMusic(life: LifeSnapshot?, delta: Float) {
        studyClock += delta
        val player = placedAt["record_player"]
        val playing = life?.music != null
        if (playing) discTurn = (discTurn + delta * DISC_SPEED) % 360f
        player?.let { crayon.model(models.disc(), at.set(it).translate(RoomModels.DISC_X, RoomModels.DISC_Y, 0f).rotate(Vector3.Y, -discTurn)) }

        recordNoteIn -= delta
        if (playing && player != null && recordNoteIn <= 0f) {
            recordNoteIn = NOTE_EVERY
            notes += Note(player.getTranslation(source).add(RoomModels.DISC_X, RoomModels.DISC_Y + 0.12f, 0.1f))
        }
        guitarNoteIn -= delta
        if (blob.held == Blob.Held.Guitar && guitarNoteIn <= 0f) {
            guitarNoteIn = NOTE_EVERY * 0.7f
            notes += Note(source.set(blob.headTop).add(0.1f, -0.05f, 0.15f))
        }
        val it = notes.iterator()
        while (it.hasNext()) {
            val n = it.next()
            n.age += delta
            if (n.age > NOTE_LIFE) { it.remove(); continue }
            val grow = NOTE_SIZE * (n.age / 0.25f).coerceAtMost(1f) * ((NOTE_LIFE - n.age) / 0.7f).coerceAtMost(1f)
            val k = n.age * 2.4f + n.phase
            at.setToTranslation(n.x + 0.08f * MathUtils.sin(k), n.y + NOTE_RISE * n.age, n.z)
                .rotate(Vector3.Z, 14f * MathUtils.sin(k + 1f)).scale(grow, grow, grow)
            crayon.model(models.note(n.color), at)
        }
    }

    /** One note on its way up, from where it started. */
    private class Note(from: Vector3) {
        val x = from.x + MathUtils.random(-0.12f, 0.12f)
        val y = from.y
        val z = from.z
        val phase = MathUtils.random(MathUtils.PI2)
        val color = NOTE_COLORS[MathUtils.random(NOTE_COLORS.size - 1)]
        var age = 0f
    }

    /** Queues a square of a piece's picture, [size] wide, facing +Z or lying face up when [flat], at [transform]; nothing until it's drawn. */
    private fun picture(texture: Texture?, size: Float, flat: Boolean, transform: Matrix4) {
        texture ?: return
        crayon.surface(quads.getOrPut("$size/$flat") { models.quad(size, flat) }, texture, transform)
    }

    /** Ambient, sun, sky, background and the wall clock for the time of day; the bedroom's clock, or noon without one. */
    private fun lightFor(life: LifeSnapshot?) {
        val hour = life?.clock?.time?.let { t ->
            val (h, m) = t.split(':').map { it.toIntOrNull() ?: 0 }
            h + m / 60f
        } ?: 12f
        light.at(hour)
        hourAt.setToTranslation(clockX, RoomModels.CLOCK_Y, 1.06f).rotate(Vector3.Z, -30f * hour)
        minuteAt.setToTranslation(clockX, RoomModels.CLOCK_Y, 1.07f).rotate(Vector3.Z, -360f * hour)
        // Everything shades itself; the room's light only tints it, white at midday.
        val a = light.ambient
        val s = light.sun
        tint.set((a.r + s.r) / DAY_LIGHT[0], (a.g + s.g) / DAY_LIGHT[1], (a.b + s.b) / DAY_LIGHT[2], 1f)
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

    /** [shop]: null with the catalogue closed, else the item he's buying ("" for none); [page] is the one shown when he isn't. */
    private fun drawHud(session: SimSession, life: LifeSnapshot?, bubble: String?, banner: String?, shop: String?, page: Int) {
        val catalogue = shop?.let { life?.catalog?.takeIf { c -> c.isNotEmpty() } }
        shapes.projectionMatrix = viewport.camera.combined
        batch.projectionMatrix = viewport.camera.combined
        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)

        shapes.begin(ShapeRenderer.ShapeType.Filled)
        life?.let { cardShape(it) }
        catalogue?.let { catalogueShape(it, shop, page) }
        bubble?.let { bubbleShape(it) }
        banner?.let { bannerShape(it) }
        shapes.end()

        batch.begin()
        life?.let { cardText(session, it) }
        catalogue?.let { catalogueText(it, life!!.money, shop, page) }
        bubble?.let { bubbleText(it) }
        banner?.let { bannerText(it) }
        font.data.setScale(HINT_SCALE)
        font.color = HINT
        val hint = if (catalogue != null) "DOWN turns the page · UP turns back or closes · LEFT/RIGHT for the dev pages" else "DOWN for the shop · LEFT/RIGHT for the dev pages"
        font.draw(batch, hint, DevView.WIDTH - MARGIN - 700f, DevView.HEIGHT - MARGIN, 700f, Align.right, false)
        batch.end()
    }

    private fun cardShape(life: LifeSnapshot) {
        shapes.color = CARD
        roundRect(MARGIN, DevView.HEIGHT - MARGIN - CARD_HEIGHT, CARD_WIDTH, CARD_HEIGHT, 18f)
        val needs = listOf(life.energy to ENERGY, life.fullness to FULLNESS, life.mood to MOOD, life.stimulation to STIMULATION)
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
        for ((i, label) in listOf("energy", "food", "mood", "fun").withIndex()) {
            font.draw(batch, label, left, DevView.HEIGHT - MARGIN - 86f - i * BAR_PITCH + BAR_HEIGHT - 1f)
        }
        font.data.setScale(DOING_SCALE)
        font.color = INK
        val doing = life.doing?.let { d ->
            d.replace('_', ' ') + (life.progress?.let { " · ${(it * 100).toInt()}%" } ?: "") + session.fastForward.let { if (it > 1) "   >> ${it}x" else "" }
        }
        font.draw(batch, doing ?: if (session.thinkingFor != null) "deciding..." else "idle", left, DevView.HEIGHT - MARGIN - CARD_HEIGHT + 40f)
    }

    // --- the catalogue: the shop as a mail-order card ----------------------------------------------

    /**
     * What the shop sells, known things first, [SHOP_ROWS] to a page: the page with the item he's
     * [buying] on it, else page [page] (kept in range). Returns that page's items and its number.
     */
    private fun shown(catalog: List<dev.lilguy.sim.CatalogEntry>, buying: String, page: Int): Pair<List<dev.lilguy.sim.CatalogEntry>, Int> {
        val pages = catalog.sortedBy { it.taughtBy != null }.chunked(SHOP_ROWS)
        val at = pages.indexOfFirst { p -> p.any { it.id == buying } }.takeIf { it >= 0 } ?: page.coerceIn(0, pages.lastIndex)
        return pages[at] to at
    }

    /** Every page is as tall as a full one, so the card doesn't jump as it turns. */
    private fun shopHeight(catalog: List<dev.lilguy.sim.CatalogEntry>) = SHOP_HEADER + minOf(catalog.size, SHOP_ROWS) * SHOP_PITCH + SHOP_FOOTER

    private fun catalogueShape(catalog: List<dev.lilguy.sim.CatalogEntry>, buying: String, page: Int) {
        val items = shown(catalog, buying, page).first
        val h = shopHeight(catalog)
        val x = SHOP_X
        val top = SHOP_TOP
        // Paper with scalloped top and bottom edges, and a stitched line just inside.
        shapes.color = PAPER_EDGE
        roundRect(x - 4f, top - h - 4f, SHOP_WIDTH + 8f, h + 8f, 22f)
        shapes.color = CARD
        roundRect(x, top - h, SHOP_WIDTH, h, 18f)
        var sx = x + 22f
        while (sx < x + SHOP_WIDTH - 16f) {
            shapes.circle(sx, top, 11f, 10)
            shapes.circle(sx, top - h, 11f, 10)
            sx += 26f
        }
        shapes.color = STITCH
        var dx = x + 18f
        while (dx < x + SHOP_WIDTH - 30f) {
            shapes.rect(dx, top - 16f, 12f, 3f)
            shapes.rect(dx, top - h + 13f, 12f, 3f)
            dx += 22f
        }
        var dy = top - h + 28f
        while (dy < top - 28f) {
            shapes.rect(x + 14f, dy, 3f, 12f)
            shapes.rect(x + SHOP_WIDTH - 17f, dy, 3f, 12f)
            dy += 22f
        }
        // A ribbon across the top with notched ends.
        val ry = top - 74f
        shapes.color = RIBBON_DARK
        shapes.rect(x + 34f, ry - 6f, 36f, 44f)
        shapes.rect(x + SHOP_WIDTH - 70f, ry - 6f, 36f, 44f)
        shapes.color = CARD
        shapes.triangle(x + 34f, ry - 6f, x + 34f, ry + 38f, x + 50f, ry + 16f)
        shapes.triangle(x + SHOP_WIDTH - 34f, ry - 6f, x + SHOP_WIDTH - 34f, ry + 38f, x + SHOP_WIDTH - 50f, ry + 16f)
        shapes.color = RIBBON
        roundRect(x + 56f, ry, SHOP_WIDTH - 112f, 50f, 8f)

        items.forEachIndexed { i, e ->
            val y = top - SHOP_HEADER - i * SHOP_PITCH
            if (e.id == buying) {
                shapes.color = PICKED
                roundRect(x + 26f, y - SHOP_PITCH + 6f, SHOP_WIDTH - 52f, SHOP_PITCH - 4f, 12f)
            }
            val cy = y - SHOP_PITCH / 2 + 4f
            shapes.color = if (e.taughtBy != null) BAR_BG else GROUP_COLORS[e.group] ?: BAR_BG
            shapes.circle(x + 50f, cy, 12f, 14)
            if (e.taughtBy != null) return@forEachIndexed
            // A swing tag: string, card, hole.
            val tx = x + SHOP_WIDTH - 270f
            shapes.color = STRING
            shapes.rectLine(tx - 18f, cy + 12f, tx + 4f, cy + 2f, 2f)
            shapes.color = if (e.affordable || owned(e)) TAG else TAG_DEAR
            roundRect(tx, cy - 16f, 104f, 32f, 9f)
            shapes.color = CARD
            shapes.circle(tx + 10f, cy, 4f, 8)
            if (owned(e)) {
                // A little tick beside the tag.
                shapes.color = FULLNESS
                val kx = tx + 128f
                shapes.rectLine(kx, cy, kx + 9f, cy - 10f, 5f)
                shapes.rectLine(kx + 9f, cy - 10f, kx + 26f, cy + 12f, 5f)
            }
        }
    }

    private fun catalogueText(catalog: List<dev.lilguy.sim.CatalogEntry>, money: Int, buying: String, page: Int) {
        val (items, at) = shown(catalog, buying, page)
        val h = shopHeight(catalog)
        val x = SHOP_X
        val top = SHOP_TOP
        font.data.setScale(SHOP_TITLE_SCALE)
        font.color = Color.WHITE
        font.draw(batch, "The Mail-Order Catalogue", x + 56f, top - 34f, SHOP_WIDTH - 112f, Align.center, false)
        items.forEachIndexed { i, e ->
            val y = top - SHOP_HEADER - i * SHOP_PITCH
            val cy = y - SHOP_PITCH / 2 + 4f
            val hidden = e.taughtBy != null
            font.data.setScale(SHOP_NAME_SCALE)
            font.color = if (hidden) MUTED else INK
            font.draw(batch, e.name, x + 74f, cy + 11f, SHOP_WIDTH - 360f, Align.left, false)
            font.data.setScale(SHOP_SMALL_SCALE)
            if (hidden) {
                font.color = MUTED
                font.draw(batch, "learn of it in " + e.taughtBy!!.substringBefore(','), x + SHOP_WIDTH - 310f, cy + 9f, 280f, Align.right, false)
                return@forEachIndexed
            }
            val tx = x + SHOP_WIDTH - 270f
            font.color = if (e.affordable || owned(e)) INK else Color.WHITE
            font.draw(batch, if (e.pack > 1) "${e.pack} for $${e.price}" else "$${e.price}", tx + 16f, cy + 9f, 84f, Align.center, false)
            font.color = MUTED
            val note = when {
                e.id == buying -> "on its way!"
                owned(e) -> if (e.stackable) "${e.owned} at home" else ""
                !e.affordable -> "saving up"
                else -> ""
            }
            font.draw(batch, note, tx + 122f, cy + 9f, 120f, Align.left, false)
        }
        font.data.setScale(SHOP_SMALL_SCALE)
        font.color = MUTED
        val pages = shopPages(catalog.size)
        val more = if (pages > 1) " · page ${at + 1} of $pages" else ""
        font.draw(batch, "in his purse: $$money$more", x, top - h + 40f, SHOP_WIDTH, Align.center, false)
    }

    /** Has one already, of something bought once (stackable things are bought again and again). */
    private fun owned(e: dev.lilguy.sim.CatalogEntry) = e.owned > 0 && !e.stackable

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
        quads.values.forEach { it.dispose() }
        models.dispose()
        blob.dispose()
        crayon.dispose()
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
        /**
         * Where [item]s are made ([activity]) and the newest unsold one shows: on which stand, offset
         * from its tile centre, how big, lying flat or upright, the colour of the blank it's made on
         * (0xRRGGBB), whether the stand needs a [RoomModels.canvas] for it, and how far he moves in from
         * his tile to work at it (on the chair at the desk, a little; standing at the easel, a step).
         */
        class Stand(
            val id: String, val item: String, val activity: String,
            val x: Float, val y: Float, val z: Float, val size: Float, val flat: Boolean, val blank: Int, val canvas: Boolean = false, val slide: Float,
        )

        val SKETCH = Stand("desk", "sketch", "sketch", RoomModels.SKETCH_X, 0.567f, RoomModels.SKETCH_Z, 0.2f, flat = true, blank = 0xfdf9ef, slide = 0.14f)
        val PAINTING = Stand("easel", "painting", "paint", 0f, RoomModels.CANVAS_Y, RoomModels.CANVAS_Z + 0.025f, 0.44f, flat = false, blank = 0xfdf8ee, canvas = true, slide = 0.38f)
        val STANDS = listOf(SKETCH, PAINTING)
        /** Activities done by hand at a stand, and which: his working hand goes to it. */
        val WORK = mapOf("sketch" to SKETCH, "journal" to SKETCH, "study" to SKETCH, "paint" to PAINTING)

        /** Studying: seconds between page turns of the open book, and how long one takes. */
        const val STUDY_PAGE_EVERY = 7f
        const val STUDY_PAGE_TURN = 0.8f
        /** The record's turn (degrees a second), and the notes: seconds apart, how long each lasts, and how fast it rises (metres a second). */
        const val DISC_SPEED = 200f
        const val NOTE_EVERY = 0.9f
        const val NOTE_LIFE = 2.6f
        const val NOTE_RISE = 0.3f
        /** Notes are drawn this much bigger than [RoomModels.note], to read from across the room. */
        const val NOTE_SIZE = 1.7f
        val NOTE_COLORS = listOf("#f2928a", "#8fb0ec", "#9fd49a", "#c9a0e6")

        /** What he stands, sits or lies on, by item id, and how high its top is. */
        val SURFACES = mapOf("bed" to 0.3f, "chair" to 0.38f)
        /** Furniture shadows: how high over the floor (above the rug), and how much wider than the footprint. */
        const val SHADOW_LIFT = 0.04f
        const val SHADOW_SPREAD = 1.2f
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
        const val CARD_HEIGHT = 270f
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
        val STIMULATION = Color.valueOf("c58ae0ff")
        val BUBBLE = Color.valueOf("fffdf6ff")
        val BANNER_BG = Color.valueOf("2a1e18ff")

        const val SHOP_WIDTH = 640f
        const val SHOP_X = DevView.WIDTH - MARGIN - SHOP_WIDTH
        const val SHOP_TOP = DevView.HEIGHT - MARGIN - 44f
        const val SHOP_HEADER = 104f
        const val SHOP_PITCH = 46f
        const val SHOP_FOOTER = 64f
        const val SHOP_TITLE_SCALE = 1.7f
        const val SHOP_NAME_SCALE = 1.45f
        const val SHOP_SMALL_SCALE = 1.15f
        val PAPER_EDGE = Color.valueOf("e8cfb2ff")
        val STITCH = Color.valueOf("e7a99bff")
        val RIBBON = Color.valueOf("e07f74ff")
        val RIBBON_DARK = Color.valueOf("b9605aff")
        val PICKED = Color.valueOf("fde9c6ff")
        val STRING = Color.valueOf("c0504aff")
        val TAG = Color.valueOf("f6e3b8ff")
        val TAG_DEAR = Color.valueOf("d98a7aff")
        /** A swatch per kind of thing, in the room's pastels. */
        val GROUP_COLORS = mapOf(
            "food" to Color.valueOf("9fd49aff"), "supply" to Color.valueOf("ffe3a1ff"), "furniture" to Color.valueOf("dba77cff"),
            "record" to Color.valueOf("f4a3adff"), "book" to Color.valueOf("8fb0ecff"), "instrument" to Color.valueOf("f0b46eff"),
            "decor" to Color.valueOf("c2e6a8ff"),
        )
    }
}

/** Rows on a page of the catalogue card. */
private const val SHOP_ROWS = 17

/** How many pages of the catalogue card [entries] fill. */
internal fun shopPages(entries: Int) = maxOf(1, (entries + SHOP_ROWS - 1) / SHOP_ROWS)
