---
title: "Stepping back scrubs a snapshot history"
date: 2026-10-29
status: active
---

# Stepping back scrubs a snapshot history

LEFT used to step forward a second, because the world can only move forward and the agent runs
against it live. `SimSession` now keeps a rolling two-minute history of snapshots. While paused,
LEFT moves the view one tick back through it and RIGHT replays forward, advancing the real world
only at the live tick. Only the view rewinds; the world and the agent stay live. Each recorded
tick also keeps the agent's latest decision and how long it had been waiting on the model, so the
side panel and speech bubble match the frame on screen. Resuming (OK) jumps back to the live tick.
