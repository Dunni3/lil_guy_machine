---
title: "Boredom locks what he has worn thin, and new things feed him"
date: 2026-11-01
status: active
---

# Boredom locks what he has worn thin, and new things feed him

Amends `2026-10-31-stimulation-habituation-and-novelty-mood-is-the-summary.md`. In the TV run
`app-bedroom-20261101-141758` (Jev deciding), stimulation sat at 0 to 8 for two days and he bought
no decor. Painting kept winning even with "gains at 10%, and stimulation -5", because wear cut its
gains but not its pay, and low stimulation only lowered mood, which nothing reads. Decor paid only
in mood. Ian wants boredom to stop him from doing things, and buying things to be a way out.

- **Bored, what he has worn thin is refused, pay and all.** `habituation.bored_lock: { below, worn }`:
  below `below` stimulation (25), a verb worn `worn` or more (0.5) is locked: "you're bored, and
  you've done it a lot lately (gains at 40%, stimulation 6)". It's out of Jev's choice set, and the
  world refuses it from any model. It unlocks when he's less bored or the wear fades.
- **What never locks:** routine verbs, verbs that feed energy or fullness (the oats and sleep stay
  open, so `no-dead-ends` still holds), and verbs with no mood or stimulation gains (think). A verb
  that wears per item (each record) is judged per item.
- **Wear recovers faster:** `recover_per_hour` 0.015 → 0.04, so a lock lasts hours, not days. Back
  to back, sketching locks after three sessions and painting after about four; after that he can
  do each about once every six hours. That makes him alternate, and paint less often than he did.
- **New things feed stimulation.** `novelty.things: { stimulation_per_value, days }`: a thing he
  buys that sits in a slot feeds stimulation while he's awake, `stimulation_per_value` (0.5) an
  hour per point of its value to him, fading linearly to nothing over `days` (3). Value is the
  affinity score that comfort already uses, so only things he likes count: a plant (value 4) starts
  at +2/h and a rug (9 for a cozy-loving guy) at +4.5/h. Two or three new things about cancel the
  awake drain of 5.5/h. Things out of sight (books, records) already pay the first-time bonus
  when he uses them, so they don't feed him here.
- **What models see:** the shop and the shopper's catalog say "new to you: stimulation +2/h while
  you're awake, fading over 3 days"; the purchase event says the same; the variety line of the
  prompt states both rules.
- **Comes with** `2026-11-01-art-skill-pays-in-dollars-and-strokes.md`: art earns more as his skill
  grows, so he has money for the new things boredom pushes him toward.

Not done: a forced novelty turn at stimulation 0, consequences for low mood, savings goals that
expire.
