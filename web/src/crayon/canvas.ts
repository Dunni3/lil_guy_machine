import { DataTexture, LinearFilter, LinearMipmapNearestFilter, NoColorSpace, RGBAFormat, RepeatWrapping, UnsignedByteType } from "three";
import type { Rgba } from "./color";
import { DEG } from "./math";
import type { Rng } from "./noise";

const SUB_ROWS = 4;

/**
 * A small anti-aliased canvas for the hand-drawn textures, in floats, so thousands of faint strokes
 * add up the same as in the Kotlin app whatever the browser's canvas would round them to. Shapes are
 * given in design units and drawn at [scale], offset by ([ox], [oy]); colours blend in sRGB. Every
 * stroke is one shape (round caps and joins), composited once, and [clip] masks what is drawn until [unclip].
 */
export class CrayonCanvas {
  /** Straight (not premultiplied) RGBA, row by row from the top. */
  private readonly px: Float32Array;
  private mask: Float32Array | null = null;

  constructor(readonly width: number, readonly height: number, private readonly scale = 1, private readonly ox = 0, private readonly oy = 0) {
    this.px = new Float32Array(width * height * 4);
  }

  fill(c: Rgba) {
    const px = this.px;
    for (let i = 0; i < px.length; i += 4) {
      px[i] = c.r; px[i + 1] = c.g; px[i + 2] = c.b; px[i + 3] = c.a;
    }
  }

  /** Copies [from]'s pixels starting at its pixel ([x0], [y0]), for a crop at the same scale. */
  copy(from: CrayonCanvas, x0: number, y0: number) {
    for (let y = 0; y < this.height; y++) for (let x = 0; x < this.width; x++) {
      const sx = Math.min(from.width - 1, Math.max(0, x + x0));
      const sy = Math.min(from.height - 1, Math.max(0, y + y0));
      const s = 4 * (sy * from.width + sx);
      const d = 4 * (y * this.width + x);
      for (let k = 0; k < 4; k++) this.px[d + k] = from.px[s + k];
    }
  }

  /** A polyline [pts] (x, y pairs) stroked [lineWidth] wide. */
  stroke(pts: ArrayLike<number>, lineWidth: number, c: Rgba, alpha = 1) {
    const n = pts.length / 2;
    const xs = new Float64Array(n);
    const ys = new Float64Array(n);
    let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
    for (let i = 0; i < n; i++) {
      const x = (xs[i] = (pts[2 * i] - this.ox) * this.scale);
      const y = (ys[i] = (pts[2 * i + 1] - this.oy) * this.scale);
      if (x < minX) minX = x;
      if (x > maxX) maxX = x;
      if (y < minY) minY = y;
      if (y > maxY) maxY = y;
    }
    const half = (lineWidth * this.scale) / 2;
    const x0 = Math.max(0, Math.floor(minX - half - 1));
    const x1 = Math.min(this.width - 1, Math.ceil(maxX + half + 1));
    const y0 = Math.max(0, Math.floor(minY - half - 1));
    const y1 = Math.min(this.height - 1, Math.ceil(maxY + half + 1));
    for (let y = y0; y <= y1; y++) for (let x = x0; x <= x1; x++) {
      const qx = x + 0.5;
      const qy = y + 0.5;
      let d = Infinity;
      if (n === 1) d = Math.hypot(qx - xs[0], qy - ys[0]);
      for (let k = 0; k < n - 1; k++) {
        const v = segmentDistance(qx, qy, xs[k], ys[k], xs[k + 1], ys[k + 1]);
        if (v < d) d = v;
      }
      const cover = Math.min(1, Math.max(0, half - d + 0.5));
      if (cover > 0) this.blend(y * this.width + x, c, alpha * cover);
    }
  }

  /** A closed polygon [pts] filled (even-odd), anti-aliased horizontally exactly and vertically by sub-rows. */
  fillPath(pts: ArrayLike<number>, c: Rgba, alpha = 1) {
    const cover = this.coverage(pts);
    for (let i = 0; i < cover.length; i++) if (cover[i] > 0) this.blend(i, c, alpha * cover[i]);
  }

