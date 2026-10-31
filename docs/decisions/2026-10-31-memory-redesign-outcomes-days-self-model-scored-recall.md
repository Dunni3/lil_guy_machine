---
title: "Memory redesign: no journal or wants; outcome events, day memories, a self-model, Jev-scored recall"
date: 2026-10-31
status: active
supersedes: 2026-10-29-the-journal-a-second-memory-aware-llm-call-that-records-what.md
---

# Memory redesign: no journal or wants; outcome events, day memories, a self-model, Jev-scored recall

Built from `research_notes/2026-10-31_1527_memory-redesign-plan.md` (a review of memory v0 with
Ian). It supersedes:
- All of `2026-10-29-the-journal-a-second-memory-aware-llm-call-that-records-what.md`.
- The journal, the persona goal and "memories every 20 events" parts of
  `2026-10-29-think-intent-journal-on-by-default-memories-every-20-events.md`.
- The event stream and level-1-only parts of `2026-10-29-memory-v0-is-a-three-layer-hierarchy-in-sqlite.md`
  (SQLite stays, and so does the `memories` table with its `level` column).

**No journal, no wants.**
- The `journal` verb, its item, `needs.neglect` (whose only rule was the journal's), `Want`,
  `JournalWriter`, the journal store and table, the journal destination and handler, the JOURNAL
  dev-view page and `TokenPurpose.Journal` are gone. `DecisionLog.read` still skips old
  `"kind": "journal"` lines, so old logs replay.
- His plans and latent desires live in his thoughts, which are now recallable.
- `journal_endpoint` is now `writer_endpoint` (thoughts, the self-model, commission wording). The
  old name is still read, and so are `--journal-endpoint` and `JOURNAL_ENDPOINT`.

**The persona is a fixed core plus a self-model.**
- `personality.yaml` holds `core` (`you` for prompts, `he` for Jev's state) and `self_seed`. The
  core says he is intelligent, creative and capable of a full and rich life, that what he makes
  of it is up to him, and (a stopgap for runs without a self-model) that there are things to learn
  and make. There's no "restrictive little world", no "living here a long time" and no creator line.
- What he can do is a world fact, so it's in the rules ("What you can do: ...").
- Taste is still generated from affinities, so the prompt can't contradict the sim.

**Events record outcomes.**
- One `outcome` event per finished intent, from the sim's own words for what its ops did
  (`Observation.outcome`, set by `GridWorld.outcome`), with how long it took: "sketch (1h): done;
  +1 sketch, energy -6, mood -4, stimulation +3".
- `world` events are what happened around him (`Observation.news`, the recent events minus the
  outcomes). There's a `snapshot` (money, needs, belongings) once a day when he wakes. `thought` and
  `art` events are as before.
- There are no more full observations and action lines at every decision.

**Memories are a hierarchy with days.**
- Level 1 is a stretch of events. With a scorer, a memory is written when the significance of
  uncovered events adds up to `Summarizer.THRESHOLD` (1.5, not tuned), with at least 4 events. Without
  a scorer it's every 20. A stretch never crosses a day change or runs past 20 events, and each
  memory is written with the previous one in view.
- Going to sleep flushes the day's events, then writes one day memory (level 2) over its level-1
  memories. At most one is written per game day, and only when there's something new.
- Memory importance is the most significant thing it covers.

**The self-model is level 3, revised nightly.**
- After the day memory, one text-model call (`SelfModeler`) reads the core, the current self-model,
  the new day and up to three before it, and the day's thoughts. It returns `{changed, self, note}`,
  where `self` has fixed sections: who, cares, believes, working_toward, learned.
- Each changed version is a level-3 row whose `text` is `{self, note}` as JSON. The latest is
  current, and every version is kept.
- Stability comes from temperature 0.3, a 180-word cap, fixed sections, a core it can't edit, and a
  summarizer that never sees the self-model.
- It needs both a memory writer and a writer. The system prompt and the persona are read per
  request (`PromptBuilder.systemFor`), so they change when it does, once a night.

**Recall is scored by Jev, keywords otherwise.**
- `Scorer` (`decision/.../Scorer.kt`) sends many TypeSafe Score questions in one request.
  `TypesafeBackend` implements it.
- `MemoryScorer` asks two things. One is relevance: one question per candidate, with the memory
  in an object `instructions`. The other is significance: one question per new event.
- Candidates are every day memory, the level-1 memories of today and yesterday plus any not yet in
  a day, and every kept thought except the latest (that one is shown as his plan).
- `Recall.selectRanked` scores relevance + 0.5 importance + 0.5 recency (24-game-hour half-life).
  It always keeps the latest memory and takes up to 6 within 1500 characters.
- Scoring runs in the background after each choice, with the state plus what he just chose, so the
  ranking is ready by the next decision. If it isn't, the previous ranking is used.
- The scorer is `recall_endpoint` / `--recall-endpoint`, defaulting to the deciding endpoint when
  it's typesafe ("none" for keywords). Token purposes are `recall`, `significance` and `self`.
- There's no stage 2 yet.

**Not done:** disk persistence for the app and the TV (part of the save/checkpoint effort). Also
not done: tuning the threshold, levels, weights and N, measuring Jev latency at a few hundred
questions, and re-running the scripted baseline after the journal's +12 mood went away.
