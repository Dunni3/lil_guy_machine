import type { CameraMode } from "./room/camera";

/**
 * The page's URL settings, all per viewer:
 * - `life`: which life to watch (`main`);
 * - `broadcast`: the broadcaster's page: no menu, keys or cursor, the camera following him, the
 *   HUD in a 16:9 frame's title-safe area;
 * - `scale`: draw the canvas at this fraction of the window and let CSS scale it up (0.25 to 1,
 *   for a weak GPU such as the TV's: 0.5 or 0.67);
 * - `fps`: draw at most this many frames a second (for example 30);
 * - `camera`: `room` (the whole room, the app's view) or `follow` (nearer, following him);
 * - `hour`: light the room as at this hour (0 to 24) whatever the game clock says, for looking at
 *   the look; the clock on the wall and the HUD still tell the game's time.
 */
export interface PageSettings {
  life: string;
  broadcast: boolean;
  scale: number;
  fps: number | null;
  camera: CameraMode;
  hour: number | null;
}

export function readSettings(search: string): PageSettings {
  const p = new URLSearchParams(search);
  const broadcast = p.has("broadcast") && p.get("broadcast") !== "0";
  const scale = Number(p.get("scale"));
  const fps = Number(p.get("fps"));
  const camera = p.get("camera");
  const hour = p.get("hour") === null ? NaN : Number(p.get("hour"));
  return {
    hour: Number.isFinite(hour) ? Math.min(Math.max(hour, 0), 24) : null,
    life: p.get("life") || "main",
    broadcast,
    scale: scale > 0 ? Math.min(Math.max(scale, 0.25), 1) : 1,
    fps: fps > 0 ? Math.max(fps, 5) : null,
    camera: camera === "room" || camera === "follow" ? camera : broadcast ? "follow" : "room",
  };
}
