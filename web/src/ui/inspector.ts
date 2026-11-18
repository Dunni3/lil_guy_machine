import type { Content, Entity, LifeState } from "../../../protocol/ts/protocol";
import { thingInfo } from "./label";
import { COLORS } from "./paper";

/** The id the room's pick gives the guy, who isn't one of the things. */
export const GUY = "guy";

/** How often an open panel is read again from the state, in seconds. */
const EVERY = 0.25;

/** What the panel says about one thing (or him), all from the stream. */
export interface Inspected {
  id: string;
  kind: string;
  name: string;
  what: string;
  state: [string, string][];
  holdsTitle: string;
  holds: { what: string; detail: string | null }[];
  empty: string;
  uses: { label: string; detail: string | null; locked: boolean }[];
  idle: string;
}

/**
 * The gadget inspector, after the Field Lab sketch (`world_sketches/fable_field_lab.html`): click a
 * thing in the room, or him, to see what it is, what's live in it, what it holds and the verbs he
 * works it with. A panel of DOM over the room, at the right on a wide screen and along the bottom on
 * a phone, read again from the stream while it's open. Viewers only watch, so how to work it says
 * what he can do with it and why not, not buttons. Escape closes it; [ and ] step through the things.
 */
export class Inspector {
  private readonly panel: HTMLElement;
  private id: string | null = null;
  private sig = "";
  private wait = 0;

  constructor() {
    style();
    this.panel = document.createElement("aside");
    this.panel.id = "inspector";
    this.panel.hidden = true;
    this.panel.setAttribute("aria-live", "polite");
    this.panel.setAttribute("aria-label", "What this is");
    document.body.append(this.panel);
    this.panel.addEventListener("click", (e) => {
      const b = (e.target as HTMLElement).closest("button");
      if (b?.dataset.go === "close") this.close();
      else if (b?.dataset.go === "prev") this.stepBy = -1;
      else if (b?.dataset.go === "next") this.stepBy = 1;
    });
  }

  /** A step asked for by the panel's buttons or a key, taken on the next [update] (which has the state). */
  stepBy = 0;

  get open(): boolean {
    return this.id !== null;
  }

  /** What it's open on, or null. */
  get showing(): string | null {
    return this.id;
  }

  show(id: string) {
    this.id = id;
    this.sig = "";
    this.wait = 0;
  }

  close() {
    this.id = null;
    this.panel.hidden = true;
    this.sig = "";
  }

  /** Reads the panel from [life] now and then while it's open, and only touches the page when what it says has changed. */
  update(life: LifeState, content: Content, delta: number) {
    if (this.id === null) return;
    const order = inspectable(life);
    if (this.stepBy) {
      const at = Math.max(0, order.indexOf(this.id));
      this.id = order[(at + this.stepBy + order.length) % order.length] ?? this.id;
      this.stepBy = 0;
      this.wait = 0;
    }
    this.wait -= delta;
    if (this.wait > 0) return;
    this.wait = EVERY;
    const it = inspect(this.id, life, content);
    // What it was open on went (sold, eaten, put away): close rather than show nothing.
    if (!it) return this.close();
    const index = `${order.indexOf(it.id) + 1} / ${order.length}`;
    const sig = JSON.stringify([it, index]);
    if (sig === this.sig) return;
    this.sig = sig;
    this.panel.innerHTML = render(it, index);
    this.panel.hidden = false;
  }
}

/** Him, then every thing standing on the map, in the order the stream lists them: what [ and ] step through. */
export function inspectable(life: LifeState): string[] {
  return [GUY, ...life.things.entities.filter((e) => !e.carried_by).map((e) => e.id)];
}

/** What the panel says about [id] in [life], or null when it isn't there to inspect. */
export function inspect(id: string, life: LifeState, content: Content): Inspected | null {
  if (id === GUY) return him(life, content);
  const e = life.things.entities.find((t) => t.id === id && !t.carried_by);
  return e ? thing(e, life, content) : null;
}

function placeName(life: LifeState, place: string | null | undefined): string | null {
  if (!place) return null;
  return life.world.places.find((p) => p.id === place)?.name ?? place.replace(/_/g, " ");
}

