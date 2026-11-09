import { BufferAttribute, BufferGeometry, type Vector3 } from "three";
import { WHITE, hex, mix, times, type Rgba } from "../crayon/color";
import { clamp } from "../crayon/math";
import { FLOOR_METRES, SECTION_METRES, WALL_METRES } from "./paint";
import { constants } from "./parts";

const THICK = 0.14;
const FLOOR_GRID = 4;
/** How far from a wall the floor's corner shade fades out, and what it multiplies by at the wall. */
const CORNER_SHADE_REACH = 0.4;
const CORNER_SHADE = hex("d9c9dc");
/** The shaded side's tone, as the crayon shader's (sRGB). */
const SHADE = hex("e2d2ec");
/** (dx, dy) to each neighbouring tile. */
const SIDES: [number, number][] = [[0, 1], [0, -1], [1, 0], [-1, 0]];
const FULL_UV = [0, 1, 1, 1, 1, 0, 0, 0];

/** The room's terrain: `#` wall, `.` floor, space (or off the map) for outside. */
export class Terrain {
  readonly width: number;
  readonly height: number;
  constructor(private readonly rows: string[]) {
    this.height = rows.length;
    this.width = rows[0]?.length ?? 0;
  }

  at(x: number, y: number): string {
    return this.rows[y]?.[x] ?? " ";
  }
}

/** A flat-shaded mesh being built for the paper shader: position, uv and colour per vertex, quads only. */
class PaperMesh {
  private readonly pos: number[] = [];
  private readonly uv: number[] = [];
  private readonly color: number[] = [];
  private readonly idx: number[] = [];

  /** A quad of corners 00, 10, 11, 01 (counter-clockwise from the front) in [p], with [uv] and one colour per corner, or one for all. */
  rect(p: number[], uv: number[], c: Rgba | Rgba[]) {
    const base = this.pos.length / 3;
    for (let k = 0; k < 4; k++) {
      const col = Array.isArray(c) ? c[k] : c;
      this.pos.push(p[3 * k], p[3 * k + 1], p[3 * k + 2]);
      this.uv.push(uv[2 * k], uv[2 * k + 1]);
      this.color.push(col.r, col.g, col.b);
    }
    for (const k of [0, 1, 2, 2, 3, 0]) this.idx.push(base + k);
  }

  geometry(): BufferGeometry {
    const g = new BufferGeometry();
    g.setAttribute("position", new BufferAttribute(new Float32Array(this.pos), 3));
    g.setAttribute("uv", new BufferAttribute(new Float32Array(this.uv), 2));
    g.setAttribute("a_color", new BufferAttribute(new Float32Array(this.color), 3));
    g.setIndex(new BufferAttribute(new Uint32Array(this.idx), 1));
    return g;
  }
}

/** A square of a picture, [size] wide, facing +Z, or lying face up when [flat], for the paper shader. */
export function quad(size: number, flat: boolean): BufferGeometry {
  const h = size / 2;
  const m = new PaperMesh();
  if (flat) m.rect([-h, 0, h, h, 0, h, h, 0, -h, -h, 0, -h], FULL_UV, WHITE);
  else m.rect([-h, -h, 0, h, -h, 0, h, h, 0, -h, h, 0], FULL_UV, WHITE);
  return m.geometry();
}

export interface Shell {
  floor: BufferGeometry;
  walls: BufferGeometry;
  section: BufferGeometry;
  glass: BufferGeometry;
}

/**
 * Floor and walls of [map], with the front wall cut down to a lip, each side shaded once for the
 * world-space [light] it faces. They aren't part lists: big surfaces are painted textures (see
 * `docs/decisions/2026-10-31-models-are-crayon-part-lists.md`). Ported from `RoomModels.kt`.
 */
