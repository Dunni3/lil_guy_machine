---
title: "The compost bin collects his scraps itself (`collects`), and his observation's `tending` says which machines want him"
date: 2026-11-17
status: active
---

**Context.** In the live run (01a1e006, 61 days, Opus deciding) he bought a compost bin and a raised bed on
days 50 and 52, as pretty things on trips for planks. He held 34 food scraps and the bin's `tend` was
open every turn, but he never tended it (13 tends, all the kiln), so the bed never had compost and never
grew a tomato. `tend` is routine, so `lately`'s "never yet" leaves it out, and nothing else he sees says
a machine is waiting on him. The machines decision
(`2026-11-15-machines-are-fed-and-wear-out-instead-of-uncapped.md`) had him carry everything a
neighbouring machine doesn't hand over.

**Decision.**

- **A machine can collect.** items.yaml `machine.collects` lists things its recipes take that it gathers
  from his things by itself, wherever he is, up to what it holds, on the same 10-minute step as a machine
  taking from its neighbours. Quietly: no event. The compost bin collects `food_scrap`. Content checks
  that what it collects is something one of its recipes takes. Nothing comes from nothing still holds: the
  scraps are his, from his meals.
- **He's told what wants tending.** `Observation.tending` lists his machines that want him, wherever they
  stand, one line each, null when none do: jammed (mend it), holding what it made that no machine beside it
  takes, or empty while he has what it takes. Tool models get it in the status, Jev in its state, both
  described in the system prompt.
- **The inspector shows every verb a thing is for**, locked with why, not only the ones its ops allow it
  now (the shop's `usesOf` still drops those), and an empty machine says what a batch takes and which
  machine beside it makes that.

**Consequences.** With a bin and a bed he only takes the tomatoes out. Other machines (the kiln) are still
fed by hand; `collects` is there for anything else that should gather by itself.