  /** An axis-aligned rectangle filled, with fractional coverage at its edges. */
  fillRect(x: number, y: number, w: number, h: number, c: Rgba, alpha = 1) {
    const l = (x - this.ox) * this.scale;
    const t = (y - this.oy) * this.scale;
    const r = l + w * this.scale;
    const b = t + h * this.scale;
    for (let py = Math.max(0, Math.floor(t)); py <= Math.min(this.height - 1, Math.ceil(b) - 1); py++) {
      const cy = Math.min(1, Math.max(0, Math.min(b, py + 1) - Math.max(t, py)));
      for (let px = Math.max(0, Math.floor(l)); px <= Math.min(this.width - 1, Math.ceil(r) - 1); px++) {
        const cx = Math.min(1, Math.max(0, Math.min(r, px + 1) - Math.max(l, px)));
        this.blend(py * this.width + px, c, alpha * cx * cy);
      }
    }
  }

  clip(pts: ArrayLike<number>) {
    this.mask = this.coverage(pts);
  }

  clipRect(x: number, y: number, w: number, h: number) {
    this.clip([x, y, x + w, y, x + w, y + h, x, y + h]);
  }

  unclip() {
    this.mask = null;
  }

  /** The picture as a texture; [repeat] wraps it (for tiling), and [mipmaps] gives it mip levels, for a big surface seen smaller than it's painted. */
  texture(repeat = false, mipmaps = false): DataTexture {
    const px = this.px;
    const bytes = new Uint8Array(px.length);
    for (let i = 0; i < px.length; i++) bytes[i] = Math.floor(Math.min(1, Math.max(0, px[i])) * 255 + 0.5);
    const t = new DataTexture(bytes, this.width, this.height, RGBAFormat, UnsignedByteType);
    t.colorSpace = NoColorSpace;
    t.magFilter = LinearFilter;
    t.minFilter = mipmaps ? LinearMipmapNearestFilter : LinearFilter;
    t.generateMipmaps = mipmaps;
    if (repeat) t.wrapS = t.wrapT = RepeatWrapping;
    t.needsUpdate = true;
    return t;
  }

  private blend(i: number, c: Rgba, a0: number) {
    const a = a0 * c.a * (this.mask ? this.mask[i] : 1);
    if (a <= 0) return;
    const px = this.px;
    const o = 4 * i;
    const da = px[o + 3];
    const outA = a + da * (1 - a);
    px[o] = (c.r * a + px[o] * da * (1 - a)) / outA;
    px[o + 1] = (c.g * a + px[o + 1] * da * (1 - a)) / outA;
    px[o + 2] = (c.b * a + px[o + 2] * da * (1 - a)) / outA;
    px[o + 3] = outA;
  }

  private coverage(pts: ArrayLike<number>): Float32Array {
    const n = pts.length / 2;
    const xs = new Float64Array(n);
    const ys = new Float64Array(n);
    let minY = Infinity, maxY = -Infinity;
    for (let i = 0; i < n; i++) {
      xs[i] = (pts[2 * i] - this.ox) * this.scale;
      const y = (ys[i] = (pts[2 * i + 1] - this.oy) * this.scale);
      if (y < minY) minY = y;
      if (y > maxY) maxY = y;
    }
    const out = new Float32Array(this.width * this.height);
    const y0 = Math.max(0, Math.floor(minY));
    const y1 = Math.min(this.height - 1, Math.ceil(maxY));
    const hits = new Float64Array(n);
    for (let y = y0; y <= y1; y++) for (let sub = 0; sub < SUB_ROWS; sub++) {
      const sy = y + (sub + 0.5) / SUB_ROWS;
      let count = 0;
      for (let k = 0; k < n; k++) {
        const j = (k + 1) % n;
        const ay = ys[k];
        const by = ys[j];
        if (ay <= sy !== by <= sy) hits[count++] = xs[k] + ((sy - ay) / (by - ay)) * (xs[j] - xs[k]);
      }
      hits.subarray(0, count).sort();
      for (let h = 0; h + 1 < count; h += 2) this.span(out, y, hits[h], hits[h + 1]);
    }
    return out;
  }

  /** Adds a sub-row's covered span [a, b) to row [y], with fractional coverage at both ends. */
  private span(out: Float32Array, y: number, a: number, b: number) {
    const l = Math.min(this.width, Math.max(0, a));
    const r = Math.min(this.width, Math.max(0, b));
    if (r <= l) return;
    const w = 1 / SUB_ROWS;
    const li = Math.trunc(l);
    const ri = Math.min(this.width - 1, Math.trunc(r));
    const row = y * this.width;
    if (li === ri) {
      out[row + li] += (r - l) * w;
      return;
    }
    out[row + li] += (li + 1 - l) * w;
    for (let x = li + 1; x < ri; x++) out[row + x] += w;
    if (ri < this.width) out[row + ri] += (r - ri) * w;
  }

