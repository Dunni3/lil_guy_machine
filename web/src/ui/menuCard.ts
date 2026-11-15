import type { LifeState } from "../../../protocol/ts/protocol";
import type { Layer, Panel } from "./layer";
import type { Menu, Spot } from "./menu";
import { TABS, detail, empty, entries, stateColor, type Entry, type PageView, type Para } from "./pages";
import { COLORS, fit, font, lineHeight, roundRect, wrap, type Frame } from "./paper";
import { adjustable, isAction, value, type RunRow } from "./run";

// The card, in units of the app's frame (`GameMenu`): right of the HUD's needs card, so his needs
// stay in view, or over it in a window too narrow for both.
const LEFT = 520;
const NARROW = 1700;
const MARGIN = 36;
const PAD = 36;
const TAB_H = 52;
const LIST_TOP = PAD + TAB_H + 56;
const LIST_W = 440;
const ENTRY_H = 74;
/** About how wide the tabs run, for whether the run's state fits beside them. */
const TABS_W = 820;
const DETAIL_X = PAD + LIST_W + 56;
/** The pick is ringed this thick, in the ribbon's red, so it reads from across a room. */
const RING = 5;
/** The close button's size, at the card's top right (bottom right if the tabs reach it). */
const CLOSE = TAB_H;
/** A Run setting's ‹ and › buttons, at its row's right end: LEFT and RIGHT for a pointer. */
const ARROW = 46;
const ARROWS_W = 2 * ARROW + 14;
/** What a pointer is over, lighter than the pick. */
const HOVER_TAB = "#f0d39f";
const HOVER_ROW = "#fcefd8";

const TAB_SCALE = 1.45;
const ENTRY_SCALE = 1.5;
const SMALL = 1.25;
const HINT_SCALE = 1.2;
/** How often the open card may redraw for the life changing; a key redraws it at once. */
const REFRESH = 0.5;

/** A box on the card, in its units, where a pointer means [spot]. */
interface Hit {
  x: number;
  y: number;
  w: number;
  h: number;
  spot: Spot;
}

/**
 * The menu drawn as a paper card over the room: tabs, the list with the pick ringed, the detail
 * beside it and the keys at the bottom, on one [Panel] redrawn only when what it shows changed.
 * The run's state at the top right, which changes every game minute, is its own small panel.
 */
export class MenuCard {
  private readonly card: Panel;
  private readonly state: Panel;
  private cardKey = "";
  private stateKey = "";
  private since = Infinity;
  private lifeFrom: LifeState | null = null;
  /** Where a pointer can act, in card units, as last drawn: later ones are on top. */
  private hits: Hit[] = [];
  private ox = 0;
  private oy = 0;
  private u = 1;
  /** What the pointer is over, drawn lighter; set by the page, which redraws when it changes. */
  hover: Spot | null = null;
  /** A mouse has moved over the page, or a click or tap came: the hints say what clicks do. */
  mouse = false;

  constructor(private readonly layer: Layer) {
    this.card = layer.panel(20);
    this.state = layer.panel(21);
  }

  // Its place and size in the frame, set each frame.
  private x = LEFT;
  private w = 0;
  private h = 0;

  /** One frame: hidden while the menu's closed; [pressed] when a key just changed it. */
  update(menu: Menu, v: PageView | null, f: Frame, pressed: boolean, delta: number) {
    if (!menu.open || !v) {
      this.card.hide();
      this.state.hide();
      this.cardKey = "";
      return;
    }
    const u = f.u;
    const fw = f.w / u;
    this.x = fw >= NARROW ? LEFT : MARGIN;
    this.w = fw - this.x - MARGIN;
    this.h = f.h / u - 2 * MARGIN;
    this.since += delta;
    if (pressed || (v.life !== this.lifeFrom && this.since >= REFRESH) || !this.card.shown) {
      this.lifeFrom = v.life;
      this.since = 0;
      this.drawCard(menu, v, u);
    }
    this.ox = f.x + this.x * u;
    this.oy = f.y + MARGIN * u;
    this.u = u;
    this.card.place(this.ox, this.oy);
    // A card too narrow for it beside the tabs and the close button (a phone) goes without; the HUD's card has the clock.
    if (this.w < TABS_W + 520 + CLOSE + 16) return this.state.hide();

    const life = v.life;
    const clock = life.fast.clock;
    const text = life.run.state.toUpperCase() + (life.fast.held ? " · HELD" : "") + (clock ? ` · day ${clock.day} ${clock.time} · $${life.wallet.money}` : "");
    const key = `${text}/${u}/${this.layer.ratio}`;
    if (key !== this.stateKey) {
      this.stateKey = key;
      this.state.draw(520 * u, TAB_H * u, (ctx) => {
        ctx.font = font(SMALL, u);
        ctx.fillStyle = stateColor(life.run.state);
        ctx.textAlign = "right";
        ctx.textBaseline = "middle";
        ctx.fillText(text, 520 * u, (TAB_H / 2) * u);
      });
    }
    this.state.place(f.x + (this.x + this.w - PAD - CLOSE - 16 - 520) * u, f.y + (MARGIN + PAD) * u);
  }

