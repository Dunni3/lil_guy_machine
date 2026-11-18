---
title: "Inventions are built from bought materials; a new thing goes to any free spot, and he can take his things apart"
date: 2026-11-15
status: active
related: 2026-11-13-he-invents-items-at-a-workbench-and-the-content-grows-during-a-run.md, 2026-11-15-machines-are-fed-and-wear-out-instead-of-uncapped.md, 2026-11-15-he-puts-his-things-down-at-free-spots-outdoors-included.md
---

# Inventions are built from bought materials

**Context.** In a live run he had plenty of money, had bought everything in the shop, and had invented
things until the bedroom was full. After that his money had nothing to go on. An invention cost
abstract dollars (rules.yaml `invent`: `base` plus `per_strength`), which was cheap once he earned. It
could only take the bedroom's 6 spare spots (`InventRules.check` refused with "there's no free spot
left on the floor"), and nothing ever left the world. Ian's idea: the shop sells raw materials that
never run out, and he builds machines from them that work, that he writes code for, and that we can
see.

**Decision.**

- **Materials are shop items.** `wood` (wood plank, 5 for $10), `metal` (metal bar, 5 for $15),
  `copper_wire` (copper wire coil, 3 for $12) and `glass` (glass pane, 3 for $9) are in items.yaml,
  group `supply`, stackable, with no stock limit. They're bought on shopping trips like clay, so the
  writer-shopper and Jev's one pick see them in the catalog. The shop holds them back until he has a
  workbench (`BedroomWorld.findUses` counts a material as being for any verb with the `build` op), so
  they don't add to the prompt before he can build. The names are count nouns so the sim's plurals
  read right ("3 copper wire coils").
- **Each look part names its material.** `LookPart.material` comes from a closed list,
  rules.yaml `invent.materials` (each with what it's for, which the inventor's brief prints). The
  design schema requires it with an enum, so canned replies carry it. A blank material is refused for
  a built thing ("shape 2 of its look needs a material"). A look saved before materials has `""` and
  is drawn as its colour alone.
- **The bill.** `InventRules.bill(design)` maps material to count:
  - Each part takes one unit of its material per `unit` (0.006 m³, a 0.18 m cube) of its shape's
    volume (`InventRules.volume`: a box's, a cylinder's with taper, hole and arc, a sphere's with
    cut), and at least 1.
  - The main material (the one most of the volume is made of) takes `finish` (1) more per point of
    quality strength, so strong qualities still cost something.
  - A design with recipes (`runs`) adds `workings.moving` (5 metal).
  - `InventRules.workings(design, code)` is what the code written with it takes: `workings.code`
    (3 copper wire) for any code, plus `workings.moving` when the code calls `world.motion(` and the
    body has no recipes. The match is on the code's text.
- **What uses are built.** `viewable`, `playable` and `sellable` are `built: true`, and so are
  machines, since a machine is a viewable thing with `runs`. **Food and books keep dollars**
  (`base` plus `per_strength`, as before): a meal isn't made of wood, and a book is paper and time.
- **The cost.** `Invention.bill` is what it took. `Invention.cost` is now the bill's value at the
  shop's unit prices (`InventRules.value`), or dollars for food and books. A sellable thing still
  sells for its cost plus the build's wage, so selling one never earns more than its materials. Prices
  are calibrated on the canned designs:

  | Design | Bill | Cost |
  |---|---|---|
  | Pebble lamp (decor) | 5 wood, 8 glass | $34 |
  | Tin robot | 13 metal, 2 glass, 1 copper wire | $49 |
  | Garden sprinkler (machine: code, a spinning head) | 9 metal, 5 copper wire, 5 glass | $62 |

  The sprinkler costs about the same as a kiln ($60). A 12-part machine with a big metal casing comes
  to about $150. A 3-part paper kite is only $12, below the $15 to $40 asked for plain decor, but it
  is a very small thing.
- **Taking and refusing.** The `build` op takes the bill from his stock. If he's short, the verb fails
  with what it takes, what he has, and where to get it, for example: "the pebble lamp takes 8 wood
  planks and 2 glass panes; you have no glass panes. The shop sells glass panes (3 for $9) on a
  shopping trip". `invent` is locked only when he has no materials at all and can't afford food or a
  book. The inventor checks its own design against his stock before it reaches the sim, and redoes it
  smaller once (`Inventor.short`). If he has the body but not the workings, the thing is built
  without its code: "you didn't have 3 copper wire coils for its workings, so it's built but doesn't
  do anything yet". Code written later at the desk for a built invention that isn't wired yet takes the
  wiring then (`Invention.wired`), or doesn't go in. Bought things' code isn't gated, since they come
  with their own workings. `world.make` in his code takes materials the same way and throws the same
  reason.
