---
title: "Machines are fed and wear out, and his code makes nothing from nothing: physical costs replace \"uncapped\""
date: 2026-11-15
status: active
supersedes: 2026-11-15-he-writes-his-verbs-and-what-his-things-do-as-code.md (its "Uncapped" part only)
---

# Machines are fed and wear out, instead of uncapped

**Context.** The PR #60 decision (`2026-11-15-he-writes-his-verbs-and-what-his-things-do-as-code.md`)
let his code add money, set needs and give him things with no limit, so a run could be broken by a
verb that prints money. Phase 5 of the world plan
(`research_notes/2026-11-15_1451_effects-with-reach-world-plan.md`) asked for the answer to come from
the world rather than from an arbitrary cap. Ian doesn't want made-up costs, such as energy to carry or
clutter penalties. Costs should be inputs, upkeep and wear. **This is the default, and Ian may overrule
it:** the PR says so.

**Decision.**

- **Nothing comes from nothing.** Money comes from selling things, things come from machines fed with
  inputs (or are collected or bought), fullness comes from eating, energy from sleep, and skill from
  doing things himself. In his code, `world.earn` and `world.give` are gone. Calling one throws an
  error that says where money or things come from instead (`ScriptApi.RETIRED`). `world.need` can't raise
  fullness or energy. `world.practice` works only in his own verbs, not in a thing's code.
  `world.make` pays for the design's materials, as the workbench does. `world.sell(id, n)` sells
  something that has a price (samples), but not art, which is sold at the mail hatch.
- **A machine is a thing that runs recipes** (`sim/.../Works.kt`). An item's `machine: { runs, holds,
  wear }` (items.yaml) names recipes from rules.yaml `machines.recipes`. A batch takes its inputs and,
  after `hours` of working, makes its outputs. It works only while it has the inputs, room for the
  outputs (`holds` of each) and the effects it `needs` at its tile (light, read on the light engine
  without his lantern). Each machine has a store (inputs, outputs, wear, progress) that is saved with the
  world (`BedroomState.works`) and rolled back with everything else when his code breaks partway.
- **Flows go through nearness or his hands.** Every 10 game minutes, a machine takes what it needs from
  what another machine has made within `reach` (3) tiles in the same place. Anything else he carries
  over with the `tend` verb (kind `upkeep`, routine, at the machine): it puts in what he has that the
  machine takes and takes out what it made. His code moves things with `world.transfer(from, to, item,
  n)` between a machine and him (`"self"`), or between two machines. Both ends must be within reach,
  and so must the thing whose code it is. `world.machine(id)` reads a machine's store.
- **Wear is the upkeep.** A batch adds the machine's `wear`, or the rules' `wear_per_batch` if it has
  none. A run of a thing's code that changes something adds `wear_per_run`, so every machine of his has
  upkeep, the sprinkler included. Past `slow_at` (50) a machine works slower, down to half speed at 100.
  At 100 it jams: its batches stop, and its code doesn't run. The `repair` verb uses a spare part ($8
  for 2) and 45 minutes, and takes off `restores` (45) plus `per_level` (25) for each level of
  tinkering, a new skill that rises 0.2 with each repair. So mending gets cheaper with practice but always
  costs a part and his time. Anything with code answers to the `machine` tag, so it can be repaired too.
- **The worked chain.** Eating a meal leaves a food scrap (`eat` now produces `food_scrap`). The
  compost bin (garden corner, $25) turns 3 scraps into a compost brick in 6 hours and wears 15 a
  batch. The raised bed beside it ($20, 2 tiles away) takes the brick itself and grows a serving of cherry
  tomatoes from it in 10 hours of daylight (light 3), wearing 8 a batch. (The indoor `planter` from the
  warmth-sound-water decision grows the same cherry tomatoes by watering and sun instead; the raised bed
  is the machine that grows them from compost.) Cherry tomatoes are food, and eating them leaves a scrap. The
  loop leaks: 3 scraps make 1 brick, which makes 1 serving, which leaves 1 scrap. So 3 meals give 4.5 in
  all, and the chain needs bought meals, his walks and spare parts to keep going.
- **His inventions join a chain** through `Design.runs`, the recipes a viewable invention runs. The
  inventor's schema and the workbench's `build` list them with their inputs and outputs. New recipes
  are content only, so an invention can't define a better exchange rate than the world's.
- **How it shows.** A machine without a code readout shows the sim's own readout ("composting 40%",
  "growing: dark", "full", "ready", "empty", with " · worn" past 50, or "jammed"). Its entity `state` is
  "jammed", "full" or "worn", so he sees it in `nearby`. Batches, flows, "is full" and "jammed and
  needs repairing" are events in the machine's place, through the placed-event path from machines-reach:
  he hears of them when he's there or next arrives.

**Why not a cap.** A cap on dollars per day, or on calls, is a number that stands for nothing in the
world, and he would just hit it. Conservation plus wear gives every printer an input bill and an upkeep
bill. Those bills use the same prices and time as everything else, so what's worth building is what's
worth it in the world.

**Consequences.** His existing code that calls `earn`/`give`, or `need("fullness", +n)`, now fails with
the reason. Live-run verbs and behaviors that relied on them break until he rewrites them, and three
broken runs stop a behavior, as before. The coder's brief replaces "nothing caps it" with one paragraph
on where things come from and on wear. The API table loses `earn` and `give` and gains `machine`,
`transfer` and `sell`. The prompt gains `tend` and `repair` (locked until he owns a machine). Jev's menu
has a `machines` node under the workshop. Measured in characters on the default content (tokens about a
quarter of that): the coder's brief went from 7,650 to 8,293 (+643, about 160 tokens a code call), the
inventor's design ask gained about 270 (the `runs` hint with the recipes), and the system prompt about
260 (the two verbs' phrases, the skill, the code line). Before he owns a machine, a decision's activities
gain about 95 (tend and repair, locked). The scripted baseline (`BedroomPolicy`) now eats what he grew
before meals, fills a machine once he has a batch's worth of scraps, takes out the tomatoes, and mends a
machine only once it has jammed.

**Not done.** Recipes he invents, which would need an accounting of what an input is worth; machines
that make money directly, such as a stall that sells its tray; power or other machine-made effects
feeding machines; machines that move (a robot vacuum: items are slot-bound, so it needs a non-slot
entity with a position and its own walk); a web model that changes for a jammed or full machine.
