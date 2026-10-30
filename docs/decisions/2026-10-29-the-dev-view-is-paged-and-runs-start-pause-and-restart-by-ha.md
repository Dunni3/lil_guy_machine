---
title: "The dev view is paged, and runs start, pause and restart by hand"
date: 2026-10-29
status: active
---

# The dev view is paged, and runs start, pause and restart by hand

Ian asked for a UI to explore a run rather than watch a loop. The map stays on the left; the right
panel is a header (run number and state, model, memory writer, game clock, when the run ends) over
four pages flipped with LEFT/RIGHT: **Now** (needs, what he's doing, the latest decision, recent
events), **Memory** (short-term recent actions with results; long-term memories newest first,
marking the ones recalled at the latest decision; events recorded and how many until the next
memory), **History** (every decision this run; UP/DOWN picks one, and the map shows the world as
it was then under a "THE PAST" banner) and **Log** (money and the event log).

- **Run control.** A run waits in READY until OK; OK then pauses and resumes. BACK twice throws it
  away for a blank run that again waits for OK (R on a keyboard does it at once). A run ends on its
  own (the key-and-door room solved, or the bedroom's days lived, 3 by default, `DAYS=0` for no
  end) and stays ended: nothing restarts automatically. Rejected: auto-starting and the old
  3-second reset loop.
- **History is per decision, not per tick.** Each decision is kept with the snapshot at the tick
  it was observed, for the whole run (capped at 5000). The per-tick scrub-back (LEFT/RIGHT while
  paused) is gone; "." steps one tick while paused on a keyboard. Decisions are the unit worth
  flipping through, and LEFT/RIGHT now flip pages.
- **The app records memory.** Every run gets an `AgentMemory` over an `InMemoryStore`, summarizing
  in the background in a per-run scope that restart cancels. The writer is the config's new
  `agents[0].memory_endpoint` (override with `MEMORY_ENDPOINT=<name|none>` for `make desktop` and
  `make tv-run`). Without one, events are still recorded but nothing is written up. Memory doesn't
  survive a restart or app exit (no store on the TV yet). An ended run flushes its last memory.
- `DecisionRecord.recalled` carries the memories each prompt got; `AgentRuntime.recent` exposes
  short-term memory to the view.
- `make tv-run` now force-stops before launching (`am start -S`), so new extras take effect.
- Decision logs from the app are named `run-NNN.jsonl` (were `episode-NNN.jsonl`).
