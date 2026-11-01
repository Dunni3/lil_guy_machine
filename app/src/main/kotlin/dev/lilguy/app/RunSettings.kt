package dev.lilguy.app

import dev.lilguy.sim.GameClock

/**
 * The knobs on the dev view's Run page. Three rates are tied together:
 *
 *     ticks per real second = [speed] (game minutes per real second) x [ticksPerMinute]
 *
 * [speed] is how fast his day passes on screen; [ticksPerMinute] is the clock's resolution, how
 * finely walking and timing are cut (and baked into his prompt, so it only changes with a new run).
 * The tick rate follows from the two.
 *
 * A model's reply takes real seconds whatever the speed, and the guy stands still while it's out.
 * At a high speed that wait costs a lot of his day, so [decideCap] bounds it: the clock may run
 * that many game minutes while a decision is out and then holds until it comes back. 0 is lockstep
 * (latency costs no game time), null lets the clock run (latency costs game time, as in real life).
 *
 * [quiet] is for the viewer: while he settles into something with little to watch ([QUIET_VERBS]:
 * reading, sleeping), the clock runs that many times faster. He does the same things; the
 * screen spends less time on them.
 */
data class RunSettings(
    /** Bedroom: game days a run lasts; null for no end. Live: raising it extends the run. */
    val days: Int? = ModelSetup.DEFAULT_DAYS,
    /** Game minutes per real second. Live. */
    val speed: Int = DEFAULT_SPEED,
    /** Clock resolution, ticks per game minute. Takes effect on the next run. */
    val ticksPerMinute: Int = GameClock.DEFAULT_TICKS_PER_MINUTE,
    /** Game minutes the clock may run while a decision is out; 0 holds at once, null never holds. Live. */
    val decideCap: Int? = DEFAULT_DECIDE_CAP,
    /** How many times faster the clock runs through quiet stretches; 1 for not at all. Live. */
    val quiet: Int = DEFAULT_QUIET,
) {
    /** Real seconds per tick at [ticksPerMinute] (the run's resolution, which may differ from this one's), [faster] times faster. */
    fun tickSeconds(ticksPerMinute: Int, faster: Int = 1): Float = 1f / (speed * ticksPerMinute * faster)

    /** Real seconds a game day takes. */
    val secondsPerDay: Int get() = GameClock.MINUTES_PER_DAY / speed

    companion object {
        /** 4 game minutes a second: a day in 6 real minutes, 20 ticks a second at the default resolution. */
        const val DEFAULT_SPEED = 4
        /** A few seconds of model latency at the default speed before the clock holds. */
        const val DEFAULT_DECIDE_CAP = 15
        const val DEFAULT_QUIET = 4
        /** What's quiet to watch: he sits still with it for an hour or more. */
        val QUIET_VERBS = setOf("read", "sleep")

        val SPEEDS = listOf(1, 2, 4, 8, 15, 30, 60)
        val RESOLUTIONS = listOf(1, 2, 5, 10)
        val DECIDE_CAPS = listOf<Int?>(0, 5, 15, 30, 60, null)
        val QUIETS = listOf(1, 2, 4, 8)
        const val MAX_DAYS = 30

        /** "6 min", "24 s", "1h 36m" */
        fun realTime(seconds: Int): String = when {
            seconds < 60 -> "$seconds s"
            seconds < 3600 -> if (seconds % 60 == 0) "${seconds / 60} min" else "${seconds / 60} min ${seconds % 60} s"
            else -> GameClock.duration(seconds / 60)
        }
    }
}
