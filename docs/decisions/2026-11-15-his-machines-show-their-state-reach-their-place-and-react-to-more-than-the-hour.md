---
title: "His machines show their state, reach what's in their own place, and react to more than the hour; events can belong to a place"
date: 2026-11-15
status: active
---

# His machines show their state, reach their place, and react to more than the hour

**Context.** Code he writes (`2026-11-15-he-writes-his-verbs-and-what-his-things-do-as-code.md`) could
change him and his things' key/value state, but nothing it did showed, it couldn't see where a thing
stood or act on anything near it, and the only trigger was the top of the hour. In the live run (day
24) he asked for inventions that sense the weather and react to the time of day; his pinecone lamp
"glows every hour", which did nothing. Phase 2 of the effects-with-reach plan
(`research_notes/2026-11-15_1451_effects-with-reach-world-plan.md`); the design is
`research_notes/2026-11-15_1427_machines-that-act-on-world.md`, layers 1, 2 and 2b.

**Decision.**

- **How a thing looks is a closed vocabulary, not rendering code.** An invention's `Design` may have
  `variants`: named lists of `PartChange` (a `part` of its look, and a new `color`, `glow`,
  `rot_x/y/z`, `scale` about the part's bottom centre, or `hidden`). His code sets, per thing:
  `world.show(id, variant)`, `world.motion(id, part, kind, speed)` with kind one of `spin`, `bob`,
  `sway`, `pulse`, `shake` (speed 0.1 to 4, at most 4 moving parts), and `world.readout(id, text)`
  (24 characters). Variants and motion are for **his inventions only**, since they're made from a
  design's named parts; a readout goes on any thing of his that stands somewhere.
- **The protocol carries them as their own `Entity` fields**: `variant`, `motions` (`MotionView`:
  part, kind, speed) and `readout`, all defaulting to empty, so older clients ignore them and
  `PROTOCOL_VERSION` stays 3. `Invented` gains `variants`; `BehaviorView` gains `every`. The
  sim-owned `state` ("playing", "bare") is not reused. The sim keeps them in `Machines`
  (`sim/.../Machines.kt`) and saves them with the world (`BedroomState.machines`).
- **The web builds a model per variant and splits moving parts out.** A variant is a cached model
  (`invented/<id>/<variant>`), as `_bare` is. A thing with motions is drawn as its still parts plus
  one small model per moving part, about that part's pivot; which parts move is in the cache key,
  their kind and speed are not, so a speed change builds nothing. Moving parts cast no shadow, so they
  never force the shadow map to redraw. Readouts are paper tags in the hover label's style.
- **Reach is by place.** Reads: `world.place(id)`, `world.near(id, tiles)` (walking tiles over the
  floor, up to 20, and whether he's among them), `world.where()`, `world.patches(place?)`,
  `world.things(place)`. The one new write on the land, `world.regrow(patch, hours)`, works only on a
  patch in the same place as the code's thing (or as him, in a verb), so where a machine stands
  matters. Effects that depend on him being near are code over `near(...).you`.
- **Triggers are named top-level functions** that `ScriptSandbox.functions` finds when the code goes
  in: `hourly`, `every` (with the behavior's `every_minutes`, 10 to 1440, at whole multiples of game
  time), `onArrive`/`onLeave(place)`, `onFinish(verb)`, `onSleep`/`onWake`, and `onTimer(key)` for
  `world.after(minutes, key, id?)` (1 minute to 2 days, 8 a thing, the same key resets). A behavior
  needs at least one. Three broken runs in a row stop it, as three broken hours did.
- **A step budget a tick across all his things' code** (5,000,000, on top of each call's 2,000,000).
  Code past it waits that minute, and he's told which things waited.
- **Machine need gains wear like verbs'.** A thing's code wears its own key (`thing:<id>`) in the
  habituation table when it raises mood or stimulation: an hourly run wears as much as doing a verb,
  an `every` run by its share of an hour, so running every 10 minutes wears no faster than hourly.
  Costs, and other needs, don't wear.
- **Events can belong to a place.** `WorldEvent.place` (a place id) is optional; any world event may
  set it, and memory files a placed event under that place rather than where he is
  (`Observation.newsPlaces`, `AgentMemory.observed`). A thing's code puts its events in the thing's
  place. One in a place he isn't in is held (repeats counted, the latest 8 a place) and told when he
  next walks in: "while you were out: your garden sprinkler: the seed-pod bush will grow back 2 hours
  sooner (4 times, 12:00 to 13:30)". Viewers' event log shows it then too.

**Consequences.** The coder's brief lists the triggers and says code reaches only its own place,
that what it does elsewhere he hears of on his return, and that mood gains wear. The fixed part of the
brief grew by about 2,200 characters (about 550 tokens a code call), and the inventor's prompt and
design schema by about 990 characters (about 250 tokens a design), both metered under the ledger's
`code` and `invent` purposes. Rhino rejects `for (const x of ...)`, so the brief says to use `let` in loops.
`LILGUY_INVENTOR=machines` builds a canned sprinkler with its code, for runs without a model.

**Not done.** `field()`/`emit()` and light (wait for the effects engine), variants and motion for the
world's own things, flows between machines, upkeep and breakdowns, moving machines.
