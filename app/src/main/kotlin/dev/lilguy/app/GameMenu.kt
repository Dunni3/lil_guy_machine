package dev.lilguy.app

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.Disposable
import com.badlogic.gdx.utils.viewport.FitViewport
import dev.lilguy.agent.memory.Describe
import dev.lilguy.agent.memory.SelfModel
import dev.lilguy.agent.memory.SelfRevision
import dev.lilguy.agent.memory.SelfVersion
import dev.lilguy.agent.memory.StoredMemory
import dev.lilguy.agent.memory.Thought
import dev.lilguy.agent.shop.Trip
import dev.lilguy.app.SimSession.RunState
import dev.lilguy.decision.Usage
import dev.lilguy.sim.BedroomWorld
import dev.lilguy.sim.CatalogEntry
import dev.lilguy.sim.GameClock
import dev.lilguy.sim.IntentStatus
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration

/**
 * The in-game menu: a paper card over the 3D room, in the room's look, opened with OK. Tabs across
 * the top; each is a list on the left and the picked entry in full on the right.
 *
 * - [Tab.Run]: start, pause and resume; the run's settings; restart and replay.
 * - [Tab.Shop]: everything for sale, what it's for, and what he has and can afford.
 * - [Tab.Thoughts]: his thoughts, newest first, with what he asked about his world while thinking.
 * - [Tab.Memory]: right now (recent actions, what he recalled at the latest decision), then his
 *   long-term memories, stretches and whole days, newest first.
 * - [Tab.Self]: who he sees himself as now; his fixed core and taste; every night's revision of
 *   his self-model, what changed and why; the seed he started with.
 * - [Tab.Trips]: every shopping trip: what he picked and why, what he's saving for, his note,
 *   what he was shown, and the model's raw reply.
 *
 * Keys ([press]): LEFT/RIGHT switch tabs (on a Run setting they change it), UP/DOWN pick, OK reads
 * the picked entry (then UP/DOWN scroll it, OK or BACK go back to the list) or, on the Run tab,
 * does the row's action. BACK closes the menu. Opening it on a run that hasn't started lands on
 * Start, so OK twice starts a run.
 *
 * The run carries on while it's open. Fill rate: one opaque card, small rectangles, text.
 */
class GameMenu(private val onRestart: () -> Unit) : Disposable {
    enum class Tab(val title: String) { Run("RUN"), Shop("SHOP"), Thoughts("THOUGHTS"), Memory("MEMORY"), Self("SELF"), Trips("TRIPS") }

    /** What the remote pressed, as the menu sees it. */
    enum class Press { Ok, Back, Left, Right, Up, Down }

    /** One entry in a tab's list: [key] keeps the pick on it as newer entries arrive. */
    private class Entry(val key: Any, val title: String, val sub: String, val titleColor: Color = INK, val subColor: Color = MUTED, val mark: Color? = null)

    /** One laid-out line of the detail pane. */
    private class Row(val text: String, val scale: Float, val color: Color, val indent: Float, val gapBefore: Float)

    var open = false
        private set

    var tab = Tab.Run
        private set

    /** The picked entry per tab, by key; null picks the first (the newest, in the lists that run newest first). */
    private val picked = arrayOfNulls<Any>(Tab.entries.size)

    /** The detail pane has the focus: UP/DOWN scroll it. */
    private var reading = false
    private var detailScroll = 0

    /** Set by the first OK on Restart or Replay: a second OK does it, any other key cancels. */
    private var confirm = false

    /** The run Replay would start again (1-based); null for this one. */
    private var replayPick: Int? = null

    private val viewport = FitViewport(DevView.WIDTH, DevView.HEIGHT)
    private val shapes = ShapeRenderer()
    private val batch = SpriteBatch()
    private val font = BitmapFont().apply {
        region.texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
        setUseIntegerPositions(false)
    }
    private val glyphs = GlyphLayout()
    private val wrapped = HashMap<String, List<String>>()
    private val rows = ArrayList<Row>()

    fun resize(width: Int, height: Int) = viewport.update(width, height, true)

    /** Opens the menu: on Start when the run hasn't started, else where it was left. */
    fun show(session: SimSession) {
        open = true
        reading = false
        confirm = false
        if (session.state == RunState.Ready) {
            tab = Tab.Run
            picked[Tab.Run.ordinal] = RunRow.Play
        }
    }

    fun close() {
        open = false
        reading = false
        confirm = false
    }

    /** After a restart: every pick back to the top; the old run's entries are gone. */
    fun reset() {
        picked.fill(null)
        reading = false
        detailScroll = 0
        confirm = false
        replayPick = null
    }

