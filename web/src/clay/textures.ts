import { CanvasTexture, RepeatWrapping, SRGBColorSpace, type Texture } from "three";

/** His expressions, as the view picks them from his needs and what he's doing. */
export type Face = "neutral" | "happy" | "sad" | "tired" | "thinking" | "asleep";

/** A seeded random stream (mulberry32), so a texture paints the same every time. */
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

function paint(w: number, h: number, fn: (g: CanvasRenderingContext2D, w: number, h: number) => void, repeat = false): CanvasTexture {
  const c = document.createElement("canvas");
  c.width = w;
  c.height = h;
  fn(c.getContext("2d")!, w, h);
  const t = new CanvasTexture(c);
  t.colorSpace = SRGBColorSpace;
  t.anisotropy = 8;
  if (repeat) t.wrapS = t.wrapT = RepeatWrapping;
  return t;
}

/**
 * The room's textures in clay: flat and clean, as the Field Lab's are, painted once with the
 * browser's 2D canvas. The crayon textures (`CrayonCanvas`) are gone with the crayon look; his
 * paintings keep their own pixels (`art.ts`).
 */
export class ClayTextures {
  private readonly cache = new Map<string, Texture>();
  private readonly faces = new Map<string, Texture>();

  /** A texture a part list names, or null for one it doesn't know. */
  texture(name: string): Texture | null {
    let t = this.cache.get(name);
    if (t) return t;
    const make = PAINTERS[name];
    if (!make) return name === "face" ? this.face("neutral", false) : null;
    this.cache.set(name, (t = make()));
    return t;
  }

  /** Names [t] [name] for part lists, in place of any texture of that name before (the land's ground, painted from the world). */
  set(name: string, t: Texture) {
    const old = this.cache.get(name);
    if (old && old !== t) old.dispose();
    this.cache.set(name, t);
  }

  /** His face on his body's texture: [face], with his eyes [shut] for a blink. */
  face(face: Face, shut: boolean): Texture {
    const key = `${face}/${shut}`;
    let t = this.faces.get(key);
    if (!t) this.faces.set(key, (t = paint(1024, 512, (g, w, h) => paintFace(g, w, h, face, shut))));
    return t;
  }
}

