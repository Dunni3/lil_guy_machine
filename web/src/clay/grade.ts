/**
 * How hard the room works the GPU. A phone's GPU can give out drawing it (Chrome then loses the WebGL
 * context and shows a blank canvas with a broken-image icon), so a lost context while the page is
 * on screen reloads it a grade lighter, and phones start a grade down. The grade is kept per tab.
 */
export interface Grade {
  /** The most drawing-buffer pixels to a CSS pixel. */
  ratio: number;
  antialias: boolean;
  /** The sun's shadow map's size; 0 for no shadows. */
  shadowMap: number;
}

export const GRADES: Grade[] = [
  { ratio: 2, antialias: true, shadowMap: 2048 },
  { ratio: 1.5, antialias: true, shadowMap: 1024 },
  { ratio: 1, antialias: false, shadowMap: 0 },
];

const KEY = "lilguy.grade";

/** This tab's grade: the one a lost context left, else 1 on a phone (a touch screen and no mouse) and 0 elsewhere. */
export function gradeNow(): number {
  try {
    const kept = Number(sessionStorage.getItem(KEY));
    if (kept > 0 && kept < GRADES.length) return kept;
  } catch {
    // No storage: start as a fresh tab would.
  }
  return matchMedia("(pointer: coarse) and (not (any-pointer: fine))").matches ? 1 : 0;
}

/**
 * Watches [canvas] for a lost context. Lost while the page is hidden (a phone does this to a tab in
 * the background), it reloads when the page is back, at the same grade. Lost on screen, it reloads a
 * grade lighter; past the lightest it calls [giveUp] instead.
 */
export function watchContext(canvas: HTMLCanvasElement, grade: number, giveUp: () => void) {
  canvas.addEventListener("webglcontextlost", (e) => {
    e.preventDefault();
    if (document.visibilityState === "hidden") {
      const back = () => {
        if (document.visibilityState === "hidden") return;
        document.removeEventListener("visibilitychange", back);
        location.reload();
      };
      document.addEventListener("visibilitychange", back);
      return;
    }
    const lighter = grade + 1;
    if (lighter >= GRADES.length) {
      giveUp();
      return;
    }
    try {
      sessionStorage.setItem(KEY, String(lighter));
    } catch {
      // No storage: a reload would start at this grade again and lose it again.
      giveUp();
      return;
    }
    location.reload();
  }, { once: true });
}
