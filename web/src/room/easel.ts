import type { Easel, UnderwayView } from "../../../protocol/ts/protocol";
import type { ArtPhase } from "../guy/blob";

/** How fast the brush catches up with the cell going on (per second), and the jump (cells) that lifts it. */
const FOLLOW = 18;
const LIFT_JUMP = 2.5;

/** A picture's cells as 0xRRGGBB, row by row from the top-left: [rows] has a letter per cell, a for [palette] 0. */
export function pictureColors(rows: string[], palette: number[]): Int32Array {
  const size = rows.length;
  const out = new Int32Array(size * size);
  for (let y = 0; y < size; y++) {
    const row = rows[y];
    for (let x = 0; x < size; x++) out[y * size + x] = palette[row.charCodeAt(x) - 97] ?? palette[0] ?? 0;
  }
  return out;
}

/** [colors] (0xRRGGBB) as RGBA bytes, into [out]. */
export function toBytes(colors: Int32Array, out = new Uint8Array(colors.length * 4)): Uint8Array {
  colors.forEach((c, i) => setCell(out, i, c));
  return out;
}

function setCell(bytes: Uint8Array, i: number, c: number) {
  bytes[4 * i] = (c >> 16) & 0xff;
  bytes[4 * i + 1] = (c >> 8) & 0xff;
  bytes[4 * i + 2] = c & 0xff;
  bytes[4 * i + 3] = 0xff;
}

/**
 * The piece under way, played out on this screen at the host's pace (`ArtShow.kt`, sent as
 * `state.easel`): the host says what he's doing at it, for how long, and the picture after the
 * stroke going on; this keeps the picture as it stood before and, through a stroke, puts the cells
 * that differ on along a brush path over the stroke's seconds, the brush following them ([u], [v]).
 * The brush takes the new crayon halfway through a dip. Ported from `PaintScene.kt`, less the pace,
 * which is the host's now so every viewer sees the same.
 *
 * Pure bookkeeping: [bytes] is the picture as far as it has gone on (RGBA, [size] square), and
 * [version] counts its changes, for whoever uploads it.
 */
export class EaselPlay {
  phase: ArtPhase = "ponder";
  /** Real seconds into [phase]. */
  phaseTime = 0;
  /** The latest word on the piece, null for none. */
  piece: UnderwayView | null = null;
  /** Where the brush is on the picture, 0..1 from the top-left, and whether it's off the surface. */
  u = 0.5;
  v = 0.5;
  lifted = true;
  /** The brush's colour, 0xRRGGBB. */
  color = 0;
  size = 0;
  bytes = new Uint8Array(0);
  version = 0;

  private from: Easel | null = null;
  private phaseSeconds = 0;
  private tick: number | null = null;
  private colors = new Int32Array(0);
  /** The stroke going on: the picture it ends at, its cells in brush order, and how many are on. */
  private target: Int32Array | null = null;
  private cells: number[] = [];
  private on = 0;

  /** Advances [delta] seconds with [easel] the host's latest, [easelTime] its seconds into the phase when it was sent. */
  update(easel: Easel, easelTime: number, delta: number) {
    if (easel !== this.from) {
      this.from = easel;
      this.receive(easel, easelTime);
    } else this.phaseTime += delta;
    if (!this.piece) return;
    if (this.phase === "stroke") this.paint(delta);
    else if (this.phase === "dip" && this.phaseTime >= this.phaseSeconds / 2) this.color = this.crayon(this.piece);
  }

  private receive(easel: Easel, easelTime: number) {
    const up = easel.underway;
    if (!up || up.state === "dropped") {
      this.piece = null;
      this.tick = null;
      this.target = null;
      return;
    }
    const picture = pictureColors(up.rows, up.palette);
    if (up.tick !== this.tick || up.size !== this.size) this.start(up, picture);
    else {
      this.finishStroke();
      // In a dip the picture is already the next stroke's, which waits for the stroke; otherwise
      // what's sent is what shows (a stroke with nothing to animate goes straight on).
      if (easel.phase === "stroke") this.startStroke(picture);
      else if (easel.phase !== "dip") this.show(picture);
    }
    this.piece = up;
    this.phase = easel.phase as ArtPhase;
    this.phaseTime = easelTime;
    this.phaseSeconds = easel.phase_seconds;
    if (easel.phase === "stroke") this.color = this.crayon(up);
  }