const n1 = (v: number) => (Math.round(v * 10) / 10).toString();
const sentence = (s: string) => s.charAt(0).toUpperCase() + s.slice(1) + (/[.!?"]$/.test(s) ? "" : ".");

function thing(e: Entity, life: LifeState, content: Content): Inspected {
  const info = thingInfo(e.id, e.kind, life, content);
  const g = (life.gadgets?.things ?? []).find((t) => t.id === e.id);
  const changed = life.code.changed.find((c) => c.id === e.id);
  const made = (life.shop.inventions ?? []).find((i) => i.id === e.id);
  const book = life.growth.books.find((b) => b.id === e.id);
  const behavior = life.code.behaviors.find((b) => b.thing === e.id);
  const stored = life.code.stored[e.id] ?? {};

  const what: string[] = [];
  if (made) what.push(`He invented it, for ${made.use.replace(/_/g, " ")}: "${made.why}"`);
  for (const line of g?.about ?? []) what.push(sentence(line));
  if (behavior) what.push(sentence(`By his code, it ${behavior.says.replace(/^it /i, "")}`));

  const state: [string, string][] = [];
  const where = placeName(life, e.place);
  if (where) state.push(["where", where]);
  if (e.state) state.push(["state", e.state.replace(/_/g, " ")]);
  if (e.readout) state.push(["readout", e.readout]);
  if (e.light) state.push(["light", `giving ${n1(e.light.strength)}, reaching ${n1(e.light.radius)} tile${e.light.radius === 1 ? "" : "s"}`]);
  for (const m of e.motions ?? []) state.push(["moving", `${m.part}: ${m.kind} at ${n1(m.speed)}`]);
  if (changed?.color || e.color) state.push(["painted", changed?.color ?? e.color ?? ""]);
  const owned = life.wallet.owns[e.id];
  if (owned && owned > 1) state.push(["he has", String(owned)]);
  if (book) {
    state.push(["read", `${book.read} of ${book.chapters} chapters`]);
    if (book.raises?.length) state.push(["raises", book.raises.join(", ")]);
    if (book.teaches) state.push(["teaches", book.teaches]);
  }
  for (const f of g?.state ?? []) state.push([f.label, f.value]);
  for (const [k, v] of Object.entries(stored)) state.push([`kept · ${k}`, v]);
  if (behavior) {
    state.push(["its code", `v${behavior.version}${behavior.every ? `, every ${behavior.every} min` : ""}`]);
    if (behavior.failures) state.push(["broke", `${behavior.failures} run${behavior.failures > 1 ? "s" : ""} in a row${behavior.failures >= 3 ? ", so it stopped" : ""}`]);
  }

  const holds = [...(g?.holds ?? [])];
  const uses = (g?.uses ?? [])
    .map((u) => ({ label: u.phrase ?? u.verb.replace(/_/g, " "), detail: u.locked ?? u.gives, locked: u.locked !== null }))
    .sort((a, b) => Number(a.locked) - Number(b.locked));

  return {
    id: e.id,
    kind: made ? "he invented this" : info.group,
    name: g?.name ?? changed?.name ?? info.name,
    what: what.join(" ") || "Nothing in it runs by itself: it stands there and is what it is.",
    state,
    holdsTitle: "in it",
    holds,
    empty: g?.about.length ? "Nothing in it now." : "It holds nothing.",
    uses,
    idle: g ? "Nothing he does uses it." : "The host hasn't said what it's for (an older host).",
  };
}

function him(life: LifeState, content: Content): Inspected {
  const f = life.fast;
  const guy = f.entities.find((e) => e.kind === "guy");
  const agent = f.agents[0];
  const state: [string, string][] = [];
  const where = placeName(life, guy?.place);
  if (where) state.push(["where", where]);
  if (f.doing) state.push(["doing", `${f.doing.replace(/_/g, " ")}${f.progress != null ? `, ${Math.round(f.progress * 100)}%` : ""}`]);
  else if (agent?.path.length) state.push(["doing", `walking, ${agent.path.length} tiles to go`]);
  if (agent?.intent) state.push(["meaning to", agent.intent.text]);
  if (agent?.status) state.push(["last", agent.status.state + (agent.status.reason ? `: ${agent.status.reason}` : "")]);
  if (f.music) state.push(["music", f.music.replace(/_/g, " ")]);
  if (agent?.saying) state.push(["saying", agent.saying]);
  if (f.needs) {
    const n = f.needs;
    state.push(["energy", String(Math.round(n.energy))], ["fullness", String(Math.round(n.fullness))], ["mood", `${Math.round(n.mood)}, settling toward ${Math.round(n.mood_target)}`]);
    state.push(["stimulation", String(Math.round(n.stimulation))], ["frustration", String(Math.round(n.frustration))]);
  }
  state.push(["money", `$${life.wallet.money}`]);
  const name = (id: string) => content.catalog.find((c) => c.id === id)?.name ?? id.replace(/_/g, " ");
  const holds = [
    ...(agent?.inventory ?? []).map((id) => ({ what: name(id), detail: "in his hands" })),
    ...life.wallet.holding.filter((id) => !agent?.inventory.includes(id)).map((id) => ({ what: name(id), detail: "art he's keeping" })),
  ];
  const plan = life.mind.plan?.text;
  return {
    id: GUY,
    kind: "the guy",
    name: "him",
    what: `What he does next comes from ${life.run.models.decision}, one verb at a time; nobody watching steers him.${plan ? ` His plan: "${plan}"` : ""}`,
    state,
    holdsTitle: "with him",
    holds,
    empty: "His hands are empty.",
    uses: [],
    idle: "Nobody works him: he picks his own verbs. The menu (a click on the floor, or OK) shows what he could do.",
  };
}

const esc = (s: string) => s.replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]!);

