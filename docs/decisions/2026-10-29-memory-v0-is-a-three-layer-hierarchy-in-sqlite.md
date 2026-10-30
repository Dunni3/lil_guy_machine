---
title: "Memory v0 is a three-layer hierarchy in SQLite"
date: 2026-10-29
status: active
---

# Memory v0 is a three-layer hierarchy in SQLite

Ian's design, replacing the plan's JSONL file with `remember`/`plan` fields (those wait for a
later step). Layer 0 is the event stream: one free-text row per observation shown and per action
chosen, nothing else (no thoughts yet). Layer 1 is memories: a language model reads the next 40
events and writes a first-person paragraph, stored with the event-id range and game-time span it
covers. The `memories` table has a `level` column so level 2 (summaries of summaries) is a new job,
not a new table. Layer 2 of the loop is recall: at each decision, the latest 3 memories plus up to
3 older ones sharing words with the current state and options, in 1200 characters, go into the
prompt (and into `state.memories` for picking models).

- **Storage is behind `MemoryStore`** in `:agent`; `:memory-sqlite` holds the SQLite file
  (xerial JDBC, JVM only) and `InMemoryStore` backs tests. The JDBC jar isn't packaged for the
  TV's 32-bit ARM, so the TV keeps no long-term memory until it has a store of its own (Android's
  built-in SQLite behind the same interface is the likely route). `:agent` stays free of it.
- **Everything is scoped to a `run`** (one life). Recall reads only the current run, so a fresh
  world doesn't remember a previous world's day 5; cross-restart continuity comes with saves.
- **Observation text leaves out the option lists** (same every time) and keeps the sim events
  that are new since the previous observation.
- **The summarizer must write text.** TypeSafe's Jev only picks from options, so it can't be the
  writer; `--memory-endpoint` names a different endpoint, and the constructor rejects a
  closed-choice model. A failed job stores nothing and retries from the same event.
- **Background vs inline.** `AgentMemory` summarizes in a given scope when it has one (never
  holding up a decision) and inline otherwise, which keeps lockstep runs and tests deterministic.
  Not wired into the app yet.
