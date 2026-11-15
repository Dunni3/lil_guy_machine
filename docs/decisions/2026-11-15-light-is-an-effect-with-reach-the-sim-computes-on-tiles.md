---
title: "Light is an effect with reach the sim computes on tiles; what he sees, what he can do where, and the web's lamps all read it"
date: 2026-11-15
status: active
---

# Light is an effect with reach the sim computes on tiles

**Context.** Nothing he owned did anything to the space round it, so where a thing stood didn't
matter, and the sim had no light: day and night were only the clock. In live runs he invented six
lamps "to light up the room at night", and they did nothing. The plan
(`research_notes/2026-11-15_1451_effects-with-reach-world-plan.md`, phases 1 and 3) is one primitive,
an effect with reach, that perception, verb conditions and needs read. Light is the first.

**Decision.**

- **The engine is general** (`sim/.../Effects.kt`). An `EffectKind` (only `light` so far) has what
  blocks it (walls and trees, for light) and a ceiling. A field is a baseline on every tile plus each
  `Emitter`: strength on its own tile, falling with the square of the distance to nothing one tile past
  its radius, along a clear straight line. An emitter in a room reaches only that room, and one outdoors
  only the land. A field is worked out again only when its inputs change (the layout, the emitters, the
  baseline's key). Warmth, sound, sun and water, and attraction are meant to be further kinds on the
  same engine, not new systems.
- **Light** (`sim/.../Lighting.kt`, rules.yaml `light`) runs from 0 to 10, where 10 is noon outdoors.
  Outdoors it is the daylight by time of day (a curve of `HH:MM` keys that matches the web's `day.ts`).
  In a room it is `indoors` (0.4) of the daylight, plus each window (room.yaml and world.yaml room
  `windows`), which lets in 0.8 of the daylight over 8 tiles. At night the moon gives 0.5.
  The weather's `daylight()` (1 clear, less under cloud, rain or fog) scales it, and a weather
  station logs this light at its own tile (0 to 10), so a lamp beside it shows in its log. Items give light with
  `light: { strength, radius }`: the floor, lava, mushroom and invented lamps, the string lights and
  cloud light, and the glowcaps (a faint 1.2, out while the patch is picked bare). He carries a faint
  lantern (`light.lantern`, 1.5 over 1.5 tiles): enough to make out what's beside him, not to work by.
- **What he perceives.** He sees a thing with `see` (1) light on its tile. At night `nearby` holds only
  what's lit. What he knows is there without seeing it shows as `remembered`: his house's things, and
  the things in a place he has explored. Anything else isn't listed at all. `Observation.light` gives a
  few landmark phrases ("where you stand: light 3.2, from the sunroom lamp", "lit by the sunroom lamp
  (light 2.9 to 6.2): the picture rail, the bed and the mail hatch", "too dark to see the rest of the
  room; you remember the desk and the chair there"). They appear only when something in his place is
  darker than the most light any verb needs, so by day there's usually nothing to say.
- **Verbs need light where they're done.** `needs: { light: N }` on a verb (verbs.yaml) is checked on
  the tile it would be done at: one of the things in `at`, or where he stands for `here`. Below N the
  verb is locked as "too dark to read at the bed or the chair (light 0.4; it needs 3)", after the need
  blocks, so tiredness is still the reason when it is one. Of the things it can be done at, he walks to
  the nearest that is lit enough. Read, sketch, paint, invent, devise, write_letter, study_sample,
  explore and collect all need 3, more than his lantern and the moon (2), so he can't forage by lantern
  alone. The desk has its own little lamp (3.5 over 0.8 tiles), as its model shows: enough for desk
  work at night, not for reading in the chair beside it. By day the room's 4 is enough everywhere. From about 19:30 a
  spot needs a window or a lamp.
- **The web lights the scene from the sim.** The protocol's `Entity.light` (`{ strength, radius }`)
  carries every sim light, his lantern included. The renderer's fixed pool of 4 point lights takes the
  lights in the place on screen first, then the brightest, each with its range from the sim's radius.
  His lantern's reach is the sim's. Nothing is added per frame, and the pool stays at 4, so the TV's
  fill rate is unchanged.

**Why tiles and phrases.** The lot is 40x30 tiles and A* already works per tile, so the sim's light
matches the renderer. Models read landmark phrases better than coordinates, and the phrases cost
little: over two scripted days the `light` field was present on 23 to 43% of decisions, at 44 to 70
tokens when present (about 13 to 30 a decision overall, by characters / 4). The system prompt's new
sentences on light and comfort are about 130 tokens (net about 90, since the house-cap sentence is
gone), the same every decision, so cacheable.

**With phase 0's `put`.** A lamp he carries lights where he is, and one put down lights its new spot.
With the amber floor lamp put at `pond_shore`, at 22:00 the pond is explorable and he sees its edge
and the lamp.

**Not done.** Lamps are always on (no switch). His code can't read or emit effects (`Scripts.kt`, a later session).
Shade from furniture, and a carried lantern as an item, are possible next steps.