    fun press(p: Press, session: SimSession) {
        val confirming = confirm
        confirm = false
        when (p) {
            Press.Back -> if (reading) reading = false else close()
            Press.Left, Press.Right -> {
                val by = if (p == Press.Left) -1 else 1
                val row = picked[Tab.Run.ordinal] as? RunRow ?: RunRow.Play
                if (tab == Tab.Run && !reading && row.adjustable) replayPick = RunControls.adjust(row, by, session, replayPick)
                else {
                    tab = Tab.entries[(tab.ordinal + by).mod(Tab.entries.size)]
                    reading = false
                    detailScroll = 0
                }
            }
            Press.Up, Press.Down -> {
                val by = if (p == Press.Up) -1 else 1
                if (reading) detailScroll = (detailScroll + by * SCROLL_STEP).coerceAtLeast(0)
                else {
                    val list = entries(tab, session)
                    if (list.isEmpty()) return
                    val next = (index(list) + by).coerceIn(0, list.lastIndex)
                    picked[tab.ordinal] = if (next == 0) null else list[next].key
                    detailScroll = 0
                }
            }
            Press.Ok -> when {
                tab == Tab.Run -> runAction(session, confirming)
                reading -> reading = false
                entries(tab, session).isNotEmpty() -> {
                    reading = true
                    detailScroll = 0
                }
            }
        }
    }

    /** OK on a Run row: start, pause or resume (starting or resuming closes the menu); restart and replay ask first. */
    private fun runAction(session: SimSession, confirmed: Boolean) {
        val row = picked[Tab.Run.ordinal] as? RunRow ?: RunRow.Play
        when (row) {
            RunRow.Play -> {
                session.toggle()
                if (session.state == RunState.Running) close()
            }
            RunRow.Restart, RunRow.Replay -> {
                if (!confirmed) {
                    confirm = true
                    return
                }
                if (row == RunRow.Replay) session.replay(replayPick ?: session.run) else session.restart()
                onRestart()
                reset()
                picked[Tab.Run.ordinal] = RunRow.Play
            }
            else -> Unit
        }
    }

    /** Where the pick is in [list]: the entry with the picked key, or the first. */
    private fun index(list: List<Entry>): Int = picked[tab.ordinal]?.let { k -> list.indexOfFirst { it.key == k } }?.takeIf { it >= 0 } ?: 0

    // --- entries ---------------------------------------------------------------------------------

    private fun entries(tab: Tab, s: SimSession): List<Entry> = when (tab) {
        Tab.Run -> RunRow.entries.map { row ->
            if (row == RunRow.Play) {
                val title = when (s.state) { RunState.Ready -> "Start"; RunState.Running -> "Pause"; RunState.Paused -> "Resume"; RunState.Ended -> "Ended" }
                Entry(row, title, "run ${s.run} · ${s.state.name.lowercase()}" + (if (s.holding) " · held" else ""), mark = stateColor(s.state))
            } else {
                Entry(row, row.label, RunControls.value(row, s, replayPick), subColor = if (row.isAction) MUTED else INK)
            }
        }
        Tab.Shop -> s.current.life?.catalog.orEmpty().sortedBy { it.taughtBy != null }.map { e ->
            Entry(e.id, e.name, shopStatus(e), if (e.taughtBy != null) MUTED else INK, if (!e.affordable && e.taughtBy == null && !owned(e)) DEAR else MUTED, mark = if (e.taughtBy != null) BAR_BG else GROUP_COLORS[e.group] ?: BAR_BG)
        }
        Tab.Thoughts -> {
            val thoughts = s.memory.thoughts()
            val plan = s.memory.plan
            thoughts.asReversed().map { t ->
                val asked = if (t.asked.isEmpty()) "" else " · asked ${t.asked.size}"
                Entry(t, at(t.day, t.time, t.tick, s) + (if (t === plan) " · his plan" else "") + asked,
                    t.text ?: "no thought: ${t.error}", subColor = if (t.text == null) FAILED else MUTED, mark = if (t === plan) ACCENT else null)
            }
        }
        Tab.Memory -> {
            val mem = s.memory
            val recalled = mem.lastRecalled
            fun end(m: StoredMemory) = (m.span.toDay ?: 0) * GameClock.MINUTES_PER_DAY + (m.span.toTime?.let(GameClock::parseHhmm) ?: 0) + m.level
            val all = (mem.memories() + mem.days()).sortedWith(compareByDescending<StoredMemory> { end(it) }.thenByDescending { it.id })
            listOf(Entry(NOW, "Right now", "${s.runtime.recent.size} recent actions · ${s.decision?.recalled?.size ?: 0} recalled at the latest decision", mark = ACCENT)) +
                all.map { m ->
                    val title = (if (m.level == 2) "The whole day · " else "") + m.span.label.ifEmpty { "events ${m.fromId}-${m.toId}" }
                    Entry(m.id, title, m.text, mark = if (m.id in recalled) ACCENT else if (m.level == 2) RIBBON else null)
                }
        }
        Tab.Self -> {
            val mem = s.memory
            val revisions = mem.revisions()
            val now = listOfNotNull(mem.selfText()?.let { Entry(NOW, "How he sees himself now", "in every prompt · " + (mem.selfModel()?.let { "revised " + revisions.count { it.changed }.let { if (it == 1) "once" else "$it times" } } ?: "still the seed"), mark = ACCENT) })
            now + Entry(CORE, "His core and taste", "fixed by design: never his to change", mark = RIBBON) +
                revisions.withIndex().reversed().map { (i, r) ->
                    val what = when { r.error != null -> "failed"; r.changed -> "changed"; else -> "unchanged" }
                    Entry(i, "Night of day ${r.day ?: "?"} · $what", r.error ?: r.note.orEmpty(), subColor = if (r.error != null) FAILED else MUTED, mark = if (r.changed) ACCENT else null)
                } + listOfNotNull(mem.selfText()?.let { Entry(SEED, "Before the first night", "the seed he started with") })
        }
        Tab.Trips -> s.memory.trips().asReversed().map { t ->
            val what = when {
                t.error != null -> "failed"
                t.buy.isEmpty() -> "bought nothing"
                else -> "picked ${t.buy.size}"
            }
            Entry(t, "${at(t.day, t.time, t.tick, s)} · $what", t.error ?: t.buy.joinToString { name(it.item, t.catalog) }.ifEmpty { t.saveFor?.let { "saving for ${name(it, t.catalog)}" } ?: "-" },
                subColor = if (t.error != null) FAILED else MUTED, mark = if (t.error != null) FAILED else if (t.buy.isNotEmpty()) FULLNESS else null)
        }
    }

