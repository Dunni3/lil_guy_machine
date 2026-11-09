import { BufferAttribute, BufferGeometry, Matrix3, Matrix4, Vector3, type Texture } from "three";
import { hex, linear, type Rgba } from "./color";
import { PartGeometry, cylinderGeometry, panelGeometry, roundedBoxGeometry, sphereGeometry } from "./geometry";
import { Y_AXIS, X_AXIS, Z_AXIS, rotate, translate } from "./math";
import { noise3 } from "./noise";

/** One part of a model, as `parts/export/parts.json` has it: see `Part.kt`. */
export interface Part {
  shape: string;
  w: number;
  h: number;
  d: number;
  x: number;
  y: number;
  z: number;
  rotX: number;
  rotY: number;
  rotZ: number;
  color: string;
  texture: string | null;
  round: number;
  lump: number;
  seed: number;
  outline: number;
  taper: number;
}

/** The outline and hatching redraw a few times a second, cycling through this many frames. */
export const BOIL_FRAMES = 3;
/** The guy's parts are drawn 1.25 times their size, so furniture in metres hatches at this to match him. */
const HATCH_METRES = 0.8;
let maxRound = 0.035;

/** How far a soft-edged part rounds when it doesn't say, from the part lists' own constant. */
export function setMaxRound(v: number) {
  maxRound = v;
}

export function partGeometry(p: Part): PartGeometry {
  const small = Math.min(p.w, p.h, p.d);
  const soft = p.round >= 0 ? p.round : Math.min(maxRound, small * 0.3);
  const segments = (size: number) => Math.min(40, Math.max(14, Math.ceil(Math.fround(size * 40))));
  let geo: PartGeometry;
  switch (p.shape) {
    case "sphere": {
      const ws = Math.min(32, Math.max(12, Math.trunc(Math.fround(Math.max(p.w, p.h, p.d) * 48))));
      geo = sphereGeometry(ws, Math.trunc((ws * 3) / 4), (x, y, z) => [(x * p.w) / 2, (y * p.h) / 2, (z * p.d) / 2]);
      break;
    }
    case "box":
      geo = roundedBoxGeometry(p.w, p.h, p.d, soft);
      break;
    case "cylinder":
      geo = cylinderGeometry(p.w, p.h, p.d, soft, p.taper, segments(Math.max(p.w, p.d)));
      break;
    case "capsule":
      geo = cylinderGeometry(p.w, p.h, p.d, Math.min(p.w, p.d) / 2, p.taper, segments(Math.max(p.w, p.d)));
      break;
    case "panel":
      geo = panelGeometry(p.w, p.h);
      break;
    default:
      throw new Error(`a part of unknown shape: ${p.shape}`);
  }
  return geo.lump(p.lump, p.seed);
}

/** Where a part's geometry goes in the model: to the bottom centre, turned, then up by half its height. */
function placement(p: Part): Matrix4 {
  const m = new Matrix4().makeTranslation(p.x, p.y, p.z);
  rotate(m, Y_AXIS, p.rotY);
  rotate(m, X_AXIS, p.rotX);
  rotate(m, Z_AXIS, p.rotZ);
  return translate(m, 0, p.h / 2, 0);
}

/** How a piece's outline boils: [base] thick plus up to [vary] of noise ([seed]), over its normals and, by [wander] per metre, its position. */
export interface Outline {
  base: number;
  vary: number;
  seed: number;
  wander: number;
}

/** A part ready to merge: geometry, where it goes in the model, a linear colour, a texture, an outline. */
export interface Piece {
  geo: PartGeometry;
  color: Rgba;
  texture: Texture | null;
  outline: Outline | null;
  place: Matrix4;
}

export interface Batch {
  geometry: BufferGeometry;
  texture: Texture | null;
}

/**
 * Pieces merged for drawing: one geometry per texture (colour per vertex), and one outline hull per
 * boil frame, so a whole item costs a draw call or two plus one for its ink.
 */
export class CrayonModel {
  readonly batches: Batch[] = [];
  /** One per boil frame; empty when nothing has an outline. */
  readonly hulls: BufferGeometry[] = [];
  /** What it takes up: min x, y, z, then max x, y, z. */
  readonly bounds = [Infinity, Infinity, Infinity, -Infinity, -Infinity, -Infinity];

