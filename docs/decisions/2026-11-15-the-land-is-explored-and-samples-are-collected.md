---
title: "The land is explored from lookouts, samples are collected from patches that regrow, and kept, studied or sold"
date: 2026-11-15
status: active
---

**Context.** Track C of the Field Lab plan, samples first. Ian (2026-11-15): samples are for a shelf collection,
study for skill, selling and materials.

**Decision.**

- **All of it is content and two ops.** `world.yaml` gains `slots` (tiles on the lot things stand on, as the
  room's) and `finds` (a patch, what it yields, its `regrow_hours`). Each outdoor place has a lookout
  (`explorable`: the garden bench, the forest trail, the log in the clearing, the pond's edge) and patches
  (`forageable`). They're fixtures he has from the start (an older checkpoint gets them back on load).
- **Finding is knowing.** Patches are hidden (`BedroomContent.taught` includes them) until `explore` at their
  place's lookout runs `survey`, which makes them known, as finishing a book does. Until then nothing offers or
  maps them, `nearby` leaves them out, and `collect` is locked "you haven't found anywhere to collect yet".
  `nearby` is now only what's in his place.
- **`collect` runs `gather`:** +1 of what the patch yields, then the patch is bare (entity state `bare`, its own
  `_bare` model) and `collect` there is locked with when it grows back.
- **Samples** (group `sample`) have a new affordance, `specimen`, priced like `sellable`, so `sell_art` (which
  sells everything `sellable`) leaves the collection alone. `sell_sample` sells a kind (`sell` now takes an
  argument slot). Each kind he keeps adds comfort (rules.yaml `collection`: 2 each, up to 6 kinds), and the web
  shows them as jars on his shelf. `study_sample` at a microscope raises `nature`; the microscope and the verb are
  tier 1, as the easel is, so tier 0's shop still sells only meals.
- **What he's told:** an Outdoors line in the rules, and the map says which places he has explored and, from
  what he knows, what he can collect there.

**Not done.** Samples as materials for inventing (Ian's fourth use); studying teaching things; whether exploring
at night should be allowed (a live model went to the pond at 02:42).

**Measured.** One live game day on gemini-3.5-flash (tools), Jev drawing: he explored all four places and
collected five samples, mood 62–75 (46–61 the day before places existed), 4.5k–4.8k prompt tokens a decision,
$0.23 a game day.