const PAINTERS: Record<string, () => Texture> = {
  /**
   * The grain of an invention's wooden parts: near white, so the part's colour shows through, with
   * darker streaks along it and a knot or two. Small and repeated; each face of a part gets it once.
   */
  wood_grain: () => paint(128, 128, (g, w, h) => {
    const r = rng(11);
    g.fillStyle = "#fbf3e8";
    g.fillRect(0, 0, w, h);
    for (let i = 0; i < 26; i++) {
      const y0 = r() * h;
      g.strokeStyle = `rgba(120,78,44,${0.12 + r() * 0.16})`;
      g.lineWidth = 1 + r() * 2.5;
      g.beginPath();
      g.moveTo(0, y0);
      for (let x = 0; x <= w; x += 16) g.lineTo(x, y0 + Math.sin(x / 19 + i) * 2.2 + (r() - 0.5) * 1.5);
      g.stroke();
    }
    for (let k = 0; k < 2; k++) {
      g.strokeStyle = "rgba(110,70,40,.28)";
      g.lineWidth = 1.5;
      g.beginPath();
      g.ellipse(20 + r() * 88, 20 + r() * 88, 5 + r() * 4, 2.5 + r() * 2, 0, 0, Math.PI * 2);
      g.stroke();
    }
  }, true),
  /** Honey planks with a little grain, a texture to every 3 metres. */
  floor: () => paint(512, 512, (g, w, h) => {
    const r = rng(3);
    g.fillStyle = "#e4c8a2";
    g.fillRect(0, 0, w, h);
    for (let j = 0; j < 8; j++) {
      let x = -r() * 200;
      while (x < w) {
        const l = 150 + r() * 160;
        g.fillStyle = ["#e8cfab", "#e0c198", "#dcbb92", "#ead3b3"][Math.floor(r() * 4)];
        g.fillRect(x, j * 64, l - 3, 61);
        x += l;
      }
    }
    g.strokeStyle = "rgba(150,105,70,.16)";
    g.lineWidth = 1.5;
    for (let i = 0; i < 160; i++) {
      const x = r() * w;
      const y = r() * h;
      g.beginPath();
      g.moveTo(x, y);
      g.lineTo(x + 30 + r() * 60, y + (r() - 0.5) * 3);
      g.stroke();
    }
  }, true),
  /** Rings of coral, cream and sage with a fringe of stitches. */
  rug: () => paint(256, 256, (g) => {
    const ring = (r: number, c: string) => {
      g.fillStyle = c;
      g.beginPath();
      g.arc(128, 128, r, 0, 7);
      g.fill();
    };
    g.fillStyle = "#f6e7cf";
    g.fillRect(0, 0, 256, 256);
    ring(128, "#d9806f"); ring(118, "#f6e7cf"); ring(106, "#e9a08f"); ring(84, "#f6e7cf"); ring(76, "#8fb8a0"); ring(52, "#f6e7cf"); ring(40, "#e9a08f");
    g.strokeStyle = "#d9806f";
    g.lineWidth = 4;
    for (let i = 0; i < 28; i++) {
      const a = (i / 28) * Math.PI * 2;
      g.beginPath();
      g.moveTo(128 + Math.cos(a) * 90, 128 + Math.sin(a) * 90);
      g.lineTo(128 + Math.cos(a) * 100, 128 + Math.sin(a) * 100);
      g.stroke();
    }
  }),
  /** A view of hills under a low sun, on cream, with a margin. */
  poster: () => paint(160, 212, (g, w, h) => {
    g.fillStyle = "#fbf3e4";
    g.fillRect(0, 0, w, h);
    g.save();
    g.beginPath();
    g.roundRect(12, 12, w - 24, h - 52, 6);
    g.clip();
    g.fillStyle = "#f4dcc0";
    g.fillRect(0, 0, w, h);
    g.fillStyle = "#f2a48a";
    g.beginPath();
    g.arc(104, 62, 20, 0, 7);
    g.fill();
    g.fillStyle = "#9cc0a4";
    g.beginPath();
    g.ellipse(40, 168, 90, 62, 0, 0, 7);
    g.fill();
    g.fillStyle = "#7fae96";
    g.beginPath();
    g.ellipse(132, 176, 80, 50, 0, 0, 7);
    g.fill();
    g.restore();
    g.fillStyle = "#c9a78a";
    g.fillRect(40, h - 26, w - 80, 4);
  }),
  /** A cream dial with twelve ticks, the top one longer; the hands are the view's. */
  clock: () => paint(256, 256, (g) => {
    g.fillStyle = "#fffaf0";
    g.fillRect(0, 0, 256, 256);
    g.strokeStyle = "#4a3a4f";
    g.lineCap = "round";
    for (let i = 0; i < 12; i++) {
      const a = (i / 12) * Math.PI * 2;
      const long = i % 3 === 0;
      g.lineWidth = long ? 9 : 6;
      g.beginPath();
      g.moveTo(128 + Math.sin(a) * (long ? 82 : 90), 128 - Math.cos(a) * (long ? 82 : 90));
      g.lineTo(128 + Math.sin(a) * 104, 128 - Math.cos(a) * 104);
      g.stroke();
    }
    g.fillStyle = "#e58573";
    g.beginPath();
    g.arc(128, 128, 10, 0, 7);
    g.fill();
  }),
};

const BODY = "#ffe3a1";
const INK = "#4a3a4f";
const BLUSH = "rgba(255,159,180,.85)";
const MOUTH = "#b05a6e";
const TONGUE = "#ff9aaa";

/**
 * His face on the body's texture, which wraps round him once with the front at u = 0.25 and v from
 * the top. Sizes follow the Field Lab's 3D face (eyes 0.042 x 0.052 across his 0.33-wide body), so a
 * 1024 x 512 texture puts his eyes about 42 pixels tall.
 */
