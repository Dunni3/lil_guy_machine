package dev.lilguy.app

import dev.lilguy.sim.GameClock
import dev.lilguy.sim.WorldSnapshot

/**
 * The run's settings and actions as rows, shared by the dev view's Run page and the menu's RUN tab
 * ([GameMenu]). [Play] (start, pause, resume) is the menu's own first row; the dev view starts and
 * pauses with OK anywhere, so it shows [SETTINGS].
 */
internal enum class RunRow(val label: String) {
    Play("Run"), Days("Length"), Speed("Speed"), Quiet("Quiet stretches"), DecideCap("While he decides"), Resolution("Clock resolution"), Restart("Restart"), Replay("Replay");

    /** OK does something (asking first for Restart and Replay); LEFT/RIGHT change the others. */
    val isAction: Boolean get() = this == Play || this == Restart || this == Replay

    /** LEFT/RIGHT change its value. */
    val adjustable: Boolean get() = this != Play && this != Restart

    companion object {
        val SETTINGS = entries.filter { it != Play }
    }
}

internal object RunControls {
    /**
     * LEFT (-1) or RIGHT (+1) on [row]: the session's settings stepped, or for Replay the run it
     * would start again (1-based; null for this one), which is returned. Other rows return [replayPick].
     */
    fun adjust(row: RunRow, by: Int, session: SimSession, replayPick: Int?): Int? {
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
            RunRow.Quiet -> session.configure(s.copy(quiet = RunSettings.QUIETS.step(s.quiet, by)))
            RunRow.DecideCap -> session.configure(s.copy(decideCap = RunSettings.DECIDE_CAPS.step(s.decideCap, by)))
            RunRow.Resolution -> session.configure(s.copy(ticksPerMinute = RunSettings.RESOLUTIONS.step(s.ticksPerMinute, by)))
            RunRow.Replay -> return ((replayPick ?: session.run) + by).coerceIn(1, session.run).takeIf { it != session.run }
            RunRow.Play, RunRow.Restart -> Unit
        }
        return replayPick
    }

    /** The value [by] steps on from [current] in this list, stopping at either end. */
    private fun <T> List<T>.step(current: T, by: Int): T = this[(indexOf(current).coerceAtLeast(0) + by).coerceIn(0, lastIndex)]

    fun value(row: RunRow, session: SimSession, replayPick: Int?): String {
        val s = session.settings
        return when (row) {
            RunRow.Play -> when (session.state) {
                SimSession.RunState.Ready -> "start"
                SimSession.RunState.Running -> "pause"
                SimSession.RunState.Paused -> "resume"
                SimSession.RunState.Ended -> "ended: ${session.endedBecause}"
            }
            RunRow.Days -> s.days?.let { d -> plural(d, "day") + (session.endTick?.let { " · ends ${stamp(it, session.current)}" } ?: "") } ?: "no end"
            RunRow.Speed -> "${s.speed} game min/s · a day in ${RunSettings.realTime(s.secondsPerDay)}"
            RunRow.Quiet -> quietText(s.quiet)
            RunRow.DecideCap -> capText(s.decideCap)
            RunRow.Resolution -> "${s.ticksPerMinute} ticks/game min" + if (s.ticksPerMinute != session.ticksPerMinute) " · next run (now ${session.ticksPerMinute})" else ""
            RunRow.Restart -> "a fresh run with these settings"
            RunRow.Replay -> "run ${replayPick ?: session.run} as it started" + if (session.run > 1) " · LEFT/RIGHT picks the run" else ""
        }
    }

    fun help(row: RunRow, session: SimSession): String {
        val s = session.settings
        return when (row) {
            RunRow.Play -> when (session.state) {
                SimSession.RunState.Ended -> "The run is over. More days (Length) reopen it, paused; Restart starts a fresh one."
                else -> "OK starts, pauses or resumes the run. Starting or resuming closes the menu; PLAY/PAUSE on the remote does the same from anywhere."
            }
            RunRow.Days -> "Game days the run lasts, and the next run's. Raising it extends this run; an ended run reopens, paused. It can't go below the days he has lived."
            RunRow.Speed -> "How fast his day passes on screen. Everything speeds up together; ticks per second = speed x resolution, now ${s.speed * session.ticksPerMinute}."
            RunRow.Quiet -> "While he's settled into something with little to watch (${RunSettings.QUIET_VERBS.joinToString()}), the clock runs this many times faster. " +
                "He does the same things; the screen spends less time on them. A decision out meanwhile holds the clock sooner in real time."
            RunRow.DecideCap -> "A model's reply takes real seconds whatever the speed, and he stands still meanwhile. The clock runs up to this much game time while a decision is out, " +
                "then holds until it comes back. Waits: thinking costs no game time (lockstep). Runs: it costs whatever the latency is at this speed." +
                (s.decideCap?.takeIf { it > 0 }?.let { " Now: ${seconds(it * 60f / s.speed)} s of model time." } ?: "")
            RunRow.Resolution -> "Ticks per game minute: how finely walking and timing are cut. Walking is in tiles per tick, so fewer ticks make walks cheaper in game time. His prompt states it, so it applies from the next run."
            RunRow.Restart -> "Throws this run away for a fresh one with the settings above. OK, then OK again."
            RunRow.Replay -> "A fresh run set up as the picked run started (length, speed, wait, resolution), to watch the same start play out again. A model may not decide the same way twice. OK, then OK again."
        }
    }

    /** What the clock is doing, when it's worth saying: held and why, or running while the model decides. */
    fun clock(session: SimSession): String? {
        val deciding = session.thinkingFor
        return when {
            session.holding && session.waitingOnAuthor.isNotEmpty() -> "held: waiting on his ${session.waitingOnAuthor.joinToString(" and ") { if (it == "draw") "drawing" else it }}"
            session.holding && session.lingering -> "held: he's finishing his piece"
            session.holding -> "held: waiting on the model (%.1f s)".format((deciding?.inWholeMilliseconds ?: 0) / 1000f)
            deciding != null -> "the model is deciding (%.1f s); the clock runs".format(deciding.inWholeMilliseconds / 1000f)
            else -> null
        }
    }

    fun summary(s: RunSettings): String =
        "${s.days?.let { plural(it, "day") } ?: "no end"} · ${s.speed} min/s · quiet ${quietText(s.quiet)} · ${s.ticksPerMinute} ticks/min · ${capText(s.decideCap)}"

    private fun quietText(quiet: Int): String = if (quiet == 1) "same speed" else "${quiet}x faster"

    private fun capText(cap: Int?): String = when (cap) {
        null -> "clock runs"
        0 -> "clock waits"
        else -> "clock runs ${GameClock.duration(cap)}, then waits"
    }

    fun plural(n: Int, what: String) = if (n == 1) "1 $what" else "$n ${what}s"

    fun seconds(s: Float): String = if (s < 10) "%.1f".format(s) else s.toInt().toString()
}

/** "d2 14:30" in the bedroom, "t 140" in rooms without a clock. */
internal fun stamp(tick: Long, s: WorldSnapshot): String =
    s.life?.clockScale?.reading(tick)?.let { "d${it.day} ${it.time}" } ?: "t $tick"

/** Model text in characters the bitmap font has: dashes, curly quotes and ellipses made plain. */
internal fun plain(text: String): String {
    if (text.none { it.code > 0x7e }) return text
    return text.replace("—", " - ").replace('–', '-').replace('‘', '\'').replace('’', '\'')
        .replace('“', '"').replace('”', '"').replace("…", "...")
}
