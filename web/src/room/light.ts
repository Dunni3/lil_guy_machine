import { hex, mix, type Rgba } from "../crayon/color";

interface Key {
  hour: number;
  ambient: Rgba;
  sun: Rgba;
  sky: Rgba;
}

const key = (hour: number, ambient: string, sun: string, sky: string): Key => ({ hour, ambient: hex(ambient), sun: hex(sun), sky: hex(sky) });

const KEYS = [
  // Night is lamp-lit: warm and dim inside, the sky dark blue beyond the glass.
  key(0, "7a6670", "2a2028", "141a38"),
  key(5.5, "7a6670", "2a2028", "1c2448"),
  key(7, "c8aaa0", "8a6048", "f6b48c"),
  // A pale gold between dawn and day, so the sky doesn't pass through grey.
  key(8.2, "c8b8ac", "7a6450", "f8e0a8"),
  key(9.5, "c8c4b8", "6a665e", "9fd4f5"),
  key(16.5, "c8c4b8", "6a665e", "9fd4f5"),
  key(19, "c8a08c", "8a5030", "f08a5d"),
  key(21, "947a70", "3a2820", "2a2850"),
];

/** Ambient plus sun at midday (the 9:30 to 16:30 key), so the room's tint is white then. */
const DAY = { r: KEYS[4].ambient.r + KEYS[4].sun.r, g: KEYS[4].ambient.g + KEYS[4].sun.g, b: KEYS[4].ambient.b + KEYS[4].sun.b };

export interface Light {
  /** The room's light as a tint, white at midday. */
  tint: Rgba;
  /** The window's colour. */
  sky: Rgba;
  /** What's beyond the room, dimmed with the light. */
  background: Rgba;
}

const unit = (v: number) => Math.min(1, Math.max(0, v));

/**
 * Light through the day at [hour], interpolated between [KEYS]: warm and dim at night (a lamp's worth
 * of ambient), warm at dawn and dusk, bright and near-flat by day. Everything shades itself; the
 * room's light only tints it.
 */
export function lightAt(hour: number): Light {
  const h = ((hour % 24) + 24) % 24;
  let i = 0;
  for (let k = 0; k < KEYS.length; k++) if (KEYS[k].hour <= h) i = k;
  const a = KEYS[i];
  const b = KEYS[(i + 1) % KEYS.length];
  const span = b.hour - a.hour <= 0 ? b.hour - a.hour + 24 : b.hour - a.hour;
  const t = unit((h - a.hour) / span);
  const ambient = mix(a.ambient, b.ambient, t);
  const sun = mix(a.sun, b.sun, t);
  return {
    tint: { r: unit((ambient.r + sun.r) / DAY.r), g: unit((ambient.g + sun.g) / DAY.g), b: unit((ambient.b + sun.b) / DAY.b), a: 1 },
    sky: mix(a.sky, b.sky, t),
    background: { r: ambient.r * 0.22, g: ambient.g * 0.2, b: ambient.b * 0.24, a: 1 },
  };
}
