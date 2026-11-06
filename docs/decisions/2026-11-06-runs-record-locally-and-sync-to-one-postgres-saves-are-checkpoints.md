---
title: Runs record into a local SQLite store and sync to one Postgres; saves are checkpoints
date: 2026-11-06
status: active
---

**Context.** Every writer had its own JSONL layout, the TV's memory lived only in process memory,
and nothing could be resumed or queried across runs. This is phase 1 of the sim-host plan
(`docs/decisions/2026-11-06-one-sim-host-state-streamed-browser-renders-admin-only-v0.md`): the
sim host's store is this local store. The design is in
`research_notes/2026-11-01_2236_run-database-design.md`, and what was built is in
`research_notes/2026-11-06_1634_run-database-built.md`.

**Decision.**

- **Local-first writes, one central Postgres.** Every writer (the CLI, the desktop app, the TV,
  and later the sim host) records into one SQLite file per device. On the Mac that's
  `runs/lilguy.db`; on the TV it's the app's files dir. Writes never wait on the network. A
  background `SyncWorker` sends the rows to the server. The server's Postgres is the source of
  truth for queries.
- **One table list, two stores.** `runlog/.../Tables.kt` defines every table once. The SQLite
  DDL comes from it at runtime. The Postgres DDL is generated into `server/sql/001_tables.sql` by
  `make server-schema`. Row types are `@Serializable` classes in `:runlog`. `:runlog` is pure
  Kotlin, and the SQLite driver is passed in: `:runlog-jdbc` on the JVM, `android.database.sqlite`
  in `:android`, so there are no natives to package for the TV. `:memory-sqlite` is gone. Memory
  lives in the run store (`RunMemoryStore`).
- **The ingest service is Python (FastAPI)** (Ian, 2026-11-06), in `server/`. It reads column
  names and types from Postgres, so its only copy of the schema is the generated SQL.
- **Sync keeps the highest `_rev`.** Every row carries `_rev` (how many times it was written on
  its device). Locally it also carries `_dirty` (not yet taken by the server). The server upserts
  on the primary key `WHERE _rev < EXCLUDED._rev`. So a batch sent twice or out of order lands the
  same, and rows that change after they're written (a decision's outcome, an event's
  significance, a run's end) sync again. This replaces the design's per-table `sync_state`
  watermarks. Blobs go first (`HEAD`, then `PUT` if missing). **No foreign keys on the server**,
  because batches may arrive in any order.
- **Ids are made on the device:** UUIDv7 for runs, sessions and event rows. Fact tables also carry
  a per-run `seq`, continued across sessions. Fact tables are keyed by `(run_id, seq)` or by id,
  never by tick or piece number. A resumed life replays the ticks after its checkpoint, and both
  branches keep their rows.
- **Full prompts on every call** (Ian), stored once by SHA-256 and gzipped. The system prompt is
  its own blob because it repeats. `--no-bodies` turns bodies off for long batches.
- **Retention** (Ian): the server keeps everything. A device drops a run 30 days after it ended,
  once the server has all of it (`RunStore.prune`).
- **Saves are explicit state exports, taken each morning.** `BedroomWorld.exportState()` /
  `BedroomWorld.restore()` plus `AgentState` (the loop's short-term memory and the long-term
  memory's bookkeeping) go in a `checkpoints` row. It's taken at the first decision point after he
  wakes, when nothing is in flight. The sim thread holds the agent loop at its wait
  (`AgentRuntime.hold`) so world and agent are read at the same moment. Restore reports the ids the
  content no longer has, or refuses them with `strict`. Resume adds a session to the same run;
  memory reads see only that branch's past (`RunMemoryStore.lineage`). The checkpoint round-trip
  test checks the export against a deterministic replay.
- **The server lives on its own Lightsail instance, Small (2 GB)** (Ian), not on smarter-tv's
  box. Postgres 16, the API, and Caddy for TLS run in one compose stack. Postgres is published on
  the box's localhost only, for SSH tunnels. Auth for v0 is a bearer token per writer.
- **Old JSONL logs are imported** with `make import-runs`. Their ids come from their paths, so
  importing twice changes nothing, and their runs are marked `settings.imported`. The JSONL writers
  stay on for now.

**Not done yet.** Resuming from the app's Run page, and pulling a run's rows from the server to
resume it on another device. The desktop and TV can resume at start with `RESUME=<id|latest>`.
The server isn't deployed.
