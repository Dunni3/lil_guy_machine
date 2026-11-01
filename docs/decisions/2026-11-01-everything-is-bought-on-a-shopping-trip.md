---
title: Everything is bought on a shopping trip
date: 2026-11-01
status: active
---

**Context.** Buying was an action like any other: every decision carried the whole shop in its state
(about a fifth of Jev's state in the think-ask run, more since the decor shop), the menu tree filed
each purchase under whatever it was for, Jev could only pick what he could afford so he could never
want anything, and what he saw in the shop never reached memory: only "buy canvas: done".

**Decision.** Buying is one verb, `shop`: a trip to the mail hatch to go through the whole catalog.
Food and canvas are in the catalog like everything else; there's no buying straight off.

- **The verb** (`verbs.yaml`, kind economic, 20 minutes, `no_repeat`) has two ops:
  `write: { with: shopping, timing: before }` and `basket: { most: 3 }`. Its option says how much of
  the catalog he could buy now, when he has no food, and how close he is to what he's saving for.
- **Before he goes,** the `shopping` handler (`agent/.../shop/Shopper.kt`) shows a model everything
  in the catalog he knows of that he doesn't already have: price, whether he can afford it, what it's
  for, what it's held back for. A text model (the writer) replies with up to `most` things to buy,
  each with why, the one thing it's saving for, and a note, held to the catalog's ids by the schema.
  A picking model (Jev, with no writer) picks one thing or nothing. When the writer's call fails,
  the deciding model picks instead (`Shopper.fallback`), so a writer that's down doesn't cut off
  food; when no pick can be made at all, the trip fails with a reason instead of buying nothing.
- **The picks are staged in the sim** before the verb starts (`AuthorHandler.staged`,
  `BedroomWorld.stage`), and the `basket` op buys them as the verb finishes, each checked as a `buy`
  is. What's turned away is named in the outcome. What he's saving for stays in the sim until he owns
  it (a failed call or a picking model leaves it as it was).
- **Kept,** the trip is a `shopping` event in his words, so memories, the self-model and recall see
  what he wanted and why. The run log gets a `"kind": "trip"` line; tokens are metered as `shop`.
- **Models deciding see the world through `BedroomPrompt.deciding`:** only the activities they're
  offered and, while there's a trip, no `shop` at all; the system prompt says everything is bought
  on trips, and the `buy` example is left out.
- **Without a shopper** (no writer and no picking model: the scripted policy, a replay with no
  writer) the `shop` verb isn't offered and everything is bought with `buy`, as before.

**Consequences.** Meals and canvas need a trip too, so food depends on Jev choosing `shop`; the free
oats floor (no-dead-ends) is what keeps that from being a trap. A run where he never chooses `shop`
buys nothing. That risk (what happened to `ask`) is watched in the first runs. `Intent.Buy` is
unchanged on the wire.
