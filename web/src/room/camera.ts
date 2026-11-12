import { Vector3, type PerspectiveCamera } from "three";

/** The whole room, as the app frames it, or closer in and following him. */
export type CameraMode = "room" | "follow";

const FOV = 30;
const WIDE = 16 / 9;
/** Degrees the camera looks down. */
const PITCH = 40;
/** Room beyond the side walls, in tiles, so the walls don't touch the screen edge; less when following him, so the room fills the frame. */
const SIDE_MARGIN = 0.9;
const FOLLOW_MARGIN = 0.3;
/** Following him: how much nearer than the whole room's framing, and how fast it catches up (per second; higher is snappier). */
const FOLLOW_NEARER = 0.55;
const CATCH_UP = 1.6;
/** The point looked at is this high: about his chest, and the room's middle height for the whole room. */
const FOLLOW_HEIGHT = 0.8;
const ROOM_HEIGHT = 1.1;

/**
 * Where the camera is. It's the viewer's own (view state stays in the page, not the host): the
 * whole room, a front view from above as in `RoomView.kt`, or a nearer one that keeps him in frame
 * and eases after him, never showing past the side walls. Switching eases between the two.
 */
export class CameraRig {
  private width = 0;
  private height = 0;
  private roomDistance = 0;
  private readonly target = new Vector3();
  private distance = 0;
  private placed = false;
  private readonly want = new Vector3();
  private readonly offset = new Vector3();

  constructor(public mode: CameraMode) {}

  /** A room [width] by [height] tiles: the whole of it in frame at 16:9. */
  frame(width: number, height: number) {
    this.width = width;
    this.height = height;
    const halfFovX = Math.tan((FOV / 2) * (Math.PI / 180)) * WIDE;
    this.roomDistance = (width / 2 + SIDE_MARGIN) / halfFovX;
    this.placed = false;
  }

  /** Moves [camera] on by [delta] seconds toward its mode's framing, with him at ([x], [z]). */
  update(camera: PerspectiveCamera, x: number, z: number, delta: number) {
    const want = this.want;
    let distance = this.roomDistance;
    if (this.mode === "follow") {
      distance *= FOLLOW_NEARER;
      // What half the frame covers at that distance, across and (on the floor) in depth, so the view stops at the walls and the front lip.
      const tan = Math.tan((FOV / 2) * (Math.PI / 180));
      const halfX = tan * WIDE * distance - FOLLOW_MARGIN;
      const halfZ = (tan * distance) / Math.sin(PITCH * (Math.PI / 180)) - 1;
      want.set(clampMiddle(x, halfX, this.width), FOLLOW_HEIGHT, clampMiddle(z + 0.2, halfZ, this.height));
    } else want.set(this.width / 2, ROOM_HEIGHT, this.height / 2 + 0.2);

    const k = this.placed ? 1 - Math.exp(-CATCH_UP * Math.min(delta, 0.1)) : 1;
    this.placed = true;
    this.target.lerp(want, k);
    this.distance += (distance - this.distance) * k;
    const pitch = PITCH * (Math.PI / 180);
    camera.position.copy(this.target).add(this.offset.set(0, Math.sin(pitch) * this.distance, Math.cos(pitch) * this.distance));
    camera.up.set(0, 1, 0);
    camera.lookAt(this.target);
    camera.updateMatrixWorld();
  }
}

/** [v] kept at least [half] in from both ends of 0..[size], or the middle when [size] is narrower than the view. */
function clampMiddle(v: number, half: number, size: number): number {
  return half * 2 >= size ? size / 2 : Math.min(Math.max(v, half), size - half);
}
