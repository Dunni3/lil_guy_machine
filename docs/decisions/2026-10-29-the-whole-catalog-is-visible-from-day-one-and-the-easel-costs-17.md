---
title: "The whole catalog is visible from day one, and the easel costs $17"
date: 2026-10-29
status: active
---

# The whole catalog is visible from day one, and the easel costs $17

From `research_notes/2026-10-29_2300_catalog-and-cheap-easel-plan.md`. Reverses the "tier 0 is the
small world" part of
[Gated tiers and a tighter economy](2026-10-29-gated-tiers-and-a-tighter-economy-food-takes-most-of-his-wor.md);
the tier mechanism itself stays.

- **Nothing in `bedroom.yaml` is gated.** The old tier-1 rows (canvas, easel, painting, record
  player and records, books, plant, poster, rug; `paint`, `read`, `listen`, `play_record`) have no
  `tier`, so they're in the shop, options and prompt from the start. Before, he could only ask for
  things he'd never heard of. `tier` and `granted` stay in the engine for later, bigger things
  (rooms, abilities, the outside), and the tests still exercise them by setting `tier = 1` on those
  ids in their own copy of the content.
- **The shop is the catalog.** The bedroom prompt calls `shop` the catalog and, when there's decor,
  says his room has empty places that the catalog fills. The journal prompt says the same `shop`
  is the catalog and asks him to say what in it he wants and is saving toward, or what it lacks.
  `shopOption` already says what each item is for, so no new engine code and no `browse_catalog`
  activity: the list is already in every observation.
- **Easel $120 -> $17.** At $3 a sketch, $120 couldn't be saved for. $17 is a few hours of spare
  sketching. This is an experiment: whether he buys it, then canvas ($15) and paint (~$18/h), and
  whether painting then makes sketching pointless, is unverified until a model run.
- The scripted policy only buys food, so its economy baseline (`BedroomRunTest`) is unchanged.
