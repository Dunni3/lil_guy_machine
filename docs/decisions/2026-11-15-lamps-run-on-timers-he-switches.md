---
title: "Lamps run on timers he switches in the world: a `lamp` affordance, three modes, and a `switch` op"
date: 2026-11-15
status: active
---

# Lamps run on timers he switches in the world

**Context.** Light (`2026-11-15-light-is-an-effect-with-reach-the-sim-computes-on-tiles.md`) left
every lamp always on. Ian wanted lamp timing that he (the character) controls himself in the world:
not a web admin control, and not only a content setting.

**Decision.**

- **A lamp is the `lamp` affordance** (vocabulary.yaml), on an item with a `light`. Only a lamp can
  be switched or have a timer. Anything else with a light always gives it: the glowcaps (unless picked
  bare), the desk's own little lamp, and his lantern, which isn't an item. The desk lamp stays always on
  on purpose: it is the floor under his night work, as the oats are under hunger, and a scripted or
  careless policy can't switch it off and leave him nowhere lit to work. Switchability is the
  affordance, not a flag on `light`, so verbs bind to lamps the usual way (`args: { on: lamp }`).
- **Three modes per lamp** (`LampMode`): `timer` (the default), `on` and `off`. On its timer a lamp is
  lit from `on` until `off`, across midnight when `off` is earlier: its own `light.timer`, else
  rules.yaml `light.timer` (18:00 to 00:00). The cloud night-light has its own, 21:00 to 07:00.
  Switched `on` or `off`, a lamp stays that way whatever the clock says until he switches it again.
- **He switches with the `switch` op** (`switch: { target: on, to: on|off|timer }`, `Ops.kt`), allowed
  in transformative verbs. Three verbs use it, `switch_on`, `switch_off` and `lamp_timer`: 1 minute,
  routine, done standing at the lamp, in the menu's `room > lamps` node. Each is locked when it would
  change nothing ("the lava lamp is already switched on"), so every lamp offers two of the three.
- **The light field reads it.** `BedroomWorld.lamps()` emits only lamps lit now, so a timer's edge or
  a switch changes the emitter list and the `Effects` cache works the field out again; nothing else
  is needed for verbs' `needs`, what he sees, comfort or the light phrases to follow.
- **Kept and shown.** Switched lamps are saved in `BedroomState.lamps` (id to `on`/`off`; a lamp on its
  timer isn't listed). An unlit lamp has state `off` (in `nearby` where he can see it, and in the
  protocol's `Entity.state`) and `Entity.light` null; the web builds that model with no glow. The shop
  and catalog say "on a timer (lit 18:00 to 00:00) you can switch"; the lock on `lamp_timer` says the
  times too.
- **A verb whose argument nothing can fill is dropped** by `BedroomContent.unlocked`: with every lamp
  gated, the switch verbs go with them, instead of failing validation.

**Cost.** Each lamp he owns adds two open options to every observation: about 57 tokens a lamp a
decision by characters / 4 of the observation JSON (171 with three lamps, about 4% of the observation).
The prompt's light sentence grew by 15 tokens, cacheable. If he collects many lamps this is the first
thing to trim (offer only the lamps in his place, say).

**Not done.** He can't change a timer's times, only its mode. His code (`Scripts.kt`) can't switch
lamps. Invented items with glowing parts have no sim `light`, so they aren't lamps.
