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
