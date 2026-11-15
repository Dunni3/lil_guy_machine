import { Vector3, type OrthographicCamera } from "three";

/** The whole room, closer in and following him, or wherever the viewer has looked to. */
export type CameraMode = "room" | "follow" | "free";

/** The Field Lab's angle: from the front right, a little above the diagonal, as a turn round the room (degrees from +Z toward +X) and a height. */
const ISO = new Vector3(1, 0.82, 1).normalize();
const ISO_YAW = 45;
const ISO_PITCH = (Math.asin(ISO.y) * 180) / Math.PI;
/**
 * How far looking around may turn and tilt: from straight down the front to straight down the right
 * side (the two cut-down walls; past them the back and left walls stand in the way), and from low over
 * the sill to nearly straight down.
 */
const YAW = [-8, 98];
const PITCH = [10, 84];
const DISTANCE = 120;
/** Room to spare round the room's box, as a fraction of the frame; following him, how much of the room's height (in the frame) shows. */
const MARGIN = 1.08;
const FOLLOW = 0.55;
/** Looking around: how near in it goes and how far out, as fractions of the whole room's framing, and how far past the room's box the middle of the view may wander (metres). */
const ZOOM = [0.12, 1.6];
const WANDER = 1.5;
/** How fast it catches up (per second; higher is snappier): between framings, and under the viewer's hand. */
const CATCH_UP = 2.2;
const CATCH_HAND = 16;
/** How high the point looked at is. */
const LOOK_HEIGHT = 0.8;

const RAD = Math.PI / 180;
const clamp = (v: number, lo: number, hi: number) => Math.min(Math.max(v, lo), hi);

/** The view's axes for a turn and a tilt: toward the camera, across the screen and up it. */
function axes(yaw: number, pitch: number, dir: Vector3, right: Vector3, up: Vector3) {
  const c = Math.cos(pitch * RAD);
  dir.set(Math.sin(yaw * RAD) * c, Math.sin(pitch * RAD), Math.cos(yaw * RAD) * c);
  right.set(Math.cos(yaw * RAD), 0, -Math.sin(yaw * RAD));
  up.copy(dir).cross(right).normalize();
}

/**
 * Where the camera is: the viewer's own (view state stays in the page, not the host). An
 * orthographic camera framing the whole room (its plinth and walls) in any window shape at the Field
 * Lab's isometric angle, or nearer in, easing after him and kept over the room, or (`free`) wherever
 * the viewer has dragged, zoomed and turned it to look round his world. Switching eases between them.
 */
export class CameraRig {
  /** The room's box: x0, z0, x1, z1 and its walls' height. */
  private box = [0, 0, 1, 1, 2.4];
  private aspect = 16 / 9;
  private placed = false;
  /** Where it is now: the point looked at, half the frame's height, the turn and the tilt. */
  private readonly centre = new Vector3();
  private half = 4;
  private yaw = ISO_YAW;
  private pitch = ISO_PITCH;
  /** Looking around, where the viewer has put it: the same four. */
  private readonly look = { centre: new Vector3(), half: 4, yaw: ISO_YAW, pitch: ISO_PITCH };
  /** The whole room's framing's half height, from the last frame, for the zoom's limits. */
  private roomHalf = 4;
  private readonly want = new Vector3();
  private readonly dir = new Vector3();
  private readonly right = new Vector3();
  private readonly up = new Vector3();
  private readonly isoRight = new Vector3();
  private readonly isoUp = new Vector3();
  private readonly tmp = new Vector3();

  constructor(private current: CameraMode) {
    axes(ISO_YAW, ISO_PITCH, this.tmp, this.isoRight, this.isoUp);
  }

  get mode(): CameraMode {
    return this.current;
  }

  /** Back to the whole room or to following him, or on to looking around from where it is now. */
  set mode(mode: CameraMode) {
    if (mode === "free") this.takeHold();
    else this.current = mode;
  }

  /** The room spans [x0, x1] by [z0, z1] on the floor, its walls [height] tall. */
  frame(x0: number, z0: number, x1: number, z1: number, height: number) {
    this.box = [x0, z0, x1, z1, height];
    this.placed = false;
  }

  /**
   * Moves the view by [dx], [dy] CSS pixels on a screen [height] pixels tall, as if dragging the
   * world under the pointer: what was under it stays under it.
   */
  pan(dx: number, dy: number, height: number) {
    this.takeHold();
    const l = this.look;
    const perPixel = (l.half * 2) / Math.max(1, height);
    axes(l.yaw, l.pitch, this.dir, this.right, this.up);
    l.centre.addScaledVector(this.right, -dx * perPixel).addScaledVector(this.up, dy * perPixel);
    this.settle();
  }

  /**
   * Zooms in by [factor] (above 1 nearer, below farther), keeping what's at ([nx], [ny]) where it is
   * on the screen (-1 to 1 across and up; 0, 0 is the middle).
   */
  zoom(factor: number, nx = 0, ny = 0) {
    this.takeHold();
    const l = this.look;
    const half = clamp(l.half / factor, this.roomHalf * ZOOM[0], this.roomHalf * ZOOM[1]);
    const moved = 1 - half / l.half;
    axes(l.yaw, l.pitch, this.dir, this.right, this.up);
    l.centre.addScaledVector(this.right, nx * l.half * this.aspect * moved).addScaledVector(this.up, ny * l.half * moved);
    l.half = half;
    this.settle();
  }

