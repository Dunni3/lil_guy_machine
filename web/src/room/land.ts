import { CanvasTexture, SRGBColorSpace, type Texture } from "three";
import type { WorldView } from "../../../protocol/ts/protocol";
import type { Part } from "../clay/geometry";

/**
 * The land round the house, as the Field Lab draws it: a slab of soil under the lot, its top painted
 * once from the world's ground letters (grass, paths, bare ground, flowers, water), and a tree on
 * every `T`. The rooms stand on it, drawn by the shell.
 */

/** Pixels of ground texture per tile. */
const PPU = 32;
const SOIL = "#cfa987";
const DEEP = "#b78d70";
const GROUND: Record<string, string> = {
  ",": "#b4d69c",
  "=": "#ecd6ae",
  ":": "#dedfa8",
  "*": "#bcd9a0",
  "~": "#9fd0d6",
  T: "#a6cc92",
};
const LEAVES = ["#7fb58a", "#6fa58f", "#93c493"];
const PINES = ["#5f9c80", "#4f8f7c"];
const BLOSSOM = ["#f3bccb", "#f8d3dc", "#eaa9bd"];
const TRUNK = "#a98464";

/** True on a tile of land: anything but the sea (rooms count, they stand on the land). */
export const onLand = (world: WorldView, x: number, y: number): boolean => (world.rows[y]?.[x] ?? " ") !== " ";

