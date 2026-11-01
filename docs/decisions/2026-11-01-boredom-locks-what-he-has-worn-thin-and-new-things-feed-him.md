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
  below `below` stimulation (40, the same line where boredom starts lowering his mood), a verb worn
  `worn` or more (0.5) is locked: "you're bored, and
  you've done it a lot lately (gains at 40%, stimulation 6)". It's out of Jev's choice set, and the
  world refuses it from any model. It unlocks when he's less bored or the wear fades.
- **Work stays open while he can't afford anything new.** Buying something new is the way out of
  boredom and work is the way to the money, so boredom never locks `work` verbs while the shop has
  nothing new to him that he can afford (a thing for the room he likes, or something for a verb he
  hasn't done). The option says "you're bored of it, but you can't afford anything new yet, so you
  keep at it". Seen on the TV (`app-bedroom-20261101-151847`): with sketching and his one book both
  locked and $2 left, he could only eat, shop, sleep and think for three hours.
- **What he's never done never locks,** even when it wears with something he's tired of: reading
  wears as one habit, but a book he's never opened stays open.
- **The shopper is told he's bored.** On that TV run the shopper (Gemini, playing him with his
  decision prompt: who he is, his memories and latest thought) spent his last $19 on an easel he
  couldn't use without a canvas, "so I can start painting and earning more", with seven
  decorations he liked in reach. Its instructions opened "Money is scarce and food comes first",
  and nothing tied his boredom to the catalog. Now, when he's bored, the ask says so first ("You're
  bored (stimulation 22 of 100) ... sketch and read are off limits until that wears off. Something
  new for your room that you like lifts your stimulation ..."), and the instructions put food first
  and then what he'd enjoy and use, now as well as later: money is for living well, not only for
  earning more. A picking shopper gets the same line as `state.now`.
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
  at +2/h and a rug (9 for a cozy-loving guy) at +4.5/h. Three or four new things about cancel the
  awake drain of 8/h. Things out of sight (books, records) already pay the first-time bonus
  when he uses them, so they don't feed him here.
- **What models see:** the shop and the shopper's catalog say "new to you: stimulation +2/h while
  you're awake, fading over 3 days"; the purchase event says the same; the variety line of the
  prompt states both rules.
- **Comes with** `2026-11-01-art-skill-pays-in-dollars-and-strokes.md`: art earns more as his skill
  grows, so he has money for the new things boredom pushes him toward.

- **Boredom has to keep coming back.** The first version locked below 25 with the drain at 5.5/h,
  and a 3-day Jev run never went below 27 (stimulation 60 to 100 from day 2, 100 on day 3): new
  books, the guitar and rising skill kept him topped up. So the drain is now 8/h (`needs.
  stimulation_per_hour`), the lock starts at 40, and **skill no longer raises stimulation gains**,
  only mood (`Bound.paid`): getting good at something makes it more enjoyable, not new again.

Not done: a forced novelty turn at stimulation 0, consequences for low mood, savings goals that
expire.
