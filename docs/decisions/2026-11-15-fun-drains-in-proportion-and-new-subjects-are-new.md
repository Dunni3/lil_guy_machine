---
title: "Fun drains in proportion to its level, and a subject he has never drawn is new"
date: 2026-11-15
status: active
---

# Fun drains in proportion to its level, and a subject he has never drawn is new

The live 3-day run sat at stimulation ("fun" in the HUD) near 0 from day 2 on. Fun is meant to be
the proxy for novelty: low fun should push him to explore, so it has to move.
`research_notes/2026-11-15_0235_fun-drought-diagnosis-and-proposals.md` has the evidence. Ian
picked P1 plus P3 from it.

**The drain scales with the level (P1).**
- rules.yaml `needs.stimulation_drain_at: 50`: stimulation loses `stimulation_per_hour` (-8) an
  awake hour at 50, and in proportion otherwise (-4 at 25, -16 at 100). Without the key the drain
  is flat, as before.
- **Why:** a flat drain with gains that grow when he isn't bored (more work, more money, new
  things) has no balance point, so fun sat at 0 or at 100 depending on small numbers.
  `afbb7be` (5.5 to 8 an hour) moved it from pinned high to pinned low. A proportional drain
  settles where what he does balances it, so the level says how varied his days have been.
- **Cost:** boredom is softer at the bottom (under 1 an hour below 6). The bored lock and the
  mood drag still apply below 40.
- The prompt says so: "falls while you're awake, faster the more you have (8 an hour at 50)".

**Cheap novelty that doesn't run out (P3).**
- The first piece he draws of a commission noun pays the novelty bonus (rules.yaml `novelty`)
  when the drawing is delivered. It's kept in `tried` as `subject:<noun>`
  (`BedroomWorld.subjectNovelty`) and read back as `BedroomWorld.subjectsDrawn`.
- The noun is the one the drawing showed, or the one its tags name, so a drawing model that tried a
  new subject is paid even if its shape didn't come out.
- The Painter offers at least half its own subjects with nouns he hasn't drawn, while there are
  any, and marks them "something new to him". Its instructions say a new subject lifts his
  stimulation.
- The Inventor redoes a design whose materials cost more than his money, with the reason, before
  the sim refuses it. A broke guy can still invent a cheap thing, and that is a new item and a
  new habit.
- **Limit:** only a drawing model draws subjects. The scripted policy's pieces are undrawn, so
  they get none of this.

**Not done:** the bored lock still leaves him idle when everything he owns is worn, and Jev's
menu has no "something new" branch (P4).
