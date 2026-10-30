---
title: "Bedroom numbers: what changed from the plan's first guesses"
date: 2026-10-29
status: active
---

# Bedroom numbers: what changed from the plan's first guesses

Kept: start ($40, bed, desk, chair, sketchbook, 2 meals; energy 80, fullness 70, mood 60), the
activity and shop tables, energy -4/h awake, fullness -5/h, mood drift 3/h toward 50 plus comfort,
low at 15, work refused below 5.

Changed or filled in:
- Below 15 energy or fullness, mood falls 5/h *instead of* drifting; otherwise drift cancels most
  of it.
- Sleep also ends when fullness falls below 10 (`stop_below`). Both the scripted policy and Jev
  went to sleep at fullness around 40 and slept it down to 0.
- Sleep is refused above energy 94 ("not tired") and eating above fullness 90 ("not hungry").
- Sell art is not `work`, so a hungry guy can still sell what he holds.
- Unspecified in the plan: comfort plant 4, poster 3, rug 6; three records and three books, each
  one of a kind; buying takes 10 minutes at the door; the day starts at 08:00.

Economy as built: sketching nets about $60–90 a day, the easel comes on day 2 (scripted) or day 3
(Jev), and painting then makes about $150–300 a day. That is more than the plan guessed, and the
whole shop costs about $500, so money stops being a constraint within about three days of buying
the easel. That is the first thing to tune, probably alongside milestone 6's wants.
