import { Vector3, type PerspectiveCamera } from "three";
import type { Content, LifeState } from "../../../protocol/ts/protocol";
import type { Layer, Panel, Triangle } from "./layer";
import { owned, shopItems } from "./pages";
import { COLORS, GROUP_COLORS, font, lineHeight, measure, roundRect, wrap, type Frame } from "./paper";

/** How long his newest thought stays over his head, and how much of it. */
const THOUGHT_SECONDS = 9;
const THOUGHT_CHARS = 150;

const MARGIN = 36;
/** Broadcast keeps everything inside the title-safe area of a 16:9 frame (5% in from each edge). */
const SAFE_X = 96;
const SAFE_Y = 54;
const CARD_W = 440;
const CARD_H = 270;
const BAR_X = 120;
const BAR_W = 280;
const BAR_H = 18;
const BAR_PITCH = 34;
const BUBBLE_SCALE = 1.6;
const BUBBLE_PAD = 16;
const BUBBLE_MAX_W = 440;

const SHOP_W = 640;
const SHOP_ROWS = 17;
const SHOP_HEADER = 104;
const SHOP_PITCH = 46;
const SHOP_FOOTER = 64;

const ACTIVE = new Set(["executing", "validated", "submitted"]);

/** The safe area's edges in CSS pixels. */
interface Box {
  left: number;
  top: number;
  right: number;
  bottom: number;
}

/** What the room says about him this frame, for the bubble and the card. */
export interface Speech {
  head: Vector3;
  asleep: boolean;
  thinking: boolean;
  announced: string | null;
}

export interface HudInput {
  life: LifeState;
  content: Content;
  speech: Speech;
  /** The piece he's at, for the card's "painting · stroke 3 of 9". */
  making: { canvas: boolean } | null;
  /** The run's state and what OK does, across the bottom; null when there's nothing to say. */
  banner: string | null;
  /** "OK for the menu", at the top right; null for none. */
  hint: string | null;
  menuOpen: boolean;
}

/**
 * The HUD over the room, ported from `RoomView.kt`: the needs card (clock, money, bars, what he's
 * doing), his speech bubble anchored over his head, the banner, the hint, and the mail-order
 * catalogue that opens by itself while he's off to buy something. Each is a [Panel], redrawn only
 * when its text or bars change; the bubble moves with him every frame without redrawing.
 *
 * Laid out in units of the app's 1920 x 1080 frame ([Frame]); broadcast keeps it to a 16:9
 * frame's title-safe area.
 */
export class Hud {
  private readonly card: Panel;
  private readonly bubble: Panel;
  private readonly tail: Triangle;
  private readonly banner: Panel;
  private readonly hint: Panel;
  private readonly shop: Panel;
  private readonly keys = { card: "", bubble: "", banner: "", hint: "", shop: "" };
  private readonly head = new Vector3();

  private run = "";
  private thoughtSeq: number | null = null;
  private thought: string | null = null;
  private thoughtAge = 0;

  constructor(private readonly layer: Layer, private readonly broadcast: boolean) {
    this.card = layer.panel(10);
    this.shop = layer.panel(11);
    this.bubble = layer.panel(12);
    this.tail = layer.triangle(COLORS.bubble, 12);
    this.banner = layer.panel(13);
    this.hint = layer.panel(13);
  }

  update(input: HudInput, camera: PerspectiveCamera, f: Frame, delta: number) {
    const mx = this.broadcast ? SAFE_X : MARGIN;
    const my = this.broadcast ? SAFE_Y : MARGIN;
    // The safe area's edges, in CSS pixels.
    const box = { left: f.x + mx * f.u, top: f.y + my * f.u, right: f.x + f.w - mx * f.u, bottom: f.y + f.h - my * f.u };
    this.updateThought(input.life, delta);
    this.drawCard(input, f.u);
    this.card.place(box.left, box.top);
    this.drawShop(input, f.u, box);
    this.drawBubble(input, camera, f.u, box);
    this.drawBanner(input.banner, f.u, box);
    this.drawHint(input.hint, f.u, box);
  }

  /** A new thought shows for a while; the ones there when the page joined don't. */
  private updateThought(life: LifeState, delta: number) {
    const thoughts = life.logs.thoughts;
    const newest = thoughts[thoughts.length - 1];
    if (life.run.run_id !== this.run) {
      this.run = life.run.run_id;
      this.thoughtSeq = this.run && newest ? newest.seq : null;
      this.thought = null;
    }
    if (newest && newest.seq !== this.thoughtSeq) {
      this.thoughtSeq = newest.seq;
      const t = newest.text;
      this.thought = t === null ? null : t.length > THOUGHT_CHARS ? t.slice(0, THOUGHT_CHARS).trimEnd() + "..." : t;
      this.thoughtAge = 0;
    }
    this.thoughtAge += delta;
    if (this.thoughtAge > THOUGHT_SECONDS) this.thought = null;
  }