- **Build anywhere.** A new thing goes to the room's first free spare spot of its kind (room.yaml
  `spots`). If there's none, it goes to the free spot of its kind nearest the workbench by walk
  (`BedroomWorld.inventSpot`/`inventSpots`, using the `Spots` rules: ground things go on the ground,
  wall things in walls, so nothing that hangs goes outdoors). "No free spot" happens only when every
  spot of that kind is taken, and its reason names `dismantle` and moving things. The `invent` option
  says how many free spots are left.
- **`dismantle`.** A new verb (kind `upkeep`, 40 minutes, at the thing, +0.1 tinkering), with
  `args: { on: invention }`. `invention` is a tag only things he invented that stand somewhere answer
  to (`BedroomWorld.answers`; the content check accepts it before any exist). The verb is dropped where
  nothing can be built. It gives back `invent.salvage` (half) of the bill, each material rounded down.
  The thing leaves the content (`BedroomContent.minus`): its book, his verbs done with it or at it, its
  code, looks, timers, state and colour all go. **Its machine store goes to him**, because the inputs
  and outputs were his and dropping them would break conservation. Its spot frees up. Bought things,
  fixtures and the house can't be taken apart. Things that don't stand anywhere (a record, a ware)
  can't either, since dismantling is for making room. The outcome is a normal verb outcome, so it lands
  in his events and memory. Tool models, text models and Jev (menu.yaml `workshop`) all see it.
- **Seeing it.** The protocol's `LookShape` gains `material` and `Invented` gains `bill`. The web
  renderer draws each invented part by its material, with the part's colour still tinting it
  (`room/parts.ts` `MATERIAL_TINTS`):
  - wood is warm, with a small repeated grain texture;
  - metal is grey, roughness 0.34 and metalness 0.55, so it catches the lights;
  - copper is copper-toned with more sheen;
  - glass is lighter and at most 0.55 opaque.

  Each material is one shared `MeshStandardMaterial` and one batch per model (`clay/renderer.ts`
  `SURFACES`). That adds no passes and no per-frame work. The page for an invention shows its bill.

**Why.** Money now goes into stuff that takes space, so a rich run keeps having something to spend on,
and what he can make depends on what he has bought. Wiring and mechanisms make "it does something"
cost something visible. Taking things apart gives space and half the materials back, so a full world
is a choice he can act on rather than a dead end.

**Consequences.**

- Earlier runs' invention costs aren't comparable. Old saves keep their inventions. A thing built
  before materials has an empty bill, so taking it apart gives nothing back.
- The scripted baseline (`BedroomPolicy.workshop`) runs only when he has a workbench and `invent` is
  offered (an inventor is configured). It keeps a stock of each material, invents once a day, and takes
  apart its oldest invention when the ground has no free spot. `LILGUY_INVENTOR=workshop` (CLI and host)
  cycles the sprinkler, pebble lamp and tin robot, numbering repeats ("pebble lamp 2").
- Prompt growth, in characters on the default content with a workbench and 6 of each material,
  measured with a throwaway probe on main and on this branch (tokens are about a quarter of these):

  | Prompt | Main | This branch | Change | When |
  |---|---|---|---|---|
  | Decision request | 30,156 | 30,854 | +698 (~175 tokens) | Every decision once he has a workbench |
  | Inventor's ask (situation and brief) | 22,499 | 24,292 | +1,793 | Each invent |
  | Design schema | 4,676 | 5,101 | +425 | Each invent |
  | Coder's brief | 9,064 | 9,361 | +297 | Each code call |
  | System prompt | 12,163 | 12,249 | +86 | Every decision |

  The decision request's growth comes from the invent option's stock and spot counts, the locked
  `dismantle`, his materials in `owns`, and four short shop lines. Before he has a workbench it's only
  the locked `dismantle` line (about 90 characters) and the system prompt's 86.

**Not done.**

- Rooms and a shed that cost materials (another session builds that on this branch).
- Selling materials or things back.
- Taking apart things that don't stand anywhere.
- Material prices that move (market saturation is another session's, for wares).
- Copper parts drawn thinner than designed (they're drawn copper, at their designed size).
- The TV viewer (`:app`) drawing materials.
- `world.make`'s API line lists the default content's materials rather than reading them from the content.
