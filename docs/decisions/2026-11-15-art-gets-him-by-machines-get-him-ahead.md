---
title: "Art gets him by; machines that make things to sell get him ahead, he's told so, and he starts with enough for one"
date: 2026-11-15
status: active
related: 2026-11-15-machines-are-fed-and-wear-out-instead-of-uncapped.md
---

# Art gets him by, machines get him ahead

**Context.** After machines were fed and wore out (PR #74), the only machines in the content made
food, and art paid well enough that he never needed anything else. A sketch was $5 plus $3 a skill level,
a painting $40 plus $12, and a commission added $20 a day. Ian asked for a different shape: making and
selling art should just get him by, building machines that make money should make far more, he should be
told this, and he should start with enough money to begin.

**Decision.**

- **Art is a living, not a fortune.** A sketch sells for $4 plus $1 a level of art, a painting for $25
  plus $4 (still on a $15 canvas), and the daily commission adds $5. He burns about 2 meals a day ($8
  each, about $17), so about four or five hours of sketching keep him fed, and practice raises that
  slowly.
- **Wares.** A new affordance, `ware: { price }`, marks something a machine makes to sell. It's sold at
  the mail hatch by a new verb, `sell_wares` (kind `economic`, `sell: what`), and by `world.sell` in his
  code. Art stays `sellable` and sells through `sell_art`.
- **The kiln** (items.yaml, $60, by the front door at `kiln_pad`) runs the recipe `fire`: 2 clay ($6 for
  4) make 1 clay pot in 4 hours, and a pot sells for $20. It holds 6 of each, so one filling is three
  firings, and it wears 8 a batch. Kept fed and emptied twice a day it makes about $100 a day, against
  about $20 a day from art. It earns only while he buys clay, fills it, takes the pots out, sells them
  and mends it (a spare part every few days). A second kiln doubles that, and an invention can run
  `fire` too (`Design.runs`). Those are the ways to grow.
- **He's told.** The system prompt gains a "Getting ahead" line, built from the content: art keeps him
  fed and not much more, and machines that make things to sell earn far more. It names each machine
  that makes a ware, what it takes and costs, how long a batch takes and what the ware sells for, plus
  the verbs that fill, sell and mend it (`BedroomPrompt.machinesHint`). Content without such a machine
  gets no line.
- **He starts with $100** (room.yaml `start.money`, was $20): enough for a kiln and its first clay, with
  some over for food.

**Why.** Art stays something he does because it's his, and money stops being a reason to do it all day.
The kiln is a choice with up-front cost and upkeep, and it pays only for attention. That gives a model
something to plan around: save, buy, keep it running, buy another or build one.

**Consequences.** Earlier runs' money numbers aren't comparable. The scripted baseline still never buys
a machine. Started with a kiln (`LILGUY_START_ITEMS=kiln,...`), it sells pots, fills and empties the kiln,
and buys clay and a spare part. The prompt grows by the one line, about 600 characters on the default
content. Demand is unlimited, so pots always sell. A market that tires of pots, or prices that move, is
not done.
