import type { CatalogItem, CatalogState, Content, LifeState, SelfModelView, Settings, ThoughtView, TripView } from "../../../protocol/ts/protocol";
import { COLORS, GROUP_COLORS } from "./paper";
import { ADMIN_ROWS, LABELS, SIGN_IN, clockNote, help, isAction, plural, stamp, summary, value, type RunRow } from "./run";

export type TabId = "run" | "shop" | "thoughts" | "memory" | "self" | "trips";
export const TABS: { id: TabId; title: string }[] = [
  { id: "run", title: "RUN" }, { id: "shop", title: "SHOP" }, { id: "thoughts", title: "THOUGHTS" },
  { id: "memory", title: "MEMORY" }, { id: "self", title: "SELF" }, { id: "trips", title: "TRIPS" },
];

/** One entry in a tab's list: [key] keeps the pick on it as newer entries arrive. */
export interface Entry {
  key: string;
  title: string;
  sub: string;
  titleColor?: string;
  subColor?: string;
  mark?: string | null;
}

/** One paragraph of the detail pane, wrapped to its width when drawn; [scale] is the app's font scale. */
export interface Para {
  text: string;
  scale: number;
  color: string;
  indent: number;
  gap: number;
}

export const SCALE = { title: 1.9, body: 1.5, head: 1.2, small: 1.25 };

/** What a page is made from: the life as the stream has it, and the menu's own state. */
export interface PageView {
  life: LifeState;
  content: Content;
  admin: boolean;
  /** The settings as the admin last asked for them, until the host's match. */
  settings: Settings;
  replayPick: number | null;
  confirm: boolean;
  /** How the last command went, for the RUN tab. */
  result: string | null;
}

const NOW = "now";

/**
 * The menu's tabs as lists and details, ported from `GameMenu.kt` onto what the stream carries.
 * Some of the app's detail isn't in the protocol: what was recalled at the latest decision, his
 * fixed core and taste, the self-model's seed, each model call's tokens and a trip's raw reply.
 */
export function entries(tab: TabId, v: PageView): Entry[] {
  const { life, content } = v;
  switch (tab) {
    case "run":
      return (v.admin ? ADMIN_ROWS : SIGN_IN).map((row) => {
        if (row === "play") {
          const title = { ready: "Start", running: "Pause", paused: "Resume", ended: "Ended" }[life.run.state];
          return { key: row, title, sub: `run ${life.run.run} · ${life.run.state}` + (life.fast.held ? " · held" : ""), mark: stateColor(life.run.state) };
        }
        return { key: row, title: LABELS[row], sub: value(row, life, v.settings, v.replayPick), subColor: isAction(row) ? COLORS.muted : COLORS.ink };
      });
    case "shop":
      return shopItems(life, content).map(({ item, state }) => {
        const hidden = state.taught_by !== null;
        return {
          key: item.id, title: item.name, sub: shopStatus(item, state),
          titleColor: hidden ? COLORS.muted : COLORS.ink,
          subColor: !state.affordable && !hidden && !owned(item, state) ? COLORS.dear : COLORS.muted,
          mark: hidden ? COLORS.barBg : GROUP_COLORS[item.group] ?? COLORS.barBg,
        };
      });
    case "thoughts": {
      const plan = life.mind.plan?.seq;
      return [...life.logs.thoughts].reverse().map((t) => ({
        key: String(t.seq),
        title: at(t.day, t.time, t.tick, life) + (t.seq === plan ? " · his plan" : "") + (t.asked.length ? ` · asked ${t.asked.length}` : ""),
        sub: t.text ?? `no thought: ${t.error ?? "?"}`,
        subColor: t.text === null ? COLORS.failed : COLORS.muted,
        mark: t.seq === plan ? COLORS.accent : null,
      }));
    }
    case "memory": {
      const recent = outcomes(life).length;
      return [
        { key: NOW, title: "Right now", sub: `${plural(recent, "recent action")} · ${plural(life.logs.memories.length, "memory", "memories")} so far`, mark: COLORS.accent },
        ...[...life.logs.memories].reverse().map((m) => ({
          key: String(m.seq),
          title: (m.level === 2 ? "The whole day · " : "") + (m.span || "a stretch"),
          sub: m.text,
          mark: m.level === 2 ? COLORS.ribbon : null,
        })),
      ];
    }
    case "self": {
      const mind = life.mind;
      const revisions = life.logs.revisions;
      const changed = revisions.filter((r) => r.changed).length;
      const now: Entry[] = mind.self_text === null ? [] : [{
        key: NOW, title: "How he sees himself now",
        sub: "in every prompt · " + (mind.self_model ? `revised ${changed === 1 ? "once" : `${changed} times`}` : "still the seed"), mark: COLORS.accent,
      }];
      return now.concat([...revisions].reverse().map((r) => {
        const what = r.error !== null ? "failed" : r.changed ? "changed" : "unchanged";
        return {
          key: String(r.seq), title: `Night of day ${r.day ?? "?"} · ${what}`, sub: r.error ?? r.note ?? "",
          subColor: r.error !== null ? COLORS.failed : COLORS.muted, mark: r.changed ? COLORS.accent : null,
        };
      }));
    }
    case "trips":
      return [...life.logs.trips].reverse().map((t) => {
        const what = t.error !== null ? "failed" : t.buy.length === 0 ? "bought nothing" : `picked ${t.buy.length}`;
        const sub = t.error ?? (t.buy.map((p) => itemName(p.item, t.catalog)).join(", ") || (t.save_for ? `saving for ${itemName(t.save_for, t.catalog)}` : "-"));
        return {
          key: String(t.seq), title: `${at(t.day, t.time, t.tick, life)} · ${what}`, sub,
          subColor: t.error !== null ? COLORS.failed : COLORS.muted,
          mark: t.error !== null ? COLORS.failed : t.buy.length ? COLORS.fullness : null,
        };
      });
  }
}

