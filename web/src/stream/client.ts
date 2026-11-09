import { PROTOCOL_VERSION, type Content, type ServerMessage } from "../../../protocol/ts/protocol";
import { StreamState } from "./state";

export type Connection = "connecting" | "live" | "reconnecting" | "refused";

const CONTENT_KEY = "lilguy.content";
/** Waits between tries, doubling up to this, so a restarting host isn't hammered. */
const RETRY_FIRST = 0.5;
const RETRY_MOST = 5;
/** A version mismatch means this page is older than the host: reload for the new one, unattended. */
const RELOAD_AFTER = 15;

/**
 * Stays joined to one life's stream: applies its messages in order, rejoins on a dropped socket or
 * a missed message, and keeps the static content in browser storage under its hash, so a join with
 * `?have=` doesn't download it again.
 */
export class StreamClient {
  readonly state = new StreamState();
  connection: Connection = "connecting";
  /** Called after every message that may have changed the state; [snapshot] says it was replaced whole. */
  onChange: (snapshot: boolean) => void = () => {};
  private tries = 0;

  constructor(private readonly life: string) {
    this.state.content = readContent();
  }

  start() {
    this.connect();
  }

  private connect() {
    const scheme = location.protocol === "https:" ? "wss" : "ws";
    const have = this.state.content?.hash;
    const url = `${scheme}://${location.host}/v1/lives/${encodeURIComponent(this.life)}/stream?v=${PROTOCOL_VERSION}` + (have ? `&have=${have}` : "");
    const socket = new WebSocket(url);
    let leaving = false;
    socket.onmessage = (event) => {
      if (leaving) return;
      const m = JSON.parse(event.data as string) as ServerMessage;
      const outcome = this.state.receive(m);
      if (m.type === "welcome") { this.connection = "live"; this.tries = 0; }
      if (m.type === "content") saveContent(m);
      if (outcome === "refused") {
        leaving = true;
        this.connection = "refused";
        socket.close();
        // Another version: this page is stale, and a reload brings the host's. Otherwise (no such life) keep trying.
        this.retry(m.type === "refused" && m.version !== PROTOCOL_VERSION ? null : RETRY_MOST);
        return;
      }
      if (outcome === "rejoin") {
        leaving = true;
        // Whatever we thought we had, ask for it all.
        if (m.type === "snapshot") { this.state.content = null; clearContent(); }
        socket.close();
        this.retry(0);
        return;
      }
      if (m.type === "snapshot" || m.type === "delta") this.onChange(m.type === "snapshot");
    };
    socket.onclose = () => {
      if (leaving) return;
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