    /** What a tab with nothing in it says instead. */
    private fun empty(tab: Tab, s: SimSession): String = when (tab) {
        Tab.Shop -> "There's no shop in this room."
        Tab.Thoughts -> if (s.memory.thinking) "He hasn't thought anything yet." else "Nothing writes his thoughts in this setup: give the run a writer endpoint (WRITER_ENDPOINT)."
        Tab.Trips -> if (s.state == RunState.Ready) "No trips yet: the run hasn't started." else "He hasn't been shopping yet. Trips need a shopper: a writer endpoint, or a picking model."
        else -> ""
    }

    // --- detail ----------------------------------------------------------------------------------

    private fun detail(tab: Tab, s: SimSession, e: Entry) {
        when (tab) {
            Tab.Run -> runDetail(s, e.key as RunRow)
            Tab.Shop -> shopDetail(s, e.key as String)
            Tab.Thoughts -> thoughtDetail(s, e.key as Thought)
            Tab.Memory -> if (e.key == NOW) nowDetail(s) else memoryDetail(s, e.key as Long)
            Tab.Self -> when (e.key) {
                NOW -> selfNowDetail(s)
                CORE -> coreDetail(s)
                SEED -> {
                    head("BEFORE THE FIRST NIGHT")
                    para(seedOf(s))
                    para("What he knew of himself when the run began (personality.yaml's self_seed). The first night's revision starts from it.", SMALL, MUTED, gap = 12f)
                }
                else -> nightDetail(s, e.key as Int)
            }
            Tab.Trips -> tripDetail(s, e.key as Trip)
        }
    }

    private fun runDetail(s: SimSession, row: RunRow) {
        head(row.label.uppercase())
        para(RunControls.help(row, s))
        if (confirm) para(if (row == RunRow.Replay) "OK again to replay run ${replayPick ?: s.run} · any other key cancels" else "OK again to restart · any other key cancels", BODY, ACCENT, gap = 10f)
        head("CLOCK")
        val life = s.current.life
        para((life?.let { "day ${it.clock.day} · ${it.clock.time} · " } ?: "") + "tick ${s.current.tick} · ${s.settings.speed * s.ticksPerMinute} ticks/s" + s.fastForward.let { if (it > 1) " · quiet stretch, ${it}x" else "" }, SMALL, INK)
        RunControls.clock(s)?.let { para(it, SMALL, if (s.holding) DEAR else ACCENT) }
        s.endedBecause?.let { para("ended: $it", SMALL, ACCENT) }
        head("MODELS")
        para("decides: ${s.runtime.modelId}", SMALL, INK)
        para("memory: ${s.memory.writer ?: "off"} · thoughts: ${s.memory.thoughtWriter ?: "off"} · self: ${s.memory.selfWriter ?: "off"} · recall: ${s.memory.scorerId ?: "keywords"}", SMALL, INK)
        s.setup.notice?.let { para(it, SMALL, FAILED) }
        head("RUNS SINCE THE APP STARTED")
        s.runStarts.withIndex().toList().asReversed().forEach { (i, start) ->
            val n = i + 1
            val mark = when (n) { s.run -> " · this run"; replayPick -> " · replay picks this"; else -> "" }
            para("run $n: ${RunControls.summary(start)}$mark", SMALL, if (n == (replayPick ?: s.run)) INK else MUTED)
        }
    }

