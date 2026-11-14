import type { LifeState } from "../../../protocol/ts/protocol";
import type { Layer, Panel } from "./layer";
import type { Menu } from "./menu";
import { TABS, detail, empty, entries, stateColor, type Entry, type PageView, type Para } from "./pages";
import { COLORS, fit, font, lineHeight, roundRect, wrap, type Frame } from "./paper";
import { adjustable, isAction, value } from "./run";

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

const TAB_SCALE = 1.45;
const ENTRY_SCALE = 1.5;
const SMALL = 1.25;
const HINT_SCALE = 1.2;
/** How often the open card may redraw for the life changing; a key redraws it at once. */
const REFRESH = 0.5;

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
    this.card.place(f.x + this.x * u, f.y + MARGIN * u);
    // A card too narrow for it beside the tabs (a phone) goes without; the HUD's card has the clock.
    if (this.w < TABS_W + 520) return this.state.hide();

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
    this.state.place(f.x + (this.x + this.w - PAD - 520) * u, f.y + (MARGIN + PAD) * u);
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
    const hint = hintFor(menu, v);
    const shownList = list.slice(first, last).map((e) => [e.title, e.sub, e.mark, e.titleColor, e.subColor].join("|")).join("\n");
    const key = JSON.stringify([menu.tab, sel, first, menu.reading, menu.scroll, menu.confirm, list.length, shownList, rows, hint, u, W, H, this.layer.ratio]);
    if (key === this.cardKey) return;
    this.cardKey = key;

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
        ctx.fillStyle = t.id === menu.tab ? COLORS.ribbon : COLORS.tag;
        roundRect(ctx, tx * u, PAD * u, tw * u, TAB_H * u, 12 * u);
        ctx.fillStyle = t.id === menu.tab ? "#ffffff" : COLORS.ink;
        ctx.textBaseline = "middle";
        ctx.fillText(t.title, (tx + 20) * u, (PAD + TAB_H / 2 + 1) * u);
        tx += tw + 12;
      }
      ctx.textBaseline = "top";

      // The list, the pick filled and ringed while the list has the focus.
      for (let i = first; i < last; i++) {
        const e = list[i];
        const y = LIST_TOP + (i - first) * ENTRY_H;
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
        entryText(ctx, e, PAD + 30, y, u);
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
  }
}

/** The ring round the pick: a rounded outline [RING] units outside the rectangle. */
function ring(ctx: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, r: number, u: number) {
  const k = RING * u;
  ctx.fillStyle = COLORS.ribbon;
  roundRect(ctx, x - k, y - k, w + 2 * k, h + 2 * k, r + k);
}

function entryText(ctx: CanvasRenderingContext2D, e: Entry, x: number, y: number, u: number) {
  const w = (LIST_W - 40) * u;
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

function hintFor(menu: Menu, v: PageView): string {
  if (menu.confirm) return "OK again to confirm · any other key cancels";
  if (menu.reading) return "UP/DOWN scroll · OK or BACK back to the list · LEFT/RIGHT tabs";
  if (menu.tab === "run") {
    const row = menu.runRow(v);
    if (row === "play") return `OK ${value(row, v.life, v.settings, menu.replayPick).split(":")[0]} · UP/DOWN pick · LEFT/RIGHT tabs · BACK closes`;
    if (adjustable(row) && isAction(row)) return "LEFT/RIGHT pick the run · OK replays · UP/DOWN pick · BACK closes";
    if (isAction(row)) return `OK ${row === "signin" ? "(Enter) signs in" : row === "signout" ? "signs out" : row} · UP/DOWN pick · LEFT/RIGHT tabs · BACK closes`;
    return "LEFT/RIGHT change it · UP/DOWN pick · BACK closes (Run row: LEFT/RIGHT tabs)";
  }
  return "UP/DOWN pick · OK read it all · LEFT/RIGHT tabs · BACK closes";
}
