import type { CameraMode } from "./room/camera";

/**
 * The page's URL settings, all per viewer:
 * - `life`: which life to watch (`main`);
 * - `broadcast`: the broadcaster's page: no menu, keys or cursor, the camera following him, the
 *   HUD in a 16:9 frame's title-safe area;
 * - `scale`: draw the canvas at this fraction of the window and let CSS scale it up (0.25 to 1,
 *   for a weak GPU such as the TV's: 0.5 or 0.67);
 * - `fps`: draw at most this many frames a second (for example 30);
 * - `camera`: `room` (the whole room, the app's view) or `follow` (nearer, following him).
 */
export interface PageSettings {
  life: string;
  broadcast: boolean;
  scale: number;
  fps: number | null;
  camera: CameraMode;
}

export function readSettings(search: string): PageSettings {
  const p = new URLSearchParams(search);
  const broadcast = p.has("broadcast") && p.get("broadcast") !== "0";
  const scale = Number(p.get("scale"));
  const fps = Number(p.get("fps"));
  const camera = p.get("camera");
  return {
    life: p.get("life") || "main",
    broadcast,
    scale: scale > 0 ? Math.min(Math.max(scale, 0.25), 1) : 1,
    fps: fps > 0 ? Math.max(fps, 5) : null,
    camera: camera === "room" || camera === "follow" ? camera : broadcast ? "follow" : "room",
  };
}