  /** Clock and money, the need bars, and what he's doing: at a piece, how far, and a note when the clock waits for it. */
  private drawCard(input: HudInput, u: number) {
    const life = input.life;
    const fast = life.fast;
    const needs = fast.needs;
    const clock = fast.clock ? `Day ${fast.clock.day} · ${fast.clock.time}   $${life.wallet.money}` : `$${life.wallet.money}`;
    const up = life.easel.underway;
    const making = input.making && up ? `${input.making.canvas ? "painting" : "drawing"} · stroke ${life.easel.shown} of ${up.of}` : null;
    const doing = fast.doing
      ? fast.doing.replace(/_/g, " ") + (fast.progress !== null ? ` · ${Math.trunc(fast.progress * 100)}%` : "") + (fast.fast_forward > 1 ? `   >> ${fast.fast_forward}x` : "")
      : null;
    const line = making ?? doing ?? (fast.thinking_ms !== null ? "deciding..." : "idle");
    const waits = making !== null && fast.held;
    const bars = needs ? [needs.energy, needs.fullness, needs.mood, needs.stimulation] : [];
    // Bars to the nearest pixel at this size, so they redraw only when they visibly move.
    const widths = bars.map((b) => Math.round(Math.max(BAR_H, BAR_W * Math.min(Math.max(b / 100, 0), 1)) * u));
    const key = [clock, line, waits, widths.join(","), u, this.layer.ratio].join("|");
    if (key === this.keys.card) return;
    this.keys.card = key;
    const colors = [COLORS.energy, COLORS.fullness, COLORS.mood, COLORS.stimulation];
    this.card.draw(CARD_W * u, CARD_H * u, (ctx) => {
      ctx.fillStyle = COLORS.card;
      roundRect(ctx, 0, 0, CARD_W * u, CARD_H * u, 18 * u);
      ctx.textBaseline = "top";
      ctx.font = font(2.0, u, 600);
      ctx.fillStyle = COLORS.ink;
      ctx.fillText(clock, 28 * u, 20 * u);
      ["energy", "food", "mood", "fun"].forEach((label, i) => {
        const y = 86 + i * BAR_PITCH - BAR_H;
        ctx.font = font(1.3, u);
        ctx.fillStyle = COLORS.muted;
        ctx.textBaseline = "middle";
        ctx.fillText(label, 28 * u, (y + BAR_H / 2) * u);
        if (i >= bars.length) return;
        ctx.fillStyle = COLORS.barBg;
        roundRect(ctx, BAR_X * u, y * u, BAR_W * u, BAR_H * u, (BAR_H / 2) * u);
        ctx.fillStyle = colors[i];
        roundRect(ctx, BAR_X * u, y * u, widths[i], BAR_H * u, (BAR_H / 2) * u);
      });
      ctx.textBaseline = "alphabetic";
      if (waits) {
        ctx.font = font(1.15, u);
        ctx.fillStyle = COLORS.muted;
        ctx.fillText("the clock waits while he works", 28 * u, (CARD_H - 64) * u);
      }
      ctx.font = font(1.6, u, 600);
      ctx.fillStyle = COLORS.ink;
      ctx.fillText(line, 28 * u, (CARD_H - 30) * u);
    });
  }

  /** What he's saying: his own words, "z z z" asleep, a finished piece's title, his newest thought, or "..." while he thinks. */
  private drawBubble(input: HudInput, camera: PerspectiveCamera, u: number, box: Box) {
    const s = input.speech;
    const agent = input.life.fast.agents[0];
    const text = agent?.saying ?? (s.asleep ? "z z z" : s.announced ?? this.thought ?? (s.thinking ? "..." : null));
    if (text === null) {
      this.bubble.hide();
      this.tail.hide();
      return;
    }
    const key = `${text}/${u}/${this.layer.ratio}`;
    if (key !== this.keys.bubble) {
      this.keys.bubble = key;
      const ctx = measure();
      ctx.font = font(BUBBLE_SCALE, u, 600);
      const lines = wrap(ctx, text, BUBBLE_MAX_W * u);
      const w = Math.max(...lines.map((l) => ctx.measureText(l).width)) + 2 * BUBBLE_PAD * u;
      const step = lineHeight(BUBBLE_SCALE, u);
      const h = lines.length * step + 2 * BUBBLE_PAD * u;
      this.bubble.draw(w, h, (c) => {
        c.fillStyle = COLORS.bubble;
        roundRect(c, 0, 0, w, h, 16 * u);
        c.font = font(BUBBLE_SCALE, u, 600);
        c.fillStyle = COLORS.ink;
        c.textAlign = "center";
        c.textBaseline = "top";
        lines.forEach((l, i) => c.fillText(l, w / 2, BUBBLE_PAD * u + i * step + 2 * u));
      });
    }
    // His head on screen, and the bubble above it, kept inside the frame.
    const p = this.head.copy(s.head).project(camera);
    const hx = ((p.x + 1) / 2) * this.layer.width;
    const hy = ((1 - p.y) / 2) * this.layer.height;
    const w = this.bubble.w;
    const h = this.bubble.h;
    const x = Math.min(Math.max(hx - w / 2, box.left), box.right - w);
    const y = Math.max(hy - 24 * u - h, box.top);
    this.bubble.place(x, y);
    const tip = Math.min(Math.max(hx, x + 20 * u), x + w - 20 * u);
    this.tail.set(tip - 12 * u, y + h - 1, tip + 12 * u, y + h - 1, hx, y + h + 16 * u);
  }