    private fun shopDetail(s: SimSession, id: String) {
        val life = s.current.life ?: return
        val e = life.catalog.firstOrNull { it.id == id } ?: return
        head("${e.group.uppercase()} · ${e.id}")
        para(e.name, TITLE, INK)
        para(shopStatus(e) + " · he has $${life.money}", BODY, if (!e.affordable && !owned(e) && e.taughtBy == null) DEAR else INK, gap = 6f)
        head("WHAT IT'S FOR")
        para(e.gives.ifEmpty { "-" })
        e.taughtBy?.let {
            head("HIDDEN FROM HIM")
            para("He doesn't know it's for sale until a book teaches him: $it.", BODY, MUTED)
        }
        val last = s.memory.trips().lastOrNull { it.error == null }
        last?.saveFor?.takeIf { it == e.id }?.let { para("He's saving for this (his last trip, ${at(last.day, last.time, last.tick, s)}).", BODY, ACCENT, gap = 12f) }
        val picks = s.memory.trips().flatMap { t -> t.buy.filter { it.item == e.id }.map { t to it } }
        if (picks.isNotEmpty()) {
            head("WHY HE PICKED IT")
            picks.asReversed().forEach { (t, p) -> para("${at(t.day, t.time, t.tick, s)}: ${p.why.ifEmpty { "(no reason: a picking model)" }}", SMALL, INK) }
        }
    }

    private fun thoughtDetail(s: SimSession, t: Thought) {
        head("${at(t.day, t.time, t.tick, s).uppercase()} · ${t.thinker}")
        if (t === s.memory.plan) para("His latest kept thought: shown as his plan in every prompt until the next.", SMALL, ACCENT)
        para(t.text ?: "No thought: ${t.error}", BODY, if (t.text == null) FAILED else INK, gap = 6f)
        t.asked.forEachIndexed { i, a ->
            head("HE ASKED${if (t.asked.size > 1) " (${i + 1} of ${t.asked.size})" else ""}")
            para(a.question, BODY, INK)
            if (a.opened.isNotEmpty()) para("the oracle opened: ${a.opened.joinToString()}", SMALL, MUTED)
            para(a.reply.text ?: "no answer: ${a.reply.error}", BODY, if (a.reply.text == null) FAILED else CODE, indent = 24f)
        }
        head("CALL")
        para(call(t.usage, t.latency), SMALL, MUTED)
    }

    private fun nowDetail(s: SimSession) {
        head("SHORT-TERM · recent actions, sent with every prompt")
        val recent = s.runtime.recent
        if (recent.isEmpty()) para("(nothing yet)", SMALL, MUTED)
        recent.asReversed().forEach { e ->
            val result = e.result?.let(::statusText) ?: "in progress"
            para("${stamp(e.tick, s.current)}  ${Describe.intent(e.intent)} · $result", SMALL, if (e.result is IntentStatus.Failed) FAILED else INK)
        }
        val d = s.decision
        head("RECALLED AT THE LATEST DECISION")
        if (d == null || d.recalled.isEmpty()) para(if (d == null) "(no decision yet)" else "(nothing recalled)", SMALL, MUTED)
        d?.recalled?.asReversed()?.forEach { para(it, SMALL, INK, gap = 4f) }
        val mem = s.memory
        head("LONG-TERM")
        val writer = mem.writer
        val status = when {
            writer == null || mem.batchSize == null -> "No memory writer configured: ${mem.eventCount()} events recorded, none written up."
            mem.summarizing -> "$writer is writing a memory..."
            else -> "${mem.eventCount()} events recorded · " + (mem.pendingSignificance()?.let { (sum, of) -> "significance %.1f of %.1f toward the next memory".format(sum, of) }
                ?: "next memory after ${(mem.batchSize!! - mem.pending()).coerceAtLeast(0)} more") + " · written by $writer"
        }
        para(status, SMALL, INK)
        para("${mem.memories().size} memories and ${RunControls.plural(mem.days().size, "day")} so far · recall by ${mem.scorerId ?: "keywords"}", SMALL, MUTED)
        s.memoryError?.let { para("last write failed: $it", SMALL, FAILED) }
        s.writerError?.let { para("last failure: $it", SMALL, FAILED) }
    }

    private fun memoryDetail(s: SimSession, id: Long) {
        val mem = s.memory
        val m = (mem.memories() + mem.days()).firstOrNull { it.id == id } ?: return
        head((if (m.level == 2) "THE WHOLE DAY · " else "A STRETCH · ") + m.span.label.uppercase())
        if (m.id in mem.lastRecalled) para("Recalled at the latest decision.", SMALL, ACCENT)
        para(m.text, BODY, INK, gap = 6f)
        head("ABOUT IT")
        para("covers events ${m.fromId}-${m.toId} · written by ${m.source}" + (m.importance?.let { " · importance %.2f".format(it) } ?: ""), SMALL, MUTED)
        if (m.level == 2) {
            val parts = mem.memories().filter { it.id in m.fromId..m.toId }
            if (parts.isNotEmpty()) para("made from ${RunControls.plural(parts.size, "memory")} of the day", SMALL, MUTED)
        }
    }

