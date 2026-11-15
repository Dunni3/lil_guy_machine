---
title: "Comfort is what he can see lit from where he stands; the house no longer holds a fixed amount"
date: 2026-11-15
status: active
supersedes: 2026-11-15-the-house-holds-only-so-much-comfort-and-he-sees-his-rut.md
---

# Comfort is what he can see lit from where he stands

**Context.** The house's comfort cap (rules.yaml `comfort: { holds: 25, per_room: 10 }`) was added
because 25 pieces of decor pinned his mood at 100 and their newness held his stimulation near 100, so
nothing was worth changing. It worked, but it was a made-up limit: a number for the house that nothing
in the world explains. Now that the sim has light (`2026-11-15-light-is-an-effect-with-reach-*`),
comfort can come from what he can actually see.

**Decision.**

- **Comfort counts what he can see from where he stands** (rules.yaml `comfort: { near: 2, far: 6,
  lit: 4 }`, `Lighting.viewWeight`). That means things in the place he's in, with a clear line to
  them. A thing counts in full within `near` tiles, less and less out to `far`, and not at all past
  that. It also counts in full at `lit` light or more, less in dimmer light, and not at all where it's
  too dark to see. Hung art counts where it hangs (the picture rail). His collection counts where its
  shelf is (in his bedroom). A built room's qualities count while he's in it. Asleep, he counts what's
  round him in the room he sleeps in, lit or not, so a night's sleep doesn't drag his mood down for
  being dark. `comfort.holds` and `comfort.per_room` are gone, and so are the shop's "your house is
  full" and the novelty gate that came with them.
- **Newness is felt the same way.** A new thing for the room feeds stimulation (novelty `things`)
  times how much he can see it now. The catalog says "comfort +4 in view (near you, lit)" and
  "stimulation +2/h while you're awake and can see it". A lamp says how much light it gives and how far.

**Why.** It's the same limit with a reason the world gives: a room only has so many things near any
one spot, and only what's lit counts. Where things stand and what lights them now matter. A lamp by the
bed raises comfort at night where he reads, and the far corner adds little. That's the incentive to
place things and to buy lamps, without a cost made up for it.

**Measured** (scripted policy, 4 game days, every piece of decor in the shop owned from the start via
`LILGUY_START_ITEMS`):

| | main (cap) | this change, reach 3 to 8 | this change, reach 2 to 6 (shipped) |
|---|---|---|---|
| mood, by day | 59-74 | 80-98 | 53-79 |
| comfort at the bed, desk, easel, chair, hatch, noon, no lamps | 25 (capped) | 24, 45, 31, 38, 31 | 17, 26, 23, 20, 22 |
| same, 22:30, no lamps | 25 | 0 | 0 |
| same, 22:30, with the six lamps | 25 | 44, 50, 28, 46, 45 | 35, 31, 12, 18, 29 |

So mood doesn't pin at 100, and it moves with where he is and the hour. In the scripted run he was
bored (stimulation near 0) and mood still ranged 53-79, so boredom still shows in it. Over 2 scripted
days with a novel only he sketched as often as on main (nine times), since the desk has its own lamp.
Money and food were the same as main. The last column was measured with the weather (#66) on.

**Not measured.** Whether a model notices and acts on it (moves a lamp to where it reads, buys one for
the dark evenings) needs a live run, and the `place` tool from phase 0 to move things.
