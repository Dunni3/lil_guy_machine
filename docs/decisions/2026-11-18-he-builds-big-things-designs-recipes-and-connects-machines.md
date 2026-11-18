---
title: "He builds big things on the land, designs his own recipes and wares, and connects his machines over the air; fewer caps"
date: 2026-11-18
status: active
supersedes: 2026-11-15-machines-are-fed-and-wear-out-instead-of-uncapped.md (its "new recipes are content only" and nearness rules only), 2026-11-15-his-machines-show-their-state-reach-their-place-and-react-to-more-than-the-hour.md (its limits on motions, readouts and timers only)
related: 2026-11-15-inventions-are-built-from-bought-materials.md, 2026-11-15-rooms-are-bought-as-plans-and-built-on-sites.md
---

# He builds big things, designs recipes, and connects his machines

**Context.** In the live run he had bought both rooms there are, and the materials he buys only went into
things one tile big (at most 12 shapes, 1.5 m, inside a 1 m square). Rooms were two fixed layouts bought
with dollars, so materials never became a shed or a cabin. Machines could only run the three recipes in
the content, so no machine he made could earn more than the kiln, and things passed between machines only
within 3 tiles in the same place. Ian (2026-11-18): he should be able to make new rooms and structures,
beat the world's exchange rates, connect machines any way he likes ("they can all just talk to each other
via the internet"), design a machine that makes more money and put the money into more things; fewer caps,
more creativity, big things with looks we didn't design, that exist in and act on the world.

**Decision.**

- **Big things on the land.** A design with `place: land` and a `footprint` (w by d tiles, up to 16 each)
  stands out on the land (`sim/.../Structures.kt`, `Structure`; `BedroomContent.structures`, set by his
  inventions, never the files). Its look covers its footprint (x within ±w/2, z within ±d/2, half a metre
  more for eaves and open doors, up to 12 m tall, 240 shapes). Out there a unit of material is 0.1 m³
  (rules.yaml `invent.land.unit`), not 0.006, so a 5 by 4 shed is about 150 planks ($300), a few days of a
  machine's earning.
  - With a `door` (a side) it's a **building**: `Layout.of` stamps its edge tiles as wall but for the
    doorway, its inside is a place of its own (`PlaceKind.Building`, indoors for light, warmth and rain as a
    room is), its inside tiles hold free spots (every other tile each way, none by the door, so nothing set
    down can shut it off), and `windows` (sides) let daylight in. The thing itself stands just inside the door.
  - Without a door it's **solid**: every tile blocks (a tower, a windmill, a big machine); it stands at the
    tile south of it, where verbs done at it walk to.
  - **Where**: open ground nearest the workbench, or in the outdoor place it names (`where`), with a clear
    tile all round it, and only where every outdoor place and spot is still reachable from the front door
    (`BedroomWorld.inventSite`). Nothing waits to be put down: it goes up where it stands.
  - **Taking it apart** gives half its materials back and the ground back, once nothing of his stands inside.
- **More land.** The lot is 66 tiles wide: a meadow east past the forest, open for building (world.yaml).
- **His own recipes and wares.** A machine he designs can have `recipes` (takes things he keeps, makes
  things that exist or new `products` he names) as well as `runs`. He never sets a price or a rate: a
  machine adds `machines.earning.per_hour` (3%) of what its materials are worth in value each hour it
  works, and `per_level` (50%) more of that per level of tinkering he had when he built it. A recipe takes
  as long as the value it adds needs at that rate (at least `least_hours`); a product sells for what goes
  into it plus those hours' earning (`InventRules.recipes`). So a bigger, finer machine earns more an hour,
  and money compounds into bigger machines. What holds a ware back is the market, which already lowers what
  each kind fetches the more of it is sold; a new kind of ware has a fresh price. A recipe still takes at
  least one thing: nothing comes from nothing. Products stay his after their machine is taken apart
  (`BedroomState.wares`).
- **Machines talk over the air.** A `Link` (`sim/.../Works.kt`) sends what one machine makes to another every
  10 minutes however far apart they stand; `to: market` sells it by itself at the going price; `from: you`
  fills a machine from his things; `to: you` brings it to him. Set by the design (`send_to`, `take_from`), by
  the verbs `connect`, `sell_by_itself`, `feed_by_itself` and `disconnect` (kind upkeep, done anywhere; ops
  `connect` and `disconnect`), and by his code (`world.link`, `world.unlink`, `world.links`). `world.transfer`
  no longer needs the two ends near each other. Saved with the world (`BedroomState.links`). Neighbours
  within 3 tiles still feed each other as before.
- **Fewer caps.** Inventions a day 1 to 4; shapes in a thing 12 to 40, up to 3 m tall; variants 6 to 12;
  moving parts 4 to 12; readouts 24 to 40 characters; timers 8 to 24 a thing, up to a week; `world.near` up
  to 60 tiles; `world.transfer` up to 200 at a time. The step budgets for code stay: they keep a tick fast.
- **What he's told.** The inventor's brief gains a paragraph on the land and one on machines of his own
  (the earning rate at his tinkering, links); the design schema and the workbench's `build` gain the fields;
  the workbench gains `add_parts` for many shapes at once. The guide gains "connecting" and "building big";
  the coder's brief says machines talk over the air; "Getting ahead" says what he invents can earn more.
- **On the web.** A building is drawn from his look only (no house shell), cut down to 1.1 m while he's
  inside it so the camera sees in (`room/parts.ts` `cutAway`). Links are copper arcs between the things they
  join, a bead running from maker to taker; one to the market runs to the mail hatch; ones to or from his
  things aren't drawn (`room/links.ts`). The protocol's `Things` carries `links` (`LinkView`).

**Why.** Materials needed somewhere big to go, and a machine he designs needs a reason to be big: tying its
earning to what it's made of makes "build a better machine" a real choice with an up-front cost, and keeps
every number derived from the world's own prices instead of a cap. The market is the brake, not a rule.
Links make a running business possible without him standing at each machine.

**Consequences.**

- An economy that compounds: a $300 machine earns about $9 an hour of value, $18 at tinkering 2. If runs
  get rich too fast, `earning.per_hour` and the market's `drop` are the knobs.
- Prompt growth (estimated from the strings, not measured): the inventor's ask gains about 2,200
  characters, its schema the new fields (about 1,500); a decision request gains the four locked connect verbs
  until he has machines, and they're listed among what he can do anywhere. The coder's brief gains a sentence
  and three API lines.
- A building's place shows on his map ("you built it, in the garden").
- The TV viewer (`:app`) draws neither buildings nor links.

**Not done.** Moving a building once built; buildings joined to the house; walls drawn by the sim rather
than his look (a look without walls still blocks at its edge); recipes that need power or a machine-made
effect; links drawn to him; anything that stops a link from carrying when he's short of money.
