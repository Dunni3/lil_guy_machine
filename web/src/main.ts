import { CrayonRenderer } from "./crayon/renderer";
import { RoomView } from "./room/view";
import { StreamClient } from "./stream/client";
import { Track } from "./stream/track";

// The read-only room: one life's state stream, drawn. `?life=` picks the life. Nothing here sends anything.
const params = new URLSearchParams(location.search);
const canvas = document.getElementById("room") as HTMLCanvasElement;
const status = document.getElementById("status") as HTMLElement;

const stream = new StreamClient(params.get("life") ?? "main");
const track = new Track();
let lastGuy: unknown = null;
const seconds = () => performance.now() / 1000;

stream.onChange = (snapshot) => {
  const guy = stream.state.life?.fast.entities.find((e) => e.kind === "guy");
  if (!guy) return;
  if (!snapshot && guy === lastGuy) return;
  lastGuy = guy;
  track.push(seconds(), guy.x, guy.y, snapshot);
};

let renderer: CrayonRenderer;
try {
  renderer = new CrayonRenderer(canvas);
} catch (e) {
  status.textContent = "this browser can't draw the room: WebGL isn't available";
  throw e;
}
const resize = () => renderer.resize(canvas.clientWidth || window.innerWidth, canvas.clientHeight || window.innerHeight);
window.addEventListener("resize", resize);
resize();

let view: RoomView | null = null;
let last = seconds();
let statusText = "";
const stats = { frames: 0, worst: 0, since: last, fps: 0 };

function frame() {
  requestAnimationFrame(frame);
  const now = seconds();
  const delta = now - last;
  last = now;
  stats.frames++;
  stats.worst = Math.max(stats.worst, delta);

  const { life, content } = stream.state;
  if (life && content) {
    // Painting the textures takes a moment, so it waits until there's a room to draw.
    view ??= new RoomView(renderer);
    view.render(life, content, track.sample(now), delta);
    renderer.render(view.background);
  }
  const text = describe();
  if (text !== statusText) status.textContent = statusText = text;
}

function describe(): string {
  const { life, refused } = stream.state;
  switch (stream.connection) {
    case "connecting": return "connecting";
    case "reconnecting": return "connection lost; reconnecting";
    case "refused": return refused?.reason ?? "refused";
    case "live": {
      if (!life) return "joining";
      const clock = life.fast.clock;
      const when = clock ? `day ${clock.day} · ${clock.time}` : "";
      const run = life.run.state === "running" ? "" : life.run.state;
      return [when, run].filter(Boolean).join(" · ");
    }
  }
}

// What a person or a headless browser checking the page can read from the console.
(window as unknown as { lilguy: unknown }).lilguy = { stream, track, renderer, stats };

stream.start();
requestAnimationFrame(frame);
