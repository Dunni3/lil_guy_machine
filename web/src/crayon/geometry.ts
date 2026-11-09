import { noise3 } from "./noise";

/**
 * The hand-drawn look's shapes: lumpy spheres, rounded boxes and cylinders with smooth normals,
 * laid out as the Kotlin app's (`Crayon.kt`), so a seed gives the same lumps and the same hull.
 */
export class PartGeometry {
  readonly count: number;
  constructor(readonly pos: Float32Array, readonly uv: Float32Array, readonly idx: number[], readonly normal = new Float32Array(pos.length)) {
    this.count = pos.length / 3;
  }

  /** Normals averaged across every vertex at the same position, so seams don't crack the hull. */
  smoothNormals(): this {
    const { pos, idx, normal, count } = this;
    const acc = new Map<string, Float64Array>();
    const keys = new Array<string>(count);
    for (let i = 0; i < count; i++) {
      keys[i] = `${Math.round(pos[3 * i] * 1e4)},${Math.round(pos[3 * i + 1] * 1e4)},${Math.round(pos[3 * i + 2] * 1e4)}`;
      if (!acc.has(keys[i])) acc.set(keys[i], new Float64Array(3));
    }
    for (let t = 0; t < idx.length / 3; t++) {
      const a = idx[3 * t], b = idx[3 * t + 1], c = idx[3 * t + 2];
      const ux = pos[3 * b] - pos[3 * a], uy = pos[3 * b + 1] - pos[3 * a + 1], uz = pos[3 * b + 2] - pos[3 * a + 2];
      const vx = pos[3 * c] - pos[3 * a], vy = pos[3 * c + 1] - pos[3 * a + 1], vz = pos[3 * c + 2] - pos[3 * a + 2];
      const nx = uy * vz - uz * vy;
      const ny = uz * vx - ux * vz;
      const nz = ux * vy - uy * vx;
      for (const i of [a, b, c]) {
        const n = acc.get(keys[i])!;
        n[0] += nx; n[1] += ny; n[2] += nz;
      }
    }
    for (let i = 0; i < count; i++) {
      const n = acc.get(keys[i])!;
      const l = Math.hypot(n[0], n[1], n[2]) || 1;
      normal[3 * i] = n[0] / l; normal[3 * i + 1] = n[1] / l; normal[3 * i + 2] = n[2] / l;
    }
    return this;
  }

  /** The inverted-hull outline's positions: this part pushed out along its smooth normals by [thickness]. */
  hull(thickness: (x: number, y: number, z: number, nx: number, ny: number, nz: number) => number): Float32Array {
    const { pos, normal, count } = this;
    const v = new Float32Array(count * 3);
    for (let i = 0; i < count; i++) {
      const k = thickness(pos[3 * i], pos[3 * i + 1], pos[3 * i + 2], normal[3 * i], normal[3 * i + 1], normal[3 * i + 2]);
      for (let j = 0; j < 3; j++) v[3 * i + j] = pos[3 * i + j] + normal[3 * i + j] * k;
    }
    return v;
  }

  /** Pushes each vertex [amp] along its normal, times smooth noise over the part ([seed]); normals stay as they were. */
  lump(amp: number, seed: number): this {
    if (amp === 0) return this;
    const { pos, normal, count } = this;
    let reach = 1e-4;
    for (let i = 0; i < pos.length; i++) reach = Math.max(reach, Math.abs(pos[i]));
    const nz = noise3(seed, 5, 3.5);
    for (let i = 0; i < count; i++) {
      const k = amp * nz(pos[3 * i] / reach, pos[3 * i + 1] / reach, pos[3 * i + 2] / reach);
      for (let j = 0; j < 3; j++) pos[3 * i + j] += normal[3 * i + j] * k;
    }
    return this;
  }

  /** Where the rig hangs off a body: its extent below and above centre, arm height and reach, and how far forward the feet go. */
  measure(): BodyMeasure {
    const { pos, count } = this;
    let lo = Infinity, hi = -Infinity;
    for (let i = 0; i < count; i++) { lo = Math.min(lo, pos[3 * i + 1]); hi = Math.max(hi, pos[3 * i + 1]); }
    const armY = lo + 0.42 * (hi - lo);
    let armX = 0, front = 0;
    for (let i = 0; i < count; i++) {
      const x = Math.abs(pos[3 * i]);
      const y = pos[3 * i + 1];
      if (Math.abs(y - armY) < 0.025) armX = Math.max(armX, x);
      if (y < lo + 0.05 && x < 0.15) front = Math.max(front, pos[3 * i + 2]);
    }
    return { bottom: -lo, top: hi, armY, armX, footZ: Math.max(0.08, front - 0.03) };
  }
}

