import type { Command, Settings } from "../../../protocol/ts/protocol";
import { TABS, entries, type Entry, type PageView, type TabId } from "./pages";
import { adjust, adjustable, confirms, pickReplay, type RunRow } from "./run";

/** What a key means to the menu (`GameMenu.Press`). */
export type Press = "ok" | "back" | "left" | "right" | "up" | "down";

/** What a press asks of the page: a command for the host, or the sign-in. */
export type Act =
  | { type: "command"; command: Command }
  | { type: "configure"; settings: Settings }
  | { type: "signIn" }
  | { type: "signOut" };

/** Detail lines a press of UP or DOWN scrolls. */
const SCROLL_STEP = 4;

/**
 * The in-game menu's state, ported from `GameMenu.kt`: which tab, the picked entry per tab (by
 * key, so it stays put as newer entries arrive), whether the detail has the focus, and the
 * confirm before a restart or replay. Keys: LEFT/RIGHT tabs (on a Run setting, change it), UP/DOWN
 * pick, OK reads the entry (then UP/DOWN scroll, OK or BACK go back to the list) or does the Run
 * row's action, BACK closes. Opening it on a run that hasn't started lands an admin on Start, so
 * OK twice starts a run; the first opening of a visit lands anyone else on Sign in, so the admin
 * finds it. All of it is this viewer's own; the host only sees the commands.
 */
export class Menu {
  open = false;
  tab: TabId = "shop";
  /** The detail pane has the focus: UP/DOWN scroll it. */
  reading = false;
  /** The detail's first line shown; the drawing keeps it in range. */
  scroll = 0;
  /** Set by the first OK on Restart or Replay: a second OK does it, any other key cancels. */
  confirm = false;
  /** The run Replay would start again (1-based); null for this one. */
  replayPick: number | null = null;
  private readonly picked = new Map<TabId, string>();
  /** The menu has opened before on this page: after the first time, a viewer's tab stays where they left it. */
  private opened = false;

  show(v: PageView) {
    this.open = true;
    this.reading = false;
    this.confirm = false;
    if (v.admin ? v.life.run.state === "ready" : !this.opened) {
      this.tab = "run";
      this.picked.delete("run");
    }
    this.opened = true;
  }

  close() {
    this.open = false;
    this.reading = false;
    this.confirm = false;
  }

  /** A new run: every pick back to the top, since the old run's entries are gone. */
  reset() {
    this.picked.clear();
    this.reading = false;
    this.scroll = 0;
    this.confirm = false;
    this.replayPick = null;
  }

  /** The picked entry's index in [list]: the one with the picked key, or the first. */
  index(list: Entry[]): number {
    const key = this.picked.get(this.tab);
    const i = key === undefined ? 0 : list.findIndex((e) => e.key === key);
    return Math.max(i, 0);
  }

  /** The Run row picked, for the hints and the detail. */
  runRow(v: PageView): RunRow {
    const list = entries("run", v);
    return (this.tab === "run" ? list[this.index(list)]?.key : this.picked.get("run") ?? list[0]?.key) as RunRow;
  }

  press(p: Press, v: PageView): Act | null {
    const confirming = this.confirm;
    this.confirm = false;
    switch (p) {
      case "back":
        if (this.reading) this.reading = false;
        else this.close();
        return null;
      case "left":
      case "right": {
        const by = p === "left" ? -1 : 1;
        if (this.tab === "run" && !this.reading && adjustable(this.runRow(v))) return this.adjust(this.runRow(v), by, v);
        const i = TABS.findIndex((t) => t.id === this.tab);
        this.tab = TABS[(i + by + TABS.length) % TABS.length].id;
        this.reading = false;
        this.scroll = 0;
        return null;
      }
      case "up":
      case "down": {
        const by = p === "up" ? -1 : 1;
        if (this.reading) {
          this.scroll = Math.max(0, this.scroll + by * SCROLL_STEP);
          return null;
        }
        const list = entries(this.tab, v);
        if (!list.length) return null;
        const next = Math.min(Math.max(this.index(list) + by, 0), list.length - 1);
        if (next === 0) this.picked.delete(this.tab);
        else this.picked.set(this.tab, list[next].key);
        this.scroll = 0;
        return null;
      }
      case "ok":
        if (this.tab === "run") return this.runAction(v, confirming);
        if (this.reading) this.reading = false;
        else if (entries(this.tab, v).length) {
          this.reading = true;
          this.scroll = 0;
        }
        return null;
    }
  }

  private adjust(row: RunRow, by: number, v: PageView): Act | null {
    if (row === "replay") {
      this.replayPick = pickReplay(v.life.run, this.replayPick, by);
      return null;
    }
    const settings = adjust(row, by, v.life.run, v.settings);
    return settings ? { type: "configure", settings } : null;
  }

  /** OK on a Run row: start, pause or resume (starting or resuming closes the menu); restart and replay ask first. */
  private runAction(v: PageView, confirmed: boolean): Act | null {
    const row = this.runRow(v);
    const state = v.life.run.state;
    switch (row) {
      case "play":
        if (state === "ended") return null;
        if (state !== "running") this.close();
        return { type: "command", command: state === "running" ? { type: "pause" } : { type: "start" } };
      case "signin": return { type: "signIn" };
      case "signout": return { type: "signOut" };
      default:
        if (!confirms(row)) return null;
        if (!confirmed) {
          this.confirm = true;
          return null;
        }
        {
          const command: Command = row === "replay" ? { type: "replay", run: this.replayPick ?? v.life.run.run } : { type: "restart" };
          this.reset();
          return { type: "command", command };
        }
    }
  }
}