    private fun selfNowDetail(s: SimSession) {
        val mem = s.memory
        head("HOW HE SEES HIMSELF NOW · in every prompt")
        val current = mem.selfModel()
        if (current != null) sections(current.self, previous = null) else para(mem.selfText().orEmpty())
        val rev = mem.lastRevision
        head("LAST NIGHT")
        para(when {
            rev?.error != null -> "The revision failed: ${rev.error}"
            rev != null && !rev.changed -> "Unchanged: ${rev.note}"
            current != null -> "Changed: ${current.note}"
            else -> "Not revised yet: the first night revises it."
        }, SMALL, if (rev?.error != null) FAILED else MUTED)
        para("Revised by ${mem.selfWriter ?: "nothing"} after each day's memory is written.", SMALL, MUTED)
    }

    private fun coreDetail(s: SimSession) {
        val p = (s.world as? BedroomWorld)?.content?.personality ?: return para("This room has no personality.", BODY, MUTED)
        head("HIS CORE · true by design, never revised")
        para(p.core.you)
        head("TASTE · how much each quality of a thing pleases him")
        if (p.affinities.isEmpty()) para("(no affinities: everything pleases him the same)", SMALL, MUTED)
        p.affinities.entries.sortedByDescending { it.value }.forEach { (q, v) ->
            para("%+.1f  %s".format(v, q.replace('_', ' ')), BODY, if (v > 0) FULLNESS_INK else if (v < 0) DEAR else MUTED)
        }
        para("curiosity %.1f · how much new things draw him".format(p.curiosity), SMALL, MUTED, gap = 8f)
    }

    private fun nightDetail(s: SimSession, i: Int) {
        val revisions = s.memory.revisions()
        val r = revisions.getOrNull(i) ?: return
        head("NIGHT OF DAY ${r.day ?: "?"}")
        if (r.error != null) return para("The revision failed: ${r.error}", BODY, FAILED)
        val version = r.version?.let { runCatching { SelfVersion.decode(it.text) }.getOrNull() }
        val before = revisions.take(i).lastOrNull { it.version != null }?.version?.let { runCatching { SelfVersion.decode(it.text) }.getOrNull() }
        para(if (r.changed) "What changed and why: ${r.note.orEmpty()}" else "Nothing changed: ${r.note.orEmpty()}", BODY, if (r.changed) ACCENT else MUTED, gap = 6f)
        if (version != null) {
            head(if (before == null) "THE SELF-MODEL, FIRST WRITTEN (sections new since the seed marked)" else "THE SELF-MODEL AFTER (changed sections marked)")
            sections(version.self, previous = before?.self, fromSeed = before == null)
        }
    }

    /** His self-model a section at a time; with [previous] (or [fromSeed]), sections that differ are marked. */
    private fun sections(self: SelfModel, previous: SelfModel?, fromSeed: Boolean = false) {
        val now = listOf(self.who, self.cares, self.believes, self.workingToward, self.learned)
        val then = previous?.let { listOf(it.who, it.cares, it.believes, it.workingToward, it.learned) }
        SelfModel.SECTIONS.zip(now).forEachIndexed { k, (name, text) ->
            if (text.isBlank()) return@forEachIndexed
            val changed = fromSeed || (then != null && then[k].trim() != text.trim())
            para(name + if (changed) "  · changed" else "", SMALL, if (changed) ACCENT else MUTED, gap = 8f)
            para(text.trim(), BODY, INK, indent = 16f)
            if (changed && then != null && then[k].isNotBlank()) para("was: ${then[k].trim()}", SMALL, MUTED, indent = 16f)
        }
    }

    private fun tripDetail(s: SimSession, t: Trip) {
        head("${at(t.day, t.time, t.tick, s).uppercase()} · ${t.shopper}")
        t.fellBack?.let { para("The first shopper failed ($it); this one picked instead.", SMALL, DEAR) }
        t.error?.let { para("The trip failed: $it", BODY, FAILED) }
        if (t.error == null) {
            head("HE PICKED")
            if (t.buy.isEmpty()) para("nothing", BODY, MUTED)
            t.buy.forEach { p ->
                para(name(p.item, t.catalog) + (price(p.item, t.catalog)?.let { " ($it)" } ?: ""), BODY, INK)
                if (p.why.isNotEmpty()) para(p.why, BODY, CODE, indent = 24f)
            }
            head("SAVING FOR")
            para(t.saveFor?.let { name(it, t.catalog) + (price(it, t.catalog)?.let { p -> " ($p)" } ?: "") } ?: if (t.choseSaving) "nothing" else "(no say: a picking model keeps what it had)", BODY, if (t.saveFor != null) INK else MUTED)
            t.note?.let {
                head("HIS NOTE")
                para(it, BODY, INK)
            }
        }
        head("WHAT HE WAS SHOWN · ${t.catalog.size} things")
        t.catalog.forEach { para(it.removePrefix("- "), SMALL, if (t.buy.any { p -> it.startsWith("- ${p.item} (") }) INK else MUTED, indent = 8f) }
        t.raw?.let {
            head("RAW REPLY")
            para(it, SMALL, MUTED)
        }
        head("CALL")
        para(call(t.usage, t.latency), SMALL, MUTED)
    }