export interface BodyMeasure {
  bottom: number;
  top: number;
  /** Relative to the body's centre, like [bottom] and [top] (below centre is negative). */
  armY: number;
  armX: number;
  footZ: number;
}

type Place = (x: number, y: number, z: number) => [number, number, number];

/** A UV sphere ([ws] around, [hs] down; u = 0.25 faces +Z, v = 0 at the top), each unit-sphere point moved by [place]. */
export function sphereGeometry(ws: number, hs: number, place: Place): PartGeometry {
  const n = (ws + 1) * (hs + 1);
  const pos = new Float32Array(3 * n);
  const uv = new Float32Array(2 * n);
  for (let iy = 0; iy <= hs; iy++) {
    const v = iy / hs;
    const uOffset = iy === 0 ? 0.5 / ws : iy === hs ? -0.5 / ws : 0;
    for (let ix = 0; ix <= ws; ix++) {
      const u = ix / ws;
      const i = iy * (ws + 1) + ix;
      const st = Math.sin(v * Math.PI);
      const x = -Math.cos(u * 2 * Math.PI) * st;
      const y = Math.cos(v * Math.PI);
      const z = Math.sin(u * 2 * Math.PI) * st;
      const l = Math.hypot(x, y, z);
      const out = place(x / l, y / l, z / l);
      pos[3 * i] = out[0]; pos[3 * i + 1] = out[1]; pos[3 * i + 2] = out[2];
      uv[2 * i] = u + uOffset;
      uv[2 * i + 1] = v;
    }
  }
  const idx: number[] = [];
  for (let iy = 0; iy < hs; iy++) for (let ix = 0; ix < ws; ix++) {
    const a = iy * (ws + 1) + ix + 1;
    const b = iy * (ws + 1) + ix;
    const c = (iy + 1) * (ws + 1) + ix;
    const d = (iy + 1) * (ws + 1) + ix + 1;
    if (iy !== 0) idx.push(a, b, d);
    if (iy !== hs - 1) idx.push(b, c, d);
  }
  return new PartGeometry(pos, uv, idx).smoothNormals();
}

/** A sphere with lumps, scaled to [rx], [ry], [rz]; lumps are relative to the radius. */
export function lumpySphere(rx: number, ry: number, rz: number, ws: number, hs: number, seed: number, amp: number): PartGeometry {
  const nz = noise3(seed, 5, 3.5);
  return sphereGeometry(ws, hs, (x, y, z) => {
    const k = 1 + amp * nz(x, y, z);
    return [x * k * rx, y * k * ry, z * k * rz];
  });
}

/** The tater: wide, a little squat and lumpy, [radius] across before the lumps. */
export function taterBody(radius: number): PartGeometry {
  const nz = noise3(11, 5, 3.5);
  const big = noise3(12, 3, 2);
  return sphereGeometry(56, 40, (x, y, z) => {
    const k = radius * (1 + 0.06 * nz(x, y, z) + 0.07 * big(x, y, z));
    return [x * 1.14 * k, y * 0.9 * k, z * 0.96 * k];
  });
}

/** One face of a box: its normal's axis and sign, then the axes and signs of u and v (u x v = normal). */
const BOX_FACES: [number, number, number, number, number, number][] = [
  [0, 1, 2, -1, 1, 1], [0, -1, 2, 1, 1, 1],
  [1, 1, 0, 1, 2, -1], [1, -1, 0, 1, 2, 1],
  [2, 1, 0, 1, 1, 1], [2, -1, 0, -1, 1, 1],
];

/**
 * A box [w] x [h] x [d] centred on the origin with its edges and corners rounded to [r]: each face a
 * grid whose outer rows bend round onto a quarter circle ([arc] steps), so normals are exact and
 * faces meet seam to seam for the hull. Long faces get extra rows every [step]. UVs run 0..1 across each face, v = 0 at the top.
 */
