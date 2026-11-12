import type { FastState, LifeState, RunInfo, Settings } from "../../../protocol/ts/protocol";

/**
 * The run's settings and actions as rows of the menu's RUN tab, ported from `RunControls.kt`.
 * Admins get [ADMIN_ROWS]; viewers only [SIGN_IN]. The lists are `RunSettings`'s.
 */
export type RunRow = "play" | "days" | "speed" | "quiet" | "cap" | "resolution" | "restart" | "replay" | "signout" | "signin";

export const ADMIN_ROWS: RunRow[] = ["play", "days", "speed", "quiet", "cap", "resolution", "restart", "replay", "signout"];
export const SIGN_IN: RunRow[] = ["signin"];

export const LABELS: Record<RunRow, string> = {
  play: "Run", days: "Length", speed: "Speed", quiet: "Quiet stretches", cap: "While he decides", resolution: "Clock resolution",
  restart: "Restart", replay: "Replay", signout: "Sign out", signin: "Sign in",
};

const SPEEDS = [1, 2, 4, 8, 15, 30, 60];
const RESOLUTIONS = [1, 2, 5, 10];
const DECIDE_CAPS: (number | null)[] = [0, 5, 15, 30, 60, null];
const QUIETS = [1, 2, 4, 8];
const MAX_DAYS = 30;
const MINUTES_PER_DAY = 1440;

/** OK does something (asking first for Restart and Replay); LEFT/RIGHT change the others. */
export const isAction = (row: RunRow) => row === "play" || row === "restart" || row === "replay" || row === "signout" || row === "signin";
/** LEFT/RIGHT change its value (for Replay, which run). */
export const adjustable = (row: RunRow) => !isAction(row) || row === "replay";
/** OK asks first, and a second OK does it. */
export const confirms = (row: RunRow) => row === "restart" || row === "replay";

/** The value [by] steps on from [current] in [list], stopping at either end. */
function step<T>(list: T[], current: T, by: number): T {
  return list[Math.min(Math.max(Math.max(list.indexOf(current), 0) + by, 0), list.length - 1)];
}

/** LEFT (-1) or RIGHT (+1) on a setting's [row]: the settings to send, or null when nothing changes. */
export function adjust(row: RunRow, by: number, run: RunInfo, s: Settings): Settings | null {
  let next: Settings;
  switch (row) {
    case "days": {
      const d = s.days;
      const days = d === null ? (by < 0 ? Math.max(MAX_DAYS, run.min_days) : null) : by > 0 && d >= MAX_DAYS ? null : d + by;
      next = { ...s, days: days === null ? null : Math.max(days, 1, run.min_days) };
      break;
    }
    case "speed": next = { ...s, speed: step(SPEEDS, s.speed, by) }; break;
    case "quiet": next = { ...s, quiet: step(QUIETS, s.quiet, by) }; break;
    case "cap": next = { ...s, decide_cap: step(DECIDE_CAPS, s.decide_cap, by) }; break;
    case "resolution": next = { ...s, ticks_per_minute: step(RESOLUTIONS, s.ticks_per_minute, by) }; break;
    default: return null;
  }
  return JSON.stringify(next) === JSON.stringify(s) ? null : next;
}

/** The run Replay picks after LEFT/RIGHT: 1-based, null for this one. */
export function pickReplay(run: RunInfo, pick: number | null, by: number): number | null {
  const n = Math.min(Math.max((pick ?? run.run) + by, 1), run.run);
  return n === run.run ? null : n;
}

export function value(row: RunRow, life: LifeState, s: Settings, replayPick: number | null): string {
  const run = life.run;
  switch (row) {
    case "play":
      return { ready: "start", running: "pause", paused: "resume", ended: `ended: ${run.ended_because ?? ""}` }[run.state];
    case "days":
      return s.days === null ? "no end" : plural(s.days, "day") + (run.end_tick !== null ? ` · ends ${stamp(run.end_tick, life.fast, run.ticks_per_minute)}` : "");
    case "speed": return `${s.speed} game min/s · a day in ${realTime(Math.trunc(MINUTES_PER_DAY / s.speed))}`;
    case "quiet": return quietText(s.quiet);
    case "cap": return capText(s.decide_cap);
    case "resolution":
      return `${s.ticks_per_minute} ticks/game min` + (s.ticks_per_minute !== run.ticks_per_minute ? ` · next run (now ${run.ticks_per_minute})` : "");
    case "restart": return "a fresh run with these settings";
    case "replay": return `run ${replayPick ?? run.run} as it started` + (run.run > 1 ? " · LEFT/RIGHT picks the run" : "");
    case "signout": return "forget the password on this browser";
    case "signin": return "for the run's controls";
  }
}