  /** A new piece, or the first sight of one: the picture as it stands (blank paper, normally). */
  private start(up: UnderwayView, picture: Int32Array) {
    this.tick = up.tick;
    this.size = up.size;
    this.colors = picture;
    this.bytes = toBytes(picture);
    this.version++;
    this.target = null;
    this.color = up.palette[up.crayon ?? 1] ?? up.palette[0] ?? 0;
    this.u = this.v = 0.5;
    this.lifted = true;
  }

  private startStroke(picture: Int32Array) {
    const changed: number[] = [];
    for (let i = 0; i < picture.length; i++) if (picture[i] !== this.colors[i]) changed.push(i);
    this.target = picture;
    this.cells = brushPath(changed, this.size);
    this.on = 0;
  }

  /** Puts on what's left of the stroke going on, if it was cut short. */
  private finishStroke() {
    if (!this.target) return;
    this.putOn(this.cells.length);
    this.target = null;
  }

  private show(picture: Int32Array) {
    let changed = false;
    for (let i = 0; i < picture.length; i++) {
      if (picture[i] === this.colors[i]) continue;
      this.colors[i] = picture[i];
      setCell(this.bytes, i, picture[i]);
      changed = true;
    }
    if (changed) this.version++;
  }

  /** Cells go on in step with the stroke's seconds; the brush follows the one going on. */
  private paint(delta: number) {
    const cells = this.cells;
    if (!this.target || cells.length === 0) return;
    const f = this.phaseSeconds > 0 ? Math.min(this.phaseTime / this.phaseSeconds, 1) : 1;
    this.putOn(Math.floor(cells.length * f));
    const c = cells[Math.min(this.on, cells.length - 1)];
    const size = this.size;
    const tu = ((c % size) + 0.5) / size;
    const tv = (Math.floor(c / size) + 0.5) / size;
    // A jump of more than a couple of cells lifts the brush across; at the end of the stroke it comes off.
    this.lifted = this.on >= cells.length || Math.abs(tu - this.u) + Math.abs(tv - this.v) > LIFT_JUMP / size;
    const k = Math.min(delta * FOLLOW, 1);
    this.u += (tu - this.u) * k;
    this.v += (tv - this.v) * k;
  }

  private putOn(due: number) {
    const target = this.target!;
    if (due <= this.on) return;
    for (let i = this.on; i < due; i++) {
      const c = this.cells[i];
      this.colors[c] = target[c];
      setCell(this.bytes, c, target[c]);
    }
    this.on = due;
    this.version++;
  }

  private crayon(up: UnderwayView) {
    return up.palette[up.crayon ?? 0] ?? 0;
  }
}

/**
 * [cells] in the order a brush would cover them: across the stroke's longer side a column (or row)
 * at a time, up and down alternately, so a fill goes on in back-and-forth sweeps and a line from
 * one end to the other.
 */
export function brushPath(cells: number[], size: number): number[] {
  if (cells.length === 0) return cells;
  const xs = cells.map((c) => c % size);
  const ys = cells.map((c) => Math.floor(c / size));
  const wide = Math.max(...xs) - Math.min(...xs) >= Math.max(...ys) - Math.min(...ys);
  const along = (c: number) => (wide ? c % size : Math.floor(c / size));
  const across = (c: number) => (wide ? Math.floor(c / size) : c % size);
  return [...cells].sort((a, b) => along(a) - along(b) || (along(a) % 2 === 0 ? across(a) - across(b) : across(b) - across(a)));
}