  /** CSS pixels of wheel or drag to a notch over [spot]: a row over the list, a line of body text over the detail. */
  notch(spot: Spot): number {
    return (spot.kind === "detail" ? lineHeight(1.5, 1) : ENTRY_H) * this.u;
  }

  /**
   * What's at ([cx], [cy]) CSS pixels on the card as it's drawn: a [Spot], from the same layout the
   * drawing used; null off the card, or when it isn't shown.
   */
  hit(cx: number, cy: number): Spot | null {
    if (!this.card.shown) return null;
    const x = (cx - this.ox) / this.u;
    const y = (cy - this.oy) / this.u;
    if (x < 0 || y < 0 || x > this.w || y > this.h) return null;
    for (let i = this.hits.length - 1; i >= 0; i--) {
      const r = this.hits[i];
      if (x >= r.x && x < r.x + r.w && y >= r.y && y < r.y + r.h) return r.spot;
    }
    return { kind: "card" };
  }

  private drawCard(menu: Menu, v: PageView, u: number) {
    const W = this.w;
    const H = this.h;
    const BODY_BOTTOM = H - 84;
    const LIST_BOTTOM = BODY_BOTTOM - 20;
    const DETAIL_W = W - PAD - DETAIL_X;
    const list = entries(menu.tab, v);
    const sel = menu.index(list);
    const rows: Para[] = list.length ? detail(menu.tab, v, list[sel]) : [{ text: empty(menu.tab, v), scale: 1.5, color: COLORS.muted, indent: 0, gap: 0 }];
    const visible = Math.floor((LIST_BOTTOM - LIST_TOP) / ENTRY_H);
    const first = Math.min(Math.max(sel - Math.floor(visible / 2), 0), Math.max(0, list.length - visible));
    const last = Math.min(list.length, first + visible);
    const hint = hintFor(menu, v, this.mouse);
    const hover = this.hover;
    const hovered = (s: Spot) => hover !== null && JSON.stringify(hover) === JSON.stringify(s);
    const arrows = (e: Entry) => menu.tab === "run" && adjustable(e.key as RunRow);
    const shownList = list.slice(first, last).map((e) => [e.title, e.sub, e.mark, e.titleColor, e.subColor].join("|")).join("\n");
    const key = JSON.stringify([menu.tab, sel, first, menu.reading, menu.scroll, menu.confirm, list.length, shownList, rows, hint, hover, u, W, H, this.layer.ratio]);
    if (key === this.cardKey) return;
    this.cardKey = key;
    const hits: Hit[] = [];
    const spot = (x: number, y: number, w: number, h: number, s: Spot) => hits.push({ x, y, w, h, spot: s });
    spot(PAD - 10, LIST_TOP - 30, LIST_W + 10, LIST_BOTTOM - LIST_TOP + 60, { kind: "list" });
    spot(DETAIL_X - 14, LIST_TOP - 12, DETAIL_W + 24, BODY_BOTTOM - LIST_TOP + 18, { kind: "detail" });

    this.card.draw(W * u, H * u, (ctx) => {
      ctx.textBaseline = "top";
      ctx.fillStyle = COLORS.paperEdge;
      roundRect(ctx, 0, 0, W * u, H * u, 24 * u);
      ctx.fillStyle = COLORS.card;
      roundRect(ctx, 5 * u, 5 * u, (W - 10) * u, (H - 10) * u, 20 * u);

      // Tabs: tags along the top, the open one in the ribbon's red.
      ctx.font = font(TAB_SCALE, u, 600);
      let tx = PAD;
      for (const t of TABS) {
        const tw = ctx.measureText(t.title).width / u + 40;
        const s: Spot = { kind: "tab", tab: t.id };
        spot(tx - 6, PAD - 6, tw + 12, TAB_H + 12, s);
        ctx.fillStyle = t.id === menu.tab ? COLORS.ribbon : hovered(s) ? HOVER_TAB : COLORS.tag;
        roundRect(ctx, tx * u, PAD * u, tw * u, TAB_H * u, 12 * u);
        ctx.fillStyle = t.id === menu.tab ? "#ffffff" : COLORS.ink;
        ctx.textBaseline = "middle";
        ctx.fillText(t.title, (tx + 20) * u, (PAD + TAB_H / 2 + 1) * u);
        tx += tw + 12;
      }
      ctx.textBaseline = "top";

      // The close button, beside the run's state; where the tabs reach it, at the bottom right.
      const cx = W - PAD - CLOSE;
      const cy = tx + 12 <= cx ? PAD : H - 26 - CLOSE;
      closeButton(ctx, cx, cy, hovered({ kind: "close" }), u);
      spot(cx - 6, cy - 6, CLOSE + 12, CLOSE + 12, { kind: "close" });

      // The list, the pick filled and ringed while the list has the focus.
      for (let i = first; i < last; i++) {
        const e = list[i];
        const y = LIST_TOP + (i - first) * ENTRY_H;
        const s: Spot = { kind: "entry", key: e.key };
        spot(PAD - 10, y, LIST_W + 10, ENTRY_H, s);
        if (i !== sel && hovered(s)) {
          ctx.fillStyle = HOVER_ROW;
          roundRect(ctx, (PAD - 10) * u, (y + 2) * u, (LIST_W + 10) * u, (ENTRY_H - 4) * u, 12 * u);
        }
        if (i === sel) {
          if (!menu.reading) ring(ctx, (PAD - 10) * u, (y + 2) * u, (LIST_W + 10) * u, (ENTRY_H - 4) * u, 12 * u, u);
          ctx.fillStyle = menu.reading ? COLORS.barBg : COLORS.picked;
          roundRect(ctx, (PAD - 10) * u, (y + 2) * u, (LIST_W + 10) * u, (ENTRY_H - 4) * u, 12 * u);
        }
        if (e.mark) {
          ctx.fillStyle = e.mark;
          ctx.beginPath();
          ctx.arc((PAD + 10) * u, (y + 26) * u, 8 * u, 0, Math.PI * 2);
          ctx.fill();
        }
        const withArrows = arrows(e);
        entryText(ctx, e, PAD + 30, y, withArrows ? LIST_W - 40 - ARROWS_W : LIST_W - 40, u);
        if (withArrows) {
          for (const by of [-1, 1] as const) {
            const ax = PAD + LIST_W - 4 - ARROW - (by < 0 ? ARROW + 14 : 0);
            const ay = y + (ENTRY_H - ARROW) / 2;
            const a: Spot = { kind: "arrow", key: e.key, by };
            arrow(ctx, ax, ay, by, hovered(a), u);
            spot(ax - 6, y, ARROW + 12, ENTRY_H, a);
          }
        }
      }
      ctx.font = font(SMALL, u);
      ctx.fillStyle = COLORS.muted;
      ctx.textAlign = "right";
      if (first > 0) ctx.fillText(`${first} more above`, (PAD + LIST_W) * u, (LIST_TOP - 30) * u);
      if (last < list.length) ctx.fillText(`${list.length - last} more below`, (PAD + LIST_W) * u, (LIST_BOTTOM + 6) * u);
      ctx.textAlign = "left";
      if (!list.length) ctx.fillText("(nothing yet)", (PAD + 30) * u, (LIST_TOP + 14) * u);

      // The stitched rule between list and detail.
      ctx.fillStyle = COLORS.stitch;
      for (let dy = LIST_TOP; dy < BODY_BOTTOM; dy += 22) ctx.fillRect((DETAIL_X - 26) * u, dy * u, 3 * u, 12 * u);

      // The detail, from the scroll; the pane is filled and ringed while it has the focus.
      if (menu.reading) {
        const [x, y, w, h] = [(DETAIL_X - 14) * u, (LIST_TOP - 12) * u, (DETAIL_W + 24) * u, (BODY_BOTTOM - LIST_TOP + 18) * u];
        ring(ctx, x, y, w, h, 14 * u, u);
        ctx.fillStyle = COLORS.picked;
        roundRect(ctx, x, y, w, h, 14 * u);
      }
      const lines = layout(ctx, rows, DETAIL_W, u);
      const room = BODY_BOTTOM - LIST_TOP - 24;
      // The furthest the scroll may go: the last lines exactly fill the pane.
      let fill = 0;
      let most = lines.length;
      while (most > 0 && fill + lines[most - 1].height <= room) fill += lines[--most].height;
      menu.scroll = Math.min(Math.max(menu.scroll, 0), most);
      let used = 0;
      let end = menu.scroll;
      while (end < lines.length && used + lines[end].height <= room) used += lines[end++].height;
      let y = LIST_TOP;
      for (let k = menu.scroll; k < end; k++) {
        const l = lines[k];
        y += l.gap;
        ctx.font = font(l.scale, u, l.scale <= 1.2 ? 600 : 500);
        ctx.fillStyle = l.color;
        ctx.fillText(l.text, (DETAIL_X + l.indent) * u, y * u);
        y += l.height - l.gap;
      }
      ctx.font = font(SMALL, u);
      ctx.fillStyle = COLORS.muted;
      ctx.textAlign = "right";
      if (menu.scroll > 0) ctx.fillText("more above", (DETAIL_X + DETAIL_W) * u, (LIST_TOP - 30) * u);
      if (end < lines.length) ctx.fillText(menu.reading ? "more below · DOWN" : "more below · OK to read", (DETAIL_X + DETAIL_W) * u, (BODY_BOTTOM - 2) * u);

      // Keys.
      ctx.font = font(HINT_SCALE, u, 600);
      ctx.fillStyle = menu.confirm ? COLORS.accent : COLORS.muted;
      ctx.textAlign = "center";
      ctx.fillText(hint, (W / 2) * u, (H - 54) * u);
      ctx.textAlign = "left";
    });
    this.hits = hits;
  }
}

