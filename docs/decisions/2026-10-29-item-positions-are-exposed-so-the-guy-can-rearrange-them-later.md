---
title: "Item positions are exposed to the renderer, and become movable later"
date: 2026-10-29
status: active
---

# Item positions are exposed to the renderer, and become movable later

For the planned 3D room (`research_notes/2026-10-29_2053_3d-room-and-character-plan.md`), Ian
decided that item positions should be part of what the sim exposes, because a later iteration
will let the little guy organize the items available to him.

What is true today:

- **Positions already reach the renderer.** `BedroomWorld.snapshot()` emits an `EntitySnapshot`
  per placed item with its tile centre (`cell.x + 0.5`, `cell.y + 0.5`), so a 3D view can place
  models without sim changes.
- **They are fixed, not owned state.** Each item has one `slot` in `bedroom.yaml`'s room map, and
  `slotCells` maps slot to tile once, at load. An item's place is a property of the map text, not
  of the world's state. Slots are also how activities find where they happen (`at`), and the
  wall-slot list says which items sit in a wall.

Direction:

- **Positions stay in the snapshot** and the renderer reads them from there, never from the content file.
- **When rearranging arrives, position becomes sim state.** The slot becomes the item's default or
  starting place, an owned item has a current tile (and facing), and moving it is an intent with
  validation (inside the room, floor versus wall slots, not on top of other furniture or blocking
  the only path), logged like other changes to the world. Activities find an item by where it is,
  not by slot name.
- **Nothing is built yet.** The 3D spike reads the existing snapshot positions only.
