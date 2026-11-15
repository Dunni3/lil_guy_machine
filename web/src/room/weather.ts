import { BufferAttribute, BufferGeometry, LineBasicMaterial, LineSegments, type Scene } from "three";
import type { Terrain } from "./shell";

/** Streaks at once: few enough to move on the CPU each frame for nothing. */
const DROPS = 360;
/** Metres either way of where the view is that rain falls, and the height it falls from. */
const SPREAD = 11;
const TOP = 7;
/** Metres a second it falls, and a streak's length. */
const FALL = 9;
const STREAK = 0.35;

/**
 * Rain over the land as a few hundred short grey line segments that fall round the middle of the
 * view (one draw call, no textures, no full-screen pass). A drop that would land in a room (on `#` or
 * `.`) is hidden: the rooms are cut away, but they have roofs. [sky] "rain" shows it; anything else hides it.
 */
export class Rain {
  private readonly pos = new Float32Array(DROPS * 6);
  private readonly geometry = new BufferGeometry();
  private readonly lines: LineSegments;
  /** Each drop's x, z and height above the ground; at 0 it starts again somewhere else. */
  private readonly drops = Array.from({ length: DROPS }, () => ({ x: 0, z: 0, y: -1, indoors: false }));

  constructor(scene: Scene) {
    this.geometry.setAttribute("position", new BufferAttribute(this.pos, 3));
    this.lines = new LineSegments(this.geometry, new LineBasicMaterial({ color: "#9aa7b8", transparent: true, opacity: 0.55 }));
    this.lines.frustumCulled = false;
    this.lines.visible = false;
    scene.add(this.lines);
  }

  /** One frame: [sky] now, round ([x], [z]) on [map], [delta] seconds on. */
  update(sky: string | null, x: number, z: number, map: Terrain | null, delta: number) {
    const raining = sky === "rain";
    this.lines.visible = raining;
    if (!raining) return;
    const step = Math.min(delta, 0.1) * FALL;
    this.drops.forEach((d, i) => {
      d.y -= step;
      if (d.y <= 0) {
        d.x = x + (Math.random() * 2 - 1) * SPREAD;
        d.z = z + (Math.random() * 2 - 1) * SPREAD;
        d.y = TOP * (0.3 + Math.random() * 0.7);
        const tile = map?.at(Math.floor(d.x), Math.floor(d.z)) ?? " ";
        // Over a room it doesn't show: it falls hidden and tries somewhere else.
        d.indoors = tile === "#" || tile === "." || tile === "+";
      }
      if (d.indoors) {
        this.hide(i);
        return;
      }
      const p = this.pos;
      const k = i * 6;
      p[k] = d.x;
      p[k + 1] = d.y + STREAK;
      p[k + 2] = d.z;
      p[k + 3] = d.x - 0.04;
      p[k + 4] = d.y;
      p[k + 5] = d.z + 0.02;
    });
    this.geometry.attributes.position.needsUpdate = true;
  }

  private hide(i: number) {
    this.pos.fill(0, i * 6, i * 6 + 6);
    this.pos[i * 6 + 1] = -5;
    this.pos[i * 6 + 4] = -5;
  }
}