export function buildShell(map: Terrain, light: Vector3): Shell {
  const floor = new PaperMesh();
  const walls = new PaperMesh();
  const section = new PaperMesh();
  const front = map.height - 1;
  for (let y = 0; y < map.height; y++) for (let x = 0; x < map.width; x++) {
    const t = map.at(x, y);
    if (t === ".") floorTile(floor, map, x, y, faceShade(0, 1, 0, light));
    else if (t === "#") wallTile(walls, section, map, x, y, y === front ? constants.LIP : constants.WALL_HEIGHT, light);
  }
  const glass = new PaperMesh();
  const w = constants.WINDOW_WIDTH / 2;
  const h = (constants.WINDOW_TOP - constants.WINDOW_BOTTOM) / 2;
  glass.rect([-w, -h, 0, w, -h, 0, w, h, 0, -w, h, 0], FULL_UV, WHITE);
  return { floor: floor.geometry(), walls: walls.geometry(), section: section.geometry(), glass: glass.geometry() };
}

/** A floor tile, on a quarter-metre grid so the shade where floor meets wall can be laid on per vertex. */
function floorTile(m: PaperMesh, map: Terrain, x: number, y: number, lit: Rgba) {
  const n = FLOOR_GRID;
  for (let j = 0; j < n; j++) for (let i = 0; i < n; i++) {
    const x0 = x + i / n;
    const x1 = x + (i + 1) / n;
    const z0 = y + j / n;
    const z1 = y + (j + 1) / n;
    // Corners in the order the face wants them: (x0, z1), (x1, z1), (x1, z0), (x0, z0).
    const xs = [x0, x1, x1, x0];
    const zs = [z1, z1, z0, z0];
    const p: number[] = [];
    const uv: number[] = [];
    const c: Rgba[] = [];
    for (let k = 0; k < 4; k++) {
      p.push(xs[k], 0, zs[k]);
      uv.push(xs[k] / FLOOR_METRES, zs[k] / FLOOR_METRES);
      const near = clamp(nearestWall(map, xs[k], zs[k]) / CORNER_SHADE_REACH, 0, 1);
      c.push(times(mix(CORNER_SHADE, WHITE, near * near * (3 - 2 * near)), lit));
    }
    m.rect(p, uv, c);
  }
}

/** How far point ([px], [pz]) is from the nearest tile that isn't floor, looking one tile around. */
function nearestWall(map: Terrain, px: number, pz: number): number {
  let best = Infinity;
  const cx = Math.trunc(px);
  const cz = Math.trunc(pz);
  for (let tz = cz - 1; tz <= cz + 1; tz++) for (let tx = cx - 1; tx <= cx + 1; tx++) {
    if (map.at(tx, tz) === ".") continue;
    const dx = Math.max(tx - px, 0, px - (tx + 1));
    const dz = Math.max(tz - pz, 0, pz - (tz + 1));
    best = Math.min(best, Math.hypot(dx, dz));
  }
  return best;
}

/** The face of wall tile (x, y) toward (dx, dy): points along it (s, 0..1 left to right from the room) and back into the wall (d). */
class Face {
  readonly nx: number;
  readonly nz: number;
  readonly cx: number;
  readonly cz: number;
  readonly rx: number;
  readonly rz: number;
  constructor(x: number, y: number, dx: number, dy: number) {
    this.nx = dx;
    this.nz = dy;
    this.cx = x + 0.5 + dx * 0.5;
    this.cz = y + 0.5 + dy * 0.5;
    this.rx = dy;
    this.rz = -dx;
  }
  px(s: number, d: number) { return this.cx + this.rx * (s - 0.5) - this.nx * d; }
  pz(s: number, d: number) { return this.cz + this.rz * (s - 0.5) - this.nz * d; }
}

/**
 * A wall tile: a thin slab along each side that faces the floor, so the room reads as a cutaway
 * dollhouse. The face into the room carries the painted wall (or its bottom strip, on the low front
 * lip); the top and the other faces are the cut section. Slabs run [THICK] past both ends of the tile, so they meet at the corners.
 */