  private drawBanner(text: string | null, u: number, box: Box) {
    if (text === null) return this.banner.hide();
    const key = `${text}/${u}/${this.layer.ratio}`;
    if (key !== this.keys.banner) {
      this.keys.banner = key;
      const m = measure();
      m.font = font(1.8, u, 600);
      const w = m.measureText(text).width + 56 * u;
      const h = 27 * 1.8 * u + 10 * u;
      this.banner.draw(w, h, (c) => {
        c.fillStyle = COLORS.banner;
        roundRect(c, 0, 0, w, h, 14 * u);
        c.font = font(1.8, u, 600);
        c.fillStyle = "#ffffff";
        c.textAlign = "center";
        c.textBaseline = "middle";
        c.fillText(text, w / 2, h / 2 + u);
      });
    }
    this.banner.place((box.left + box.right) / 2 - this.banner.w / 2, box.bottom - this.banner.h);
  }

  private drawHint(text: string | null, u: number, box: Box) {
    if (text === null) return this.hint.hide();
    const key = `${text}/${u}/${this.layer.ratio}`;
    if (key !== this.keys.hint) {
      this.keys.hint = key;
      this.hint.draw(700 * u, 30 * u, (c) => {
        c.font = font(1.2, u, 600);
        c.fillStyle = COLORS.hint;
        c.textAlign = "right";
        c.textBaseline = "top";
        c.fillText(text, 700 * u, 0);
      });
    }
    this.hint.place(box.right - 700 * u, box.top);
  }

