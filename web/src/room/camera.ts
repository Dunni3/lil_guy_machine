import { Vector3, type OrthographicCamera } from "three";

/** The whole room, or closer in and following him. */
export type CameraMode = "room" | "follow";

/** The Field Lab's angle: from the front right, a little above the diagonal. */
const ISO = new Vector3(1, 0.82, 1).normalize();
const DISTANCE = 120;
/** Room to spare round the room's box, as a fraction of the frame; following him, how much of the room's height (in the frame) shows. */
const MARGIN = 1.08;
const FOLLOW = 0.55;
/** How fast it catches up (per second; higher is snappier), and how high the point looked at is. */
const CATCH_UP = 2.2;
const LOOK_HEIGHT = 0.8;

/**
 * Where the camera is: the viewer's own (view state stays in the page, not the host). An
 * orthographic camera at the Field Lab's isometric angle, framing the whole room (its plinth and
 * walls) in any window shape, or nearer in, easing after him and kept over the room. Switching eases
 * between the two.
 */
export class CameraRig {
  /** The room's box: x0, z0, x1, z1 and its walls' height. */
  private box = [0, 0, 1, 1, 2.4];
  private readonly centre = new Vector3();
  private half = 4;
  private aspect = 0;
  private placed = false;
  private readonly want = new Vector3();
  private readonly right = new Vector3();
  private readonly up = new Vector3();

  constructor(public mode: CameraMode) {
    this.right.set(1, 0, -1).normalize();
    this.up.copy(ISO).cross(this.right).normalize();
  }

  /** What's framed spans [x0, x1] by [z0, z1] on the ground, [height] tall; with [ease], the camera moves over to it rather than jumping. */
  frame(x0: number, z0: number, x1: number, z1: number, height: number, ease = false) {
    this.box = [x0, z0, x1, z1, height];
    if (!ease) this.placed = false;
  }

  /** Moves [camera] on by [delta] seconds toward its mode's framing, with him at ([x], [z]), for a screen [aspect] wide. */
  update(camera: OrthographicCamera, x: number, z: number, aspect: number, delta: number) {
    // A new window shape frames at once; only moving between framings eases.
    if (aspect !== this.aspect) {
      this.aspect = aspect;
      this.placed = false;
    }
    const [x0, z0, x1, z1, h] = this.box;
    // The room's box seen down the camera's axis: its extent across and up the screen, and its middle.
    let minR = Infinity, maxR = -Infinity, minU = Infinity, maxU = -Infinity;
    const p = new Vector3();
    for (const cx of [x0, x1]) for (const cz of [z0, z1]) for (const cy of [-0.85, h]) {
      p.set(cx, cy, cz);
      const r = p.dot(this.right);
      const u = p.dot(this.up);
      minR = Math.min(minR, r); maxR = Math.max(maxR, r);
      minU = Math.min(minU, u); maxU = Math.max(maxU, u);
    }
    const roomHalf = Math.max((maxU - minU) / 2, (maxR - minR) / 2 / aspect) * MARGIN;
    const want = this.want;
    let half = roomHalf;
    if (this.mode === "follow") {
      half = roomHalf * FOLLOW;
      // Him in the middle, but not so far that the frame runs off the room's box.
      want.set(x, LOOK_HEIGHT, z);
      const r = want.dot(this.right);
      const u = want.dot(this.up);
      const halfR = half * aspect;
      const midR = (minR + maxR) / 2;
      const midU = (minU + maxU) / 2;
      const cr = halfR * 2 >= maxR - minR ? midR : Math.min(Math.max(r, minR + halfR), maxR - halfR);
      const cu = half * 2 >= maxU - minU ? midU : Math.min(Math.max(u, minU + half), maxU - half);
      want.addScaledVector(this.right, cr - r).addScaledVector(this.up, cu - u);
    } else {
      want.set(0, 0, 0).addScaledVector(this.right, (minR + maxR) / 2).addScaledVector(this.up, (minU + maxU) / 2);
    }
    const k = this.placed ? 1 - Math.exp(-CATCH_UP * Math.min(delta, 0.1)) : 1;
    this.placed = true;
    this.centre.lerp(want, k);
    this.half += (half - this.half) * k;
    camera.left = -this.half * aspect;
    camera.right = this.half * aspect;
    camera.top = this.half;
    camera.bottom = -this.half;
    camera.position.copy(this.centre).addScaledVector(ISO, DISTANCE);
    camera.up.set(0, 1, 0);
    camera.lookAt(this.centre);
    camera.near = 1;
    camera.far = DISTANCE * 2;
    camera.updateProjectionMatrix();
    camera.updateMatrixWorld();
  }
}