function wallTile(walls: PaperMesh, section: PaperMesh, map: Terrain, x: number, y: number, h: number, light: Vector3) {
  for (const [dx, dy] of SIDES) {
    if (map.at(x + dx, y + dy) !== ".") continue;
    slab(section, x, y, dx, dy, -THICK, 1 + THICK, THICK, h, light);
    const f = new Face(x, y, dx, dy);
    const a0 = [f.px(-THICK, 0), f.pz(-THICK, 0)];
    const a1 = [f.px(1 + THICK, 0), f.pz(1 + THICK, 0)];
    const u0 = (dx !== 0 ? f.pz(-THICK, 0) : f.px(-THICK, 0)) / WALL_METRES;
    const u1 = (dx !== 0 ? f.pz(1 + THICK, 0) : f.px(1 + THICK, 0)) / WALL_METRES;
    const v0 = 1;
    const v1 = 1 - h / constants.WALL_HEIGHT;
    walls.rect([a0[0], 0, a0[1], a1[0], 0, a1[1], a1[0], h, a1[1], a0[0], h, a0[1]], [u0, v0, u1, v0, u1, v1, u0, v1], faceShade(dx, 0, dy, light));
  }
}

/** The cut section of a wall slab behind the face of tile (x, y) toward (dx, dy): its top, back and two ends, [thick] deep, spanning [s0]..[s1] along the face. */
function slab(m: PaperMesh, x: number, y: number, dx: number, dy: number, s0: number, s1: number, thick: number, h: number, light: Vector3) {
  const f = new Face(x, y, dx, dy);
  const k = 1 / SECTION_METRES;
  const px = (s: number, d: number) => f.px(s, d);
  const pz = (s: number, d: number) => f.pz(s, d);
  m.rect(
    [px(s0, 0), h, pz(s0, 0), px(s1, 0), h, pz(s1, 0), px(s1, thick), h, pz(s1, thick), px(s0, thick), h, pz(s0, thick)],
    [px(s0, 0) * k, pz(s0, 0) * k, px(s1, 0) * k, pz(s1, 0) * k, px(s1, thick) * k, pz(s1, thick) * k, px(s0, thick) * k, pz(s0, thick) * k],
    faceShade(0, 1, 0, light),
  );
  const len = (s1 - s0) * k;
  const hk = h * k;
  const tk = thick * k;
  m.rect(
    [px(s1, thick), 0, pz(s1, thick), px(s0, thick), 0, pz(s0, thick), px(s0, thick), h, pz(s0, thick), px(s1, thick), h, pz(s1, thick)],
    [0, hk, len, hk, len, 0, 0, 0],
    faceShade(-f.nx, 0, -f.nz, light),
  );
  m.rect(
    [px(s1, 0), 0, pz(s1, 0), px(s1, thick), 0, pz(s1, thick), px(s1, thick), h, pz(s1, thick), px(s1, 0), h, pz(s1, 0)],
    [0, hk, tk, hk, tk, 0, 0, 0],
    faceShade(f.rx, 0, f.rz, light),
  );
  m.rect(
    [px(s0, thick), 0, pz(s0, thick), px(s0, 0), 0, pz(s0, 0), px(s0, 0), h, pz(s0, 0), px(s0, thick), h, pz(s0, thick)],
    [0, hk, tk, hk, tk, 0, 0, 0],
    faceShade(-f.rx, 0, -f.rz, light),
  );
}

/** The shade a side facing (nx, ny, nz) gets from the world [light]: the crayon shader's two tones, worked out once per side, since the paper shader does no lighting of its own. */
function faceShade(nx: number, ny: number, nz: number, light: Vector3): Rgba {
  const ndl = nx * light.x + ny * light.y + nz * light.z;
  const t = clamp((ndl + 0.12 + 0.03) / 0.06, 0, 1);
  return mix(SHADE, WHITE, t * t * (3 - 2 * t));
}
