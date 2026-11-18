import { Vector3, type Camera } from "three";
import type { LifeState } from "../../../protocol/ts/protocol";
import type { Layer, Panel, Triangle } from "./layer";
import { COLORS, font, lineHeight, measure, roundRect } from "./paper";

const PAD = 8;
const DOT = 7;

/** One tag: its panel and tail, and what it was last painted with. */
interface Tag {
  panel: Panel;
  tail: Triangle;
  key: string;
}

/**
 * The short readouts his code floats over his things (`world.readout`: "watering", "62%"), as small
 * paper tags in the hover label's style ([ThingLabel]), each with a tail down to the thing's top.
 * Each tag's canvas is painted again only when its text or the scale changes. Hidden while
 * something is open over the room, and for the thing the hover label is on.
 */
export class Readouts {
  private readonly tags = new Map<string, Tag>();
  private readonly at = new Vector3();

  constructor(private readonly layer: Layer) {}

  /** [top]: where a thing's top is in the world, or null when it isn't drawn. [hovered]: the thing the hover label is on. */
  update(life: LifeState, top: (id: string, out: Vector3) => Vector3 | null, camera: Camera, u: number, covered: boolean, hovered: string | null) {
    const seen = new Set<string>();
    for (const e of covered ? [] : life.things.entities) {
      if (!e.readout || e.id === hovered) continue;
      const p = top(e.id, this.at);
      if (!p) continue;
      p.project(camera);
      if (p.z > 1 || p.x < -1.1 || p.x > 1.1 || p.y < -1.1 || p.y > 1.1) continue;
      seen.add(e.id);
      let tag = this.tags.get(e.id);
      if (!tag) this.tags.set(e.id, (tag = { panel: this.layer.panel(12), tail: this.layer.triangle(COLORS.bubble, 12), key: "" }));
      const key = `${e.readout}/${u}/${this.layer.ratio}`;
      if (key !== tag.key) {
        tag.key = key;
        paint(tag.panel, e.readout, u);
      }
      const tx = ((p.x + 1) / 2) * this.layer.width;
      const ty = ((1 - p.y) / 2) * this.layer.height;
      const { w, h } = tag.panel;
      const x = Math.min(Math.max(tx - w / 2, 4), this.layer.width - w - 4);
      const y = Math.max(ty - 10 * u - h, 4);
      tag.panel.place(x, y);
      const tip = Math.min(Math.max(tx, x + 10 * u), x + w - 10 * u);
      tag.tail.set(tip - 6 * u, y + h - 1, tip + 6 * u, y + h - 1, tx, y + h + 8 * u);
    }
    for (const [id, tag] of this.tags) {
      if (seen.has(id)) continue;
      tag.panel.hide();
      tag.tail.hide();
    }
  }
}

/** A dot in his inventions' colour and the text, on paper edged as the label's. */
function paint(panel: Panel, text: string, u: number) {
  const pad = PAD * u;
  const dot = DOT * u;
  const ctx = measure();
  ctx.font = font(1.15, u, 700);
  const w = ctx.measureText(text).width + dot + 6 * u + 2 * pad;
  const h = lineHeight(1.15, u) + 2 * pad * 0.75;
  panel.draw(w, h, (c) => {
    c.fillStyle = COLORS.paperEdge;
    roundRect(c, 0, 0, w, h, 9 * u);
    c.fillStyle = COLORS.bubble;
    roundRect(c, 1.5 * u, 1.5 * u, w - 3 * u, h - 3 * u, 7.5 * u);
    c.fillStyle = COLORS.dear;
    c.beginPath();
    c.arc(pad + dot / 2, h / 2, dot / 2, 0, Math.PI * 2);
    c.fill();
    c.font = font(1.15, u, 700);
    c.fillStyle = COLORS.ink;
    c.textBaseline = "middle";
    c.textAlign = "left";
    c.fillText(text, pad + dot + 6 * u, h / 2 + u);
  });
}
