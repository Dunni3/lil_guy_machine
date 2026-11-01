---
title: "Watchability: books wear as one habit; the app runs quiet stretches faster"
date: 2026-10-31
status: active
---

# Watchability: books wear as one habit; the app runs quiet stretches faster

Watching him, most of his day went to reading and studying, which is dull on screen
(`research_notes/2026-10-31_1936_watchability-books-win-and-quiet-stretches.md`). The aim is to
line his incentives up with the viewer's, without forcing his hand.

- **A verb chooses what repetition wears (`wear`).** By default each way of filling its arguments
  wears on its own (`items`: each record). `wear: verb` wears the verb as a whole, whatever it's done
  with: read and study, so five books are one habit each rather than ten fresh ones. What counts as
  new stays per item, so a new book still pays the novelty bonus once.
- **Art and guitar pay better, books a little less.** sketch: mood -1, stimulation +4 (was -4, +3).
  paint: mood -3, stimulation +12 (was -8, +6). play_guitar: mood +5, stimulation +10 (was +3, +8).
  read: mood +2, stimulation +6 (was +3, +8). study: no mood, stimulation +8 (was +1, +10). Not yet
  checked on a Jev run.
- **Quiet stretches run faster, in the app only.** While he is settled into a verb with little to
  watch (`RunSettings.QUIET_VERBS`: read, study, sleep; not while walking to it), the clock runs
  `RunSettings.quiet` times faster (default 4; a Run page knob, 1 for off). The sim and the CLI
  don't know about it: he does the same things, and the screen spends less time on them. The HUD
  shows `>> 4x` meanwhile. Which verbs are quiet is a viewer's call, so the list lives with the app,
  not in the content.
- **What he's doing shows.** Activities a viewer should notice get a pose and props in the renderer
  (book in hand, guitar in his arms, the record turning, notes rising, an open book on the desk). A
  new verb meant to be watched needs its pose in `Blob.kt`, or it falls back to idling.
- **Parked:** a restless need that sitting builds and active verbs work off, with verbs to work it
  off (stretch, dance, water the plant). It aligns his wants with the viewer's in the sim itself.
