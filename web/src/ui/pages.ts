import type { BehaviorView, CatalogItem, CatalogState, CodedVerbView, Content, Invented, LifeState, SavedRun, SearchView, SelfModelView, Settings, ThoughtView, TripView } from "../../../protocol/ts/protocol";
import { COLORS, GROUP_COLORS } from "./paper";
import { ADMIN_ROWS, LABELS, SIGN_IN, clockNote, help, isAction, pickedRun, plural, runLine, runsValue, stamp, summary, value, type RunRow } from "./run";

export type TabId = "run" | "shop" | "made" | "thoughts" | "web" | "memory" | "self" | "trips";
export const TABS: { id: TabId; title: string }[] = [
  { id: "run", title: "RUN" }, { id: "shop", title: "SHOP" }, { id: "made", title: "MADE" }, { id: "thoughts", title: "THOUGHTS" }, { id: "web", title: "WEB" },
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
  /** Code: drawn in a fixed-width font, its lines and indentation kept. */
  mono?: boolean;
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
  /** The recorded runs, for the admin's Saved runs (null until fetched), and the one picked there by id. */
  runs?: SavedRun[] | null;
  runPick?: string | null;
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
        const sub = row === "runs" ? runsValue(v.runs, v.runPick ?? null) : value(row, life, v.settings, v.replayPick);
        return { key: row, title: LABELS[row], sub, subColor: isAction(row) ? COLORS.muted : COLORS.ink };
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
    case "made": return madeEntries(v);
    case "thoughts": {
      const plan = life.mind.plan?.seq;
      return [...life.logs.thoughts].reverse().map((t) => {
        const searched = t.reflection ? 0 : searchedAt(life, t.tick).length;
        return {
        key: String(t.seq),
        title: at(t.day, t.time, t.tick, life) + (t.reflection ? " · stepping back" : "") + (t.seq === plan ? " · his plan" : "") + (t.asked.length ? ` · asked ${t.asked.length}` : "") + (searched ? ` · searched ${searched}` : ""),
        sub: t.text ?? `no thought: ${t.error ?? "?"}`,
        subColor: t.text === null ? COLORS.failed : COLORS.muted,
        mark: t.seq === plan ? COLORS.accent : t.reflection ? COLORS.code : null,
        };
      });
    }
    case "web":
      return [...life.logs.searches].reverse().map((s) => ({
        key: String(s.seq),
        title: `${at(s.day, s.time, s.tick, life)} · "${s.query}"`,
        sub: s.refused ? "not searched: enough for today" : s.error !== null ? `failed: ${s.error}` : s.thought ?? s.summary ?? s.sources.map((x) => x.title).join(", "),
        subColor: s.error !== null && !s.refused ? COLORS.failed : COLORS.muted,
        mark: s.error !== null ? (s.refused ? COLORS.barBg : COLORS.failed) : COLORS.accent,
      }));
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
    case "made": return "He hasn't made anything yet. He invents things at the workbench (invent), and writes verbs of his own, and what his things do, as code at his desk (devise). Both need a writer endpoint, or a text model deciding.";
    case "thoughts": return life.run.models.thoughts ? "He hasn't thought anything yet." : "Nothing writes his thoughts in this setup: the run needs a writer endpoint (WRITER_ENDPOINT).";
    case "web": return life.run.models.search === null
      ? "He can't search the web in this setup: it needs a model that calls tools deciding (ENDPOINT=gemini) and a search: block in config/endpoints.yaml."
      : `He hasn't searched the web yet. He can, with ${life.run.models.search}, whenever he's curious.`;
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
    case "made": madeDetail(out, v, entry.key); break;
    case "thoughts": {
      const t = life.logs.thoughts.find((x) => String(x.seq) === entry.key);
      if (t) thoughtDetail(out, life, t);
      break;
    }
    case "web": {
      const s = life.logs.searches.find((x) => String(x.seq) === entry.key);
      if (s) searchDetail(out, life, s);
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
  code(source: string) {
    this.rows.push({ text: source, scale: SCALE.small, color: COLORS.code, indent: 16, gap: 6, mono: true });
  }
}

function runDetail(out: Detail, v: PageView, row: RunRow) {
  const life = v.life;
  const run = life.run;
  out.head(LABELS[row].toUpperCase());
  out.para(help(row, life, v.settings));
  const back = pickedRun(v.runs, v.runPick ?? null);
  if (v.confirm) {
    const what = row === "replay" ? `replay run ${v.replayPick ?? run.run}` : row === "runs" ? `go back to the run saved at ${back?.saved ?? "?"}` : "start a new run";
    out.para(`OK again to ${what} · any other key cancels`, SCALE.body, COLORS.accent, 0, 10);
  }
  if (v.result) out.para(v.result, SCALE.small, v.result.includes(": ok") ? COLORS.muted : COLORS.failed, 0, 8);
  if (row === "signin") return;
  if (row === "runs") {
    out.head("RUNS, MOST RECENTLY PLAYED FIRST");
    if (!v.runs) out.para("loading...", SCALE.small, COLORS.muted);
    for (const r of v.runs ?? []) {
      const picked = r.run_id === back?.run_id;
      out.para(`${picked ? "> " : ""}${r.live ? "this run: " : ""}${runLine(r)}`, SCALE.small, picked || r.live ? COLORS.ink : COLORS.muted, picked ? 0 : 16);
      if (!picked) continue;
      out.para(`started ${new Date(r.started_at).toLocaleString()} on ${r.platform} · ${r.run_id.slice(0, 8)}`, SCALE.small, COLORS.muted, 16);
      if (r.reached !== null && r.reached !== r.saved) out.para(`got to ${r.reached}; going back carries on from ${r.saved}`, SCALE.small, COLORS.muted, 16);
    }
    return;
  }
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
  if ((life.shop.inventions ?? []).some((i) => i.id === id)) out.para("He invented this: what it does, and its code, are under MADE.", SCALE.small, COLORS.accent, 0, 6);
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
  if (t.reflection) out.para("Stepping back: what he made of the stretch since the last time. His latest two show in every prompt; the night's self-model reads the day's.", SCALE.small, COLORS.code);
  out.para(t.text ?? `No thought: ${t.error ?? "?"}`, SCALE.body, t.text === null ? COLORS.failed : COLORS.ink, 0, 6);
  t.asked.forEach((a, i) => {
    out.head(`HE ASKED${t.asked.length > 1 ? ` (${i + 1} of ${t.asked.length})` : ""}`);
    out.para(a.question, SCALE.body, COLORS.ink);
    if (a.opened.length) out.para(`the oracle opened: ${a.opened.join(", ")}`, SCALE.small, COLORS.muted);
    out.para(a.answer ?? "no answer", SCALE.body, a.answer === null ? COLORS.failed : COLORS.code, 24);
  });
  const searched = t.reflection ? [] : searchedAt(life, t.tick);
  searched.forEach((s, i) => {
    out.head(`HE SEARCHED THE WEB FIRST${searched.length > 1 ? ` (${i + 1} of ${searched.length})` : ""} · more under WEB`);
    out.para(`"${s.query}"`, SCALE.body, COLORS.ink);
    out.para(s.summary ?? (s.error !== null ? `failed: ${s.error}` : s.sources.map((x) => x.title).join(", ")), SCALE.small, s.error !== null ? COLORS.failed : COLORS.code, 24);
  });
}

/** His searches made at the decision of [tick]: the ones he did before the thought of that tick. */
function searchedAt(life: LifeState, tick: number): SearchView[] {
  return life.logs.searches.filter((s) => s.tick === tick && !s.refused);
}

function searchDetail(out: Detail, life: LifeState, s: SearchView) {
  out.head(`${at(s.day, s.time, s.tick, life).toUpperCase()} · HE SEARCHED THE WEB FOR`);
  out.para(`"${s.query}"`, SCALE.title, COLORS.ink);
  if (s.queries.length) out.para(`sent as: ${s.queries.join(" · ")}`, SCALE.small, COLORS.muted);
  out.head("WHAT WENT THROUGH HIS HEAD, AS HE CHOSE WHAT TO DO NEXT");
  out.para(s.thought ?? (s.then === null ? "(still deciding)" : "(no thought)"), SCALE.body, s.thought ? COLORS.code : COLORS.muted);
  if (s.then) out.para(`then: ${s.then}`, SCALE.small, COLORS.ink, 0, 6);
  const others = life.logs.searches.filter((x) => x.tick === s.tick && x.seq !== s.seq);
  if (others.length) out.para(`also searched then: ${others.map((x) => `"${x.query}"`).join(", ")}`, SCALE.small, COLORS.muted, 0, 4);
  if (s.refused) {
    out.head("NOT SEARCHED");
    out.para(s.error ?? "He'd searched enough for today.", SCALE.body, COLORS.muted);
    return;
  }
  if (s.error !== null) {
    out.head("THE SEARCH FAILED");
    out.para(s.error, SCALE.body, COLORS.failed);
    return;
  }
  if (s.summary) {
    out.head("WHAT THE RESULTS SAY");
    out.para(s.summary);
  }
  out.head(s.sources.length ? `WHERE FROM · ${plural(s.sources.length, "page")}` : "NO PAGES CAME BACK");
  for (const x of s.sources) {
    out.para(x.title, SCALE.body, COLORS.ink, 0, 8);
    out.para(x.url, SCALE.small, COLORS.muted, 16);
    if (x.snippet) out.para(x.snippet, SCALE.small, COLORS.code, 16);
  }
  out.para(`${s.searcher} · ${(s.latency_ms / 1000).toFixed(1)} s`, SCALE.small, COLORS.muted, 0, 14);
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

// --- MADE: what he invented, and the code he wrote ------------------------------------------------

const INVENTED = "inv:";
const VERB = "verb:";
const BEHAVIOR = "by:";
const CHANGED = "changed";

/** The verbs and behavior that are an invention's function: the ones that take it, are done at it, or are its own. */
function functionOf(life: LifeState, id: string): { verbs: CodedVerbView[]; behavior: BehaviorView | null } {
  const code = life.code;
  return {
    verbs: (code?.verbs ?? []).filter((x) => x.what === id || x.at === id),
    behavior: (code?.behaviors ?? []).find((b) => b.thing === id) ?? null,
  };
}

function madeEntries(v: PageView): Entry[] {
  const life = v.life;
  const code = life.code;
  const inventions = life.shop.inventions ?? [];
  const invented = new Set(inventions.map((i) => i.id));
  const out: Entry[] = [];
  for (const i of [...inventions].reverse()) {
    const f = functionOf(life, i.id);
    const does = [f.verbs.length ? plural(f.verbs.length, "verb") : "", f.behavior ? "does something every hour" : ""].filter(Boolean).join(" · ");
    out.push({
      key: INVENTED + i.id, title: thingName(v, i.id),
      sub: `invented ${stamp(i.tick, life.fast, life.run.ticks_per_minute)} · ${does || `one use: ${i.use}`}`,
      mark: colorOf(life, i.id) ?? GROUP_COLORS[i.group] ?? COLORS.barBg,
    });
  }
  const own = (x: CodedVerbView) => !(x.what && invented.has(x.what)) && !invented.has(x.at);
  for (const x of [...(code?.verbs ?? [])].filter(own).reverse()) {
    out.push({ key: VERB + x.id, title: `${x.phrase ?? x.id.replace(/_/g, " ")}${x.version > 1 ? ` · v${x.version}` : ""}`, sub: `his own verb · ${x.says}`, mark: COLORS.accent });
  }
  for (const b of (code?.behaviors ?? []).filter((b) => !invented.has(b.thing))) {
    out.push({ key: BEHAVIOR + b.thing, title: `${thingName(v, b.thing)} · by itself`, sub: b.says, subColor: b.failures ? COLORS.failed : COLORS.muted, mark: COLORS.stimulation });
  }
  const changed = (code?.changed ?? []).length;
  const stored = Object.keys(code?.stored ?? {}).length;
  if (changed || stored) {
    out.push({
      key: CHANGED, title: "What his code changed",
      sub: [changed ? `${plural(changed, "thing")} moved, renamed or painted` : "", stored ? `values kept on ${plural(stored, "thing")}` : ""].filter(Boolean).join(" · "),
      mark: COLORS.ribbon,
    });
  }
  return out;
}

function madeDetail(out: Detail, v: PageView, key: string) {
  const life = v.life;
  if (key.startsWith(INVENTED)) {
    const i = (life.shop.inventions ?? []).find((x) => x.id === key.slice(INVENTED.length));
    if (i) inventionDetail(out, v, i);
  } else if (key.startsWith(VERB)) {
    const x = (life.code?.verbs ?? []).find((c) => c.id === key.slice(VERB.length));
    if (x) {
      out.head(`HIS OWN VERB · ${x.id}`);
      out.para(x.phrase ?? x.id.replace(/_/g, " "), SCALE.title, COLORS.ink);
      verbDetail(out, v, x, false);
    }
  } else if (key.startsWith(BEHAVIOR)) {
    const b = (life.code?.behaviors ?? []).find((c) => c.thing === key.slice(BEHAVIOR.length));
    if (b) {
      out.head(`WHAT IT DOES BY ITSELF · ${b.thing}`);
      out.para(thingName(v, b.thing), SCALE.title, COLORS.ink);
      behaviorDetail(out, life, b);
    }
  } else if (key === CHANGED) changedDetail(out, v, null);
}

function inventionDetail(out: Detail, v: PageView, i: Invented) {
  const life = v.life;
  out.head(`${i.group.toUpperCase()} · HIS OWN INVENTION · ${i.id}`);
  out.para(thingName(v, i.id), SCALE.title, COLORS.ink);
  const has = life.wallet.owns[i.id] ?? 0;
  const like = Object.entries(i.like).map(([q, n]) => `${q} ${n}`).join(", ");
  const bill = Object.entries(i.bill ?? {}).map(([m, n]) => `${n} ${m.replace("_", " ")}`).join(", ");
  out.para(`built ${stamp(i.tick, life.fast, life.run.ticks_per_minute)} · ` + (bill ? `made of ${bill} ($${i.cost})` : `materials $${i.cost}`) + (i.price !== null ? ` · the shop sends more for $${i.price}` : "") + ` · ${has ? `he has ${has}` : "he doesn't have it now"}`, SCALE.small, COLORS.muted, 0, 4);
  out.para(`It's ${i.use}${like ? `, and it's ${like}` : ""}${i.wall ? "; it hangs on a wall" : ""}.`, SCALE.body, COLORS.ink, 0, 6);
  if (i.why) {
    out.head("WHY HE MADE IT");
    out.para(i.why);
  }
  const f = functionOf(life, i.id);
  out.head("WHAT IT DOES");
  if (!f.verbs.length && !f.behavior) out.para(`No code: it's only for its one use (${i.use}).`, SCALE.body, COLORS.muted);
  for (const x of f.verbs) {
    out.para(`He can ${x.phrase ?? x.id.replace(/_/g, " ")}: ${x.says}`, SCALE.body, COLORS.ink, 0, 6);
  }
  if (f.behavior) out.para(`Every hour, by itself: ${f.behavior.says}`, SCALE.body, COLORS.ink, 0, 6);
  out.head(`WHAT IT LOOKS LIKE · ${plural(i.look.length, "shape")}`);
  for (const s of i.look) out.para(`${s.part || "part"}: ${s.shape}, ${s.color}, ${Math.round(s.w * 100)} x ${Math.round(s.h * 100)} x ${Math.round(s.d * 100)} cm${s.glow > 0 ? ", glows" : ""}`, SCALE.small, COLORS.muted, 8);
  changedDetail(out, v, i.id);
  for (const x of f.verbs) {
    out.head(`VERB · ${x.phrase ?? x.id}`);
    verbDetail(out, v, x, true);
  }
  if (f.behavior) {
    out.head("EVERY HOUR, BY ITSELF");
    behaviorDetail(out, life, f.behavior);
  }
}

/** A verb of his: what it does, how it's done, how often, and its code; [brief] leaves out what the invention's detail says already. */
function verbDetail(out: Detail, v: PageView, x: CodedVerbView, brief: boolean) {
  const life = v.life;
  if (!brief) out.para(x.says, SCALE.body, COLORS.ink, 0, 6);
  const where = x.at === "here" ? "wherever he is" : x.at === "what" ? "at the thing it takes" : `at the ${thingName(v, x.at)}`;
  const takes = x.what ? `takes ${x.what === x.at ? "it" : thingName(v, x.what)} · ` : "";
  out.para(`${takes}done ${where} · ${x.minutes ?? "?"} min · written ${at(x.day, x.time, x.tick, life)}` + (x.version > 1 ? ` · rewrite ${x.version - 1}` : ""), SCALE.small, COLORS.muted, 0, 4);
  const done = life.growth.done[x.id];
  const lock = life.shop.verbs.find((s) => s.id === x.id)?.locked ?? null;
  out.para((done ? `He's done it ${done.count === 1 ? "once" : `${done.count} times`}.` : "He hasn't done it yet.") + (lock ? ` Right now he can't: ${lock}` : ""), SCALE.small, lock ? COLORS.dear : COLORS.ink, 0, 4);
  if (x.why) {
    out.para("Why he wrote it", SCALE.small, COLORS.muted, 0, 8);
    out.para(x.why, SCALE.body, COLORS.ink, 16);
  }
  out.para("The code: " + (/\bcanDo\s*\(/.test(x.source) ? "canDo(world) says whether he can, " : "") + "finish(world) runs as he finishes", SCALE.small, COLORS.muted, 0, 8);
  out.code(x.source);
}

function behaviorDetail(out: Detail, life: LifeState, b: BehaviorView) {
  out.para(b.says, SCALE.body, COLORS.ink, 0, 6);
  out.para(`hourly(world, thing) runs every game hour while he has it · written ${at(b.day, b.time, b.tick, life)}` + (b.version > 1 ? ` · rewrite ${b.version - 1}` : ""), SCALE.small, COLORS.muted, 0, 4);
  if (b.failures) out.para(`It has broken ${plural(b.failures, "hour")} in a row; at 3 it stops until he writes it again.`, SCALE.small, COLORS.failed, 0, 4);
  out.code(b.source);
}

/** What his code changed about things and kept on them: one thing's ([id]), or all of it. */
function changedDetail(out: Detail, v: PageView, id: string | null) {
  const code = v.life.code;
  const changed = (code?.changed ?? []).filter((c) => id === null || c.id === id);
  const stored = Object.entries(code?.stored ?? {}).filter(([k]) => id === null || k === id);
  if (changed.length) {
    out.head(id === null ? "WHAT HIS CODE CHANGED ABOUT HIS THINGS" : "WHAT HIS CODE CHANGED ABOUT IT");
    for (const c of changed) {
      const what = [c.name ? `renamed "${c.name}"` : "", c.color ? `painted ${c.color}` : "", c.spot ? `moved to ${c.spot.replace(/_/g, " ")}` : ""].filter(Boolean).join(", ");
      out.para(id === null ? `${thingName(v, c.id)}: ${what}` : what, SCALE.body, COLORS.ink);
    }
  }
  if (stored.length) {
    out.head(id === null ? "WHAT HIS CODE KEEPS ON THINGS" : "WHAT HIS CODE KEEPS ON IT");
    for (const [thing, kept] of stored) {
      if (id === null) out.para(thing === "self" ? "himself" : thingName(v, thing), SCALE.body, COLORS.ink, 0, 6);
      for (const [k, value] of Object.entries(kept)) out.para(`${k} = ${value}`, SCALE.small, COLORS.code, 16);
    }
  }
}

/** A thing's name as he sees it: what his code renamed it, else its invented or catalogue name, else its id. */
function thingName(v: PageView, id: string): string {
  const renamed = v.life.code?.changed.find((c) => c.id === id)?.name;
  if (renamed) return renamed;
  const invented = (v.life.shop.inventions ?? []).find((i) => i.id === id);
  if (invented) return invented.name;
  return v.content.catalog.find((c) => c.id === id)?.name ?? id.replace(/_/g, " ");
}

function colorOf(life: LifeState, id: string): string | null {
  return life.code?.changed.find((c) => c.id === id)?.color ?? null;
}

// --- helpers -------------------------------------------------------------------------------------

/** The shop's items with what he knows of each, known things first. */
export function shopItems(life: LifeState, content: Content): { item: CatalogItem; state: CatalogState }[] {
  const items = new Map(content.catalog.map((c) => [c.id, c]));
  // What he invented isn't in the static catalogue. Food the shop restocks has a state of its own;
  // the rest is listed while he has it, as something he made.
  const made: CatalogState[] = [];
  for (const i of life.shop.inventions ?? []) {
    items.set(i.id, { id: i.id, name: i.name, group: i.group, price: i.price ?? i.cost, pack: 1, stackable: i.price !== null });
    const has = life.wallet.owns[i.id] ?? 0;
    if (i.price !== null || has === 0) continue;
    const like = Object.entries(i.like).map(([q, s]) => `${q} ${s}`).join(", ");
    made.push({ id: i.id, owned: has, affordable: true, gives: `his own invention${like ? ` (${like})` : ""}${i.why ? `: ${i.why}` : ""}`, taught_by: null });
  }
  const out = [...life.shop.catalog, ...made].flatMap((state) => {
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
