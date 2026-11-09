import type { DataTexture } from "three";
import { CrayonCanvas, arcPts, blobPath, linePts } from "../crayon/canvas";
import { WHITE, hex, shade, type Rgba } from "../crayon/color";
import { rng, type Rng } from "../crayon/noise";
import { constants } from "./parts";

/** The floor texture covers this many metres each way, and the wall's this many across. */
export const FLOOR_METRES = 4;
export const WALL_METRES = 2;
/** Section texture: one repeat per this many metres. */
export const SECTION_METRES = 0.5;
const FLOOR_PX = 512;
const WALL_PX = 256;
const SECTION_PX = 64;
const BOARDS = 12;
const GRAIN_PER_ROW = 90;
const DEG_PER_RAD = 180 / Math.PI;

const FLOOR_WOOD = "#e7cca9";
const FLOOR_LINE = hex("#b4977d");
const PLASTER = "#f3e8da";
const CLAY = "#e2c2ac";
const BASEBOARD = "#d4ad95";
const WALL_INK = "#6e5a5a";
const SECTION_COLOR = "#8a7268";
const RUG_OAT = "#e6d5bd";
const RUG_RUST = "#c9785a";
const RUG_CREAM = "#f6eee2";
const RUG_SAGE = "#b3bf9f";
const POSTER_BORDER = "#f8f1e6";
const POSTER_SUN = "#e9b65e";
const POSTER_RUST = "#c9785a";
const POSTER_SAND = "#e5c7a4";
const POSTER_SAGE = "#9fae8f";
const CLOCK_RIM = "#4f4848";
const CLOCK_DIAL = "#faf4ea";

/**
 * The room's painted textures, in the guy's hand-drawn look: crayon strokes and pen lines laid down
 * once with [CrayonCanvas] when first asked for, then kept. Ported from `RoomPaint.kt`, with the same
 * seeds, so the pictures come out the same. The floor and walls are drawn with the cheap paper
 * shader, so all their crayon look lives here; part lists name the rest ([texture]).
 *
 * Every picture is painted at one design unit per pixel. The floor and wall tile, so strokes near an
 * edge are drawn again on the far side ([wrapStroke]).
 */
export class RoomPaint {
  private readonly textures = new Map<string, DataTexture | null>();

  /** The texture called [name], painted the first time; null for a name nothing here paints. */
  texture(name: string): DataTexture | null {
    let t = this.textures.get(name);
    if (t === undefined) {
      t = paint(name);
      this.textures.set(name, t);
    }
    return t;
  }
}

function paint(name: string): DataTexture | null {
  switch (name) {
    case "floor": return floor();
    case "wall": return wall();
    case "section": return section();
    case "white": {
      const c = new CrayonCanvas(4, 4);
      c.fill(WHITE);
      return c.texture();
    }
    case "rug": return rug();
    case "poster": return poster();
    case "clock": return clock();
    default: return null;
  }
}

/**
 * Wide white-oak boards running east-west, [FLOOR_METRES] square: planks of one and a half to three
 * metres, each a slightly different wood, grain in long faint crayon strokes, soft broken lines
 * between the rows, ticks at the joints and the odd knot.
 */