/** What a tab with nothing in it says instead. */
export function empty(tab: TabId, v: PageView): string {
  const life = v.life;
  switch (tab) {
    case "shop": return "There's no shop in this room.";
    case "thoughts": return life.run.models.thoughts ? "He hasn't thought anything yet." : "Nothing writes his thoughts in this setup: the run needs a writer endpoint (WRITER_ENDPOINT).";
    case "self": return "Nothing revises his self-model in this setup: the run needs a writer endpoint.";
    case "trips": return life.run.state === "ready" ? "No trips yet: the run hasn't started." : "He hasn't been shopping yet. Trips need a shopper: a writer endpoint, or a picking model.";
    default: return "";
  }
}

/** The picked [entry] of [tab] in full. */
export function detail(tab: TabId, v: PageView, entry: Entry): Para[] {
  const out = new Detail();
  const life = v.life;
  switch (tab) {
    case "run": runDetail(out, v, entry.key as RunRow); break;
    case "shop": shopDetail(out, v, entry.key); break;
    case "thoughts": {
      const t = life.logs.thoughts.find((x) => String(x.seq) === entry.key);
      if (t) thoughtDetail(out, life, t);
      break;
    }
    case "memory": {
      if (entry.key === NOW) nowDetail(out, life);
      else {
        const m = life.logs.memories.find((x) => String(x.seq) === entry.key);
        if (!m) break;
        out.head((m.level === 2 ? "THE WHOLE DAY · " : "A STRETCH · ") + m.span.toUpperCase());
        out.para(m.text, SCALE.body, COLORS.ink, 0, 6);
        out.head("ABOUT IT");
        const days = m.from_day === null ? "" : m.from_day === m.to_day || m.to_day === null ? `day ${m.from_day}` : `days ${m.from_day}-${m.to_day}`;
        out.para([days, m.level === 2 ? "a whole day" : "a stretch of it", m.importance !== null ? `importance ${m.importance.toFixed(2)}` : ""].filter(Boolean).join(" · "), SCALE.small, COLORS.muted);
      }
      break;
    }
    case "self": {
      if (entry.key === NOW) selfNowDetail(out, life);
      else nightDetail(out, life, entry.key);
      break;
    }
    case "trips": {
      const t = life.logs.trips.find((x) => String(x.seq) === entry.key);
      if (t) tripDetail(out, life, t);
      break;
    }
  }
  return out.rows;
}

class Detail {
  readonly rows: Para[] = [];
  head(text: string) {
    this.para(text, SCALE.head, COLORS.muted, 0, this.rows.length ? 18 : 0);
  }
  para(text: string, scale = SCALE.body, color = COLORS.ink, indent = 0, gap = 0) {
    this.rows.push({ text, scale, color, indent, gap });
  }
}

