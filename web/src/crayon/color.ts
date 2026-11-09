export interface Rgba {
  r: number;
  g: number;
  b: number;
  a: number;
}

/** "#rrggbb" or "rrggbbaa", to 0..1 channels. */
export function hex(s: string): Rgba {
  const h = s.startsWith("#") ? s.slice(1) : s;
  const ch = (i: number) => parseInt(h.slice(i, i + 2), 16) / 255;
  return { r: ch(0), g: ch(2), b: ch(4), a: h.length >= 8 ? ch(6) : 1 };
}

export const WHITE: Rgba = { r: 1, g: 1, b: 1, a: 1 };

/** sRGB to linear, as the crayon shader works. */
export function linear(c: Rgba): Rgba {
  return { r: c.r ** 2.2, g: c.g ** 2.2, b: c.b ** 2.2, a: c.a };
}

export function mix(a: Rgba, b: Rgba, t: number): Rgba {
  return { r: a.r + (b.r - a.r) * t, g: a.g + (b.g - a.g) * t, b: a.b + (b.b - a.b) * t, a: a.a + (b.a - a.a) * t };
}

export function times(a: Rgba, b: Rgba): Rgba {
  return { r: a.r * b.r, g: a.g * b.g, b: a.b * b.b, a: a.a * b.a };
}

/** [base] made lighter by [dl] and more saturated by [ds], in HSL. */
export function shade(base: string, dl: number, ds = 0): Rgba {
  const c = hex(base);
  const mx = Math.max(c.r, c.g, c.b);
  const mn = Math.min(c.r, c.g, c.b);
  let l = (mx + mn) / 2;
  let s = 0;
  let h = 0;
  if (mx !== mn) {
    const d = mx - mn;
    s = l > 0.5 ? d / (2 - mx - mn) : d / (mx + mn);
    if (mx === c.r) h = (c.g - c.b) / d + (c.g < c.b ? 6 : 0);
    else if (mx === c.g) h = (c.b - c.r) / d + 2;
    else h = (c.r - c.g) / d + 4;
    h /= 6;
  }
  s = Math.min(1, Math.max(0, s + ds));
  l = Math.min(1, Math.max(0, l + dl));
  const q = l < 0.5 ? l * (1 + s) : l + s - l * s;
  const p = 2 * l - q;
  const hue = (t0: number) => {
    const t = t0 < 0 ? t0 + 1 : t0 > 1 ? t0 - 1 : t0;
    if (t < 1 / 6) return p + (q - p) * 6 * t;
    if (t < 0.5) return q;
    if (t < 2 / 3) return p + (q - p) * (2 / 3 - t) * 6;
    return p;
  };
  return { r: hue(h + 1 / 3), g: hue(h), b: hue(h - 1 / 3), a: 1 };
}