export function roundedBoxGeometry(w: number, h: number, d: number, r: number, arc = 3, step = 0.15): PartGeometry {
  const e = [w / 2, h / 2, d / 2];
  const rr = Math.min(Math.min(e[0], e[1], e[2]), Math.max(1e-4, r));
  const inner = e.map((v) => v - rr);
  const samples = e.map((v) => axisSamples(v, rr, arc, step));
  const pos: number[] = [], nrm: number[] = [], uv: number[] = [], idx: number[] = [];
  const p = [0, 0, 0];
  const off = [0, 0, 0];
  for (const [na, ns, ua, us, va, vs] of BOX_FACES) {
    const su = samples[ua];
    const sv = samples[va];
    const base = pos.length / 3;
    for (let j = 0; j < sv.length; j++) for (let i = 0; i < su.length; i++) {
      p[na] = ns * e[na]; p[ua] = us * su[i]; p[va] = vs * sv[j];
      for (let k = 0; k < 3; k++) {
        const c = Math.min(inner[k], Math.max(-inner[k], p[k]));
        off[k] = p[k] - c;
        p[k] = c;
      }
      const l = Math.hypot(off[0], off[1], off[2]);
      const ox = off[0] / l, oy = off[1] / l, oz = off[2] / l;
      pos.push(p[0] + ox * rr, p[1] + oy * rr, p[2] + oz * rr);
      nrm.push(ox, oy, oz);
      uv.push((su[i] / e[ua] + 1) / 2, 1 - (sv[j] / e[va] + 1) / 2);
    }
    const nu = su.length;
    for (let j = 0; j < sv.length - 1; j++) for (let i = 0; i < nu - 1; i++) {
      const a = base + j * nu + i;
      const b = a + 1;
      const c = a + nu + 1;
      const dd = a + nu;
      idx.push(a, b, c, a, c, dd);
    }
  }
  return new PartGeometry(new Float32Array(pos), new Float32Array(uv), idx, new Float32Array(nrm));
}

/** Where a rounded box's grid lines fall along an axis of half-length [e]: even angles round each edge, even steps between. */
function axisSamples(e: number, r: number, arc: number, step: number): number[] {
  const inner = e - r;
  const out: number[] = [];
  for (let k = arc; k >= 1; k--) out.push(-inner - r * Math.tan(((k / arc) * Math.PI) / 4));
  const n = Math.max(1, Math.ceil((2 * inner) / step));
  for (let i = 0; i <= n; i++) out.push(-inner + (2 * inner * i) / n);
  for (let k = 1; k <= arc; k++) out.push(inner + r * Math.tan(((k / arc) * Math.PI) / 4));
  return out;
}

/**
 * A cylinder [w] across, [h] tall and [d] deep, centred on the origin, its rims rounded to [r]
 * and its top [taper] times the bottom's width. [r] as big as the radius makes a capsule. Made by
 * turning a profile round Y in [segs] steps; UVs are a top-down projection, so a texture lies on the caps.
 */