/** The ring round the pick: a rounded outline [RING] units outside the rectangle. */
function ring(ctx: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, r: number, u: number) {
  const k = RING * u;
  ctx.fillStyle = COLORS.ribbon;
  roundRect(ctx, x - k, y - k, w + 2 * k, h + 2 * k, r + k);
}

/** The × that closes the menu, filled in the ribbon's red under a pointer. */
function closeButton(ctx: CanvasRenderingContext2D, x: number, y: number, hover: boolean, u: number) {
  const r = CLOSE / 2;
  ctx.fillStyle = hover ? COLORS.ribbon : COLORS.tag;
  ctx.beginPath();
  ctx.arc((x + r) * u, (y + r) * u, r * u, 0, Math.PI * 2);
  ctx.fill();
  ctx.strokeStyle = hover ? "#ffffff" : COLORS.ink;
  ctx.lineWidth = 4.5 * u;
  ctx.lineCap = "round";
  const k = r * 0.38;
  ctx.beginPath();
  ctx.moveTo((x + r - k) * u, (y + r - k) * u);
  ctx.lineTo((x + r + k) * u, (y + r + k) * u);
  ctx.moveTo((x + r + k) * u, (y + r - k) * u);
  ctx.lineTo((x + r - k) * u, (y + r + k) * u);
  ctx.stroke();
}