function floor(): DataTexture {
  const n = FLOOR_PX;
  const x = new CrayonCanvas(FLOOR_PX, FLOOR_PX);
  const rand = rng(77);
  const row = n / BOARDS;
  x.fill(hex(FLOOR_WOOD));
  for (let r = 0; r < BOARDS; r++) {
    const y0 = r * row;
    let at = rand() * n;
    let covered = 0;
    while (covered < n) {
      let len = 190 + rand() * 180;
      if (n - covered - len < 120) len = n - covered;
      const tone = shade(FLOOR_WOOD, (rand() - 0.5) * 0.05, (rand() - 0.5) * 0.04);
      wrapRect(x, at, y0, len, row, tone, n);
      // A pen tick at the plank's end, and now and then a knot.
      const jx = at + (rand() - 0.5) * 2;
      wrapPen(x, linePts(jx, y0 + 4, jx + (rand() - 0.5) * 3, y0 + row - 4, rand, 1, 4), 2, FLOOR_LINE, rand, n, 0.55);
      if (rand() < 0.2) {
        const kx = at + 30 + rand() * (len - 60);
        const ky = y0 + row * (0.35 + rand() * 0.3);
        wrapStroke(x, blobPath(kx, ky, 7 + rand() * 5, 2.5 + rand() * 1.5, rand, 0.12, 16), 1.6, shade(FLOOR_WOOD, -0.16), 0.55, n);
      }
      at += len;
      covered += len;
    }
    // Grain: short strokes along the boards, a shade either side.
    const light = shade(FLOOR_WOOD, 0.05);
    const dark = shade(FLOOR_WOOD, -0.06, 0.02);
    for (let g = 0; g < GRAIN_PER_ROW; g++) {
      const gx = rand() * n;
      const gy = y0 + 4 + rand() * (row - 8);
      const len = 30 + rand() * 70;
      const tilt = (rand() - 0.5) * 2;
      const width = 1.4 + rand() * 1.8;
      const c = rand() < 0.5 ? light : dark;
      wrapStroke(x, [gx, gy, gx + len, gy + tilt], width, c, 0.14 + rand() * 0.18, n);
    }
    // The seam above this row, in broken lengths.
    let sx = rand() * 40;
    while (sx < n + 40) {
      const len = 70 + rand() * 150;
      const sy = y0 + (rand() - 0.5) * 1.5;
      wrapPen(x, linePts(sx, sy, sx + len, sy + (rand() - 0.5) * 2, rand, 1.2, 8), 2.2, FLOOR_LINE, rand, n, 0.6);
      sx += len + 4 + rand() * 12;
    }
  }
  return x.texture(true, true);
}

/**
 * One stretch of wall, [WALL_METRES] wide and the wall's height tall, bottom to top: a slim
 * baseboard, a clay colour block, and warm limewashed plaster above, both clouded with broad, faint
 * crayon; ink along the top and the baseboard.
 */
function wall(): DataTexture {
  const w = WALL_PX;
  const h = WALL_PX;
  const x = new CrayonCanvas(WALL_PX, WALL_PX);
  const rand = rng(31);
  const yAt = (m: number) => (1 - m / constants.WALL_HEIGHT) * h;
  const blockTop = yAt(constants.COLOR_BLOCK);
  const baseTop = yAt(constants.BASEBOARD);

  x.fill(hex(PLASTER));
  limewash(x, PLASTER, 0, blockTop, w, rand);
  x.fillRect(0, blockTop, w, baseTop - blockTop, hex(CLAY));
  limewash(x, CLAY, blockTop, baseTop, w, rand);
  x.fillRect(0, baseTop, w, h - baseTop, hex(BASEBOARD));
  const baseDark = shade(BASEBOARD, -0.06);
  for (let i = 0; i < 120; i++) {
    const px = rand() * w;
    const py = baseTop + 2 + rand() * (h - baseTop - 4);
    const dx = 14 + rand() * 24;
    wrapStroke(x, [px, py, px + dx, py + (rand() - 0.5) * 1.5], 1.5, baseDark, 0.3, w);
  }

  // The block's edge is a crisp line of its own colour, a shade down; ink only at the top and the baseboard.
  const edge = shade(CLAY, -0.1);
  let ex = rand() * 30;
  while (ex < w + 30) {
    const len = 80 + rand() * 120;
    const y0 = blockTop + (rand() - 0.5) * 0.6;
    const y1 = blockTop + (rand() - 0.5) * 0.6;
    wrapStroke(x, linePts(ex, y0, ex + len, y1, rand, 0.4, 8), 2, edge, 0.6, w);
    ex += len + 2 + rand() * 6;
  }
  const ink = hex(WALL_INK);
  for (const y of [1.5, baseTop]) {
    let sx = rand() * 30;
    while (sx < w + 30) {
      const len = 60 + rand() * 110;
      const y0 = y + (rand() - 0.5);
      const y1 = y + (rand() - 0.5);
      wrapPen(x, linePts(sx, y0, sx + len, y1, rand, 1, 8), 2.4, ink, rand, w, 0.75);
      sx += len + 3 + rand() * 8;
    }
  }
  return x.texture(true, true);
}