function render(it: Inspected, index: string): string {
  const state = it.state.map(([k, v]) => `<dt>${esc(k)}</dt><dd>${esc(v)}</dd>`).join("");
  const holds = it.holds.length
    ? it.holds.map((h) => `<li><b>${esc(h.what)}</b>${h.detail ? `<small>${esc(h.detail)}</small>` : ""}</li>`).join("")
    : `<li class="empty">${esc(it.empty)}</li>`;
  const uses = it.uses.length
    ? `<ul class="uses">${it.uses.map((u) => `<li class="${u.locked ? "locked" : "open"}"><b>${esc(u.label)}</b>${u.detail ? `<small>${u.locked ? "not now: " : ""}${esc(u.detail)}</small>` : ""}</li>`).join("")}</ul>`
    : `<p class="idle">${esc(it.idle)}</p>`;
  return `<header><span class="kind">${esc(it.kind)}</span><button type="button" data-go="close" aria-label="Close">close ×</button></header>
<h2>${esc(it.name)}</h2><p class="what">${esc(it.what)}</p>
<h4>state, live</h4>${state ? `<dl>${state}</dl>` : `<p class="idle">Nothing about it changes.</p>`}
<h4>${esc(it.holdsTitle)}</h4><ul>${holds}</ul>
<h4>how he works it</h4>${uses}
<footer><button type="button" data-go="prev" aria-label="Previous thing">‹ prev</button><span>${esc(index)}</span><button type="button" data-go="next" aria-label="Next thing">next ›</button></footer>`;
}

/** The panel's look: the room's paper and ink ([COLORS]), the HUD's rounded face, and code's fixed-width one for readings. */
function style() {
  const c = COLORS;
  const s = document.createElement("style");
  s.textContent = `
#inspector {
  position: fixed; top: 16px; right: 16px; z-index: 5; width: min(380px, calc(100vw - 32px)); max-height: calc(100vh - 100px);
  overflow-y: auto; box-sizing: border-box; padding: 14px 18px 10px; overscroll-behavior: contain;
  background: ${c.card}; color: ${c.ink}; border: 4px solid ${c.paperEdge}; border-radius: 18px;
  box-shadow: 0 8px 28px rgba(74, 58, 79, 0.18);
  font: 500 15px/1.4 ui-rounded, "SF Pro Rounded", "Nunito", system-ui, sans-serif;
}
#inspector[hidden] { display: none; }
#inspector header, #inspector footer { display: flex; justify-content: space-between; align-items: center; font: 600 12px/1 ui-monospace, "SF Mono", Menlo, monospace; letter-spacing: 0.08em; }
#inspector .kind { text-transform: uppercase; color: ${c.accent}; }
#inspector button { background: none; border: 0; padding: 6px 0; font: inherit; color: ${c.muted}; cursor: pointer; }
#inspector button:hover { color: ${c.ink}; }
#inspector h2 { margin: 8px 0 4px; font: 700 22px/1.15 ui-rounded, "SF Pro Rounded", system-ui, sans-serif; }
#inspector .what { margin: 0; color: ${c.muted}; font-style: italic; }
#inspector h4 { margin: 12px 0 6px; padding-top: 10px; border-top: 1px solid ${c.paperEdge}; font: 600 11px/1 ui-monospace, "SF Mono", Menlo, monospace; letter-spacing: 0.14em; text-transform: uppercase; color: ${c.muted}; }
#inspector dl { display: grid; grid-template-columns: auto 1fr; gap: 3px 12px; margin: 0; font: 500 13px/1.35 ui-monospace, "SF Mono", Menlo, monospace; }
#inspector dt { color: ${c.muted}; }
#inspector dd { margin: 0; overflow-wrap: anywhere; }
#inspector ul { list-style: none; padding: 0; margin: 0; }
#inspector li { padding: 5px 0; border-bottom: 1px dashed ${c.paperEdge}; }
#inspector li:last-child { border-bottom: 0; }
#inspector li b { font-weight: 600; }
#inspector li small { display: block; color: ${c.muted}; font: 500 12px/1.35 ui-monospace, "SF Mono", Menlo, monospace; }
#inspector li.empty, #inspector .idle { color: ${c.muted}; font-style: italic; }
#inspector .idle { margin: 0 0 4px; }
#inspector .uses li.open b::before { content: "● "; color: ${c.fullness}; }
#inspector .uses li.locked b { color: ${c.muted}; font-weight: 500; }
#inspector .uses li.locked b::before { content: "○ "; }
#inspector footer { margin-top: 10px; padding-top: 4px; border-top: 1px solid ${c.paperEdge}; color: ${c.muted}; }
@media (max-width: 560px) {
  #inspector { top: auto; bottom: 8px; left: 8px; right: 8px; width: auto; max-height: 52vh; padding: 12px 14px 6px; border-radius: 16px; }
  #inspector h2 { font-size: 19px; }
}`;
  document.head.append(s);
}
