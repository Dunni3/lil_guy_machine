---
title: "He puts his things down at free spots, outdoors included: a `put` intent (the `place` tool), named free spots on the land, one set of rules with his code's `move`"
date: 2026-11-15
status: active
---

**Context.** Phase 0 of the effects-with-reach plan (`research_notes/2026-11-15_1451_effects-with-reach-world-plan.md`):
light and his machines will only matter if where a thing stands can change. On main every slot on the land
belonged to a fixture, so nothing could go outside, and only his code could move a thing (`world.move`). In the
live run 515 of 534 outcomes were in the bedroom.

**Decision.**

- **Free spots are slots no item names.** `world.yaml` `slots` gains five, each named by what it's beside:
  `by_front_door`, `by_garden_bench`, `pond_shore`, `by_clearing_log`, `trailside`. More places get more by
  adding slots; there is no separate list. A slot kept for a thing he doesn't own yet isn't free.
- **Outdoors is ground only.** The wall/floor rule stands: a thing in a wall goes only in a wall spot, and the
  land has none, so nothing that hangs goes outside.
- **One set of rules** (`sim/.../Spots.kt`) for the intent and for his code's `world.move`/`world.spots`: it must
  be his, not a fixture, stand in a spot now, and go to a spot of its kind that's free or holds another thing
  of his (they swap). Lock reasons say which ("the garden bench is part of the house or the land, and stays
  where it is", "garden_bench is kept for the garden bench", "the chair goes on the ground, and wall_1 is in a wall").
- **`Intent.Put(item, spot)`** (wire name `put`, since `place` is already `Target.Place`'s). He walks beside the
  thing, lifts it, carries it beside the spot and sets it down. `world.yaml` `carry: { lift: 2, set_down: 3 }`
  game minutes besides the walks. While carried it has `carriedBy` set and is off the map (the web doesn't
  draw it in his hands yet). The rules are checked again as he sets it down. The move is saved with his
  customizations (`custom[id].slot`), as a code move is.
- **What models see.** `Observation.put`: his movable things (ground and wall) and the free spots grouped by
  place. Tool models get a `place` tool (`what`, `spot` enums; one line per place in its description) and
  the status message leaves `put` out. Text models get `put` in the observation and the reply schema. Jev gets
  a menu node `arrange` (`put: true`, under `room`) with a node per thing, then one option per free spot.
  Only free spots are offered to models. Swapping into an occupied spot is for code.
- **Built-in tool names are reserved.** His own verbs can't be called `buy`, `wait`, `go`, `place`, `recall`, `ask`
  or `search` (`CodeRules.BUILT_IN`). A verb with one of those names is left out of the tools instead of failing
  the decision.

**Consequences.** The `place` tool costs about 240 prompt tokens per tool decision at the start of a life and
about 370 with ~35 things owned (the spot ids appear twice: in the description by place, and in the enum).
Inventions still take only the bedroom's spare spots (`room.spots`) when built; he moves them out himself.

**Not done.** A thing in his hands while he carries it; event places (`WorldEvent.place`, the machines session);
any effect of where a thing stands (light is Phase 1).
