import { Vector3, type Camera } from "three";
import type { Content, LifeState } from "../../../protocol/ts/protocol";
import type { Layer, Panel, Triangle } from "./layer";
import { COLORS, GROUP_COLORS, font, lineHeight, measure, roundRect, wrap } from "./paper";

/** How long the pointer rests on a thing before its label shows. */
const DWELL = 0.35;
const PAD = 14;
const MAX_W = 360;
const DOT = 9;

/** What the label says about one thing. */
export interface ThingInfo {
  name: string;
  group: string;
  /** For something he invented: why he made it, in his words. */
  why: string | null;
}

/** The name and group of thing [id] (of [kind]): his inventions by what he called them, the rest from the catalogue, else the id. */
export function thingInfo(id: string, kind: string, life: LifeState, content: Content): ThingInfo {
  const made = (life.shop.inventions ?? []).find((i) => i.id === id);
  if (made) return { name: made.name, group: made.group, why: made.why || null };
  const c = content.catalog.find((i) => i.id === id);
  return { name: c?.name ?? id.replace(/_/g, " "), group: c?.group ?? kind, why: null };
}

/**
 * A little paper tag over whatever the mouse rests on in the room, saying what it is, with a tail
 * down to the thing's top. Something he invented says so, and why he made it. Mouse only: a tap
 * opens the menu, as before.
 */
export class ThingLabel {
  private readonly panel: Panel;
  private readonly tail: Triangle;
  private readonly at = new Vector3();
  private key = "";
  private over: string | null = null;
  private rested = 0;

  constructor(private readonly layer: Layer) {
    this.panel = layer.panel(14);
    this.tail = layer.triangle(COLORS.bubble, 14);
  }

  /** [target] is what the pointer is over this frame (and its top in the world), or null. */
  update(target: { id: string; top: Vector3 } | null, life: LifeState, content: Content, camera: Camera, u: number, delta: number) {
    const id = target?.id ?? null;
    if (id !== this.over) {
      this.over = id;
      this.rested = 0;
    }
    this.rested += delta;
    const e = id ? life.things.entities.find((t) => t.id === id) : undefined;
    if (!target || !e || this.rested < DWELL) return this.hide();

    const info = thingInfo(e.id, e.kind, life, content);
    const key = `${JSON.stringify(info)}/${u}/${this.layer.ratio}`;
    if (key !== this.key) {
      this.key = key;
      this.paint(info, u);
    }
    const p = this.at.copy(target.top).project(camera);
    const tx = ((p.x + 1) / 2) * this.layer.width;
    const ty = ((1 - p.y) / 2) * this.layer.height;
    const w = this.panel.w;
    const h = this.panel.h;
    const x = Math.min(Math.max(tx - w / 2, 8), this.layer.width - w - 8);
    const y = Math.max(ty - 18 * u - h, 8);
    this.panel.place(x, y);
    const tip = Math.min(Math.max(tx, x + 18 * u), x + w - 18 * u);
    this.tail.set(tip - 9 * u, y + h - 1, tip + 9 * u, y + h - 1, tx, y + h + 12 * u);
  }

  hide() {
    this.panel.hide();
    this.tail.hide();
  }

  private paint(info: ThingInfo, u: number) {
    const ctx = measure();
    const pad = PAD * u;
    const dot = DOT * u;
    ctx.font = font(1.5, u, 700);
    const nameW = ctx.measureText(info.name).width + dot + 8 * u;
    const step = lineHeight(1.1, u);
    const tag = info.why ? "✦ he invented this" : info.group;
    ctx.font = font(1.1, u, 600);
    const tagW = ctx.measureText(tag).width;
    ctx.font = font(1.1, u, 500);
    const why = info.why ? wrap(ctx, `"${info.why}"`, MAX_W * u) : [];
    const whyW = why.length ? Math.max(...why.map((l) => ctx.measureText(l).width)) : 0;
    const w = Math.max(nameW, tagW, whyW) + 2 * pad;
    const nameH = lineHeight(1.5, u);
    const h = pad + nameH + step + (why.length ? 6 * u + why.length * step : 0) + pad;

    this.panel.draw(w, h, (c) => {
      c.fillStyle = COLORS.paperEdge;
      roundRect(c, 0, 0, w, h, 14 * u);
      c.fillStyle = COLORS.bubble;
      roundRect(c, 1.5 * u, 1.5 * u, w - 3 * u, h - 3 * u, 12.5 * u);
      c.textBaseline = "top";
      c.textAlign = "left";
      let y = pad;
      c.fillStyle = GROUP_COLORS[info.group] ?? COLORS.stitch;
      c.beginPath();
      c.arc(pad + dot / 2, y + nameH / 2, dot / 2, 0, Math.PI * 2);
      c.fill();
      c.font = font(1.5, u, 700);
      c.fillStyle = COLORS.ink;
      c.fillText(info.name, pad + dot + 8 * u, y + 2 * u);
      y += nameH;
      c.font = font(1.1, u, 600);
      c.fillStyle = info.why ? COLORS.dear : COLORS.muted;
      c.fillText(tag, pad, y + 2 * u);
      y += step + 6 * u;
      c.font = font(1.1, u, 500);
      c.fillStyle = COLORS.muted;
      why.forEach((l, i) => c.fillText(l, pad, y + i * step + 2 * u));
    });
  }
}
