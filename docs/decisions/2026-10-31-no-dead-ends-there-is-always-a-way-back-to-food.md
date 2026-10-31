---
title: "No dead ends: a free floor under hunger, and a verb isn't offered when it would stop at once"
date: 2026-10-31
status: active
---

# No dead ends: there is always a way back to food

In the TV run `app-bedroom-20261031-191910` (Jev deciding) he got stuck from day 2 evening on, with
$2, no food, and every need going to 0. Each way out was locked by another need:

- **Food needed money**, and the cheapest food was a $24 pack of meals.
- **Money needed sketching**, which the work floor refuses below fullness 5. Later it was also
  refused below mood 25.
- **Mood fell 5 an hour** while energy or fullness was under `low` (15), and couldn't come back up.
- **Energy needed sleep**, and sleep's `stop_below: { fullness: 10 }` woke him at once. Sleep was
  still offered ("about 6h"), so Jev picked it over and over, each time for a minute.

All that was left was rereading books he had worn thin. The rule from here on:

- **From any state there is a way back.** No need that money fixes may be the only thing standing
  between him and money. Content changes have to keep a path open from broke, starving and
  exhausted back to work. The test `broke, starving and exhausted, he still has a way back` in
  `BedroomWorldTest` is that check.
- **The floor is the cupboard's oats** (`eat_oats`, item `oats`, affordance `staple`):
  - free, never used up, and owned from the start;
  - offered only at fullness 15 or below, which is `low`, so they can always lift him out of the
    hunger that drags his mood down;
  - fullness +25 in 15 minutes.
  
  Meals stay worth buying: oats fill less, and they aren't routine, so they wear thin. A diet of
  oats costs stimulation and bores him, which lowers the mood he settles to without locking
  anything. `staple` is kept apart from `edible` so `eat` never uses the oats.
- **A verb whose `stop_below` is already met is locked** ("it ends below fullness 10 (fullness 0)").
  It isn't offered only to end a minute later.
- **The shop says when a purchase spends the food money:** "leaves you $6 with no food, short of a
  meal (3 for $24)". This is said when he has no food left and the purchase would leave him less
  than the cheapest food costs. Food is whatever the verbs that raise fullness use up, so no item
  name is written into the engine.

Not changed: the work floor, the mood block on art, the `low` rule and the meal price. Recovery from
the bottom is slow on purpose. From mood 0 it takes most of a day of oats and rest before he can
sketch again.
