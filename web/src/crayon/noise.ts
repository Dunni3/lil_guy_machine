export type Rng = () => number;

/** Seeded random numbers (mulberry32), so a seed draws the same lumps and strokes as the Kotlin app and the workshop. */
export function rng(start: number): Rng {
  let seed = start | 0;
  return () => {
    seed = (seed + 0x6d2b79f5) | 0;
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return Math.fround(((t ^ (t >>> 14)) >>> 0) / 4294967296);
  };
}

/** Smooth noise on 3D points from a few random sine waves, roughly -1..1. */
export function noise3(seed: number, waves = 5, freq = 4): (x: number, y: number, z: number) => number {
  const r = rng(seed);
  const dx: number[] = [], dy: number[] = [], dz: number[] = [], f: number[] = [], p: number[] = [], a: number[] = [];
  for (let i = 0; i < waves; i++) {
    const x = r() * 2 - 1;
    const y = r() * 2 - 1;
    const z = r() * 2 - 1;
    const l = Math.sqrt(x * x + y * y + z * z);
    dx.push(x / l); dy.push(y / l); dz.push(z / l);
    f.push(freq * (0.6 + r() * 0.8) * (1 + i * 0.35));
    p.push(r() * Math.PI * 2);
    a.push(1 / (1 + i * 0.6));
  }
  const total = a.reduce((s, v) => s + v, 0);
  return (x, y, z) => {
    let s = 0;
    for (let i = 0; i < waves; i++) s += a[i] * Math.sin((dx[i] * x + dy[i] * y + dz[i] * z) * f[i] + p[i]);
    return s / total;
  };
}