function runDetail(out: Detail, v: PageView, row: RunRow) {
  const life = v.life;
  const run = life.run;
  out.head(LABELS[row].toUpperCase());
  out.para(help(row, life, v.settings));
  if (v.confirm) out.para(row === "replay" ? `OK again to replay run ${v.replayPick ?? run.run} · any other key cancels` : "OK again to restart · any other key cancels", SCALE.body, COLORS.accent, 0, 10);
  if (v.result) out.para(v.result, SCALE.small, v.result.includes(": ok") ? COLORS.muted : COLORS.failed, 0, 8);
  if (row === "signin") return;
  const fast = life.fast;
  out.head("CLOCK");
  out.para((fast.clock ? `day ${fast.clock.day} · ${fast.clock.time} · ` : "") + `${v.settings.speed * run.ticks_per_minute} ticks/s` + (fast.fast_forward > 1 ? ` · quiet stretch, ${fast.fast_forward}x` : ""), SCALE.small, COLORS.ink);
  const note = clockNote(fast);
  if (note) out.para(note, SCALE.small, fast.held ? COLORS.dear : COLORS.accent);
  if (run.ended_because) out.para(`ended: ${run.ended_because}`, SCALE.small, COLORS.accent);
  const m = run.models;
  out.head("MODELS");
  out.para(`decides: ${m.decision}`, SCALE.small, COLORS.ink);
  out.para(`memory: ${m.memory ?? "off"} · thoughts: ${m.thoughts ?? "off"} · self: ${m.self ?? "off"} · recall: ${m.scorer ?? "keywords"} · art: ${m.artist ?? "off"}`, SCALE.small, COLORS.ink);
  for (const n of run.notices) out.para(n, SCALE.small, COLORS.failed);
  const t = life.tokens.total;
  out.head("TOKENS");
  out.para(`${plural(t.calls, "call")} · ${t.prompt_tokens.toLocaleString("en")} in + ${t.completion_tokens.toLocaleString("en")} out` + (t.cost_usd !== null ? ` · $${t.cost_usd.toFixed(2)}` : ""), SCALE.small, COLORS.ink);
  for (const [purpose, p] of Object.entries(life.tokens.by_purpose).sort((a, b) => b[1].prompt_tokens - a[1].prompt_tokens)) {
    out.para(`${purpose}: ${plural(p.calls, "call")} · ${p.prompt_tokens.toLocaleString("en")} + ${p.completion_tokens.toLocaleString("en")}` + (p.cost_usd !== null ? ` · $${p.cost_usd.toFixed(2)}` : ""), SCALE.small, COLORS.muted, 16);
  }
  out.head("RUNS SINCE THE HOST STARTED");
  run.starts.map((s, i) => ({ s, n: i + 1 })).reverse().forEach(({ s, n }) => {
    const mark = n === run.run ? " · this run" : n === v.replayPick ? " · replay picks this" : "";
    out.para(`run ${n}: ${summary(s)}${mark}`, SCALE.small, n === (v.replayPick ?? run.run) ? COLORS.ink : COLORS.muted);
  });
}

function shopDetail(out: Detail, v: PageView, id: string) {
  const life = v.life;
  const found = shopItems(life, v.content).find((x) => x.item.id === id);
  if (!found) return;
  const { item, state } = found;
  const hidden = state.taught_by !== null;
  out.head(`${item.group.toUpperCase()} · ${item.id}`);
  out.para(item.name, SCALE.title, COLORS.ink);
  out.para(`${shopStatus(item, state)} · he has $${life.wallet.money}`, SCALE.body, !state.affordable && !owned(item, state) && !hidden ? COLORS.dear : COLORS.ink, 0, 6);
  out.head("WHAT IT'S FOR");
  out.para(state.gives || "-");
  if (hidden) {
    out.head("HIDDEN FROM HIM");
    out.para(`He doesn't know it's for sale until a book teaches him: ${state.taught_by}.`, SCALE.body, COLORS.muted);
  }
  const trips = life.logs.trips;
  const last = [...trips].reverse().find((t) => t.error === null);
  if (last?.save_for === id) out.para(`He's saving for this (his last trip, ${at(last.day, last.time, last.tick, life)}).`, SCALE.body, COLORS.accent, 0, 12);
  const picks = trips.flatMap((t) => t.buy.filter((p) => p.item === id).map((p) => ({ t, p })));
  if (picks.length) {
    out.head("WHY HE PICKED IT");
    for (const { t, p } of picks.reverse()) out.para(`${at(t.day, t.time, t.tick, life)}: ${p.why || "(no reason: a picking model)"}`, SCALE.small, COLORS.ink);
  }
}

function thoughtDetail(out: Detail, life: LifeState, t: ThoughtView) {
  out.head(`${at(t.day, t.time, t.tick, life).toUpperCase()} · ${t.thinker}`);
  if (t.seq === life.mind.plan?.seq) out.para("His latest kept thought: shown as his plan in every prompt until the next.", SCALE.small, COLORS.accent);
  out.para(t.text ?? `No thought: ${t.error ?? "?"}`, SCALE.body, t.text === null ? COLORS.failed : COLORS.ink, 0, 6);
  t.asked.forEach((a, i) => {
    out.head(`HE ASKED${t.asked.length > 1 ? ` (${i + 1} of ${t.asked.length})` : ""}`);
    out.para(a.question, SCALE.body, COLORS.ink);
    if (a.opened.length) out.para(`the oracle opened: ${a.opened.join(", ")}`, SCALE.small, COLORS.muted);
    out.para(a.answer ?? "no answer", SCALE.body, a.answer === null ? COLORS.failed : COLORS.code, 24);
  });
}

