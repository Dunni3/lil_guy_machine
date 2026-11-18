---
title: "Wares sell for less the more of one kind he sells, and recover over days; one rule for every way of selling, and he's told the going price"
date: 2026-11-15
status: active
related: 2026-11-15-art-gets-him-by-machines-get-him-ahead.md
---

# Wares sell for less the more he sells

**Context.** PR #75 added wares and the kiln, and said that demand was unlimited: a pot always sold for
$20. So income from machines had no ceiling but his time, and two or three kilns would print money. In a
live run he ended up with a lot of money and nothing to spend it on. A parallel change makes inventions
cost bought materials (a sink). This one caps the other end: what wares bring in.

**Decision.**

- **Each kind of ware has a going price** (rules.yaml `market`, `sim/.../Market.kt`). Each one sold
  fetches `drop` (6%) less than the last, never below `floor` (a quarter) of its usual price. The going
  price climbs back by `recover_per_day` (half) of the gap a game day. It's kept per item id, so a clay
  pot and something else he makes to sell have their own prices.
- **What counts as a ware:** the `ware` affordance, or the `ware` group. Things he invents to sell are in
  the ware group, so they share the rule. Each invention is its own kind, and today each sells once (he
  gets one per build), so the rule rarely touches them. It will matter if he gets recipes that make his
  own designs. Art (`sellable`, carried) and samples (`specimen`) aren't wares. Art keeps its own
  economy, and samples are limited by how slowly patches regrow.
- **One rule for every way of selling.** The `sell` op (the `sell_wares` verb, and `sell_art` for an
  invented ware) and `world.sell` in his code both sell through `Market.sell`, one unit after another.
  So a stall or sorter he writes gets the same prices. The state is saved with the world
  (`BedroomState.market`, only the kinds still below their usual price). A script that breaks partway
  puts the market back, as it does his money.
- **He's told.**
  - The "Getting ahead" line says $20 is the usual price, that each sale fetches 6% less, that the
    price recovers half the gap a day, and that a second machine making the same ware earns less while a
    different ware has its own price.
  - The sell outcome and the `sell_wares` option add "a clay pot fetches $16 now, not $20: ..." while
    the price is below usual.
  - The shop's text for a machine names what its ware fetches now. The web's shop page shows that text,
    so it needed no protocol change.
  - His code reads the price with `world.price(id)` → `{ now, usual }`.
- **The scripted baseline holds pots** while they fetch under 70% of usual. It sells anyway once he
  holds 6 or is short of food money. It tends every thing whose id starts `kiln`.

**Why.** Flooding one ware pays less and less, so more of the same machine has diminishing returns, and
a new kind of ware is worth inventing. A kiln still pays well. Recovery over days gives a model (and his
code) a reason to time sales and to spread across wares, without a hidden cap.

**Calibration** (scripted policy, 7 days, `LILGUY_START_ITEMS=kiln,clay=4`; two kilns used a temporary
second kiln item, not shipped):

| | pots sold | from pots | per pot | clay | net |
|---|---|---|---|---|---|
| 1 kiln, no market (before) | 29 | $580 | $20.0 | $132 | $448 (~$64/day) |
| 1 kiln, market | 29 | $463 | $16.0 | $132 | $331 (~$47/day) |
| 2 kilns, market | 54 | $710 | $13.1 | $216 | $494 (~$71/day) |

One kiln settles at $15–16 a pot (about 4 a day). That's still more than twice what art pays (about
$20 a day). Two kilns earn about 1.5× one, and their price was still falling at day 7 ($9–10 after a
batch of 6). The steady state for 4 and 8 pots a day is about 74% and 59% of the usual price.

**Consequences.**

- Kiln income is about a quarter lower than under #75. The prompt's line grows by 188 characters (566
  to 754; system prompt 12,163 to 12,351). The code API reference grows by 166 (`world.price`).
- The going price is shown only where it's below usual, so a fresh market costs no extra text in his
  observation.
- Two kilns aren't possible in the shipped content: the kiln isn't stackable and stands on one slot, and
  `Works` keys stores by item id. A second ware machine is an invention running `fire`.
- Not done:
  - A per-sale "the market's full" refusal, or selling only some of what he holds at the hatch
    (`sell_wares` still sells everything; his code can sell `n`).
  - Demand that varies by season or day.
  - Prices for specimens.