export function cylinderGeometry(w: number, h: number, d: number, r: number, taper = 1, segs = 24, step = 0.15): PartGeometry {
  const hh = h / 2;
  const rb = w / 2;
  const rt = rb * taper;
  // The profile, bottom centre round the outside to top centre: r, y, and the outward normal.
  const prof: [number, number, number, number][] = [];
  const line = (r0: number, y0: number, r1: number, y1: number, endInclusive: boolean) => {
    const len = Math.hypot(r1 - r0, y1 - y0);
    if (len < 1e-6) return;
    const nr = (y1 - y0) / len;
    const ny = -(r1 - r0) / len;
    const n = Math.max(1, Math.ceil(len / step));
    for (let i = 0; i < (endInclusive ? n + 1 : n); i++) {
      const t = i / n;
      prof.push([r0 + (r1 - r0) * t, y0 + (y1 - y0) * t, nr, ny]);
    }
  };
  const corners = [[0, -hh], [rb, -hh], [rt, hh], [0, hh]];
  // Each outer corner's fillet: where it starts and ends, its centre and radius.
  const f0 = fillet(corners[0], corners[1], corners[2], Math.max(r, 1e-4));
  const f1 = fillet(corners[1], corners[2], corners[3], Math.max(r, 1e-4));
  line(0, -hh, f0[0], f0[1], false);
  arcPoints(f0, prof);
  line(f0[2], f0[3], f1[0], f1[1], false);
  arcPoints(f1, prof);
  line(f1[2], f1[3], 0, hh, true);

  const ring = segs + 1;
  const pos = new Float32Array(prof.length * ring * 3);
  const nrm = new Float32Array(pos.length);
  const uv = new Float32Array(prof.length * ring * 2);
  const sz = d / w;
  prof.forEach((q, j) => {
    for (let i = 0; i <= segs; i++) {
      const a = (i / segs) * 2 * Math.PI;
      const s = Math.sin(a);
      const c = Math.cos(a);
      const v = j * ring + i;
      const x = q[0] * s;
      const z = q[0] * c * sz;
      pos[3 * v] = x; pos[3 * v + 1] = q[1]; pos[3 * v + 2] = z;
      const nx = q[2] * s;
      const ny = q[3];
      const nz = (q[2] * c) / sz;
      const l = Math.hypot(nx, ny, nz);
      nrm[3 * v] = nx / l; nrm[3 * v + 1] = ny / l; nrm[3 * v + 2] = nz / l;
      uv[2 * v] = x / w + 0.5;
      uv[2 * v + 1] = z / d + 0.5;
    }
  });
  const idx: number[] = [];
  for (let j = 0; j < prof.length - 1; j++) for (let i = 0; i < segs; i++) {
    const a = j * ring + i;
    const b = a + 1;
    const c = a + ring + 1;
    const dd = a + ring;
    idx.push(a, b, c, a, c, dd);
  }
  return new PartGeometry(pos, uv, idx, nrm);
}

/**
 * The fillet of radius up to [r] at corner [p] between [prev] and [next] (2D, convex): start point,
 * end point, centre and the radius that fits, as r0, y0, r1, y1, cr, cy, radius.
 */
function fillet(prev: number[], p: number[], next: number[], r: number): number[] {
  const ax = p[0] - prev[0], ay = p[1] - prev[1];
  const bx = next[0] - p[0], by = next[1] - p[1];
  const la = Math.hypot(ax, ay);
  const lb = Math.hypot(bx, by);
  const dax = ax / la, day = ay / la;
  const dbx = bx / lb, dby = by / lb;
  const theta = Math.acos(Math.min(1, Math.max(-1, -dax * dbx - day * dby)));
  const half = Math.tan(theta / 2);
  // The side is shared with the other rim's fillet, so each may take half of it; a cap's radius is all its own.
  const t = Math.min(r / half, la * (prev[0] === 0 ? 1 : 0.5), lb * (next[0] === 0 ? 1 : 0.5));
  const rad = t * half;
  const bisx = -dax + dbx, bisy = -day + dby;
  const bl = Math.hypot(bisx, bisy);
  const k = rad / Math.sin(theta / 2);
  return [p[0] - dax * t, p[1] - day * t, p[0] + dbx * t, p[1] + dby * t, p[0] + (bisx / bl) * k, p[1] + (bisy / bl) * k, rad];
}

/** A fillet's arc into [prof], start included, end left for the next line. */
function arcPoints(f: number[], prof: [number, number, number, number][], steps = 4) {
  const rad = f[6];
  const a0 = Math.atan2(f[1] - f[5], f[0] - f[4]);
  let a1 = Math.atan2(f[3] - f[5], f[2] - f[4]);
  if (a1 < a0) a1 += 2 * Math.PI;
  for (let i = 0; i < steps; i++) {
    const a = a0 + ((a1 - a0) * i) / steps;
    prof.push([f[4] + Math.cos(a) * rad, f[5] + Math.sin(a) * rad, Math.cos(a), Math.sin(a)]);
  }
}

/** A flat [w] x [h] rectangle centred on the origin, facing +Z, UVs 0..1 with v = 0 at the top. */
export function panelGeometry(w: number, h: number): PartGeometry {
  const x = w / 2;
  const y = h / 2;
  return new PartGeometry(
    new Float32Array([-x, -y, 0, x, -y, 0, x, y, 0, -x, y, 0]),
    new Float32Array([0, 1, 1, 1, 1, 0, 0, 0]),
    [0, 1, 2, 0, 2, 3],
    new Float32Array([0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1]),
  );
}