/** A seeded random stream (mulberry32), so the land paints the same every time. */
function rng(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const part = (shape: string, x: number, y: number, z: number, w: number, h: number, d: number, color: string, extra: Partial<Part> = {}): Part => ({
  shape, x, y, z, w, h, d, color, round: 0.04, rotX: 0, rotY: 0, rotZ: 0, texture: null, taper: 1, ...extra,
});

/**
 * The ground's top as one texture over the whole map: each tile's colour, softened so paths and the
 * clearing have round edges, with grass flecks, flowers and ripples; off the land it's clear (the
 * material cuts it out), so the coast follows the land's tiles.
 */
export function groundTexture(world: WorldView): Texture {
  const W = world.width * PPU;
  const H = world.height * PPU;
  const tiles = document.createElement("canvas");
  tiles.width = W;
  tiles.height = H;
  const t = tiles.getContext("2d")!;
  world.rows.forEach((row, y) => [...row].forEach((c, x) => {
    // Under the house the ground is grass; the rooms' floors cover it.
    const color = c === " " ? null : (GROUND[c] ?? GROUND[","]);
    if (!color) return;
    t.fillStyle = color;
    t.fillRect(x * PPU, y * PPU, PPU, PPU);
  }));
  const c = document.createElement("canvas");
  c.width = W;
  c.height = H;
  const g = c.getContext("2d")!;
  g.filter = `blur(${PPU * 0.18}px)`;
  g.drawImage(tiles, 0, 0);
  g.filter = "none";
  const r = rng(11);
  // Soft patches of lighter and darker grass, as the sketch's.
  for (let i = 0; i < world.width * world.height * 0.5; i++) {
    g.fillStyle = ["rgba(255,255,220,.10)", "rgba(120,170,120,.09)", "rgba(200,225,150,.11)"][i % 3];
    g.beginPath();
    g.ellipse(r() * W, r() * H, PPU * (0.4 + r() * 1.4), PPU * (0.25 + r() * 0.8), r() * 3, 0, 7);
    g.fill();
  }
  world.rows.forEach((row, y) => [...row].forEach((ch, x) => {
    const cx = (x + 0.5) * PPU;
    const cy = (y + 0.5) * PPU;
    if (ch === "*") {
      for (let k = 0; k < 4; k++) {
        g.fillStyle = ["#f6c4cf", "#ffe3a1", "#fffaf0", "#cdbaf0"][Math.floor(r() * 4)];
        g.beginPath();
        g.arc(cx + (r() - 0.5) * PPU * 0.8, cy + (r() - 0.5) * PPU * 0.8, PPU * 0.09, 0, 7);
        g.fill();
      }
    } else if (ch === "=") {
      g.fillStyle = "rgba(190,160,130,.25)";
      for (let k = 0; k < 2; k++) {
        g.beginPath();
        g.arc(cx + (r() - 0.5) * PPU * 0.7, cy + (r() - 0.5) * PPU * 0.7, PPU * 0.05, 0, 7);
        g.fill();
      }
    } else if (ch === "~") {
      g.strokeStyle = "rgba(255,255,255,.35)";
      g.lineWidth = 2;
      g.beginPath();
      g.arc(cx + (r() - 0.5) * PPU * 0.4, cy, PPU * 0.22, Math.PI * 1.1, Math.PI * 1.9);
      g.stroke();
    }
  }));
  // Clear off the land: keep only where the tiles are, softened the same way.
  const mask = document.createElement("canvas");
  mask.width = W;
  mask.height = H;
  const m = mask.getContext("2d")!;
  m.fillStyle = "#000";
  world.rows.forEach((row, y) => [...row].forEach((ch, x) => {
    if (ch !== " ") m.fillRect(x * PPU, y * PPU, PPU, PPU);
  }));
  g.globalCompositeOperation = "destination-in";
  g.filter = `blur(${PPU * 0.3}px)`;
  g.drawImage(mask, 0, 0);
  g.filter = "none";
  g.globalCompositeOperation = "source-over";
  const tex = new CanvasTexture(c);
  tex.colorSpace = SRGBColorSpace;
  tex.anisotropy = 8;
  tex.userData.cutout = true;
  return tex;
}

/** The ground's top (textured "ground") and the soil under it: one box of soil per run of land in a row, stepped like a diorama's plinth. */
export function landParts(world: WorldView): Part[] {
  const out: Part[] = [];
  const W = world.width;
  const H = world.height;
  out.push(part("panel", W / 2, -0.01, H, W, H, 0, "#ffffff", { rotX: -90, texture: "ground", round: 0 }));
  world.rows.forEach((_, y) => {
    let x = 0;
    while (x < W) {
      if (!onLand(world, x, y)) {
        x++;
        continue;
      }
      const from = x;
      while (x < W && onLand(world, x, y)) x++;
      // Inset a little under the painted coast, whose edge is soft.
      out.push(part("box", (from + x) / 2, -0.5, y + 0.5, x - from - 0.2, 0.48, 0.8, SOIL, { round: 0.08 }));
      out.push(part("box", (from + x) / 2, -0.8, y + 0.5, Math.max(0.2, x - from - 0.6), 0.32, 0.6, DEEP, { round: 0.08 }));
    }
  });
  return out;
}

/** A tree on every `T`: a round one, a pine or a blossom, sized and turned by its tile so the forest isn't uniform. */
export function treeParts(world: WorldView): Part[] {
  const out: Part[] = [];
  world.rows.forEach((row, y) => [...row].forEach((ch, x) => {
    if (ch !== "T") return;
    const r = rng(x * 131 + y * 17);
    const s = 0.85 + r() * 0.5;
    const cx = x + 0.5 + (r() - 0.5) * 0.3;
    const cz = y + 0.5 + (r() - 0.5) * 0.3;
    const kind = r();
    out.push(part("cylinder", cx, 0, cz, 0.24 * s, 0.85 * s, 0.24 * s, TRUNK, { taper: 0.65 }));
    if (kind < 0.5) {
      const c = LEAVES[Math.floor(r() * LEAVES.length)];
      out.push(part("sphere", cx, 0.62 * s, cz, 1.36 * s, 1.3 * s, 1.36 * s, c));
      out.push(part("sphere", cx + 0.3 * s, 1.2 * s, cz + 0.12 * s, 0.9 * s, 0.86 * s, 0.9 * s, "#9ccf9a"));
      out.push(part("sphere", cx - 0.3 * s, 1.14 * s, cz - 0.14 * s, 0.84 * s, 0.8 * s, 0.84 * s, c));
    } else if (kind < 0.8) {
      const c = PINES[Math.floor(r() * PINES.length)];
      for (let i = 0; i < 3; i++) {
        out.push(part("cylinder", cx, (0.55 + i * 0.5) * s, cz, (1.32 - i * 0.3) * s, 0.85 * s, (1.32 - i * 0.3) * s, i === 2 ? "#74ad90" : c, { taper: 0.02, round: 0.02 }));
      }
    } else {
      out.push(part("sphere", cx, 0.65 * s, cz, 1.2 * s, 1.2 * s, 1.2 * s, BLOSSOM[0]));
      out.push(part("sphere", cx + 0.28 * s, 1.1 * s, cz + 0.1 * s, 0.84 * s, 0.84 * s, 0.84 * s, BLOSSOM[1]));
      out.push(part("sphere", cx - 0.26 * s, 1.05 * s, cz - 0.1 * s, 0.8 * s, 0.8 * s, 0.8 * s, BLOSSOM[2]));
    }
  }));
  return out;
}
