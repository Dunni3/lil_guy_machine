import { PROTOCOL_VERSION, type Content, type LifeSummary, type ServerMessage } from "../../../protocol/ts/protocol";
import { StreamState } from "./state";

export type Connection = "connecting" | "live" | "reconnecting" | "refused";

const CONTENT_KEY = "lilguy.content";
/** Waits between tries, doubling up to this, so a restarting host isn't hammered. */
const RETRY_FIRST = 0.5;
const RETRY_MOST = 5;
/** A version mismatch means this page is older than the host: reload for the new one, unattended. */
const RELOAD_AFTER = 15;
/** After this long without a message the page asks the host over HTTP whether it missed any, and gives that long for the answer. */
const SILENT = 15;
const ASK_FOR = 5;

/**
 * Stays joined to one life's stream: applies its messages in order, rejoins on a dropped socket or
 * a missed message, and keeps the static content in browser storage under its hash, so a join with
 * `?have=` doesn't download it again.
 *
 * The heartbeat: a paused life sends nothing, so silence alone proves nothing, and the host's
 * pings never reach the page. After [SILENT] seconds without a message it asks the host for its
 * lives (a few bytes): a running life publishes all the time, so if the host is unreachable, says
 * the life is running, or has another run or state than the socket gave, the socket is dead (a
 * dropped connection can stay "open" for minutes), and it rejoins. An unattended page recovers by
 * itself.
 */
export class StreamClient {
  readonly state = new StreamState();
  connection: Connection = "connecting";
  /** Called after every message that may have changed the state; [snapshot] says it was replaced whole. */
  onChange: (snapshot: boolean) => void = () => {};
  private tries = 0;
  private socket: WebSocket | null = null;
  /** When the last message came, in seconds (performance.now). */
  private heard = 0;
  private asking = false;

  constructor(private readonly life: string) {
    this.state.content = readContent();
  }

  start() {
    this.connect();
    setInterval(() => void this.heartbeat(), 1000);
  }

  /** Seconds since the last message, for whoever checks the page. */
  get silentFor(): number {
    return performance.now() / 1000 - this.heard;
  }

  private async heartbeat() {
    if (this.connection !== "live" || this.asking || this.silentFor < SILENT) return;
    this.asking = true;
    const socket = this.socket;
    let dead = false;
    try {
      const r = await fetch("/v1/lives", { signal: AbortSignal.timeout(ASK_FOR * 1000), cache: "no-store" });
      const host = r.ok ? ((await r.json()) as LifeSummary[]).find((l) => l.id === this.life) : undefined;
      const mine = this.state.life?.run;
      dead = !host || !mine || host.state === "running" || host.run_id !== mine.run_id || host.state !== mine.state;
    } catch {
      dead = true;
    }
    this.asking = false;
    if (socket !== this.socket) return;
    this.heard = performance.now() / 1000;
    if (dead) {
      this.connection = "reconnecting";
      this.drop();
      this.retry(0);
    }
  }

  /** Lets go of the socket without waiting for it to close. */
  private drop() {
    const s = this.socket;
    this.socket = null;
    if (s) {
      s.onmessage = s.onclose = s.onerror = null;
      s.close();
    }
  }

  private connect() {
    const scheme = location.protocol === "https:" ? "wss" : "ws";
    const have = this.state.content?.hash;
    const url = `${scheme}://${location.host}/v1/lives/${encodeURIComponent(this.life)}/stream?v=${PROTOCOL_VERSION}` + (have ? `&have=${have}` : "");
    const socket = new WebSocket(url);
    this.socket = socket;
    this.heard = performance.now() / 1000;
    socket.onmessage = (event) => {
      if (socket !== this.socket) return;
      this.heard = performance.now() / 1000;
      const m = JSON.parse(event.data as string) as ServerMessage;
      const outcome = this.state.receive(m);
      if (m.type === "welcome") { this.connection = "live"; this.tries = 0; }
      if (m.type === "content") saveContent(m);
      if (outcome === "refused") {
        this.connection = "refused";
        this.drop();
        // Another version: this page is stale, and a reload brings the host's. Otherwise (no such life) keep trying.
        this.retry(m.type === "refused" && m.version !== PROTOCOL_VERSION ? null : RETRY_MOST);
        return;
      }
      if (outcome === "rejoin") {
        // Whatever we thought we had, ask for it all.
        if (m.type === "snapshot") { this.state.content = null; clearContent(); }
        this.drop();
        this.retry(0);
        return;
      }
      if (m.type === "snapshot" || m.type === "delta") this.onChange(m.type === "snapshot");
    };
    socket.onclose = () => {
      if (socket !== this.socket) return;
      this.socket = null;
      this.connection = "reconnecting";
      this.retry(Math.min(RETRY_MOST, RETRY_FIRST * 2 ** this.tries++));
    };
    socket.onerror = () => socket.close();
  }

  private retry(seconds: number | null) {
    if (seconds === null) setTimeout(() => location.reload(), RELOAD_AFTER * 1000);
    else setTimeout(() => this.connect(), seconds * 1000);
  }
}

// Storage can be blocked or cleared, and then the host just sends the content again.
function readContent(): Content | null {
  try {
    const text = localStorage.getItem(CONTENT_KEY);
    return text ? (JSON.parse(text) as Content) : null;
  } catch {
    return null;
  }
}

function saveContent(c: Content) {
  try {
    localStorage.setItem(CONTENT_KEY, JSON.stringify(c));
  } catch {
    // Not kept; it comes again on the next join.
  }
}

function clearContent() {
  try {
    localStorage.removeItem(CONTENT_KEY);
  } catch {
    // Nothing to clear.
  }
}
