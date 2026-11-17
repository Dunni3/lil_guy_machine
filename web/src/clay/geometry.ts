import {
  BufferAttribute, BufferGeometry, CapsuleGeometry, CircleGeometry, Curve, CylinderGeometry, LatheGeometry, Matrix4, PlaneGeometry, SphereGeometry,
  TorusGeometry, TubeGeometry, Vector2, Vector3,
} from "three";
import { RoundedBoxGeometry } from "three/examples/jsm/geometries/RoundedBoxGeometry.js";
import { mergeGeometries } from "three/examples/jsm/utils/BufferGeometryUtils.js";
import { X_AXIS, Y_AXIS, Z_AXIS, rotate, translate } from "../math";

/** One part of a model, as `parts/export/parts.json` has it (`Part.kt`); the clay fields are there only when not their defaults. */
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
  taper: number;
  cut?: number;
  hole?: number;
  arc?: number;
  glow?: number;
  opacity?: number;
  /** What an invented part is made of (wood, metal, copper_wire, glass), drawn by [ClayRenderer]; absent for the room's own furniture. */
  material?: string;
}

/** A soft edge where a part doesn't say: a bit rounder than crayon's, as clay is. */
const DEFAULT_ROUND = 0.05;
const DEG = Math.PI / 180;

/** [p]'s geometry centred on its box's middle, before it's placed: position, normal and uv. */
export function partGeometry(p: Part): BufferGeometry {
  if (p.material === "copper_wire") return wire(p);
  const small = Math.min(p.w, p.h, p.d);
  const round = p.round >= 0 ? p.round : Math.min(DEFAULT_ROUND, small * 0.3);
  switch (p.shape) {
    case "box": {
      const r = Math.max(0.001, Math.min(round, small / 2 - 0.001));
      return new RoundedBoxGeometry(p.w, p.h, p.d, 3, r);
    }
    case "sphere":
      return (p.cut ?? 1) < 1 ? dome(p.w, p.h, p.d, p.cut!) : new SphereGeometry(0.5, 28, 18).scale(p.w, p.h, p.d);
    case "cylinder":
      return turned(p, round);
    case "capsule": {
      const r = Math.min(p.w, p.d) / 2;
      return new CapsuleGeometry(r, Math.max(0.0001, p.h - 2 * r), 6, 18).scale(p.w / (2 * r), 1, p.d / (2 * r));
    }
    case "panel":
      return new PlaneGeometry(p.w, p.h);
    default:
      throw new Error(`a part of unknown shape: ${p.shape}`);
  }
}

/** A copper wire part's gauge, its radius in metres, whatever size the shape was designed: wire reads as wire. */
const WIRE = 0.009;
/** A coil's turns are this many gauges apart, and it has at most this many. */
const COIL_PITCH = 2.6;
const COIL_TURNS = 14;

/** A helix up y from -h/2 to h/2, [rx] by [rz] round, of [turns]. */
class Helix extends Curve<Vector3> {
  constructor(private readonly rx: number, private readonly rz: number, private readonly h: number, private readonly turns: number) {
    super();
  }

  override getPoint(t: number, target = new Vector3()): Vector3 {
    const a = t * this.turns * Math.PI * 2;
    return target.set(this.rx * Math.cos(a), (t - 0.5) * this.h, this.rz * Math.sin(a));
  }
}

/**
 * A part made of copper wire, drawn as wire rather than a block of copper its designed size, inside
 * its box: a ring (a cylinder with a big hole, flatter than it's wide) as a loop of wire; a long thin
 * shape (one side three times the next) as a straight length of it; anything else as a coil wound
 * round the shape's long axis (a cylinder's or capsule's own).
 */
function wire(p: Part): BufferGeometry {
  const g = Math.max(0.003, Math.min(WIRE, Math.min(p.w, p.h, p.d) / 2));
  if (p.shape === "cylinder" && (p.hole ?? 0) >= 0.5 && p.h < p.w / 2) {
    const r = Math.max(2 * g, p.w / 2 - g);
    return new TorusGeometry(r, g, 6, 32, Math.min(360, p.arc ?? 360) * DEG).rotateZ(-((Math.min(360, p.arc ?? 360) * DEG) / 2) + Math.PI / 2).rotateX(Math.PI / 2).scale(1, 1, p.d / p.w);
  }
  const sides: [number, "x" | "y" | "z"][] = [[p.w, "x"], [p.h, "y"], [p.d, "z"]];
  const sorted = [...sides].sort((a, b) => b[0] - a[0]);
  // Laid along y, then turned to its axis.
  const along = (geo: BufferGeometry, axis: "x" | "y" | "z") => (axis === "x" ? geo.rotateZ(Math.PI / 2) : axis === "z" ? geo.rotateX(Math.PI / 2) : geo);
  if (sorted[0][0] >= 3 * sorted[1][0]) return along(new CylinderGeometry(g, g, sorted[0][0], 6, 1), sorted[0][1]);
  const axis = p.shape === "cylinder" || p.shape === "capsule" ? "y" : sorted[0][1];
  const length = axis === "x" ? p.w : axis === "z" ? p.d : p.h;
  // The other two sides, in the order the coil's x and z take them once it's turned to its axis.
  const [a, b] = axis === "x" ? [p.h, p.d] : axis === "z" ? [p.w, p.h] : [p.w, p.d];
  const turns = Math.max(1.5, Math.min(COIL_TURNS, Math.floor((length - 2 * g) / (COIL_PITCH * g))));
  const helix = new Helix(Math.max(g, a / 2 - g), Math.max(g, b / 2 - g), Math.max(0, length - 2 * g), turns);
  return along(new TubeGeometry(helix, Math.ceil(turns * 20), g, 6, false), axis);
}

