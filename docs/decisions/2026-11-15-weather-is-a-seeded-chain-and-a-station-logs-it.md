---
title: "Weather is a seeded hourly chain with a hidden pressure; a station outdoors logs it, and he reads it standing there"
date: 2026-11-15
status: active
---

**Context.** In the live run (01a1e006) 515 of 534 activities happened in the bedroom, and he asked by letter
for inventions that "sense the weather outside" (day 24) and "a little machine that tracks ambient changes"
(day 25). Ian liked the mechanic: a machine he places that records what happens at its spot, which he has to go
back to read. The plan is `research_notes/2026-11-15_1451_effects-with-reach-world-plan.md`, "He asked for a
recorder". This is that slice.

**Decision.**

- **Weather is a pure function of the run's seed, the rules and the game hour** (`sim/.../Weather.kt`, numbers in
  `content/bedroom/weather.yaml`, its own content file). The sky (clear, cloudy, rain, fog) steps once a game hour
  by a Markov chain (`turns`, weights per sky), each step with its own seeded random, so asking in any order
  gives the same month and a checkpoint stores nothing of the weather. Fog only forms in its hours. Temperature
  is a daily cosine plus a day's eased drift plus the sky's offset (clear nights colder). Rain is mm per hour.
- **Pressure is hidden and leads rain.** It's the mean of the sky levels over the next `lead_hours`, less a
  trough round rain (`drop`, easing in over `lead_hours` before and out over `after_hours` after), so it falls
  for about four hours before any rain, however short, and climbs back after. Only a recorder sees it. The field
  guide's sparrows ("fly low an hour ahead") match: they'd fly low near the trough's bottom.
- **Effects, kept small:** rain grows bare patches back `regrow` (3) hours an hour (each wet hour pulls
  `bareUntil` in; the gather text says "sooner if it rains"); out in the cold (below `cold.below`) his mood
  falls; `BedroomWorld.daylight()` is the sky's multiplier on sunlight (cloudy 0.6, rain 0.4, fog 0.5) for the
  light engine to use. The later warmth session builds on `Weather.at()` / `conditionsAt(cell)`.
- **What he sees:** `Observation.weather`, only now: outdoors the sky and how it feels ("raining, cold"),
  indoors only the sky through the window. Never the pressure, a number, the history, or what happened while he
  slept. One "Weather" line in the rules.
- **A recorder is an affordance, `recorder`**, and must stand on a land slot (checked at load). The shipped one
  is `weather_station` ($40). It arrives on the free land spot `by_garden_bench`, and `put` (or `world.move`)
  can carry it to any other spot on the land, never indoors (`Spots.lock`). Each game hour every recorder he owns logs temperature, rain, pressure and light at its tile into
  `Records`: thing id to key to the latest `station.keeps` (48) readings, saved in checkpoints
  (`BedroomState.records`). The same store is his code's.
- **Reading means going there.** `check_station` (new kind `perceptual`, new op `read_log`, `at: [what]`) sums up
  the last day (`StationLog`: rain spells with times and mm, where pressure is and which way since when, the
  range of temperature, the brightest hour). It's an outcome event, so memory files it under the place he's
  standing in: the station's. Locked until the station has logged something. In Jev's menu under outdoors.
- **His code:** `world.record(id, key, value)`, `world.readings(id, key)` and `world.conditions(id)` (sky,
  temperature, rain, pressure, light at the thing's spot) on things he owns. With `hourly` he can write his own
  loggers. A barometer of his reads pressure as the station does.
- **Web:** `FastState.weather`; the renderer dims the sun and greys the sky by `daylight`, uses three's linear
  fog for fog (per fragment, no extra pass), and draws rain as ~360 CPU-moved line segments over outdoor tiles.
  `?sky=` forces a sky for looking. The station has a clay model.

**Not done.** A readout over the station (machines-reach's field), the light engine using `daylight()`, weather as the baseline for warmth, sparrows as a free forecast,
and the TV viewer.
