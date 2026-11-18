import { expect, test } from "vitest";
import type { Easel, UnderwayView } from "../../../protocol/ts/protocol";
import { EaselPlay } from "./easel";

const piece = (rows: string[], strokes: number, crayon: number | null = 1): UnderwayView => ({
  tick: 7, item: "sketch", title: "Pond", verb: "sketch", palette: [0xffffff, 0x3366cc, 0xcc3333], strokes, of: 3, crayon, state: "drawing", size: rows.length, rows,
});
const easel = (underway: UnderwayView, phase: string, seconds: number): Easel => ({ underway, phase, phase_seconds: seconds, shown: 0, drawn: underway.strokes });
const lit = (p: EaselPlay) => Array.from({ length: p.size * p.size }, (_, i) => p.bytes[4 * i] !== 0xff);

test("a stroke's cells go on over the host's seconds, after the dip, and a cut-short stroke finishes when the next phase comes", () => {
  const play = new EaselPlay();
  play.update(easel(piece(["aaaa", "aaaa", "aaaa", "aaaa"], 0), "ponder", 0), 0, 0.1);
  expect(lit(play).filter(Boolean)).toHaveLength(0);

  // The dip already carries the stroke's picture; it waits for the stroke.
  const first = piece(["bbbb", "aaaa", "aaaa", "aaaa"], 1);
  const dip = easel(first, "dip", 0.9);
  play.update(dip, 0, 0.1);
  play.update(dip, 0, 0.5);
  expect(lit(play).filter(Boolean)).toHaveLength(0);
  expect(play.color).toBe(0x3366cc);

  const stroke = easel(first, "stroke", 1);
  play.update(stroke, 0, 0);
  play.update(stroke, 0, 0.5);
  expect(lit(play).filter(Boolean)).toHaveLength(2);
  expect(play.lifted).toBe(false);

  // The next stroke's dip arrives before the first has gone on: it finishes at once.
  const second = piece(["bbbb", "cccc", "aaaa", "aaaa"], 2, 2);
  play.update(easel(second, "dip", 0.9), 0, 0);
  expect(lit(play).slice(0, 4)).toEqual([true, true, true, true]);
  expect(lit(play).slice(4, 8)).toEqual([false, false, false, false]);
});
