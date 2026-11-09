import type { DataTexture } from "three";
import { CrayonCanvas, arcPts, blobPath, linePts } from "../crayon/canvas";
import { hex, shade } from "../crayon/color";
import { INK } from "../crayon/renderer";
import { rng, type Rng } from "../crayon/noise";

export type Face = "neutral" | "happy" | "sad" | "tired" | "thinking" | "asleep";
const FACES: Face[] = ["neutral", "happy", "sad", "tired", "thinking", "asleep"];

/** The texture size in which the face and grain are drawn, and the scale they're kept at. */
export const DESIGN_W = 1024;
export const DESIGN_H = 512;
const TEX_SCALE = 0.5;
/** Where the face's own texture sits on the body's, in design units; the shader swaps it in there. */
export const FACE_X = 96;
export const FACE_Y = 148;
export const FACE_W = 320;
export const FACE_H = 160;

export const BODY = "#ffe3a1";
const PAPER_WHITE = hex("#fffdf8");
const BLUSH = hex("#ff9fb4");
const MOUTH = hex("#b05a6e");
const TONGUE = hex("#ff9aaa");
const SIDES = [-1, 1];

/** The body's colour, laid down in thousands of short crayon strokes a shade either side of it. */
export function paintGrain(): CrayonCanvas {
  const x = new CrayonCanvas(DESIGN_W * TEX_SCALE, DESIGN_H * TEX_SCALE, TEX_SCALE);
  const rand = rng(4242);
  x.fill(hex(BODY));
  const light = shade(BODY, 0.05);
  const dark = shade(BODY, -0.06, 0.03);
  for (let i = 0; i < 6500; i++) {
    const px = rand() * DESIGN_W;
    const py = rand() * DESIGN_H;
    const len = 8 + rand() * 20;
    const a = -0.7 + (rand() - 0.5) * 0.5;
    const c = rand() < 0.5 ? light : dark;
    const alpha = 0.3 + rand() * 0.35;
    const width = 1.5 + rand() * 2.2;
    x.stroke([px, py, px + Math.cos(a) * len, py + Math.sin(a) * len], width, c, alpha);
  }
  return x;
}

/** One small texture per expression, blink and boil frame, painted the first time it shows. */
export class Faces {
  private readonly painted = new Map<number, DataTexture>();

  constructor(private readonly grain: CrayonCanvas) {}

  get(face: Face, shut: boolean, boil: number): DataTexture {
    const f = FACES.indexOf(face);
    const b = shut ? 1 : 0;
    const key = f * 100 + b * 10 + boil;
    let t = this.painted.get(key);
    if (!t) {
      const x = new CrayonCanvas(FACE_W * TEX_SCALE, FACE_H * TEX_SCALE, TEX_SCALE, FACE_X, FACE_Y);
      x.copy(this.grain, FACE_X * TEX_SCALE, FACE_Y * TEX_SCALE);
      paintFace(x, face, shut, rng(1000 + boil * 97 + f * 13 + b * 5));
      t = x.texture();
      this.painted.set(key, t);
    }
    return t;
  }
}

/** Draws [face] in pen. The texture wraps once around him with the front at u = 0.25 (x = 256); left in the image is his right, as you look at him. */
function paintFace(x: CrayonCanvas, face: Face, blink: boolean, rand: Rng) {
  const cx = 256;
  const ey = 216;
  const gap = 64;
  const er = 29;
  const my = ey + 66;
  const happy = face === "happy";
  const br = happy ? 30 : 25;
  for (const s of SIDES) x.crayonFill(cx + s * 118, ey + 46, br, br * 0.75, BLUSH, rand, happy ? 0.85 : 0.6);
  const shut = blink || face === "asleep";
  for (const s of SIDES) {
    const ex = cx + s * gap;
    if (shut) x.penStroke(arcPts(ex, ey - 6, 24, 20, 160, rand), 8, INK, rand);
    else if (happy) x.penStroke(arcPts(ex, ey + 12, 24, 200, 340, rand), 9, INK, rand);
    else if (face === "thinking") inkEye(x, ex + 8, ey - 12, er, rand);
    else if (face === "tired") {
      x.clipRect(ex - 50, ey - 2, 100, 60);
      inkEye(x, ex, ey, er, rand);
      x.unclip();
      x.penStroke(linePts(ex - 33, ey - 2, ex + 33, ey - 2, rand, 2), 7, INK, rand);
    } else if (face === "sad") {
      inkEye(x, ex, ey + 6, er * 0.92, rand);
      x.penStroke(linePts(ex + s * 34, ey - 36, ex - s * 12, ey - 52, rand), 7, INK, rand);
    } else inkEye(x, ex, ey, er, rand);
  }
  switch (face) {
    case "happy": {
      const pts = arcPts(cx, my - 16, 30, 0, 180, rand, 0.03);
      x.fillPath(pts, MOUTH);
      x.clip(pts);
      x.fillPath(blobPath(cx, my + 14, 16, 12, rand), TONGUE);
      x.unclip();
      x.penStroke([...pts, pts[0], pts[1]], 6, INK, rand);
      break;
    }
    case "neutral": x.penStroke(arcPts(cx, my - 24, 24, 35, 145, rand), 7, INK, rand); break;
    case "sad": x.penStroke(arcPts(cx, my + 22, 24, 215, 325, rand), 7, INK, rand); break;
    case "tired": x.penStroke(linePts(cx - 16, my, cx + 16, my + 4, rand), 6.5, INK, rand); break;
    case "thinking": x.penStroke(linePts(cx + 2, my, cx + 32, my - 9, rand), 6.5, INK, rand); break;
    case "asleep": {
      const p = blobPath(cx, my, 11, 13, rand);
      x.fillPath(p, MOUTH);
      x.stroke(p, 5, INK);
      break;
    }
  }
}

function inkEye(x: CrayonCanvas, ex: number, ey: number, r: number, rand: Rng) {
  x.fillPath(blobPath(ex, ey, r * 0.92, r, rand, 0.04), INK);
  x.fillPath(blobPath(ex - r * 0.3, ey - r * 0.34, r * 0.36, r * 0.36, rand, 0.08), PAPER_WHITE);
  x.fillPath(blobPath(ex + r * 0.36, ey + r * 0.38, r * 0.13, r * 0.13, rand), PAPER_WHITE);
}