    private fun seedOf(s: SimSession): String = (s.world as? BedroomWorld)?.content?.personality?.selfSeed ?: s.memory.selfText().orEmpty()

    // --- rendering -------------------------------------------------------------------------------

    fun render(session: SimSession) {
        if (!open) return
        val list = entries(tab, session)
        val sel = index(list)
        rows.clear()
        if (list.isNotEmpty()) detail(tab, session, list[sel]) else para(empty(tab, session), BODY, MUTED)

        viewport.apply()
        shapes.projectionMatrix = viewport.camera.combined
        batch.projectionMatrix = viewport.camera.combined
        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)

        // Which part of the list shows, keeping the pick in view.
        val visible = ((LIST_TOP - LIST_BOTTOM) / ENTRY_H).toInt()
        val first = (sel - visible / 2).coerceIn(0, max(0, list.size - visible))
        val last = min(list.size, first + visible)
        val shown = detailWindow()

        shapes.begin(ShapeRenderer.ShapeType.Filled)
        shapes.color = PAPER_EDGE
        roundRect(X - 5f, Y - 5f, W + 10f, H + 10f, 24f)
        shapes.color = CARD
        roundRect(X, Y, W, H, 20f)
        // Tabs: tags along the top, the open one in the ribbon's red.
        font.data.setScale(TAB_SCALE)
        var tx = X + PAD
        for (t in Tab.entries) {
            glyphs.setText(font, t.title)
            shapes.color = if (t == tab) RIBBON else TAG
            roundRect(tx, TAB_Y, glyphs.width + 40f, TAB_H, 12f)
            tx += glyphs.width + 40f + 12f
        }
        // The pick, a dot per entry that has a mark, and the rule between list and detail.
        for (i in first until last) {
            val y = LIST_TOP - (i - first) * ENTRY_H
            if (i == sel) {
                shapes.color = if (reading) BAR_BG else PICKED
                roundRect(X + PAD - 10f, y - ENTRY_H + 6f, LIST_W + 10f, ENTRY_H - 4f, 12f)
            }
            list[i].mark?.let { shapes.color = it; shapes.circle(X + PAD + 10f, y - 22f, 8f, 12) }
        }
        shapes.color = STITCH
        var dy = BODY_BOTTOM
        while (dy < LIST_TOP) {
            shapes.rect(DETAIL_X - 26f, dy, 3f, 12f)
            dy += 22f
        }
        if (reading) {
            shapes.color = PICKED
            roundRect(DETAIL_X - 14f, BODY_BOTTOM - 6f, X + W - DETAIL_X - PAD + 24f, LIST_TOP - BODY_BOTTOM + 18f, 14f)
        }
        shapes.end()

        batch.begin()
        tx = X + PAD
        font.data.setScale(TAB_SCALE)
        for (t in Tab.entries) {
            glyphs.setText(font, t.title)
            font.color = if (t == tab) Color.WHITE else INK
            font.draw(batch, t.title, tx + 20f, TAB_Y + TAB_H / 2 + glyphs.height / 2)
            tx += glyphs.width + 40f + 12f
        }
        // The run's state at the right of the tabs.
        font.data.setScale(SMALL)
        font.color = stateColor(session.state)
        val life = session.current.life
        val state = session.state.name.uppercase() + (if (session.holding) " · HELD" else "") + (life?.let { " · day ${it.clock.day} ${it.clock.time} · $${it.money}" } ?: "")
        font.draw(batch, state, X + W - PAD - 520f, TAB_Y + TAB_H / 2 + 8f, 520f, Align.right, false)

        // The list.
        font.data.setScale(SMALL)
        font.color = MUTED
        if (first > 0) font.draw(batch, "$first more above", X + PAD, LIST_TOP + 30f, LIST_W, Align.right, false)
        if (last < list.size) font.draw(batch, "${list.size - last} more below", X + PAD, LIST_BOTTOM - 6f, LIST_W, Align.right, false)
        if (list.isEmpty()) font.draw(batch, "(nothing yet)", X + PAD + 30f, LIST_TOP - 14f)
        for (i in first until last) {
            val e = list[i]
            val y = LIST_TOP - (i - first) * ENTRY_H
            font.data.setScale(ENTRY_SCALE)
            font.color = e.titleColor
            font.draw(batch, plain(e.title), X + PAD + 30f, y - 12f, 0, plain(e.title).length, LIST_W - 40f, Align.left, false, "...")
            font.data.setScale(SMALL)
            font.color = e.subColor
            val sub = plain(e.sub).replace('\n', ' ')
            font.draw(batch, sub, X + PAD + 30f, y - 44f, 0, sub.length, LIST_W - 40f, Align.left, false, "...")
        }

