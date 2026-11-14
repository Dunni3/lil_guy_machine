import type { LifeState, Settings } from "../../protocol/ts/protocol";
import { CrayonRenderer } from "./crayon/renderer";
import { RoomView } from "./room/view";
import { readSettings } from "./settings";
import { StreamClient } from "./stream/client";
import { Track } from "./stream/track";
import { Admin, SignIn } from "./ui/admin";
import { Hud } from "./ui/hud";
import { pressOf } from "./ui/keys";
import { Layer } from "./ui/layer";
import { Menu, type Act } from "./ui/menu";
import { MenuCard } from "./ui/menuCard";
import type { PageView } from "./ui/pages";
import { frameFor } from "./ui/paper";

// One life's state stream, drawn, with the HUD and menu over it. `settings.ts` lists the URL settings.
// Viewers only watch; the admin's commands go to the host over HTTP (`ui/admin.ts`).
const settings = readSettings(location.search);
const canvas = document.getElementById("room") as HTMLCanvasElement;
const status = document.getElementById("status") as HTMLElement;
if (settings.broadcast) document.body.classList.add("broadcast");

const stream = new StreamClient(settings.life);
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
// A fraction of the window's pixels, scaled up by CSS: the TV's GPU can't fill all of them.
renderer.webgl.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2) * settings.scale);
const layer = new Layer();
const resize = () => {
  const w = canvas.clientWidth || window.innerWidth;
  const h = canvas.clientHeight || window.innerHeight;
  renderer.resize(w, h);
  layer.resize(w, h, renderer.webgl.getPixelRatio());
};
window.addEventListener("resize", resize);
resize();

const admin = new Admin(settings.life);
const signIn = new SignIn(admin);
const menu = new Menu();
const hud = new Hud(layer, settings.broadcast);
const card = new MenuCard(layer);

/** The settings the admin last asked for, shown until the host's match (or the ask fails). */
let requested: Settings | null = null;
let pressed = false;
let runId = "";

function pageView(life: LifeState): PageView | null {
  const content = stream.state.content;
  if (!content) return null;
  if (requested && JSON.stringify(requested) === JSON.stringify(life.run.settings)) requested = null;
  return {
    life, content, admin: admin.admin, settings: requested ?? life.run.settings,
    replayPick: menu.replayPick, confirm: menu.confirm, result: admin.result,
  };
}

function act(a: Act) {
  switch (a.type) {
    case "command":
      void admin.send(a.command).then(() => (pressed = true));
      break;
    case "configure":
      requested = a.settings;
      void admin.send({ type: "configure", settings: a.settings }).then(() => {
        if (!admin.result?.endsWith(": ok")) requested = null;
        pressed = true;
      });
      break;
    case "signIn":
      signIn.show(() => (pressed = true));
      break;
    case "signOut":
      admin.signOut();
      menu.reset();
      break;
  }
}

/** The remote's keys, as `RemoteInput.kt` lays them out; nothing here moves the guy. */
window.addEventListener("keydown", (e) => {
  if (settings.broadcast || signIn.open) return;
  const press = pressOf(e);
  const life = stream.state.life;
  if (!press || !life) return;
  e.preventDefault();
  const v = pageView(life);
  if (!v) return;
  pressed = true;
  if (press === "play") {
    // PLAY/PAUSE starts, pauses or resumes from anywhere, for the admin.
    if (admin.admin && life.run.state !== "ended") void admin.send(life.run.state === "running" ? { type: "pause" } : { type: "start" });
    return;
  }
  if (menu.open) {
    const a = menu.press(press, v);
    if (a) act(a);
    return;
  }
  // In the room: OK (or LEFT/RIGHT, to page) opens the menu, UP and DOWN pick the camera.
  if (press === "ok" || press === "left" || press === "right") menu.show(v);
  else if (view && (press === "up" || press === "down")) view.camera.mode = press === "up" ? "room" : "follow";
});

