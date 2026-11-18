import { Color, Vector3 } from "three";

/** The light at one hour: the sun's colour, strength and where it comes from (relative to what it lights), the sky's and the ground's, the background, and how far into night. */
export interface Day {
  sun: Color;
  sunIntensity: number;
  sunFrom: Vector3;
  sky: Color;
  ground: Color;
  skyIntensity: number;
  background: Color;
  /** 0 by day, 1 at night. */
  night: number;
  /** The window's glass seen from inside: sky blue by day, deep blue at night. */
  pane: Color;
}

/** hour, sun, its strength, sky, ground, the sky light's strength, background, night: `fable_field_lab.html`'s keys. */
type Key = [number, string, number, string, string, number, string, number];
const NIGHT: [string, number, string, string, number, string, number] =["#8f9ad6", 0.5, "#6f6fae", "#4f496e", 1.0, "#3a3450", 1];
const KEYS: Key[] = [
  [0, ...NIGHT],
  [5, ...NIGHT],
  [5.8, "#b9a0c8", 0.12, "#8f86b8", "#6a5f84", 1.05, "#6e5f86", 0.85],
  [6.6, "#ffc9a0", 1.3, "#f3d6c8", "#cdb9a2", 1.25, "#f3ddd0", 0.3],
  [9, "#fff1d8", 2.2, "#dcecf2", "#dccdaa", 1.5, "#f4ecdc", 0],
  [14, "#fff6e6", 2.35, "#e2eef2", "#dccfae", 1.55, "#f5eedf", 0],
  [17.5, "#ffd9a6", 2.1, "#f3ddc9", "#d6bf9f", 1.4, "#f6e2cc", 0.1],
  [19.4, "#ff9f7a", 1.05, "#c3a9c8", "#93819a", 1.15, "#d7a9a8", 0.7],
  [20.3, "#b9a0c8", 0.12, "#8a80b6", "#5f5880", 1.05, "#6b5c88", 0.95],
  [21, ...NIGHT],
  [24, ...NIGHT],
];

const a = new Color();
const b = new Color();
const mix = (out: Color, x: string, y: string, u: number) => out.copy(a.set(x)).lerp(b.set(y), u);
const lerp = (x: number, y: number, u: number) => x + (y - x) * u;

/** The light at [hour] (0 to 24, any number wraps), between the keys. */
export function dayAt(hour: number): Day {
  const h = ((hour % 24) + 24) % 24;
  let k = 0;
  while (k < KEYS.length - 2 && h >= KEYS[k + 1][0]) k++;
  const A = KEYS[k];
  const B = KEYS[k + 1];
  const u = Math.min(1, Math.max(0, (h - A[0]) / (B[0] - A[0])));
  const night = lerp(A[7], B[7], u);
  // The sun's arc from east (+x) at dawn to west at dusk; the moon, high and to the side, at night.
  const moon = h < 5.8 || h > 20.3;
  const arc = Math.min(0.96, Math.max(0.04, (h - 6) / 14)) * Math.PI;
  const sunFrom = moon ? new Vector3(14, 34, 26) : new Vector3(Math.cos(arc) * 34, 12 + Math.sin(arc) * 30, 8 + Math.sin(arc) * 22);
  return {
    sun: mix(new Color(), A[1], B[1], u),
    sunIntensity: lerp(A[2], B[2], u),
    sunFrom,
    sky: mix(new Color(), A[3], B[3], u),
    ground: mix(new Color(), A[4], B[4], u),
    skyIntensity: lerp(A[5], B[5], u),
    background: mix(new Color(), A[6], B[6], u),
    night,
    pane: mix(new Color(), "#cfe6f2", "#4a4a80", night),
  };
}