  constructor(pieces: Piece[], readonly hatchScale = 1) {
    const normalMatrix = new Matrix3();
    const v = new Vector3();
    const groups = new Map<Texture | null, Piece[]>();
    for (const p of pieces) {
      const g = groups.get(p.texture);
      if (g) g.push(p);
      else groups.set(p.texture, [p]);
    }
    for (const [texture, group] of groups) {
      const vertices = group.reduce((s, p) => s + p.geo.count, 0);
      const pos = new Float32Array(vertices * 3);
      const normal = new Float32Array(vertices * 3);
      const uv = new Float32Array(vertices * 2);
      const color = new Float32Array(vertices * 3);
      const idx: number[] = [];
      let vo = 0;
      for (const p of group) {
        const g = p.geo;
        normalMatrix.getNormalMatrix(p.place);
        for (let i = 0; i < g.count; i++) {
          const o = vo + i;
          v.set(g.pos[3 * i], g.pos[3 * i + 1], g.pos[3 * i + 2]).applyMatrix4(p.place);
          pos[3 * o] = v.x; pos[3 * o + 1] = v.y; pos[3 * o + 2] = v.z;
          for (let k = 0; k < 3; k++) {
            this.bounds[k] = Math.min(this.bounds[k], pos[3 * o + k]);
            this.bounds[k + 3] = Math.max(this.bounds[k + 3], pos[3 * o + k]);
          }
          v.set(g.normal[3 * i], g.normal[3 * i + 1], g.normal[3 * i + 2]).applyMatrix3(normalMatrix).normalize();
          normal[3 * o] = v.x; normal[3 * o + 1] = v.y; normal[3 * o + 2] = v.z;
          uv[2 * o] = g.uv[2 * i]; uv[2 * o + 1] = g.uv[2 * i + 1];
          color[3 * o] = p.color.r; color[3 * o + 1] = p.color.g; color[3 * o + 2] = p.color.b;
        }
        for (const k of g.idx) idx.push(k + vo);
        vo += g.count;
      }
      const geometry = new BufferGeometry();
      geometry.setAttribute("position", new BufferAttribute(pos, 3));
      geometry.setAttribute("normal", new BufferAttribute(normal, 3));
      geometry.setAttribute("uv", new BufferAttribute(uv, 2));
      geometry.setAttribute("a_color", new BufferAttribute(color, 3));
      geometry.setIndex(new BufferAttribute(new Uint32Array(idx), 1));
      this.batches.push({ geometry, texture });
    }
    const inked = pieces.filter((p) => p.outline !== null);
    if (inked.length === 0) return;
    const vertices = inked.reduce((s, p) => s + p.geo.count, 0);
    const uv = new BufferAttribute(new Float32Array(vertices * 2), 2);
    const idx: number[] = [];
    let at = 0;
    for (const p of inked) {
      for (const q of p.geo.idx) idx.push(q + at);
      at += p.geo.count;
    }
    const index = new BufferAttribute(new Uint32Array(idx), 1);
    for (let k = 0; k < BOIL_FRAMES; k++) {
      const pos = new Float32Array(vertices * 3);
      let vo = 0;
      for (const p of inked) {
        const o = p.outline!;
        const nz = noise3(o.seed * 31 + k * 7, 4, 4);
        const w = o.wander;
        const pushed = p.geo.hull((x, y, z, nx, ny, nz2) => o.base + o.vary * nz(nx + x * w, ny + y * w, nz2 + z * w));
        for (let i = 0; i < p.geo.count; i++) {
          v.set(pushed[3 * i], pushed[3 * i + 1], pushed[3 * i + 2]).applyMatrix4(p.place);
          pos[3 * (vo + i)] = v.x; pos[3 * (vo + i) + 1] = v.y; pos[3 * (vo + i) + 2] = v.z;
        }
        vo += p.geo.count;
      }
      const hull = new BufferGeometry();
      hull.setAttribute("position", new BufferAttribute(pos, 3));
      hull.setAttribute("uv", uv);
      hull.setIndex(index);
      this.hulls.push(hull);
    }
  }

  dispose() {
    for (const b of this.batches) b.geometry.dispose();
    for (const h of this.hulls) h.dispose();
  }

  /** A model from a part list; [texture] finds a painted texture by name. */
  static of(parts: Part[], texture: (name: string) => Texture | null, hatchScale = HATCH_METRES): CrayonModel {
    return new CrayonModel(
      parts.map((p) => ({
        geo: partGeometry(p),
        color: linear(hex(p.color)),
        texture: p.texture ? texture(p.texture) : null,
        outline: p.outline > 0 ? { base: p.outline, vary: p.outline * 0.45, seed: p.seed, wander: 2.5 } : null,
        place: placement(p),
      })),
      hatchScale,
    );
  }
}
