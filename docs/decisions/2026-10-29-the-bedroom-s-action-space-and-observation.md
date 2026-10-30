---
title: "The bedroom's action space and observation"
date: 2026-10-29
status: active
---

# The bedroom's action space and observation

`do(activity)` and `buy(item)` are new `Intent` variants; the sim walks him to the nearest owned
spot (`at`) or to the door for the shop. `use(item, on)` is item-on-item (a record on the record
player) and needs only ownership, not holding. `move_to`, `say` and `wait` work as before;
`pick_up` is refused with a reason, since owned things are already his. Key-and-door rejects `do`
and `buy`, and its schema leaves them out.

The observation adds clock, money, needs, holding (carried art), owns, music, `activities` (every
`do` activity: takes and gives, or `locked` with a reason such as "needs an easel ($120)"),
`uses` and `shop` (price, affordable, what it's for). These fields are left out of the JSON when
null, so the key-and-door observation is unchanged. The per-request schema and the TypeSafe
choice set include exactly the unlocked activities, the offered uses and the affordable purchases.
Choice options are ordered activities (content order: eat, sketch, paint, sell_art, read, listen,
sleep), uses, purchases, then wait (30 game minutes).