function paintFace(g: CanvasRenderingContext2D, w: number, h: number, face: Face, blink: boolean) {
  g.fillStyle = BODY;
  g.fillRect(0, 0, w, h);
  const cx = w * 0.25;
  const ey = h * 0.428;
  const gap = w * 0.0525;
  const my = h * 0.49;
  const happy = face === "happy";
  g.lineCap = g.lineJoin = "round";
  g.fillStyle = BLUSH;
  for (const s of [-1, 1]) {
    g.beginPath();
    g.ellipse(cx + s * w * 0.108, h * 0.508, happy ? 30 : 27, happy ? 17 : 15, 0, 0, 7);
    g.fill();
  }
  const shut = blink || face === "asleep";
  g.strokeStyle = INK;
  g.fillStyle = INK;
  for (const s of [-1, 1]) {
    const ex = cx + s * gap;
    if (shut) arc(g, ex, ey - 8, 19, 0.15 * Math.PI, 0.85 * Math.PI, 7);
    else if (happy) arc(g, ex, ey + 10, 19, 1.15 * Math.PI, 1.85 * Math.PI, 8);
    else if (face === "thinking") eye(g, ex + 8, ey - 8, 1);
    else if (face === "tired") {
      g.save();
      g.beginPath();
      g.rect(ex - 30, ey - 2, 60, 40);
      g.clip();
      eye(g, ex, ey, 1);
      g.restore();
      line(g, ex - 24, ey - 2, ex + 24, ey - 2, 7);
    } else if (face === "sad") {
      eye(g, ex, ey + 4, 0.92);
      line(g, ex + s * 26, ey - 30, ex - s * 10, ey - 40, 6);
    } else eye(g, ex, ey, 1);
  }
  switch (face) {
    case "happy":
      g.fillStyle = MOUTH;
      g.beginPath();
      g.arc(cx, my - 12, 22, 0, Math.PI);
      g.closePath();
      g.fill();
      g.save();
      g.clip();
      g.fillStyle = TONGUE;
      g.beginPath();
      g.ellipse(cx, my + 8, 12, 9, 0, 0, 7);
      g.fill();
      g.restore();
      break;
    case "neutral": g.strokeStyle = MOUTH; arc(g, cx, my - 18, 20, 0.2 * Math.PI, 0.8 * Math.PI, 10); break;
    case "sad": g.strokeStyle = MOUTH; arc(g, cx, my + 16, 18, 1.2 * Math.PI, 1.8 * Math.PI, 9); break;
    case "tired": g.strokeStyle = MOUTH; line(g, cx - 12, my, cx + 12, my + 3, 9); break;
    case "thinking": g.strokeStyle = MOUTH; line(g, cx + 2, my, cx + 24, my - 6, 9); break;
    case "asleep":
      g.fillStyle = MOUTH;
      g.beginPath();
      g.ellipse(cx, my, 8, 10, 0, 0, 7);
      g.fill();
      break;
  }
}

function eye(g: CanvasRenderingContext2D, x: number, y: number, k: number) {
  g.fillStyle = INK;
  g.beginPath();
  g.ellipse(x, y, 20 * k, 23 * k, 0, 0, 7);
  g.fill();
  g.fillStyle = "#ffffff";
  g.beginPath();
  g.arc(x + 7 * k, y - 9 * k, 7 * k, 0, 7);
  g.fill();
}

function arc(g: CanvasRenderingContext2D, x: number, y: number, r: number, a0: number, a1: number, width: number) {
  g.lineWidth = width;
  g.beginPath();
  g.arc(x, y, r, a0, a1);
  g.stroke();
}

function line(g: CanvasRenderingContext2D, x0: number, y0: number, x1: number, y1: number, width: number) {
  g.lineWidth = width;
  g.beginPath();
  g.moveTo(x0, y0);
  g.lineTo(x1, y1);
  g.stroke();
}
