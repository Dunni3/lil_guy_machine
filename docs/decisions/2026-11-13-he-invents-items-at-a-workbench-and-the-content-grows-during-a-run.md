---
title: "He invents items at a workbench; the content grows during a run, and the sim sets what an invention costs"
date: 2026-11-13
status: active
---

# He invents items at a workbench; the content grows during a run, and the sim sets what an invention costs

Rung 1 of three (items, then verbs, then rules or scripts), the last step of
`research_notes/2026-10-30_1951_world-grammar-consolidated-plan.md`. Ian's calls: he builds the
thing at a workbench rather than ordering it, and any existing affordance may be invented.

**An invention is a design the sim checks.**
- A design is one item: a name, exactly one use (an affordance), up to 3 qualities, a look, and
  for a book its chapters (`sim/.../Invention.kt`, `Design`).
- The `invent` verb's `write: { with: invention, timing: before }` has a model write it; the
  `build` op checks it (`InventRules.check`), charges the materials and adds the item. A refused
  design fails the verb with the reasons, which frustrates him and reaches his event stream.
- He never names a number. The sim makes the id, the group, the spot and the price: materials are
  a base per use plus dollars per point of quality strength (rules.yaml `invent`), calibrated to
  what the shop charges for the same thing.

**Which uses are open.**
- Open: `viewable`, `playable`, `readable`, `edible`, `sellable`, the ones some verb uses on a
  plain owned item (`InventRules.OPEN`; rules.yaml can open fewer, not more).
- Closed: `staple` (free food forever); `hangable` and `drawable` (only art pieces use them, and
  pieces come from verbs, so they wait for invented verbs); `combinable` and `wearable` (no verb
  uses them).
- Food he invents is the one thing the shop restocks, at its materials cost, which is never below
  a meal's. A thing made to sell sells once, for its materials plus a wage below sketching's, and
  the shop never stocks it, so there is no buy-low-sell-high loop.
- One invention a game day. A thing to look at takes one of the room's spare spots (room.yaml
  `spots`), and when they are full he can't build another.

**The content is no longer fixed for a run.**
- `BedroomWorld.content` grows when he builds (`BedroomContent.plus`, checked by `validate()` like
  shipped content). Code outside the sim reads `world.content` when it needs it and doesn't keep a
  copy from start-up.
- Inventions are saved with the world (`BedroomState.inventions`) and put back into the content
  before the rest of a checkpoint loads. The run's content hash stays that of the shipped files.
- The protocol's `Content` stays static under its hash. Inventions travel in the slow `shop`
  section (`Invented`, with the look as shapes), and the web renderer builds their models from it.
  The frozen libGDX app draws them with its fallback model.

**Two grammar changes came with it.**
- `eat` takes what he eats as an argument and enjoys it, so a food's qualities pay. Meals have
  none and pay as before; `eat` is now offered once per kind of food (`do:eat:meal`).
- `sell` covers plain owned sellable items as well as art pieces.

**Who designs.**
- The writer model (`agent/.../invent/Inventor.kt`), with one retry carrying the sim's reasons.
  Picking models can't write a design, so without a writer `invent` isn't offered.
- `LILGUY_INVENTOR=1` (`make host INVENTOR=1`) invents from a few fixed designs with no endpoint.

**Not done.** Making more of a design other than food (crafting), books that teach, inventing
verbs, and the clamp-off experiment. Rejected for now: letting him set prices within a clamp,
since a price the sim computes can't be gamed and needs no balance test.