/** A setting's ‹ ([by] -1) or › button: a tag with a chevron, red under a pointer. */
function arrow(ctx: CanvasRenderingContext2D, x: number, y: number, by: -1 | 1, hover: boolean, u: number) {
  ctx.fillStyle = hover ? COLORS.ribbon : COLORS.tag;
  roundRect(ctx, x * u, y * u, ARROW * u, ARROW * u, 10 * u);
  ctx.strokeStyle = hover ? "#ffffff" : COLORS.ink;
  ctx.lineWidth = 4.5 * u;
  ctx.lineCap = "round";
  ctx.lineJoin = "round";
  const cx = x + ARROW / 2 + by * 2;
  const cy = y + ARROW / 2;
  const k = ARROW * 0.17;
  ctx.beginPath();
  ctx.moveTo((cx - by * k * 0.6) * u, (cy - k) * u);
  ctx.lineTo((cx + by * k * 0.6) * u, cy * u);
  ctx.lineTo((cx - by * k * 0.6) * u, (cy + k) * u);
  ctx.stroke();
}

function entryText(ctx: CanvasRenderingContext2D, e: Entry, x: number, y: number, width: number, u: number) {
  const w = width * u;
  ctx.font = font(ENTRY_SCALE, u, 600);
  ctx.fillStyle = e.titleColor ?? COLORS.ink;
  ctx.fillText(fit(ctx, e.title, w), x * u, (y + 10) * u);
  ctx.font = font(SMALL, u);
  ctx.fillStyle = e.subColor ?? COLORS.muted;
  ctx.fillText(fit(ctx, e.sub, w), x * u, (y + 42) * u);
}

