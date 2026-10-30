---
title: "A game clock on top of ticks; 1 game minute = 5 ticks by default"
date: 2026-10-29
status: active
---

# A game clock on top of ticks; 1 game minute = 5 ticks by default

`GameClock` maps ticks to day and hh:mm, starting day 1 at 08:00. Bedroom rules are written in game
minutes and hours and converted once, so `ticksPerMinute` is the single clock-scale knob: CLI
`--minute-ticks`, desktop `MINUTE_TICKS=`, TV `--ei minute_ticks`. At 5, a game day is 7200 ticks,
6 real minutes. Walking stays at 4 tiles a second of real time, so a smaller scale makes walks
cheaper in game minutes; at 5, a tile is a game minute.
