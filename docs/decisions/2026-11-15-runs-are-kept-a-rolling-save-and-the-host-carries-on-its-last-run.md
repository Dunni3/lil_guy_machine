---
title: "Runs are kept: a rolling save per session, and the host carries on the run it was last on"
date: 2026-11-15
status: active
---

**Context.** Runs were recorded with a checkpoint each morning, but leaving one (restart, a deploy) lost everything
since that morning, and the host came back from a deploy on a fresh run. Ian wants runs he can keep, start and go
back to.

**Decision.**
- A checkpoint can only be taken between things: the world at rest at a decision point, the agent loop not deciding,
  no memory work running. So each session keeps one rolling save, a single checkpoint row (the same id, rewritten in
  place; ~5 KB) written at tick 0 of a fresh run (`start`), at decision points at most every 10 game minutes
  (`autosave`), and when the run is left if that moment can be saved (`left`). `LifeRecorder` holds the agent loop
  around every tick, not only overnight, to catch decision points. Morning checkpoints stay separate rows.
- Leaving a run (New run, Replay, Saved runs, the process going) never deletes it. Its run row says how it was left:
  `left`, `shut down`, or `shut down while running`.
- "latest" means the run played most recently (its latest session), not the one started most recently.
- The host's default (`LILGUY_RESUME` unset) is to carry on the bedroom run it played last, and start it again if it
  was `shut down while running`; with no save on that run it starts fresh, never an older run. `none` starts fresh.
- The run list is an admin-only HTTP read (`GET /v1/lives/{life}/runs`, `RunList` in `:protocol`), not part of the
  stream, so viewers never see it and the protocol version didn't change.

**Consequences.** Going back to a run replays what came after its save: at most 10 game minutes plus what he was
partway through. A model may decide differently the second time. Thoughts or memories written after the save stay in
the database but aren't part of the carried-on run (the memory lineage cuts at the save's watermarks).