interface Line {
  text: string;
  scale: number;
  color: string;
  indent: number;
  gap: number;
  /** Units it takes, gap included. */
  height: number;
}

/** The detail's paragraphs as lines that fit its width. */
function layout(ctx: CanvasRenderingContext2D, rows: Para[], width: number, u: number): Line[] {
  const out: Line[] = [];
  for (const r of rows) {
    ctx.font = font(r.scale, u, r.scale <= 1.2 ? 600 : 500);
    wrap(ctx, r.text, (width - r.indent) * u).forEach((text, i) => {
      const gap = i === 0 ? r.gap : 0;
      out.push({ text, scale: r.scale, color: r.color, indent: r.indent, gap, height: gap + lineHeight(r.scale, 1) });
    });
  }
  return out;
}

function hintFor(menu: Menu, v: PageView, mouse: boolean): string {
  if (mouse) return mouseHint(menu, v);
  if (menu.confirm) return "OK again to confirm · any other key cancels";
  if (menu.reading) return "UP/DOWN scroll · OK or BACK back to the list · LEFT/RIGHT tabs";
  if (menu.tab === "run") {
    const row = menu.runRow(v);
    if (row === "play") return `OK ${value(row, v.life, v.settings, menu.replayPick).split(":")[0]} · UP/DOWN pick · LEFT/RIGHT tabs · BACK closes`;
    if (adjustable(row) && isAction(row)) return `LEFT/RIGHT pick the run · OK ${row === "runs" ? "goes back to it" : "replays"} · UP/DOWN pick · BACK closes`;
    if (isAction(row)) return `OK ${row === "signin" ? "(Enter) signs in" : row === "signout" ? "signs out" : row} · UP/DOWN pick · LEFT/RIGHT tabs · BACK closes`;
    return "LEFT/RIGHT change it · UP/DOWN pick · BACK closes (Run row: LEFT/RIGHT tabs)";
  }
  return "UP/DOWN pick · OK read it all · LEFT/RIGHT tabs · BACK closes";
}

/** The hints for a mouse: what clicks and the wheel do; the keys still work as they say on the TV. */
function mouseHint(menu: Menu, v: PageView): string {
  if (menu.confirm) return "Click it again to confirm · anything else cancels";
  if (menu.reading) return "Wheel scrolls · click the entry to go back to the list · × closes";
  if (menu.tab === "run") {
    const row = menu.runRow(v);
    if (adjustable(row) && isAction(row)) return `‹ › pick the run · click ${row === "runs" ? "Saved runs again to go back to it" : "Replay again to replay"} · × closes`;
    const settings = entries("run", v).some((e) => adjustable(e.key as RunRow));
    if (isAction(row)) return "Click the picked row again to do it" + (settings ? " · ‹ › change a setting" : "") + " · × closes";
    return "‹ › change it · click a row to pick it · × or a click outside closes";
  }
  return "Click a row to pick it, again to read it all · wheel scrolls · × or a click outside closes";
}
