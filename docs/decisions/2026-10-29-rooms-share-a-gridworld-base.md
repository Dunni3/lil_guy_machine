---
title: "Rooms share a GridWorld base"
date: 2026-10-29
status: active
---

# Rooms share a GridWorld base

Milestone 4 adds a second room, so the intent lifecycle (inbox, validate, run, finish, the
decision-point flag), A* walking, speech, events and publishing moved from `RoomWorld` into an
abstract `GridWorld`. A room supplies `plan` and `publish`, plus hooks (`afterWork`, `workEnded`,
`entered`, `passable`, `stamp`). Work gained `Until` (start, run until a condition, finish) and
`Steps` (walk, then do). `RoomWorld` behaves exactly as before: the scripted run still takes 212
ticks and Jev 148. Key-and-door stays as the quick CLI test.