/** What he did lately: the outcomes in the events log, newest last. */
function outcomes(life: LifeState) {
  return life.logs.events.filter((e) => e.outcome).slice(-12);
}

function nowDetail(out: Detail, life: LifeState) {
  const perMinute = life.run.ticks_per_minute;
  out.head("SHORT-TERM · recent actions, sent with every prompt");
  const recent = outcomes(life);
  if (!recent.length) out.para("(nothing yet)", SCALE.small, COLORS.muted);
  for (const e of [...recent].reverse()) out.para(`${stamp(e.tick, life.fast, perMinute)}  ${e.text}`, SCALE.small, /fail/i.test(e.text) ? COLORS.failed : COLORS.ink);
  const latest = life.logs.decisions[life.logs.decisions.length - 1];
  if (latest) {
    out.head("THE LATEST DECISION");
    out.para(`${stamp(latest.tick, life.fast, perMinute)}  ${latest.intent.text}`, SCALE.small, COLORS.ink);
    if (latest.rationale) out.para(latest.rationale, SCALE.small, COLORS.code, 16);
    if (latest.error) out.para(latest.error, SCALE.small, COLORS.failed, 16);
  }
  out.head("LONG-TERM");
  const writer = life.run.models.memory;
  const days = life.logs.memories.filter((m) => m.level === 2).length;
  const stretches = life.logs.memories.length - days;
  out.para(
    writer === null ? "No memory writer configured: events are recorded, none written up." : life.mind.summarizing ? `${writer} is writing a memory...` : `written by ${writer}`,
    SCALE.small, COLORS.ink,
  );
  out.para(`${plural(stretches, "memory", "memories")} and ${plural(days, "day")} so far · recall by ${life.run.models.scorer ?? "keywords"}`, SCALE.small, COLORS.muted);
}

function selfNowDetail(out: Detail, life: LifeState) {
  const mind = life.mind;
  out.head("HOW HE SEES HIMSELF NOW · in every prompt");
  if (mind.self_model) sections(out, mind.self_model, null, false);
  else out.para(mind.self_text ?? "");
  const rev = life.logs.revisions[life.logs.revisions.length - 1];
  out.head("LAST NIGHT");
  const text = rev?.error ? `The revision failed: ${rev.error}` : rev && !rev.changed ? `Unchanged: ${rev.note ?? ""}` : rev ? `Changed: ${rev.note ?? ""}` : "Not revised yet: the first night revises it.";
  out.para(text, SCALE.small, rev?.error ? COLORS.failed : COLORS.muted);
  out.para(`Revised by ${life.run.models.self ?? "nothing"} after each day's memory is written.`, SCALE.small, COLORS.muted);
}

function nightDetail(out: Detail, life: LifeState, key: string) {
  const revisions = life.logs.revisions;
  const i = revisions.findIndex((r) => String(r.seq) === key);
  const r = revisions[i];
  if (!r) return;
  out.head(`NIGHT OF DAY ${r.day ?? "?"}`);
  if (r.error !== null) return out.para(`The revision failed: ${r.error}`, SCALE.body, COLORS.failed);
  const before = revisions.slice(0, i).reverse().find((x) => x.self !== null)?.self ?? null;
  out.para(r.changed ? `What changed and why: ${r.note ?? ""}` : `Nothing changed: ${r.note ?? ""}`, SCALE.body, r.changed ? COLORS.accent : COLORS.muted, 0, 6);
  if (r.self) {
    out.head(before === null ? "THE SELF-MODEL, FIRST WRITTEN (sections new since the seed marked)" : "THE SELF-MODEL AFTER (changed sections marked)");
    sections(out, r.self, before, before === null);
  }
}

const SECTIONS: [keyof SelfModelView, string][] = [
  ["who", "Who I am"], ["cares", "What I care about"], ["believes", "What I believe about my world"], ["working_toward", "What I'm working toward"], ["learned", "What I've learned about myself"],
];