export function help(row: RunRow, life: LifeState, s: Settings): string {
  const run = life.run;
  switch (row) {
    case "play":
      return run.state === "ended"
        ? "The run is over. More days (Length) reopen it, paused; Restart starts a fresh one."
        : "OK starts, pauses or resumes the run. Starting or resuming closes the menu; PLAY/PAUSE on the remote (P on a keyboard) does the same from anywhere.";
    case "days": return "Game days the run lasts, and the next run's. Raising it extends this run; an ended run reopens, paused. It can't go below the days he has lived.";
    case "speed": return `How fast his day passes on screen. Everything speeds up together; ticks per second = speed x resolution, now ${s.speed * run.ticks_per_minute}.`;
    case "quiet":
      return `While he's settled into something with little to watch (${s.quiet_verbs.join(", ")}), the clock runs this many times faster. ` +
        "He does the same things; the screen spends less time on them. A decision out meanwhile holds the clock sooner in real time.";
    case "cap":
      return "A model's reply takes real seconds whatever the speed, and he stands still meanwhile. The clock runs up to this much game time while a decision is out, " +
        "then holds until it comes back. Waits: thinking costs no game time (lockstep). Runs: it costs whatever the latency is at this speed." +
        (s.decide_cap ? ` Now: ${seconds((s.decide_cap * 60) / s.speed)} s of model time.` : "");
    case "resolution": return "Ticks per game minute: how finely walking and timing are cut. Walking is in tiles per tick, so fewer ticks make walks cheaper in game time. His prompt states it, so it applies from the next run.";
    case "restart": return "Throws this run away for a fresh one with the settings above. OK, then OK again.";
    case "replay": return "A fresh run set up as the picked run started (length, speed, wait, resolution), to watch the same start play out again. A model may not decide the same way twice. OK, then OK again.";
    case "signout": return "Forgets the admin password on this browser (the host's debug page shares it). The run carries on; this page goes back to watching.";
    case "signin": return "The run's controls are for its admin. OK asks for the password; this browser keeps it until you sign out.";
  }
}

/** What the clock is doing, when it's worth saying: held and why, or running while the model decides. */
export function clockNote(fast: FastState): string | null {
  const authored = fast.held_for.filter((h) => h !== "decision" && h !== "art");
  const deciding = fast.thinking_ms;
  if (fast.held && authored.length) return `held: waiting on his ${authored.map((a) => (a === "draw" ? "drawing" : a)).join(" and ")}`;
  if (fast.held && fast.held_for.includes("art")) return "held: he's finishing his piece";
  if (fast.held) return `held: waiting on the model (${((deciding ?? 0) / 1000).toFixed(1)} s)`;
  if (deciding !== null) return `the model is deciding (${(deciding / 1000).toFixed(1)} s); the clock runs`;
  return null;
}

export function summary(s: Settings): string {
  return `${s.days === null ? "no end" : plural(s.days, "day")} · ${s.speed} min/s · quiet ${quietText(s.quiet)} · ${s.ticks_per_minute} ticks/min · ${capText(s.decide_cap)}`;
}

const quietText = (quiet: number) => (quiet === 1 ? "same speed" : `${quiet}x faster`);

function capText(cap: number | null): string {
  if (cap === null) return "clock runs";
  if (cap === 0) return "clock waits";
  return `clock runs ${duration(cap)}, then waits`;
}

export const plural = (n: number, what: string, many = `${what}s`) => (n === 1 ? `1 ${what}` : `${n} ${many}`);

const seconds = (s: number) => (s < 10 ? s.toFixed(1) : String(Math.trunc(s)));

/** "45 min", "2h", "1h 36m" (`GameClock.duration`). */
export function duration(minutes: number): string {
  if (minutes < 60) return `${minutes} min`;
  if (minutes % 60 === 0) return `${minutes / 60}h`;
  return `${Math.trunc(minutes / 60)}h ${minutes % 60}m`;
}

/** "6 min", "24 s", "1h 36m" (`RunSettings.realTime`). */
function realTime(s: number): string {
  if (s < 60) return `${s} s`;
  if (s < 3600) return s % 60 === 0 ? `${s / 60} min` : `${Math.trunc(s / 60)} min ${s % 60} s`;
  return duration(Math.trunc(s / 60));
}

/** "d2 14:30" for [tick], counted from the clock now ([fast]) at [perMinute] ticks a game minute; "t 140" without a clock. */
export function stamp(tick: number, fast: FastState, perMinute: number): string {
  const clock = fast.clock;
  if (!clock) return `t ${tick}`;
  const [h, m] = clock.time.split(":").map((v) => parseInt(v, 10) || 0);
  const now = (clock.day - 1) * MINUTES_PER_DAY + h * 60 + m;
  const at = now + Math.round((tick - fast.tick) / Math.max(1, perMinute));
  const day = Math.floor(at / MINUTES_PER_DAY) + 1;
  const min = ((at % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY;
  return `d${day} ${String(Math.trunc(min / 60)).padStart(2, "0")}:${String(min % 60).padStart(2, "0")}`;
}
