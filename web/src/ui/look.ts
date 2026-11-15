import type { CameraMode } from "../room/camera";

/** What the buttons ask the camera for. */
export interface LookActions {
  zoom(factor: number): void;
  orbit(yaw: number): void;
  mode(mode: "room" | "follow"): void;
}

const SEEN = "lilguy.lookTaught";
/** How long the how-to line stays for someone who never touches the camera (seconds). */
const TEACH_FOR = 25;

/**
 * The camera's buttons, in the bottom-left corner (`#look` in index.html): zoom out and in, turn
 * left and right, and back to the whole room or to following him, the one in use lit. Above them, until the
 * viewer first moves the camera (remembered in this browser), a line saying the mouse and keys
 * do it too. Hidden for broadcast and while something is open over the room.
 */
export class LookControls {
  private readonly root = document.getElementById("look") as HTMLElement;
  private readonly teach = document.getElementById("look-teach") as HTMLElement;
  private readonly buttons = new Map<string, HTMLButtonElement>();
  private shown: boolean | null = null;
  private lit: CameraMode | null = null;
  private teaching: boolean;
  private age = 0;

  constructor(private readonly broadcast: boolean, act: LookActions) {
    let seen = false;
    try {
      seen = localStorage.getItem(SEEN) === "1";
    } catch {
      // No storage (a private window, blocked site data): teach every visit.
    }
    this.teaching = !seen && !broadcast;
    this.teach.hidden = !this.teaching;
    for (const b of this.root.querySelectorAll<HTMLButtonElement>("button[data-look]")) {
      this.buttons.set(b.dataset.look!, b);
      b.addEventListener("click", () => {
        switch (b.dataset.look) {
          case "out": act.zoom(1 / 1.4); break;
          case "in": act.zoom(1.4); break;
          case "left": act.orbit(-20); break;
          case "right": act.orbit(20); break;
          case "room": act.mode("room"); break;
          case "follow": act.mode("follow"); break;
        }
        b.blur();
      });
    }
  }

  /** The viewer moved the camera themselves: they know how, so the how-to line goes for good. */
  used() {
    if (!this.teaching) return;
    this.teaching = false;
    this.teach.hidden = true;
    try {
      localStorage.setItem(SEEN, "1");
    } catch {
      // Remembered for this visit only.
    }
  }

  /** Each frame: the camera's [mode], and whether anything ([covered]) is open over the room. */
  update(mode: CameraMode, covered: boolean, delta: number) {
    const show = !this.broadcast && !covered;
    if (show !== this.shown) {
      this.shown = show;
      this.root.hidden = !show;
    }
    if (mode !== this.lit) {
      this.lit = mode;
      for (const [k, b] of this.buttons) b.classList.toggle("on", k === mode);
    }
    if (this.teaching && show) {
      this.age += delta;
      if (this.age > TEACH_FOR) {
        this.teaching = false;
        this.teach.hidden = true;
      }
    }
  }
}
