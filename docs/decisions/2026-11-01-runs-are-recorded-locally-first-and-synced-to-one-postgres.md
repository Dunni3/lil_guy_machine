---
title: Runs are recorded locally first and synced to one Postgres; saves are explicit checkpoints
date: 2026-11-01
status: active
---

**Context.** Every writer kept its own files: the CLI wrote decisions, tokens, money, needs and hours
as JSONL at the end of a run; the app wrote decisions as it went and tokens only on teardown, and
nothing else; memory on the TV lived in RAM. About 40 run folders in three days, compared by hand,
and a TV restart lost the life. Ian wants every run (TV, desktop, CLI, several at once) in one
structured, queryable database, with old runs used only on purpose. The design is
`research_notes/2026-11-01_2236_run-database-design.md`, approved by Ian.

**Decision.**

- **Local-first, one central store.** Every writer records rows into a local store and never waits
  on the network; a background sync pushes them to one Postgres on the Lightsail box that runs
  smarter-tv, in its own container and database. The server is the source of truth for queries.
- **One row format for everything,** in a pure-Kotlin `:runlog` module (no Android, no libGDX, no
  `:agent`): `@Serializable` rows, one per table, written through `RunRecorder.record`, which
  returns at once and does the I/O on its own thread. Ids are made on the client (UUIDv7), and
  every row of a run carries a `seq` from one counter per run, so rows sort across tables and the
  server can upsert on `(run_id, seq)`.
- **A run is one life; a session is one process lifetime in it; a batch groups runs.** The run row
  records the git commit, a hash of the content files, models and prices by role, personality and
  settings.
- **Big bodies are stored once,** as blobs keyed by SHA-256 (observations, requests, pictures).
- **Saves are explicit state exports** (`BedroomWorld.exportState` / `restore`, plus the agent's
  state) taken when he wakes, not a replay of the log; replay is the test of the export.
- **Lineage is explicit.** Resume continues a run; a fork is a new run with a parent and the
  checkpoint it started from; using earlier lives in a fresh one goes through a `run_lineage`
  table, empty until a run opts in.

**Built so far (step 1 of 5).** `:runlog` with the rows, `RunRecorder`, `JsonlRecorder` and UUIDv7
ids, and `agent/.../Recording.kt`, which turns decisions, model calls, answers, trips, drawings,
ledger entries, need changes, hourly samples and memory writes into rows. The CLI and the app
both record, each run to `rows-NN.jsonl` beside the old files, which stay until the local store
and an importer exist (steps 2 and 5). The TV now records ledger, needs and hourly rows.

**Consequences.** Anything a run produces from now on goes through `Recording`; a new kind of
record is a row type (or a `run_records` line until a query needs its own table). The old
per-kind JSONL files and `DecisionLog` stay for replay and for the importer, then go.