/** Limewash between rows [y0] and [y1]: broad, faint clouds a shade either side of [base], then fine crayon over them. */
function limewash(x: CrayonCanvas, base: string, y0: number, y1: number, w: number, rand: Rng) {
  const light = shade(base, 0.04);
  const dark = shade(base, -0.05, 0.03);
  const area = ((y1 - y0) * w) / (256 * 256);
  const clamp = (v: number) => Math.min(y1, Math.max(y0, v));
  for (let i = 0; i < Math.trunc(260 * area); i++) {
    const px = rand() * w;
    const py = y0 + rand() * (y1 - y0);
    const a = (rand() - 0.5) * 1.2;
    const len = 20 + rand() * 40;
    const pts = [px, py, px + Math.cos(a) * len, clamp(py + Math.sin(a) * len)];
    const width = 10 + rand() * 14;
    const c = rand() < 0.5 ? light : dark;
    wrapStroke(x, pts, width, c, 0.07 + rand() * 0.07, w);
  }
  for (let i = 0; i < Math.trunc(700 * area); i++) {
    const px = rand() * w;
    const py = y0 + rand() * (y1 - y0);
    const a = -1.2 + (rand() - 0.5) * 0.4;
    const len = 8 + rand() * 16;
    const pts = [px, py, px + Math.cos(a) * len, clamp(py + Math.sin(a) * len)];
    const width = 1.5 + rand() * 1.5;
    const c = rand() < 0.5 ? light : dark;
    wrapStroke(x, pts, width, c, 0.18 + rand() * 0.2, w);
  }
}

/** The cut wall's top and ends: a plum-brown, scribbled. */
function section(): DataTexture {
  const n = SECTION_PX;
  const x = new CrayonCanvas(SECTION_PX, SECTION_PX);
  const rand = rng(5);
  x.fill(hex(SECTION_COLOR));
  const light = shade(SECTION_COLOR, 0.06);
  const dark = shade(SECTION_COLOR, -0.05);
  for (let i = 0; i < 260; i++) {
    const px = rand() * n;
    const py = rand() * n;
    const a = -0.7 + (rand() - 0.5) * 0.5;
    const len = 6 + rand() * 12;
    const width = 1.5 + rand();
    const c = rand() < 0.5 ? light : dark;
    wrapStroke(x, [px, py, px + Math.cos(a) * len, py + Math.sin(a) * len], width, c, 0.4, n);
  }
  return x.texture(true, true);
}

/** An oval wool rug (painted round; the rug's shape stretches it): an oat border, a cream field ringed in rust, and a thin sage ring inside; stitches round the edge. */
function rug(): DataTexture {
  const x = new CrayonCanvas(256, 256);
  const rand = rng(61);
  const c = 128;
  const rings: [number, string][] = [[1.6, RUG_OAT], [0.84, RUG_RUST], [0.8, RUG_CREAM], [0.5, RUG_SAGE], [0.46, RUG_CREAM]];
  for (const [r, col] of rings) {
    x.clip(blobPath(c, c, 124 * r, 124 * r, rand, 0.02, 48));
    x.fillRect(0, 0, 256, 256, hex(col));
    const light = shade(col, 0.05);
    const dark = shade(col, -0.07, 0.04);
    for (let k = -260; k < 260; k += 6) {
      const width = 3 + rand() * 2;
      const tone = rand() < 0.5 ? light : dark;
      x.stroke([c + k - 80, 256, c + k + 80, 0], width, tone, 0.3 + rand() * 0.25);
    }
    x.unclip();
  }
  const stitch = shade(RUG_OAT, -0.18, 0.03);
  const r = 0.95;
  const steps = Math.max(12, Math.trunc(Math.fround(Math.fround(r) * 60)));
  for (let i = 0; i < steps; i++) {
    const a0 = (i * 2 * Math.PI) / steps;
    const a1 = a0 + Math.PI / steps;
    x.stroke(arcPts(c, c, 124 * r, a0 * DEG_PER_RAD, a1 * DEG_PER_RAD, rand, 0.01, 4), 2.6, stitch, 0.75);
  }
  return x.texture();
}

/** A print, 128 x 168 (the poster's shape): a low sun behind three nested arches on cream, a thin oak frame's worth of border. */
function poster(): DataTexture {
  const x = new CrayonCanvas(128, 168);
  const rand = rng(23);
  x.fill(hex(POSTER_BORDER));
  scribble(x, POSTER_BORDER, 0, 0, 128, 168, rand);
  x.crayonFill(84, 62, 18, 18, hex(POSTER_SUN), rand, 1);
  // Arches, outermost first, each a band whose inside the next one paints over.
  const cx = 56;
  const base = 140;
  const arches: [number, string][] = [[40, POSTER_RUST], [30, POSTER_SAND], [20, POSTER_SAGE], [10, POSTER_BORDER]];
  for (const [r, col] of arches) {
    x.clip(archPath(cx, base, r, rand));
    x.fillRect(0, 0, 128, 168, hex(col));
    scribble(x, col, cx - r, base - 2 * r, 2 * r, 2 * r, rand);
    x.unclip();
  }
  x.fillRect(16, base, 96, 3, hex(POSTER_RUST));
  return x.texture();
}