/** A click on the room opens the menu, as OK does, for a mouse that reaches for a button; inside it the keys do the rest. */
canvas.addEventListener("click", () => {
  const life = stream.state.life;
  if (settings.broadcast || signIn.open || menu.open || !life) return;
  const v = pageView(life);
  if (!v) return;
  pressed = true;
  menu.show(v);
});

let view: RoomView | null = null;
let last = seconds();
let next = 0;
/** Null until the first frame sets it, so a page whose first frame is already live still hides the "connecting" it loaded with. */
let statusText: string | null = null;
const stats = { frames: 0, worst: 0, since: last, fps: 0 };

function frame() {
  requestAnimationFrame(frame);
  const now = seconds();
  // The frame cap: skip this one if the last drew too recently, with half a 60 Hz frame's slack for the display's own timing.
  if (settings.fps && now < next - 0.008) return;
  // On time, the next is a frame on from this one's slot, so the rate doesn't drift; far behind, a frame on from now.
  const gap = settings.fps ? 1 / settings.fps : 0;
  next = (now - next > gap ? now : next) + gap;
  const delta = now - last;
  last = now;
  stats.frames++;
  stats.worst = Math.max(stats.worst, delta);

  const { life, content } = stream.state;
  if (life && content) {
    if (life.run.run_id !== runId) {
      runId = life.run.run_id;
      menu.reset();
    }
    // Painting the textures takes a moment, so it waits until there's a room to draw.
    view ??= new RoomView(renderer, settings.camera);
    view.render(life, content, track.sample(now), delta);
    renderer.render(view.background);
    const f = frameFor(layer.width, layer.height, settings.broadcast);
    const v = settings.broadcast ? null : pageView(life);
    const open = menu.open && v !== null;
    hud.update({
      life, content, speech: view.speech, making: view.making, banner: banner(life, open), hint: hint(open), menuOpen: open,
    }, renderer.camera, f, delta);
    card.update(menu, v, f, pressed, delta);
    pressed = false;
    renderer.overlay(layer.scene, layer.camera);
  }
  const text = describe();
  if (text !== statusText) {
    status.textContent = statusText = text;
    status.hidden = text === "";
  }
}

/** Across the bottom: the connection when it's down, else the run's state when it isn't simply running, and what OK does about it. */
function banner(life: LifeState, menuOpen: boolean): string | null {
  if (stream.connection === "reconnecting") return "connection lost · reconnecting";
  if (menuOpen) return null;
  const ok = admin.admin && !settings.broadcast;
  switch (life.run.state) {
    case "ready": return ok ? "READY · OK opens the menu, OK again starts" : "READY · the run hasn't started";
    case "paused": return ok ? "PAUSED · OK opens the menu" : "PAUSED";
    case "ended": return `ENDED · ${life.run.ended_because ?? ""}`;
    case "running": return null;
  }
}

/** Top right: the room's keys, and for anyone not signed in, where signing in is (on a keyboard, OK is Enter). */
function hint(menuOpen: boolean): string | null {
  if (settings.broadcast || menuOpen) return null;
  const camera = view?.camera.mode === "follow" ? "UP shows the room" : "DOWN follows him";
  return admin.admin ? `OK for the menu · ${camera}` : `OK (Enter) or a click for the menu · admin sign-in is on RUN · ${camera}`;
}

/** The line under the canvas, for before there's a room to draw a HUD on, or when there's no room at all. */
function describe(): string {
  const { life, refused } = stream.state;
  switch (stream.connection) {
    case "connecting": return "connecting";
    case "reconnecting": return life ? "" : "connection lost; reconnecting";
    case "refused": return refused?.reason ?? "refused";
    case "live": return life ? "" : "joining";
  }
}

// What a person or a headless browser checking the page can read from the console.
(window as unknown as { lilguy: unknown }).lilguy = { stream, track, renderer, stats, menu, admin, hud, settings, get view() { return view; } };

stream.start();
requestAnimationFrame(frame);
