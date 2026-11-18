---
title: "Inventing and writing code pay the most mood and fun, cost little, and barely wear thin (`fresh_floor`)"
date: 2026-11-17
status: active
---

# Inventing and writing code pay the most and barely wear thin

We want him inventing (`invent`) and writing code (`devise`) more than anything else, so they are
the best-paying things he can do, not one hobby among many.

- **Pay.** `invent` gives mood +12 and stimulation +25; `devise` mood +10 and stimulation +20. The
  best hobbies give about +5 mood and +12 stimulation. Before, neither gave any mood.
- **Cost.** `invent` takes 60 minutes (was 120) and costs 1 energy (was 4); `devise` takes 30 minutes
  (was 60) and costs 1 energy (was 3). The awake drain still applies over those minutes.
- **`fresh_floor` (verbs.yaml).** Gains are paid at freshness (2026-11-15 fun-comes-from-what-is-unfamiliar),
  and what inventing is like (the workbench, the same room, the same verb) grows familiar fast, so on a
  daily habit the pay would fall to about a fifth. A verb's `fresh_floor` is the least freshness it pays
  at, and the bored lock passes it over while the floor is above the lock's line. Both verbs use 0.8:
  what he makes is new every time, which the features don't see. Fixing broken code still pays in full.
- **Kept.** Invent is still once a day (`invent.per_day`) and still refused below mood 25. A refused
  design or code that fails pays nothing. A ware he invents sells for its materials plus
  `wage_per_hour` for the build's hours; that wage is unchanged, so the shorter build earns $4
  instead of $8. Selling wares doesn't become better paid than sketching.

Other verbs can use `fresh_floor` later, but it is meant for making something new, not for keeping a
habit fun.
