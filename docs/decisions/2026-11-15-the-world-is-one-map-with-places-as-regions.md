---
title: "The world is one map: the house stands on a lot, places are named regions, and the protocol sends it as a slow section"
date: 2026-11-15
status: active
---

**Context.** Ian (2026-11-15) wants him to walk outside, explore and find things, and to buy, unlock and
rearrange rooms. The plan is `research_notes/2026-11-15_0925_world-places-perception-spatial-memory-plan.md`.
Ian's calls: rooms are modules he buys and snaps onto the house, movable later; one outdoor lot that grows
later; walking costs time only; samples are for a shelf collection, study, selling and materials.

**Decision.**

- **One stitched map.** `GridWorld` keeps one `TileMap` and one A*. `Layout` (`sim/.../Layout.kt`) composes it:
  the lot from `world.yaml` (rows of ground letters: grass, path, bare ground, flowers walk; water and trees
  block; the sea is void), the bedroom's rows (`room.yaml`) laid on it at `house.at`, and the front door cut
  through its wall at `house.door`. Rooms as separate maps joined by portals were rejected: every walk through
  a door would be a multi-map plan, and the renderer and the prompt want one frame of coordinates.
- **A place is a named region.** Rooms first (a room's rect includes its walls and doorways), then
  `world.places` in order; a tile belongs to the first that holds it. `Layout.placeOf` answers it, and every
  entity carries its place (`EntitySnapshot.place`).
- **Walking costs game time and nothing else** (Ian): it was already a tile a game minute.
- **Content without `world.yaml`** (an old run's content, a test's) is the bedroom alone, as before.
  Checkpoints are version 2; a version-1 checkpoint's position moves with the room onto the lot.
- **The observation's `localMap`** is the room he's in, or outdoors a 16-tile window round him, with
  `localMapAt` its top-left tile; the bedroom's map is the same text as before.
- **Protocol 3.** `Content.map` is gone (the house will change during a run); `LifeState.world` is a slow
  section, `WorldView` (rows of letters, places with rects and bounds, doors), replaced whole when it changes.
  `Entity.place`.
- **The web** paints the ground once from the letters (cut out off the land), stands the lot on stepped soil,
  puts a tree on every `T`, places the room shell by its rect with a gap and a step at the door, and frames
  the place he's in, easing between places, with the sun's shadow reach following the frame.

**Not decided.** How rooms are bought and snapped on (modules on a grid of cells, as `house.js` did, is the
plan); doors between rooms; whether a room's layout (`RoomDef` in `web/`) becomes data.