  /**
   * While he's off to buy something, the catalogue opens by itself at that item: what the shop
   * sells, known things first, [SHOP_ROWS] to a page, on paper with scalloped edges, a ribbon and swing tags.
   */
  private drawShop(input: HudInput, u: number, box: Box) {
    const agent = input.life.fast.agents[0];
    const intent = agent?.intent;
    const buying = !input.menuOpen && intent?.type === "buy" && intent.item && ACTIVE.has(agent?.status?.state ?? "") ? intent.item : null;
    const all = buying ? shopItems(input.life, input.content) : [];
    if (!buying || !all.length) return this.shop.hide();
    const pages: (typeof all)[] = [];
    for (let i = 0; i < all.length; i += SHOP_ROWS) pages.push(all.slice(i, i + SHOP_ROWS));
    const at = Math.max(0, pages.findIndex((p) => p.some((x) => x.item.id === buying)));
    const items = pages[at];
    const money = input.life.wallet.money;
    const key = JSON.stringify([buying, at, money, items.map((x) => [x.item.id, x.state.owned, x.state.affordable, x.state.taught_by]), u, this.layer.ratio]);
    const h = SHOP_HEADER + Math.min(all.length, SHOP_ROWS) * SHOP_PITCH + SHOP_FOOTER;
    if (key !== this.keys.shop) {
      this.keys.shop = key;
      this.shop.draw((SHOP_W + 30) * u, (h + 30) * u, (c) => {
        c.translate(15 * u, 15 * u);
        c.scale(u, u);
        paperCard(c, h);
        c.font = font(1.7, 1, 600);
        c.fillStyle = "#ffffff";
        c.textAlign = "center";
        c.textBaseline = "middle";
        c.fillText("The Mail-Order Catalogue", SHOP_W / 2, 74 - 25 + 1);
        items.forEach(({ item, state }, i) => {
          const cy = SHOP_HEADER + i * SHOP_PITCH + SHOP_PITCH / 2 - 4;
          const hidden = state.taught_by !== null;
          if (item.id === buying) {
            c.fillStyle = COLORS.picked;
            roundRect(c, 26, cy - SHOP_PITCH / 2 + 2, SHOP_W - 52, SHOP_PITCH - 4, 12);
          }
          c.fillStyle = hidden ? COLORS.barBg : GROUP_COLORS[item.group] ?? COLORS.barBg;
          c.beginPath();
          c.arc(50, cy, 12, 0, Math.PI * 2);
          c.fill();
          c.textAlign = "left";
          c.font = font(1.45, 1, 600);
          c.fillStyle = hidden ? COLORS.muted : COLORS.ink;
          c.fillText(item.name, 74, cy);
          c.font = font(1.15, 1);
          if (hidden) {
            c.fillStyle = COLORS.muted;
            c.textAlign = "right";
            c.fillText("learn of it in " + (state.taught_by ?? "").split(",")[0], SHOP_W - 30, cy);
            return;
          }
          // A swing tag: string, card, hole.
          const tx = SHOP_W - 270;
          const has = owned(item, state);
          c.strokeStyle = COLORS.string;
          c.lineWidth = 2;
          c.beginPath();
          c.moveTo(tx - 18, cy - 12);
          c.lineTo(tx + 4, cy - 2);
          c.stroke();
          c.fillStyle = state.affordable || has ? COLORS.tag : COLORS.tagDear;
          roundRect(c, tx, cy - 16, 104, 32, 9);
          c.fillStyle = COLORS.card;
          c.beginPath();
          c.arc(tx + 10, cy, 4, 0, Math.PI * 2);
          c.fill();
          c.fillStyle = state.affordable || has ? COLORS.ink : "#ffffff";
          c.textAlign = "center";
          c.fillText(item.pack > 1 ? `${item.pack} for $${item.price}` : `$${item.price}`, tx + 58, cy + 1);
          if (has) {
            // A little tick beside the tag.
            c.strokeStyle = COLORS.fullness;
            c.lineWidth = 5;
            c.lineCap = "round";
            c.beginPath();
            c.moveTo(tx + 128, cy);
            c.lineTo(tx + 137, cy + 10);
            c.lineTo(tx + 154, cy - 12);
            c.stroke();
          }
          const note = item.id === buying ? "on its way!" : has ? (item.stackable ? `${state.owned} at home` : "") : !state.affordable ? "saving up" : "";
          c.fillStyle = COLORS.muted;
          c.textAlign = "left";
          c.fillText(note, tx + (has ? 164 : 122), cy + 1);
        });
        c.font = font(1.15, 1);
        c.fillStyle = COLORS.muted;
        c.textAlign = "center";
        c.fillText(`in his purse: $${money}` + (pages.length > 1 ? ` · page ${at + 1} of ${pages.length}` : ""), SHOP_W / 2, h - 40);
      });
    }
    this.shop.place(box.right - (SHOP_W + 15) * u, box.top + (44 - 15) * u);
  }
}

/** The catalogue's paper, [h] tall: scalloped top and bottom, stitching inside, and a ribbon with notched ends. */
function paperCard(c: CanvasRenderingContext2D, h: number) {
  c.fillStyle = COLORS.paperEdge;
  roundRect(c, -4, -4, SHOP_W + 8, h + 8, 22);
  c.fillStyle = COLORS.card;
  roundRect(c, 0, 0, SHOP_W, h, 18);
  for (let sx = 22; sx < SHOP_W - 16; sx += 26) {
    for (const sy of [0, h]) {
      c.beginPath();
      c.arc(sx, sy, 11, 0, Math.PI * 2);
      c.fill();
    }
  }
  c.fillStyle = COLORS.stitch;
  for (let dx = 18; dx < SHOP_W - 30; dx += 22) {
    c.fillRect(dx, 13, 12, 3);
    c.fillRect(dx, h - 16, 12, 3);
  }
  for (let dy = 28; dy < h - 28; dy += 22) {
    c.fillRect(14, dy, 3, 12);
    c.fillRect(SHOP_W - 17, dy, 3, 12);
  }
  const ry = 74 - 50;
  c.fillStyle = COLORS.ribbonDark;
  c.fillRect(34, ry + 12, 36, 44);
  c.fillRect(SHOP_W - 70, ry + 12, 36, 44);
  c.fillStyle = COLORS.card;
  c.beginPath();
  c.moveTo(34, ry + 56);
  c.lineTo(34, ry + 12);
  c.lineTo(50, ry + 34);
  c.fill();
  c.beginPath();
  c.moveTo(SHOP_W - 34, ry + 56);
  c.lineTo(SHOP_W - 34, ry + 12);
  c.lineTo(SHOP_W - 50, ry + 34);
  c.fill();
  c.fillStyle = COLORS.ribbon;
  roundRect(c, 56, ry, SHOP_W - 112, 50, 8);
}