/** Where a part's geometry goes in the model: to the bottom centre, turned (Y, then X, then Z), then up by half its height. */
export function placement(p: Part, out = new Matrix4()): Matrix4 {
  out.makeTranslation(p.x, p.y, p.z);
  rotate(out, Y_AXIS, p.rotY);
  rotate(out, X_AXIS, p.rotX);
  rotate(out, Z_AXIS, p.rotZ);
  return translate(out, 0, p.h / 2, 0);
}

/**
 * The top [cut] of a w x (h / cut) x d ellipsoid, flat underneath, its box ([h] tall) centred on the
 * origin.
 */
function dome(w: number, h: number, d: number, cut: number): BufferGeometry {
  const theta = Math.acos(1 - 2 * cut);
  const full = h / cut;
  const cap = new SphereGeometry(1, 28, 12, 0, Math.PI * 2, 0, theta);
  const base = new CircleGeometry(Math.sin(theta), 28).rotateX(Math.PI / 2).translate(0, Math.cos(theta), 0);
  const g = mergeGeometries([cap, base].map((x) => (x.index ? x.toNonIndexed() : x)))!;
  // The cut is at y = cos(theta) on the unit sphere; it goes to the box's bottom.
  return g.scale(w / 2, full / 2, d / 2).translate(0, -h / 2 - (Math.cos(theta) * full) / 2, 0);
}

/**
 * A cylinder turned on a lathe, with rounded edges: tapered ([Part.taper]), with a hole for a ring or
 * tube ([Part.hole]), and only [Part.arc] degrees of it, centred on +Z. Made [w] across and squeezed to
 * [d], so the rounding is true across x. A texture lies on it from above, as on a dial.
 */
function turned(p: Part, round: number): BufferGeometry {
  const R = p.w / 2;
  const top = R * p.taper;
  const inner = R * (p.hole ?? 0);
  const h = p.h;
  const wall = Math.min(R, top) - inner;
  const r = Math.max(0, Math.min(round, h / 2 - 0.0005, wall / 2 - 0.0005));
  const pts: Vector2[] = [];
  const corner = (cx: number, cy: number, from: number, to: number) => {
    const n = r > 0.001 ? 5 : 0;
    for (let i = 0; i <= n; i++) {
      const a = from + ((to - from) * i) / Math.max(1, n);
      pts.push(new Vector2(cx + r * Math.cos(a), cy + r * Math.sin(a)));
    }
  };
  const y0 = -h / 2;
  const y1 = h / 2;
  // Up the outside and back down the inside, counter-clockwise as the lathe wants it facing out.
  if (inner > 0) corner(inner + r, y0 + r, Math.PI, Math.PI * 1.5);
  else pts.push(new Vector2(0, y0));
  corner(R - r, y0 + r, Math.PI * 1.5, Math.PI * 2);
  corner(top - r, y1 - r, 0, Math.PI / 2);
  if (inner > 0) {
    corner(inner + r, y1 - r, Math.PI / 2, Math.PI);
    pts.push(pts[0].clone());
  } else pts.push(new Vector2(0, y1));
  const arc = Math.min(360, p.arc ?? 360) * DEG;
  const segs = Math.max(8, Math.min(48, Math.ceil(p.w * 60 * (arc / (Math.PI * 2)))));
  const g = new LatheGeometry(pts, segs, -arc / 2, arc).scale(1, 1, p.d / p.w);
  if (p.texture) {
    // From above: u across x, v down z, the dial's top toward -z.
    const pos = g.getAttribute("position");
    const uv = g.getAttribute("uv");
    for (let i = 0; i < pos.count; i++) uv.setXY(i, pos.getX(i) / p.w + 0.5, 0.5 - pos.getZ(i) / p.d);
  }
  return g;
}

/** [geometries] (each with position, normal and uv) as one, with [colors] (linear RGB per geometry) as a colour attribute. */
export function merged(geometries: BufferGeometry[], colors: [number, number, number][]): BufferGeometry {
  const parts = geometries.map((g, i) => {
    const flat = g.index ? g.toNonIndexed() : g;
    for (const name of Object.keys(flat.attributes)) if (name !== "position" && name !== "normal" && name !== "uv") flat.deleteAttribute(name);
    const n = flat.getAttribute("position").count;
    const c = new Float32Array(n * 3);
    for (let k = 0; k < n; k++) c.set(colors[i], k * 3);
    flat.setAttribute("color", new BufferAttribute(c, 3));
    return flat;
  });
  const g = mergeGeometries(parts);
  if (!g) throw new Error("parts that can't be merged");
  g.computeBoundingBox();
  g.computeBoundingSphere();
  return g;
}
