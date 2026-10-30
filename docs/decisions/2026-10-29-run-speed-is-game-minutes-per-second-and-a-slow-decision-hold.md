---
title: "Run speed is game minutes per second, and a slow decision holds the clock"
date: 2026-10-29
status: active
---

# Run speed is game minutes per second, and a slow decision holds the clock

The app no longer ticks at a fixed 20 Hz. The dev view's Run page (`RunSettings`) sets speed in
game minutes per real second, and the tick rate follows: ticks per second = speed x ticks per game
minute. Speed changes everything together (walking, activities, needs), so it can change mid-run.
Ticks per game minute (the clock's resolution) is in his prompt, so it changes only with a new run.

A model's reply takes real seconds whatever the speed, and he stands still meanwhile, so at high
speed latency would eat his day. The clock may run at most `decideCap` game minutes (default 15)
while a decision is out, then holds until the reply arrives. 0 is lockstep, as in the CLI, and
"runs" is the old behaviour. Background writers (memories, the journal) never hold the clock.

Length in days applies to the live run: raising it extends the run, and reopens one that ended on
its days. Replay starts a fresh run with the settings an earlier run began with. The sim has no
randomness yet, so that is the whole of its starting state; a world seed belongs in `RunSettings`
once there is one.