  /** An ink stroke gone over twice like a pen: a full line and a fainter, slightly offset one. */
  penStroke(pts: number[], lineWidth: number, ink: Rgba, rand: Rng) {
    for (let pass = 0; pass <= 1; pass++) {
      const ox = (rand() - 0.5) * 3;
      const oy = (rand() - 0.5) * 3;
      const jittered = new Array<number>(pts.length);
      for (let i = 0; i < pts.length / 2; i++) {
        jittered[2 * i] = pts[2 * i] + ox + (rand() - 0.5) * 1.4;
        jittered[2 * i + 1] = pts[2 * i + 1] + oy + (rand() - 0.5) * 1.4;
      }
      this.stroke(jittered, pass === 1 ? lineWidth * 0.45 : lineWidth, ink, pass === 1 ? 0.55 : 1);
    }
  }

  /** A patch of diagonal crayon strokes inside a wobbly ellipse. */
  crayonFill(cx: number, cy: number, rx: number, ry: number, c: Rgba, rand: Rng, alpha: number) {
    this.clip(blobPath(cx, cy, rx, ry, rand, 0.09));
    for (let k = -rx * 2; k < rx * 2; k += 7) {
      const ya = cy + ry + (rand() - 0.5) * 5;
      const yb = cy - ry + (rand() - 0.5) * 5;
      this.stroke([cx + k - ry, ya, cx + k + ry, yb], 4.5, c, alpha);
    }
    this.unclip();
  }
}

function segmentDistance(px: number, py: number, ax: number, ay: number, bx: number, by: number): number {
  const dx = bx - ax;
  const dy = by - ay;
  const len = dx * dx + dy * dy;
  const t = len > 0 ? Math.min(1, Math.max(0, ((px - ax) * dx + (py - ay) * dy) / len)) : 0;
  const ex = px - (ax + t * dx);
  const ey = py - (ay + t * dy);
  return Math.sqrt(ex * ex + ey * ey);
}

// --- the pen shapes, as x, y pairs -------------------------------------------------------------

/** A wobbly closed ellipse. */
export function blobPath(cx: number, cy: number, rx: number, ry: number, rand: Rng, wob = 0.05, n = 40): number[] {
  const p1 = rand() * 6.28;
  const p2 = rand() * 6.28;
  const out = new Array<number>(2 * (n + 1));
  for (let i = 0; i <= n; i++) {
    const a = (i / n) * Math.PI * 2;
    const j = 1 + wob * Math.sin(2 * a + p1) + wob * 0.6 * Math.sin(5 * a + p2);
    out[2 * i] = cx + Math.cos(a) * rx * j;
    out[2 * i + 1] = cy + Math.sin(a) * ry * j;
  }
  return out;
}

/** A wobbly arc from [a0] to [a1] degrees, overshooting or falling short a little. */
export function arcPts(cx: number, cy: number, r: number, a0: number, a1: number, rand: Rng, wob = 0.06, n = 18): number[] {
  const ph = rand() * 6.28;
  const over = (rand() - 0.3) * 10;
  const out = new Array<number>(2 * (n + 1));
  for (let i = 0; i <= n; i++) {
    const a = (a0 - over / 2 + ((a1 - a0 + over) * i) / n) * DEG;
    const rr = r * (1 + wob * Math.sin(a * 2.5 + ph));
    out[2 * i] = cx + Math.cos(a) * rr;
    out[2 * i + 1] = cy + Math.sin(a) * rr;
  }
  return out;
}

/** A line that bows a little to one side. */
export function linePts(x0: number, y0: number, x1: number, y1: number, rand: Rng, bow = 3, n = 10): number[] {
  const b = (rand() - 0.5) * 2 * bow;
  const nx = -(y1 - y0);
  const ny = x1 - x0;
  const l = Math.hypot(nx, ny) || 1;
  const out = new Array<number>(2 * (n + 1));
  for (let i = 0; i <= n; i++) {
    const t = i / n;
    const k = Math.sin(Math.PI * t) * b;
    out[2 * i] = x0 + (x1 - x0) * t + (nx / l) * k;
    out[2 * i + 1] = y0 + (y1 - y0) * t + (ny / l) * k;
  }
  return out;
}
