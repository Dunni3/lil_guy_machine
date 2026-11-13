/**
 * The HUD's and menu's look, from `RoomView.kt` and `GameMenu.kt`: paper cards, ink, pastel need
 * bars and swatches. Sizes are in the app's frame, 1920 x 1080, where its font at scale 1 is 15 px;
 * [unit] turns them into CSS pixels for the window.
 */
export const COLORS = {
  card: "#fff8ee",
  paperEdge: "#e8cfb2",
  ink: "#3a2a20",
  muted: "#8a7464",
  code: "#4f6a3a",
  accent: "#c0504a",
  failed: "#c03a2a",
  dear: "#b5653a",
  fullness: "#6cc070",
  fullnessInk: "#3f8a44",
  barBg: "#e8dccb",
  stitch: "#e7a99b",
  ribbon: "#e07f74",
  ribbonDark: "#b9605a",
  picked: "#fde9c6",
  tag: "#f6e3b8",
  tagDear: "#d98a7a",
  string: "#c0504a",
  energy: "#f5b942",
  mood: "#6fa8e6",
  stimulation: "#c58ae0",
  bubble: "#fffdf6",
  banner: "#2a1e18",
  hint: "rgba(255,255,255,0.53)",
};

/** A swatch per kind of thing, in the room's pastels. */
export const GROUP_COLORS: Record<string, string> = {
  food: "#9fd49a", supply: "#ffe3a1", furniture: "#dba77c", record: "#f4a3ad", book: "#8fb0ec", instrument: "#f0b46e", decor: "#c2e6a8",
};

export const FRAME_W = 1920;
export const FRAME_H = 1080;
/** The app's font is 15 px at scale 1. */
const FONT_PX = 15;
const FAMILY = `ui-rounded, "SF Pro Rounded", "Nunito", system-ui, -apple-system, "Segoe UI", Roboto, sans-serif`;

/** Where the HUD is laid out, in CSS pixels, and [u], CSS pixels per unit of the app's frame. */
export interface Frame {
  x: number;
  y: number;
  w: number;
  h: number;
  u: number;
}

/**
 * The HUD's frame in a window [width] x [height]: the whole window, at the app's frame fitted to it
 * but no smaller than a phone can read; or for [broadcast], the 16:9 video frame centred in it.
 */
export function frameFor(width: number, height: number, broadcast: boolean): Frame {
  if (broadcast) {
    const w = Math.min(width, (height * FRAME_W) / FRAME_H);
    const h = (w * FRAME_H) / FRAME_W;
    return { x: (width - w) / 2, y: (height - h) / 2, w, h, u: w / FRAME_W };
  }
  return { x: 0, y: 0, w: width, h: height, u: Math.max(Math.min(width / FRAME_W, height / FRAME_H), Math.min(width / 1000, 0.5)) };
}

let measuring: CanvasRenderingContext2D | null = null;

/** A context for measuring text, for sizing a panel before drawing it. */
export function measure(): CanvasRenderingContext2D {
  return (measuring ??= document.createElement("canvas").getContext("2d")!);
}

/** The canvas font for the app's text [scale], at [u] CSS pixels a unit. */
export function font(scale: number, u: number, weight = 500): string {
  return `${weight} ${(FONT_PX * scale * u).toFixed(2)}px ${FAMILY}`;
}

/** The height of a line at [scale]: the app's font is about 17 units a line at scale 1. */
export function lineHeight(scale: number, u: number): number {
  return 17.5 * scale * u;
}

export function roundRect(ctx: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, r: number) {
  const rr = Math.min(r, w / 2, h / 2);
  ctx.beginPath();
  ctx.roundRect(x, y, w, h, rr);
  ctx.fill();
}

/** Model text in plain characters, as the app's bitmap font needs them; kept for parity in the wrapped widths. */
export function plain(text: string): string {
  return text.replace(/—/g, " - ").replace(/–/g, "-").replace(/[‘’]/g, "'").replace(/[“”]/g, '"').replace(/…/g, "...");
}

const wrapped = new Map<string, string[]>();
const WRAP_CACHE = 600;

/** [text] broken into lines no wider than [width] in [ctx]'s current font; cached, since lists are laid out often. */
export function wrap(ctx: CanvasRenderingContext2D, text: string, width: number): string[] {
  const key = `${ctx.font}/${width.toFixed(1)}/${text}`;
  const hit = wrapped.get(key);
  if (hit) return hit;
  if (wrapped.size > WRAP_CACHE) wrapped.clear();
  const out: string[] = [];
  for (const paragraph of plain(text).split("\n")) {
    let line = "";
    for (const word of paragraph.split(" ")) {
      if (!word) continue;
      const next = line ? `${line} ${word}` : word;
      if (line && ctx.measureText(next).width > width) {
        out.push(line);
        line = word;
      } else line = next;
    }
    out.push(line);
  }
  wrapped.set(key, out);
  return out;
}

/** [text] on one line, cut with "..." to fit [width]. */
export function fit(ctx: CanvasRenderingContext2D, text: string, width: number): string {
  const t = plain(text).replace(/\n/g, " ");
  if (ctx.measureText(t).width <= width) return t;
  let lo = 0;
  let hi = t.length;
  while (lo < hi) {
    const mid = (lo + hi + 1) >> 1;
    if (ctx.measureText(t.slice(0, mid) + "...").width <= width) lo = mid;
    else hi = mid - 1;
  }
  return t.slice(0, lo).trimEnd() + "...";
}
