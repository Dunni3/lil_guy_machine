---
title: "The bored lock reads how much he has done a verb lately, not what it's like; familiar things cost half as much fun"
date: 2026-11-17
status: active
---

# The bored lock reads how much he has done a verb lately, not what it's like

Amends `2026-11-15-fun-comes-from-what-is-unfamiliar.md` (its bored lock and `stale_stimulation`). Pay is
unchanged: gains and the novelty bonus are still paid at what an event is like.

**Why.** On the live run (day 49, Opus deciding) paint and sketch were locked in 35 of 37 decisions on days
48-49, and stimulation settled at about 18 however varied his days were. Two things made the lock a trap rather than
a pressure (`research_notes/2026-11-17_2059_bored-lock-trap-diagnosis.md` has the numbers):
- **The shared families never fade.** Everything he does at home shares `place:bedroom` (fresh 0.04),
  `group:furniture` (0.07) and its kind (0.12), so three of a home verb's seven families sit near 0. A home
  verb he had done once was 0.27 fresh, under the lock's 0.30: bored, anything at home he had done locked
  after a single session. Paint, last done 7 days before, was 0.22.
- **The line sat above where he settles.** With the proportional drain he settles at (net daily gains) /
  2.56. His finishes averaged 40% fresh, under the 43% where a familiar finish broke even, so gains were
  about 45 a day against the ~100 that 40 needs. The lock was on all the time.

His only way out was verbs he had never done, which never lock: `devise`'s "costumes", the thing the
familiarity rule was meant to stop paying for.

**The lock.** Below `bored_lock.below`, a verb is refused when its own `verb:` feature is at freshness
`1 - worn` or less (`BedroomWorld.repetition`); for a verb that wears per item it's the bound items'
`thing:` features. At `worn` 0.7 (level 2.33 or more):
- a daily habit stays locked while he's bored, and every other day is open;
- a lock lifts about a day and a half after he last did it (level 3.33 back to 2.33 at a 3-day half-life);
- what he has never done still never locks, nor upkeep or routine verbs, and work stays open while he can't
  afford anything new (`keep`), as before.

So art is never out of reach for more than a day or two, and boredom still stops him grinding one thing.
Doing the same thing under a new name still pays little, since pay reads what it's like. The lock says
"you're bored, and you've done it a lot lately (stimulation N)"; the prompt's Variety line says the same.

**`habituation.stale_stimulation` −6 → −3.** A familiar finish now breaks even at about 27% fresh with no gain
of its own, so an ordinary varied day isn't a losing one. On day 49's events that's about +21 a day, and he
settles near 26 instead of 18: still bored, but a day with a few new things lifts him over 40.

**Checked** on the live day-49 checkpoint, restored and run on an hour (stimulation 16): with the old rule
paint, sketch, invent, devise, the guitar, explore and study_sample were bored-locked, and 13 per-item options
(records, samples, books) were gone from the list; with this one only sketch is (last done half a day before,
its level still just over the line). Paint is open, though it pays at 20%. The scripted policy doesn't test any of this: it only
earns, eats, sleeps and waits (`BedroomPolicy`), and a 60-day run sits at stimulation 0 with or without it,
never meeting a bored lock.

**Not done.** Mood is 100 with stimulation at 18: the bored mood drag (−0.4 a point under 40) is swamped by
comfort, so low fun's only felt cost is this lock. The commissions' drawn-subject novelty goes away with
commissions, one cheap source of fun fewer. Content without `familiarity` keeps its lock by wear as before.
