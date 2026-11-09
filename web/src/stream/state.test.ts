import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { expect, test } from "vitest";
import type { ServerMessage, Snapshot } from "../../../protocol/ts/protocol";
import { StreamState } from "./state";

// A real life's stream, recorded from the host by `make web-test`: its last line is the host's own state.
const lines = readFileSync(resolve(__dirname, "../../../host/build/stream-recording.jsonl"), "utf8").trim().split("\n");
const stream = lines.slice(0, -1).map((l) => JSON.parse(l) as ServerMessage);
const host = (JSON.parse(lines[lines.length - 1]) as Snapshot).state;

test("the snapshot and deltas reproduce the host's state, and a gap asks for a fresh snapshot", () => {
  const client = new StreamState();
  for (const m of stream) expect(client.receive(m)).toBe("ok");
  expect(stream.filter((m) => m.type === "delta").length).toBeGreaterThan(50);
  expect(client.life).toEqual(host);
  expect(client.life!.logs.decisions.length).toBeGreaterThan(0);

  const missed = new StreamState();
  let gap = 0;
  let last = 0;
  for (const m of stream) {
    if (m.type === "delta" && ++last === 10) continue; // the one that goes missing
    if (missed.receive(m) === "rejoin") { gap = last; break; }
  }
  expect(gap).toBe(11);
  expect(missed.receive({ type: "snapshot", state: host })).toBe("ok");
  expect(missed.life).toEqual(host);
});