/** An arch [r] wide each side of [cx], standing on [base]: straight sides up [r], then a half circle. */
function archPath(cx: number, base: number, r: number, rand: Rng): number[] {
  return [cx - r, base, ...arcPts(cx, base - r, r, 180, 360, rand, 0.01, 24), cx + r, base];
}

/** A clock face for a cylinder's cap: a charcoal rim out to the edge, a warm dial, pen ticks at the quarters and dots between. */
function clock(): DataTexture {
  const x = new CrayonCanvas(128, 128);
  const rand = rng(12);
  const ink = hex(WALL_INK);
  x.fill(hex(CLOCK_RIM));
  scribble(x, CLOCK_RIM, 0, 0, 128, 128, rand);
  const dial = blobPath(64, 64, 52, 52, rand, 0.015);
  x.clip(dial);
  x.fillRect(0, 0, 128, 128, hex(CLOCK_DIAL));
  scribble(x, CLOCK_DIAL, 0, 0, 128, 128, rand);
  x.unclip();
  x.penStroke(dial, 2.4, ink, rand);
  for (let i = 0; i < 12; i++) {
    const a = (i * Math.PI) / 6;
    if (i % 3 === 0) x.penStroke(linePts(64 + Math.cos(a) * 37, 64 + Math.sin(a) * 37, 64 + Math.cos(a) * 46, 64 + Math.sin(a) * 46, rand, 0.4, 3), 3, ink, rand);
    else x.stroke([64 + Math.cos(a) * 43, 64 + Math.sin(a) * 43], 3.4, ink, 0.7);
  }
  return x.texture();
}

/** Diagonal crayon strokes a shade either side of [base] over a rectangle. */
function scribble(x: CrayonCanvas, base: string, x0: number, y0: number, w: number, h: number, rand: Rng) {
  const light = shade(base, 0.05);
  const dark = shade(base, -0.06, 0.04);
  for (let k = -h; k < w; k += 4.5) {
    const width = 2.5 + rand() * 1.5;
    const c = rand() < 0.5 ? light : dark;
    x.stroke([x0 + k, y0 + h, x0 + k + h * 0.6, y0], width, c, 0.3 + rand() * 0.25);
  }
}

/** A stroke on a canvas that tiles every [tile] units both ways: drawn again wherever it crosses an edge. */
function wrapStroke(x: CrayonCanvas, pts: number[], width: number, c: Rgba, alpha: number, tile: number) {
  let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
  for (let i = 0; i < pts.length / 2; i++) {
    minX = Math.min(minX, pts[2 * i]); maxX = Math.max(maxX, pts[2 * i]);
    minY = Math.min(minY, pts[2 * i + 1]); maxY = Math.max(maxY, pts[2 * i + 1]);
  }
  const pad = width;
  for (const dx of [-tile, 0, tile]) for (const dy of [-tile, 0, tile]) {
    if (maxX + dx + pad < 0 || minX + dx - pad > x.width || maxY + dy + pad < 0 || minY + dy - pad > x.height) continue;
    x.stroke(pts.map((v, i) => v + (i % 2 === 0 ? dx : dy)), width, c, alpha);
  }
}

/** [wrapStroke] gone over twice like a pen. */
function wrapPen(x: CrayonCanvas, pts: number[], width: number, c: Rgba, rand: Rng, tile: number, alpha = 1) {
  for (let pass = 0; pass <= 1; pass++) {
    const ox = (rand() - 0.5) * 2;
    const oy = (rand() - 0.5) * 2;
    const moved = pts.map((v, i) => v + (i % 2 === 0 ? ox : oy) + (rand() - 0.5));
    wrapStroke(x, moved, pass === 1 ? width * 0.45 : width, c, pass === 1 ? alpha * 0.55 : alpha, tile);
  }
}

/** A rectangle on a canvas that wraps every [tile] units across. */
function wrapRect(x: CrayonCanvas, x0: number, y0: number, w: number, h: number, c: Rgba, tile: number) {
  const start = ((x0 % tile) + tile) % tile;
  x.fillRect(start, y0, w, h, c);
  if (start + w > tile) x.fillRect(start - tile, y0, w, h, c);
}
