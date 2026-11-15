import { BufferAttribute, BufferGeometry } from "three";
import type { Part } from "../clay/geometry";
import type { RectView } from "../../../protocol/ts/protocol";
import type { RoomDef } from "./room";

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

/** A square of a picture, [size] wide and centred on the origin, facing +Z, or lying face up when [flat]; v = 0 at the picture's top. */
export function quad(size: number, flat: boolean): BufferGeometry {
  const h = size / 2;
  const pos = flat ? [-h, 0, h, h, 0, h, h, 0, -h, -h, 0, -h] : [-h, -h, 0, h, -h, 0, h, h, 0, -h, h, 0];
  const n = flat ? [0, 1, 0] : [0, 0, 1];
  const g = new BufferGeometry();
  g.setAttribute("position", new BufferAttribute(new Float32Array(pos), 3));
  g.setAttribute("normal", new BufferAttribute(new Float32Array([...n, ...n, ...n, ...n]), 3));
  g.setAttribute("uv", new BufferAttribute(new Float32Array([0, 1, 1, 1, 1, 0, 0, 0]), 2));
  g.setIndex([0, 1, 2, 2, 3, 0]);
  return g;
}

const CREAM = "#f6ead6";
const TRIM = "#fffaf0";
const SILL = "#e7d6bd";
const INSIDE = "#f8dccd";
const SOIL = "#cfa987";
const DEEP = "#b78d70";
/** The walls' thickness, and the plinth's lip beyond them. */
const WALL = 0.25;
const LEDGE = 0.12;

const part = (shape: string, x: number, y: number, z: number, w: number, h: number, d: number, color: string, round = 0.04, extra: Partial<Part> = {}): Part => ({
  shape, x, y, z, w, h, d, color, round, rotX: 0, rotY: 0, rotZ: 0, texture: null, taper: 1, ...extra,
});
/** A rounded box from (x0, y0, z0) to (x1, y1, z1). */
const span = (x0: number, y0: number, z0: number, x1: number, y1: number, z1: number, color: string, round = 0.04) =>
  part("box", (x0 + x1) / 2, y0, (z0 + z1) / 2, x1 - x0, y1 - y0, z1 - z0, color, round);

/**
 * The room as a diorama, in the Field Lab's cutaway: a plinth of soil under a floor of planks, the
 * back and left walls standing full height (plaster outside, blush inside, a white baseboard), and
 * the right and front walls, nearer the camera, cut down to a low sill so the room shows. [at] is the
 * room on the map, its walls included: its outer ring is wall, and its inside is the floor. A doorway
 * in the front wall (the x of its tile in [doors]) is a gap in the sill. The window's frame is here;
 * its glass is [paneParts], drawn apart so its colour follows the sky.
 */
export function shellParts(at: RectView, room: RoomDef, doors: number[] = []): Part[] {
  const x0 = at.x0 + room.backWall;
  const x1 = at.x1;
  const z0 = at.y0 + room.backWall;
  const z1 = at.y1;
  const wh = room.wallHeight;
  const out: Part[] = [];
  // The plinth: a lip of plaster round the walls' feet, soil under it, and darker soil under that.
  out.push(span(x0 - WALL - LEDGE, -0.18, z0 - WALL - LEDGE, x1 + WALL + LEDGE, 0.0, z1 + WALL + LEDGE, SILL, 0.06));
  out.push(span(x0 - WALL - LEDGE + 0.06, -0.55, z0 - WALL - LEDGE + 0.06, x1 + WALL + LEDGE - 0.06, -0.17, z1 + WALL + LEDGE - 0.06, SOIL, 0.08));
  out.push(span(x0 - WALL + 0.15, -0.85, z0 - WALL + 0.15, x1 + WALL - 0.15, -0.54, z1 + WALL - 0.15, DEEP, 0.1));
  // The floor, its planks a texture to every 3 metres (the view sets the repeat).
  out.push(part("panel", (x0 + x1) / 2, 0.005, z1, x1 - x0, z1 - z0, 0, "#ffffff", 0, { rotX: -90, texture: "floor" }));
  // The back and left walls, full height.
  out.push(span(x0 - WALL, 0, z0 - WALL, x1, wh, z0, CREAM));
  out.push(span(x0 - WALL, 0, z0, x0, wh, z1, CREAM));
  out.push(span(x0, 0, z0, x1, wh - 0.04, z0 + 0.03, INSIDE, 0.004));
  out.push(span(x0, 0, z0, x0 + 0.03, wh - 0.04, z1, INSIDE, 0.004));
  out.push(span(x0, 0, z0 + 0.03, x1, 0.16, z0 + 0.08, TRIM, 0.01));
  out.push(span(x0 + 0.03, 0, z0, x0 + 0.08, 0.16, z1, TRIM, 0.01));
  // The right and front walls, cut down to a sill; the front one stops at each doorway, with a step out of it.
  out.push(span(x1, 0, z0 - WALL, x1 + WALL, room.lip, z1 + WALL, CREAM));
  out.push(span(x1 - 0.02, room.lip - 0.03, z0 - WALL, x1 + WALL + 0.04, room.lip + 0.03, z1 + WALL + 0.04, SILL, 0.03));
  let from = x0 - WALL;
  for (const d of [...doors].sort((a, b) => a - b)) {
    out.push(span(from, 0, z1, d, room.lip, z1 + WALL, CREAM));
    out.push(span(from - 0.02, room.lip - 0.03, z1 - 0.02, d, room.lip + 0.03, z1 + WALL + 0.04, SILL, 0.03));
    out.push(span(d + 0.05, -0.02, z1 + WALL, d + 0.95, 0.06, z1 + WALL + 0.45, SILL, 0.03));
    from = d + 1;
  }
  out.push(span(from, 0, z1, x1, room.lip, z1 + WALL, CREAM));
  out.push(span(from - 0.02, room.lip - 0.03, z1 - 0.02, x1, room.lip + 0.03, z1 + WALL + 0.04, SILL, 0.03));
  // The window's white frame, cross bars and sill; its glass is apart ([paneParts]).
  const win = { ...room.window, x: at.x0 + room.window.x };
  const ww = win.width;
  const wy = win.bottom;
  const wt = win.top - win.bottom;
  const zf = z0 + 0.03;
  out.push(part("box", win.x, wy - 0.08, zf + 0.03, ww + 0.16, wt + 0.16, 0.06, TRIM, 0.03));
  out.push(part("box", win.x, wy, zf + 0.06, 0.045, wt, 0.06, TRIM, 0.01));
  out.push(part("box", win.x, wy + wt / 2 - 0.022, zf + 0.06, ww, 0.045, 0.06, TRIM, 0.01));
  out.push(part("box", win.x, wy - 0.14, zf + 0.09, ww + 0.3, 0.07, 0.2, TRIM, 0.02));
  return out;
}

/** The window's glass, in the room at [at]: one box, in a material the view keeps the colour of the sky. */
export function paneParts(at: RectView, room: RoomDef): Part[] {
  const win = room.window;
  return [part("box", at.x0 + win.x, win.bottom, at.y0 + room.backWall + 0.03 + 0.05, win.width, win.top - win.bottom, 0.04, "#ffffff", 0.01)];
}
