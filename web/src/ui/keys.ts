import type { Press } from "./menu";

/**
 * A key as the TV remote means it (`RemoteInput.kt`): OK is Enter (the remote's centre) or Space,
 * BACK is Escape, Backspace or the browser's back key, the arrows are themselves, and PLAY/PAUSE
 * is the media key, or P on a keyboard. Null for any other key, which the page leaves alone.
 */
export function pressOf(e: KeyboardEvent): Press | "play" | null {
  if (e.altKey || e.ctrlKey || e.metaKey) return null;
  switch (e.key) {
    case "Enter": case " ": case "Select": return "ok";
    case "Escape": case "Backspace": case "BrowserBack": case "GoBack": return "back";
    case "ArrowLeft": return "left";
    case "ArrowRight": return "right";
    case "ArrowUp": return "up";
    case "ArrowDown": return "down";
    case "MediaPlayPause": case "MediaPlay": case "MediaPause": case "p": case "P": return "play";
    default: return null;
  }
}

/** Looking round the room from the keyboard: a step of panning, turning, tilting or zooming. */
export type Look =
  | { type: "pan"; x: number; y: number }
  | { type: "orbit"; yaw: number; pitch: number }
  | { type: "zoom"; factor: number };

/**
 * A key that moves the camera (none of the remote's, which keep their meanings): W A S D pan the
 * view, Q and E turn it round the room, R and F tilt it, + and - zoom. Null for any other key.
 */
export function lookOf(e: KeyboardEvent): Look | null {
  if (e.altKey || e.ctrlKey || e.metaKey) return null;
  switch (e.key.toLowerCase()) {
    case "w": return { type: "pan", x: 0, y: 1 };
    case "s": return { type: "pan", x: 0, y: -1 };
    case "a": return { type: "pan", x: 1, y: 0 };
    case "d": return { type: "pan", x: -1, y: 0 };
    case "q": return { type: "orbit", yaw: -12, pitch: 0 };
    case "e": return { type: "orbit", yaw: 12, pitch: 0 };
    case "r": return { type: "orbit", yaw: 0, pitch: 8 };
    case "f": return { type: "orbit", yaw: 0, pitch: -8 };
    case "+": case "=": return { type: "zoom", factor: 1.25 };
    case "-": case "_": return { type: "zoom", factor: 1 / 1.25 };
    default: return null;
  }
}