/** His self-model a section at a time, under `SelfModel.SECTIONS`' names; with [previous] (or [fromSeed]), sections that differ are marked. */
function sections(out: Detail, self: SelfModelView, previous: SelfModelView | null, fromSeed: boolean) {
  for (const [k, name] of SECTIONS) {
    const text = self[k].trim();
    if (!text) continue;
    const was = previous?.[k].trim() ?? "";
    const changed = fromSeed || (previous !== null && was !== text);
    out.para(name + (changed ? "  · changed" : ""), SCALE.small, changed ? COLORS.accent : COLORS.muted, 0, 8);
    out.para(text, SCALE.body, COLORS.ink, 16);
    if (changed && was) out.para(`was: ${was}`, SCALE.small, COLORS.muted, 16);
  }
}

function tripDetail(out: Detail, life: LifeState, t: TripView) {
  out.head(`${at(t.day, t.time, t.tick, life).toUpperCase()} · ${t.shopper}`);
  if (t.fell_back) out.para(`The first shopper failed (${t.fell_back}); this one picked instead.`, SCALE.small, COLORS.dear);
  if (t.error !== null) out.para(`The trip failed: ${t.error}`, SCALE.body, COLORS.failed);
  else {
    out.head("HE PICKED");
    if (!t.buy.length) out.para("nothing", SCALE.body, COLORS.muted);
    for (const p of t.buy) {
      const price = itemPrice(p.item, t.catalog);
      out.para(itemName(p.item, t.catalog) + (price ? ` (${price})` : ""), SCALE.body, COLORS.ink);
      if (p.why) out.para(p.why, SCALE.body, COLORS.code, 24);
    }
    out.head("SAVING FOR");
    const price = t.save_for ? itemPrice(t.save_for, t.catalog) : null;
    out.para(t.save_for ? itemName(t.save_for, t.catalog) + (price ? ` (${price})` : "") : t.chose_saving ? "nothing" : "(no say: a picking model keeps what it had)", SCALE.body, t.save_for ? COLORS.ink : COLORS.muted);
    if (t.note) {
      out.head("HIS NOTE");
      out.para(t.note);
    }
  }
  out.head(`WHAT HE WAS SHOWN · ${t.catalog.length} things`);
  for (const line of t.catalog) out.para(line.replace(/^- /, ""), SCALE.small, t.buy.some((p) => line.startsWith(`- ${p.item} (`)) ? COLORS.ink : COLORS.muted, 8);
}

// --- helpers -------------------------------------------------------------------------------------

/** The shop's items with what he knows of each, known things first. */
export function shopItems(life: LifeState, content: Content): { item: CatalogItem; state: CatalogState }[] {
  const items = new Map(content.catalog.map((c) => [c.id, c]));
  const out = life.shop.catalog.flatMap((state) => {
    const item = items.get(state.id);
    return item ? [{ item, state }] : [];
  });
  return [...out.filter((x) => x.state.taught_by === null), ...out.filter((x) => x.state.taught_by !== null)];
}

/** Has one already, of something bought once (stackable things are bought again and again). */
export const owned = (item: CatalogItem, state: CatalogState) => state.owned > 0 && !item.stackable;

export function shopStatus(item: CatalogItem, state: CatalogState): string {
  return (item.pack > 1 ? `${item.pack} for $${item.price}` : `$${item.price}`) + " · " + standing(item, state);
}

/** Whether he has it, can afford it, or hasn't learned of it. */
function standing(item: CatalogItem, state: CatalogState): string {
  if (state.taught_by !== null) return "a book teaches it";
  if (owned(item, state)) return "has it";
  if (state.owned > 0) return `${state.owned} at home` + (state.affordable ? "" : " · can't afford more");
  return state.affordable ? "can afford" : "saving up";
}

/** "day 2 14:30", or the tick's stamp without a clock. */
function at(day: number | null, time: string | null, tick: number, life: LifeState): string {
  return day !== null ? `day ${day} ${time ?? ""}`.trim() : stamp(tick, life.fast, life.run.ticks_per_minute);
}

/** An item's name from a trip's catalog lines ("- easel (Easel), $17, ..."), else its id made readable. */
function itemName(item: string, catalog: string[]): string {
  const line = catalog.find((l) => l.startsWith(`- ${item} (`));
  return line ? line.slice(line.indexOf(" (") + 2, line.indexOf(")")) : item.replace(/_/g, " ");
}

function itemPrice(item: string, catalog: string[]): string | null {
  const line = catalog.find((l) => l.startsWith(`- ${item} (`));
  if (!line) return null;
  const after = line.slice(line.indexOf("), ") + 3);
  return after.split(",")[0] || null;
}

export function stateColor(state: LifeState["run"]["state"]): string {
  return { ready: COLORS.ink, running: COLORS.fullnessInk, paused: COLORS.dear, ended: COLORS.ribbon }[state];
}