  /** Turns the view round the point looked at by [yaw] degrees (positive swings the camera toward the room's right side) and tilts it by [pitch] (positive looks more straight down). */
  orbit(yaw: number, pitch: number) {
    this.takeHold();
    const l = this.look;
    l.yaw = clamp(l.yaw + yaw, YAW[0], YAW[1]);
    l.pitch = clamp(l.pitch + pitch, PITCH[0], PITCH[1]);
    this.settle();
  }

  /** Moves [camera] on by [delta] seconds toward its mode's framing, with him at ([x], [z]), for a screen [aspect] wide. */
  update(camera: OrthographicCamera, x: number, z: number, aspect: number, delta: number) {
    // A new window shape frames at once; only moving between framings eases.
    if (aspect !== this.aspect) {
      this.aspect = aspect;
      this.placed = false;
    }
    const [minR, maxR, minU, maxU] = this.extent(this.isoRight, this.isoUp);
    const roomHalf = Math.max((maxU - minU) / 2, (maxR - minR) / 2 / aspect) * MARGIN;
    this.roomHalf = roomHalf;
    const want = this.want;
    let half = roomHalf;
    let yaw = ISO_YAW;
    let pitch = ISO_PITCH;
    if (this.current === "free") {
      want.copy(this.look.centre);
      half = this.look.half;
      yaw = this.look.yaw;
      pitch = this.look.pitch;
    } else if (this.current === "follow") {
      half = roomHalf * FOLLOW;
      // Him in the middle, but not so far that the frame runs off the room's box.
      want.set(x, LOOK_HEIGHT, z);
      const r = want.dot(this.isoRight);
      const u = want.dot(this.isoUp);
      const halfR = half * aspect;
      const midR = (minR + maxR) / 2;
      const midU = (minU + maxU) / 2;
      const cr = halfR * 2 >= maxR - minR ? midR : Math.min(Math.max(r, minR + halfR), maxR - halfR);
      const cu = half * 2 >= maxU - minU ? midU : Math.min(Math.max(u, minU + half), maxU - half);
      want.addScaledVector(this.isoRight, cr - r).addScaledVector(this.isoUp, cu - u);
    } else {
      want.set(0, 0, 0).addScaledVector(this.isoRight, (minR + maxR) / 2).addScaledVector(this.isoUp, (minU + maxU) / 2);
    }
    const rate = this.current === "free" ? CATCH_HAND : CATCH_UP;
    const k = this.placed ? 1 - Math.exp(-rate * Math.min(delta, 0.1)) : 1;
    this.placed = true;
    this.centre.lerp(want, k);
    this.half += (half - this.half) * k;
    this.yaw += (yaw - this.yaw) * k;
    this.pitch += (pitch - this.pitch) * k;
    axes(this.yaw, this.pitch, this.dir, this.right, this.up);
    camera.left = -this.half * aspect;
    camera.right = this.half * aspect;
    camera.top = this.half;
    camera.bottom = -this.half;
    camera.position.copy(this.centre).addScaledVector(this.dir, DISTANCE);
    camera.up.set(0, 1, 0);
    camera.lookAt(this.centre);
    camera.near = 1;
    camera.far = DISTANCE * 2;
    camera.updateProjectionMatrix();
    camera.updateMatrixWorld();
  }

  /** The room's box seen along axes [right] and [up]: its extent across the screen and up it. */
  private extent(right: Vector3, up: Vector3): [number, number, number, number] {
    const [x0, z0, x1, z1, h] = this.box;
    let minR = Infinity, maxR = -Infinity, minU = Infinity, maxU = -Infinity;
    const p = this.tmp;
    for (const cx of [x0, x1]) for (const cz of [z0, z1]) for (const cy of [-0.85, h]) {
      p.set(cx, cy, cz);
      const r = p.dot(right);
      const u = p.dot(up);
      minR = Math.min(minR, r); maxR = Math.max(maxR, r);
      minU = Math.min(minU, u); maxU = Math.max(maxU, u);
    }
    return [minR, maxR, minU, maxU];
  }

  /** Looking around starts from wherever the camera is now, so taking hold of it doesn't jump. */
  private takeHold() {
    if (this.current === "free") return;
    this.current = "free";
    this.look.centre.copy(this.centre);
    this.look.half = this.half;
    this.look.yaw = this.yaw;
    this.look.pitch = this.pitch;
    this.settle();
  }

  /**
   * Slides the point looked at along the view's own axis (which changes nothing on the screen) to his
   * eye height, so turning goes round a point in the room rather than one up in the air, and keeps it
   * within reach of the room.
   */
  private settle() {
    const l = this.look;
    axes(l.yaw, l.pitch, this.dir, this.right, this.up);
    l.centre.addScaledVector(this.dir, (LOOK_HEIGHT - l.centre.y) / this.dir.y);
    const [x0, z0, x1, z1] = this.box;
    l.centre.x = clamp(l.centre.x, x0 - WANDER, x1 + WANDER);
    l.centre.z = clamp(l.centre.z, z0 - WANDER, z1 + WANDER);
  }
}
