---
title: "Rooms are bought as plans and built onto the house on sites; building again moves one"
date: 2026-11-15
status: active
---

**Context.** Ian (2026-11-15): he can't buy, unlock or rearrange rooms; rooms are modules he buys and snaps onto
the house, movable later (as the snap-on prototype, `world_sketches/static/house.js`, had them).

**Decision.**

- **A room is content:** `world.yaml` `rooms` (rows with walls, slots, the items that come with it, qualities) and
  `sites` (a top-left on the lot, the doorway into the room it joins, and `next_to` the site whose room must stand
  first; none is the bedroom). Rooms share the wall they meet at. Shipped: the lab (bench, tall shelf) and the
  sunroom (armchair, floor lamp); sites east of the bedroom and east again.
- **Buying is the shop:** a room's plan is an item (group `room`, `buildable`) with a price and a tier. Unlocking
  is the tier: the sunroom's plan is tier 1, and a gated plan's room and what comes with it are gated with it.
- **Placing is a verb:** `build_room` (`what`: a plan he owns, `on`: a site) runs the `place_room` op, 4 hours.
  Building a plan whose room stands elsewhere moves it. Locked while the site is taken, the room it joins isn't
  built, or another room is built onto the one being moved. The closed choice is plan x site, so Jev and tool
  models pick it like any verb with arguments.
- **The map changes during a run:** `GridWorld.map` and `BedroomWorld.layout` are swapped between ticks;
  `Layout.of(content, house)` stamps each built room on its site and cuts its doorway. Places, perception, the map
  text, the protocol's `world` section and the renderer all follow. Checkpoints keep `house`.
- **A room he likes is comfort:** its qualities, by his affinities, as decor's are.
- **The web** draws each built room with the bedroom's cutaway shell; a room joined on its west side has no wall
  there (the joined room's sill is the shared wall), so the camera sees into both.

**Not done.** A doorway cut into the shared sill; rooms elsewhere than east (the lot is sea to the west and north);
more rooms and what they unlock (the lab as where lab pieces and the microscope go).
