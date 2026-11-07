---
title: "The protocol is a snapshot then deltas, split by how often state changes, and the host owns presentation timing"
date: 2026-11-06
status: active
---

**Context.** Phase 2 of the sim-host plan
(`docs/decisions/2026-11-06-one-sim-host-state-streamed-browser-renders-admin-only-v0.md`): a
headless host runs lives and streams their state to renderers. What was built is in
`research_notes/2026-11-06_1720_sim-host-and-protocol-built.md`.

**Decision.**

- **The protocol is its own pure-Kotlin module, `:protocol`,** with no project dependencies. Its
  types mirror the sim's (`LifeSnapshot`, `PieceView`, ...) instead of reusing them, so the wire
  can't change when the sim does, and a client in another language reads only this. Versioned by
  `PROTOCOL_VERSION`: a client joins with `?v=`, and the host refuses another version (`refused`)
  instead of sending deltas it would misread.
- **JSON over WebSocket, every property always written** (`Wire`: defaults and nulls included,
  `type` as the sealed-class discriminator, unknown keys ignored). The generated TypeScript then has
  no optional fields. At a few KB a second a binary format buys nothing.
- **Joining:** `welcome` (the version, the lives, each log's cap), `content` unless the client says
  it has that hash (`?have=`), a `snapshot` of the whole `LifeState`, then `delta`s in `seq` order.
  A client that sees a gap rejoins. A new run (restart, replay, resume) sends `content` and a
  `snapshot` instead of a delta.
- **State is split by how often it changes:**
  - static `content` (map, catalog items, verbs), keyed by the content hash `:runlog` keeps the
    bedroom's files under; rooms without content files use the hash of the message;
  - `run` (state, settings, models, notices), on a command or when the run ends;
  - `fast` (clock, needs, positions, intent, the activity and its progress, whether the clock is
    held and why), every publish while it changes, ten a second by default;
  - `easel`, the piece under way as screens should show it, on each stroke;
  - slow sections, each replaced whole when it changes: `wallet`, `growth`, `gallery`, `shop`
    (the catalog's and verbs' changing half), `mind` (plan, self-model), `tokens`;
  - `logs`, what only grows (transactions, world events, decisions, thoughts with their answers,
    memories of levels 1 and 2, self-model revisions, trips, drawings with their pictures). Each
    entry has a `seq`; a delta carries only new or changed entries, and the client merges by
    `seq` and keeps the newest N of each (the caps the welcome sends).
  A delta's section is null when unchanged. `LifeState.diff` and `apply` are the one definition
  of both sides; the reconstruction test checks that snapshot plus deltas, through the JSON,
  equals what the host published.
- **The host never waits on a viewer.** Each life is an actor on its own thread, publishing into
  a shared flow that drops the oldest message for a viewer that falls behind; that viewer's
  socket sees a gap in `seq` and gets a fresh snapshot instead.
- **Commands are HTTP, not on the socket.** `POST /v1/lives/{life}/commands` takes one command
  (start, pause, restart, replay, configure, resume, step) and runs it on the life's thread between
  ticks. The socket is read-only and open to anyone. Commands go through `AdminAuth`. For v0 that's
  a dev token from `LILGUY_ADMIN_TOKEN`, compared in constant time; with none set, every command is
  refused. GitHub OAuth replaces the implementation in the next phase, behind the same interface
  (done: `2026-11-06-admin-sign-in-is-a-signed-cookie-and-commands-need-a-csrf-header-and-origin.md`).
- **Presentation timing belongs to the life,** since every viewer shares one clock.
  `RunSettings` now carries `quiet`, `quietVerbs` and `holdForArt` per life. The view's
  `SimSession.linger()` is gone: `ArtShow` plays each stroke over a set number of real seconds
  (the old `PaintScene` pacing), and the session holds the clock while the show is still going
  after the piece's verb has ended. The easel says which phase the show is in and for how long, so
  renderers follow the host instead of keeping their own pace. The app feeds its `PaintScene` from
  the same show.
- **The host and the app run the same loop.** `SimSession`, `RunSettings`, `ModelSetup` and
  `RunDatabase` moved from `:app` to `:agent` (`dev.lilguy.agent.live`). The app calls `update`
  from its render thread; the host calls it from the life's actor.
- **TypeScript comes from the JSON Schema,** like the Pydantic rows. `ProtocolSchema` writes
  `protocol/schema/protocol.schema.json` from the descriptors with named `$defs`.
  `make protocol-types` turns that into `protocol/ts/protocol.ts` with json-schema-to-typescript
  15.0.4, in a Node container. `make test` fails when the schema is stale, or when the TypeScript's
  header doesn't name the schema's SHA-256. `make protocol-check` also regenerates the TypeScript
  and compares the two.
- **The host records like every other writer:** one `RunRecorder` and `LifeRecorder` per life
  into the local store, and one `SyncWorker` for the whole process.

**Not decided.** Whether viewers' renderers interpolate positions themselves from consecutive
`fast` states (the default assumption) or the host sends velocities. Whether `?have=` should
become a client-side cache of several content versions. How a client authenticates on the
socket once viewers can send things.
