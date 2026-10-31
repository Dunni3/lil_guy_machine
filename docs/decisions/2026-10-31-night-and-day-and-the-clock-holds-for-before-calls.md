---
title: "Night and day: verbs change by day through `by_day`; the clock holds while a `before` call is out"
date: 2026-10-31
status: active
---

# Night and day, and the clock during `before` calls

Items 17 and 19 of `research_notes/2026-10-31_1711_fix-list-from-first-grammar-tv-run.md`.

- **Night is a window in rules.yaml** (`night: { from: "20:00", to: "04:00" }`, across midnight).
  A verb started inside it is done as written. Started at any other hour, its `by_day` applies:
  more `block`s, and at most `minutes` long. That's how sleep gets a day and night, as data. A
  verb without `by_day` is the same at every hour.
- **Sleep:** at night it lasts until 07:00 or full energy, as before. By day it's a nap, refused
  above 25 energy and at most 90 minutes. So the 07:00 wake-up applies only to night sleep, and a
  sleep started at 06:34 is a 90-minute nap rather than 26 minutes cut off at 07:00.
  - The night window ends at 04:00 rather than 07:00 for that reason: going to bed just before
    the morning is a nap.
  - Full energy still ends a night's sleep, so an early bedtime means an early rise (a scripted
    run woke at 03:00 to 06:00). Removing `need: energy` from sleep's `until` would keep him in
    bed until 07:00. It's left in until a Jev run shows how he spends his nights.
- **The prompt** gets a "Night and day" line built from `night` and each verb's `by_day`.
  `activities` says "up to 1h 30m" for a nap and "until 07:00" at night.
- **The clock holds while a `before` call is out** (`Authors.holding`), the same way it already held
  for a `during` call that outlasts its verb. A think costs its 10 game minutes, not those plus the
  writer's ~5 s at the run's speed (about 15 minutes, capped by `RunSettings.decideCap`). The
  deciding call itself still runs the clock up to `decideCap`. Lockstep runs (tests, the CLI) are
  unchanged, since they never ran the clock during a call.
