import { Matrix4, MeshBasicMaterial, QuadraticBezierCurve3, SphereGeometry, TubeGeometry, Vector3, type Mesh } from "three";
import type { Entity, LinkView } from "../../../protocol/ts/protocol";
import type { ClayRenderer } from "../clay/renderer";

/**
 * His machines' connections over the air (the sim's `Link`), drawn as copper arcs between the things
 * they join, each with a bead running along it from the one that makes to the one that takes, so you
 * can see which way things go. A link to the market runs to the mail hatch, where things are sold; one
 * from or to his things isn't drawn (it has no place to run to). Built again only when the links or
 * the things change; the beads move each frame.
 */
export class LinkLayer {
  private arcs: { tube: Mesh; bead: Mesh; curve: QuadraticBezierCurve3; offset: number }[] = [];
  private key = "";
  private readonly tubeMaterial = new MeshBasicMaterial({ color: "#c98a52", transparent: true, opacity: 0.55, depthWrite: false });
  private readonly beadMaterial = new MeshBasicMaterial({ color: "#ffd59a" });
  private readonly beadGeometry = new SphereGeometry(0.07, 10, 8);
  private readonly at = new Matrix4();
  private time = 0;

  constructor(private readonly r: ClayRenderer) {}

  /** Arcs for [links] between [entities]; unchanged links build nothing. */
  sync(links: LinkView[], entities: Entity[]) {
    const where = (id: string): Entity | undefined => entities.find((e) => e.id === (id === MARKET ? HATCH : id));
    const drawn = links.filter((l) => l.from !== YOU && l.to !== YOU && where(l.from) && where(l.to));
    const key = drawn.map((l) => {
      const a = where(l.from)!;
      const b = where(l.to)!;
      return `${l.from}>${l.to}@${a.x},${a.y}>${b.x},${b.y}`;
    }).join("|");
    if (key === this.key) return;
    this.key = key;
    this.clear();
    drawn.forEach((l, i) => {
      const a = where(l.from)!;
      const b = where(l.to)!;
      const from = new Vector3(a.x, LIFT, a.y);
      const to = new Vector3(b.x, LIFT, b.y);
      const span = from.distanceTo(to);
      const mid = from.clone().add(to).multiplyScalar(0.5);
      mid.y += 0.8 + span * 0.18;
      const curve = new QuadraticBezierCurve3(from, mid, to);
      const tube = this.r.mesh(new TubeGeometry(curve, Math.max(12, Math.round(span * 3)), 0.025, 6, false), this.tubeMaterial, { receives: false });
      tube.matrix.identity();
      const bead = this.r.mesh(this.beadGeometry, this.beadMaterial, { receives: false });
      this.arcs.push({ tube, bead, curve, offset: i * 0.37 });
    });
  }

  /** The beads run along their arcs, about a metre and a half a second. */
  animate(delta: number) {
    this.time += delta;
    for (const a of this.arcs) {
      const length = Math.max(1, a.curve.getLength());
      const t = ((this.time * SPEED) / length + a.offset) % 1;
      const p = a.curve.getPoint(t);
      a.bead.matrix.copy(this.at.makeTranslation(p.x, p.y, p.z));
    }
  }

  private clear() {
    for (const a of this.arcs) {
      this.r.removeMesh(a.tube);
      this.r.removeMesh(a.bead);
      a.tube.geometry.dispose();
    }
    this.arcs = [];
  }
}

const YOU = "you";
const MARKET = "market";
/** Where the market is: things are sold at the mail hatch. */
const HATCH = "mail_hatch";
/** How high above the ground an arc leaves a thing. */
const LIFT = 0.9;
/** Metres a second a bead runs. */
const SPEED = 1.5;
