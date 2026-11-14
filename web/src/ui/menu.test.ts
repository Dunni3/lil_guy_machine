import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { expect, test } from "vitest";
import type { Content, LifeState, ServerMessage, Snapshot } from "../../../protocol/ts/protocol";
import { Menu, type Press } from "./menu";
import { TABS, detail, entries, type PageView } from "./pages";

// The host's own state at the end of a real scripted life, recorded by `make web-test`.
const lines = readFileSync(resolve(__dirname, "../../../host/build/stream-recording.jsonl"), "utf8").trim().split("\n");
const content = lines.map((l) => JSON.parse(l) as ServerMessage).find((m): m is Content => m.type === "content")!;
const host = (JSON.parse(lines[lines.length - 1]) as Snapshot).state;

const view = (life: LifeState, admin: boolean, menu: Menu): PageView => ({
  life, content, admin, settings: life.run.settings, replayPick: menu.replayPick, confirm: menu.confirm, result: null,
});

test("viewers get the pages and a sign-in but no run controls; the admin's keys start, configure and restart only as asked", () => {
  const menu = new Menu();
  const watching = view(host, false, menu);
  // Every tab lists and details the real state without falling over, and one of each log the scripted life doesn't write.
  const self = { who: "a small painter", cares: "colour", believes: "", working_toward: "a record player", learned: "" };
  const logs = {
    ...host.logs,
    thoughts: [{ seq: 1, tick: 9, day: 1, time: "09:00", thinker: "w", text: "Blue again?", error: null, asked: [{ question: "Q", opened: ["paint"], answer: null }] }],
    memories: [{ seq: 1, level: 2, span: "day 1", from_day: 1, to_day: 1, text: "A day.", importance: 0.5 }],
    revisions: [{ seq: 1, day: 1, changed: true, note: "more blue", self, error: null }, { seq: 2, day: 2, changed: false, note: null, self: null, error: "timeout" }],
    trips: [{ seq: 1, tick: 9, day: 1, time: "09:00", shopper: "w", catalog: ["- easel (Easel), $17, paints"], buy: [{ item: "easel", why: "to paint" }], save_for: null, chose_saving: true, note: null, error: null, fell_back: null }],
  };
  const full = { ...watching, life: { ...host, logs, mind: { ...host.mind, self_text: "seed", self_model: self, plan: logs.thoughts[0] } } };
  for (const v of [watching, full]) for (const { id } of TABS) for (const e of entries(id, v)) expect(detail(id, v, e).length).toBeGreaterThan(0);
  expect(TABS.map(({ id }) => entries(id, full).length)).toEqual([1, host.shop.catalog.length, 1, 2, 3, 1]);
  expect(entries("shop", watching).length).toBe(host.shop.catalog.length);
  expect(entries("run", watching).map((e) => e.key)).toEqual(["signin"]);
  // A viewer's first opening lands on Sign in, so the admin finds it; after that the tab stays where they left it.
  menu.show(watching);
  expect(menu.tab).toBe("run");
  expect(menu.press("ok", watching)).toEqual({ type: "signIn" });
  menu.press("right", watching);
  menu.close();
  menu.show(watching);
  expect(menu.tab).toBe("shop");

  // The admin, on a run that hasn't started: OK twice from the room starts it, and the menu closes.
  const ready = { ...host, run: { ...host.run, state: "ready" as const } };
  const admin = new Menu();
  admin.show(view(ready, true, admin));
  expect(admin.tab).toBe("run");
  expect(admin.press("ok", view(ready, true, admin))).toEqual({ type: "command", command: { type: "start" } });
  expect(admin.open).toBe(false);

  // On Speed, LEFT asks for the next speed down (the recording runs at the top one, 60); on Run, it pages.
  const v = () => view(host, true, admin);
  admin.show(v());
  const keys = (...ps: Press[]) => ps.map((p) => admin.press(p, v())).filter(Boolean);
  expect(keys("down", "down", "right", "left")).toEqual([{ type: "configure", settings: { ...host.run.settings, speed: 30 } }]);
  // Restart needs OK twice; anything between cancels.
  expect(keys("down", "down", "down", "down", "ok", "up", "down", "ok")).toEqual([]);
  expect(admin.confirm).toBe(true);
  expect(keys("ok")).toEqual([{ type: "command", command: { type: "restart" } }]);
});
