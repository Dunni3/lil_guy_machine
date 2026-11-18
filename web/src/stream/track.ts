/** How far behind the newest position the page draws, in seconds: a bit more than the host's 0.1 s between publishes, so there's always a later one to move toward. */
const DELAY = 0.15;
/** He still counts as walking this long after his last step, so a slow clock between steps doesn't stop and start him. */
const WALKING_FOR = 0.25;
const KEEP = 32;

export interface Place {
  x: number;
  y: number;
  moving: boolean;
}

/** One character's positions as they arrive, and where he is between them: the app's own lerp between ticks, at the publish rate. */
export class Track {
  private readonly at: number[] = [];
  private readonly xs: number[] = [];
  private readonly ys: number[] = [];
  private lastMove = -Infinity;
  private readonly place: Place = { x: 0, y: 0, moving: false };

  /** A new position at [time] seconds; [jump] for one he didn't walk to (a join, a new run). */
  push(time: number, x: number, y: number, jump = false) {
    if (jump) {
      this.at.length = this.xs.length = this.ys.length = 0;
      this.lastMove = -Infinity;
    }
    const n = this.at.length;
    if (n && this.xs[n - 1] === x && this.ys[n - 1] === y) {
      // Standing still: only the newest matters as the end of the last step.
      if (n >= 2 && this.xs[n - 2] === x && this.ys[n - 2] === y) { this.at[n - 1] = time; return; }
    } else if (n) this.lastMove = time;
    this.at.push(time);
    this.xs.push(x);
    this.ys.push(y);
    if (this.at.length > KEEP) { this.at.shift(); this.xs.shift(); this.ys.shift(); }
  }

  /** Where he is on screen at [now] seconds. */
  sample(now: number): Place {
    const t = now - DELAY;
    const n = this.at.length;
    const p = this.place;
    if (n === 0) return p;
    let i = n - 1;
    while (i > 0 && this.at[i] > t) i--;
    const j = Math.min(i + 1, n - 1);
    const span = this.at[j] - this.at[i];
    const k = span > 0 ? Math.min(1, Math.max(0, (t - this.at[i]) / span)) : 0;
    p.x = this.xs[i] + (this.xs[j] - this.xs[i]) * k;
    p.y = this.ys[i] + (this.ys[j] - this.ys[i]) * k;
    p.moving = this.xs[i] !== this.xs[j] || this.ys[i] !== this.ys[j] || t - this.lastMove < WALKING_FOR && t - this.lastMove > -DELAY;
    return p;
  }
}
