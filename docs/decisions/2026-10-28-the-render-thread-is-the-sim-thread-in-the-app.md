---
title: "The render thread is the sim thread in the app"
date: 2026-10-28
status: active
---

# The render thread is the sim thread in the app

`SimSession` steps `RoomWorld` at a fixed 20 Hz from `render()` with an accumulator and
interpolates between the last two snapshots. A tick costs microseconds, so it doesn't threaten
the frame, and it keeps "one sim thread" trivially true. The agent runs on
`Dispatchers.Default`. The CLI and tests use `runLockstep`, which ticks only once the agent has
submitted, so headless runs are deterministic.