        // The detail, from the scroll.
        var y = LIST_TOP
        for (k in shown.first until shown.second) {
            val r = rows[k]
            font.data.setScale(r.scale)
            y -= r.gapBefore
            font.color = r.color
            font.draw(batch, r.text, DETAIL_X + r.indent, y, 0, r.text.length, DETAIL_W - r.indent, Align.left, false, "...")
            y -= font.lineHeight
        }
        font.data.setScale(SMALL)
        font.color = MUTED
        if (shown.first > 0) font.draw(batch, "more above", DETAIL_X, LIST_TOP + 30f, DETAIL_W, Align.right, false)
        if (shown.second < rows.size) font.draw(batch, if (reading) "more below · DOWN" else "more below · OK to read", DETAIL_X, BODY_BOTTOM + 4f, DETAIL_W, Align.right, false)

        // Keys.
        font.data.setScale(HINT_SCALE)
        font.color = if (confirm) ACCENT else MUTED
        font.draw(batch, hint(session), X + PAD, Y + 36f, W - 2 * PAD, Align.center, false)
        batch.end()
    }

    /** The rows of the detail pane that fit, from [detailScroll] (kept so the last row can't scroll above the bottom). */
    private fun detailWindow(): Pair<Int, Int> {
        val room = LIST_TOP - BODY_BOTTOM - 24f
        fun height(r: Row): Float {
            font.data.setScale(r.scale)
            return r.gapBefore + font.lineHeight
        }
        // The furthest the scroll may go: the last rows exactly fill the pane.
        var fill = 0f
        var maxScroll = rows.size
        while (maxScroll > 0 && fill + height(rows[maxScroll - 1]) <= room) fill += height(rows[--maxScroll])
        detailScroll = detailScroll.coerceIn(0, maxScroll)
        var used = 0f
        var end = detailScroll
        while (end < rows.size && used + height(rows[end]) <= room) used += height(rows[end++])
        return detailScroll to end
    }

    private fun hint(s: SimSession): String {
        if (confirm) return "OK again to confirm · any other key cancels"
        if (reading) return "UP/DOWN scroll · OK or BACK back to the list · LEFT/RIGHT tabs"
        if (tab == Tab.Run) {
            val row = picked[Tab.Run.ordinal] as? RunRow ?: RunRow.Play
            return when {
                row == RunRow.Play -> "OK ${RunControls.value(row, s, replayPick).substringBefore(':')} · UP/DOWN pick · LEFT/RIGHT tabs · BACK closes"
                row.adjustable && row.isAction -> "LEFT/RIGHT pick the run · OK replays · UP/DOWN pick · BACK closes"
                row.isAction -> "OK ${row.label.lowercase()} · UP/DOWN pick · LEFT/RIGHT tabs · BACK closes"
                else -> "LEFT/RIGHT change it · UP/DOWN pick · BACK closes (Run row: LEFT/RIGHT tabs)"
            }
        }
        return "UP/DOWN pick · OK read it all · LEFT/RIGHT tabs · BACK closes"
    }

    /** A heading in the detail pane. */
    private fun head(text: String) = para(text, HEAD, MUTED, gap = if (rows.isEmpty()) 0f else 18f)

    /** A paragraph in the detail pane, wrapped to its width. */
    private fun para(text: String, scale: Float = BODY, color: Color = INK, indent: Float = 0f, gap: Float = 0f) {
        wrap(text, scale, DETAIL_W - indent).forEachIndexed { i, l -> rows += Row(l, scale, color, indent, if (i == 0) gap else 0f) }
    }

    /** [text] broken into lines that fit [width] at [scale]; cached, since the detail is laid out every frame. */
    private fun wrap(text: String, scale: Float, width: Float): List<String> {
        val key = "$scale/$width/$text"
        wrapped[key]?.let { return it }
        if (wrapped.size > WRAP_CACHE) wrapped.clear()
        font.data.setScale(scale)
        val out = ArrayList<String>()
        for (paragraph in plain(text).split('\n')) {
            val line = StringBuilder()
            for (word in paragraph.split(' ')) {
                if (word.isEmpty()) continue
                val before = line.length
                if (line.isNotEmpty()) line.append(' ')
                line.append(word)
                glyphs.setText(font, line)
                if (glyphs.width > width && before > 0) {
                    out += line.substring(0, before)
                    line.setLength(0)
                    line.append(word)
                }
            }
            out += line.toString()
        }
        wrapped[key] = out
        return out
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

    // --- text helpers ----------------------------------------------------------------------------

    /** "day 2 14:30", or the tick without a clock. */
    private fun at(day: Int?, time: String?, tick: Long, s: SimSession): String = if (day != null) "day $day $time" else stamp(tick, s.current)

    private fun shopStatus(e: CatalogEntry): String = (if (e.pack > 1) "${e.pack} for $${e.price}" else "$${e.price}") + " · " + standing(e)

    /** Whether he has it, can afford it, or hasn't learned of it. */
    private fun standing(e: CatalogEntry): String {
        return when {
            e.taughtBy != null -> "a book teaches it"
            owned(e) -> "has it"
            e.owned > 0 -> "${e.owned} at home" + if (e.affordable) "" else " · can't afford more"
            e.affordable -> "can afford"
            else -> "saving up"
        }
    }

    /** Has one already, of something bought once (stackable things are bought again and again). */
    private fun owned(e: CatalogEntry) = e.owned > 0 && !e.stackable

    /** An item's name from a trip's catalog lines ("- easel (Easel), $17, ..."), else its id made readable. */
    private fun name(item: String, catalog: List<String>): String =
        catalog.firstOrNull { it.startsWith("- $item (") }?.substringAfter(" (")?.substringBefore(")") ?: item.replace('_', ' ')

    private fun price(item: String, catalog: List<String>): String? =
        catalog.firstOrNull { it.startsWith("- $item (") }?.substringAfter("), ")?.substringBefore(",")

    private fun call(usage: Usage, latency: Duration): String =
        "${latency.inWholeMilliseconds} ms" + if (usage.promptTokens + usage.completionTokens > 0) " · ${usage.promptTokens} in + ${usage.completionTokens} out tokens" else ""

    private fun statusText(status: IntentStatus): String = when (status) {
        IntentStatus.Submitted -> "submitted"
        IntentStatus.Validated -> "validated"
        IntentStatus.Executing -> "executing"
        IntentStatus.Succeeded -> "done"
        IntentStatus.Interrupted -> "interrupted"
        is IntentStatus.Failed -> "failed: ${status.reason}"
    }

    private fun stateColor(state: RunState): Color = when (state) {
        RunState.Ready -> INK
        RunState.Running -> FULLNESS_INK
        RunState.Paused -> DEAR
        RunState.Ended -> RIBBON
    }

    override fun dispose() {
        shapes.dispose()
        batch.dispose()
        font.dispose()
    }

    private companion object {
        /** Keys of the entries that aren't items of a log. */
        const val NOW = "now"
        const val CORE = "core"
        const val SEED = "seed"

        /** The card: right of the room's needs card, so his needs stay in view. */
        const val X = 520f
        const val Y = 36f
        const val W = DevView.WIDTH - X - 36f
        const val H = DevView.HEIGHT - 2 * Y
        const val PAD = 36f
        const val TAB_H = 52f
        const val TAB_Y = Y + H - PAD - TAB_H
        const val LIST_TOP = TAB_Y - 56f
        const val BODY_BOTTOM = Y + 84f
        const val LIST_BOTTOM = BODY_BOTTOM + 20f
        const val LIST_W = 440f
        const val ENTRY_H = 74f
        const val DETAIL_X = X + PAD + LIST_W + 56f
        const val DETAIL_W = X + W - PAD - DETAIL_X
        /** Lines a press of UP or DOWN scrolls the detail. */
        const val SCROLL_STEP = 4
        const val WRAP_CACHE = 600

        const val TAB_SCALE = 1.45f
        const val ENTRY_SCALE = 1.5f
        const val TITLE = 1.9f
        const val BODY = 1.5f
        const val HEAD = 1.2f
        const val SMALL = 1.25f
        const val HINT_SCALE = 1.2f

        val CARD = Color.valueOf("fff8eeff")
        val PAPER_EDGE = Color.valueOf("e8cfb2ff")
        val INK = Color.valueOf("3a2a20ff")
        val MUTED = Color.valueOf("8a7464ff")
        val CODE = Color.valueOf("4f6a3aff")
        val ACCENT = Color.valueOf("c0504aff")
        val FAILED = Color.valueOf("c03a2aff")
        val DEAR = Color.valueOf("b5653aff")
        val FULLNESS = Color.valueOf("6cc070ff")
        val FULLNESS_INK = Color.valueOf("3f8a44ff")
        val BAR_BG = Color.valueOf("e8dccbff")
        val STITCH = Color.valueOf("e7a99bff")
        val RIBBON = Color.valueOf("e07f74ff")
        val PICKED = Color.valueOf("fde9c6ff")
        val TAG = Color.valueOf("f6e3b8ff")
        val GROUP_COLORS = mapOf(
            "food" to Color.valueOf("9fd49aff"), "supply" to Color.valueOf("ffe3a1ff"), "furniture" to Color.valueOf("dba77cff"),
            "record" to Color.valueOf("f4a3adff"), "book" to Color.valueOf("8fb0ecff"), "instrument" to Color.valueOf("f0b46eff"),
            "decor" to Color.valueOf("c2e6a8ff"),
        )
    }
}
