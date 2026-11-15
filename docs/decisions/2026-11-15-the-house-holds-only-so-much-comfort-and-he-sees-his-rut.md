---
title: "The house holds only so much comfort, rooms hold more; the sunroom and microscope are open; he sees what he's been doing lately"
date: 2026-11-15
status: active
---

# The house holds only so much comfort, and he sees his rut

**Context.** The live life on day 10 of 12 (gemini-3.5-flash deciding): $754 saved, mood 99.7,
stimulation 97, frustration 0. He went outdoors on days 1 and 2 only, built the lab on day 2 (the
only room plan the shop sold), never invented, and owned every catalog item but a poster. From then
on each day was paint ($134) → sell → buy a canvas and some decor → guitar. Ian: he has a ton of
money and isn't building, going outside, inventing or adding a room.

Why: comfort was the sum of everything in view, so 25 pieces of decor put the mood he settles to at
50 + 84, past the cap of 100. Each piece he bought also fed stimulation for 3 days (novelty
`things`), and two or three a day kept stimulation near 100. With mood and stimulation full, nothing
he could do would improve them, so the model did what still moved a number: money. An older local
run shows he does invent once he's bored. Separately, the sunroom plan, the microscope and
`study_sample` were tier 1, and the live host grants nothing, so there was no second room to build
and nothing to do with samples except sell them for $2 to $9.

**Decision.**

- **The house holds only so much comfort** (rules.yaml `comfort: { holds: 25, per_room: 10 }`):
  comfort counts up to 25 for the bedroom and 10 more for each room built onto it. Past that, more
  decor, hung art or samples add none, and a thing bought for a full house doesn't feel new: no
  novelty stimulation. What he invents still feels new. The catalog says so ("comfort +0: your house
  is full (it holds 35 comfort; each room you build holds 10 more), so it adds nothing and doesn't
  feel new"), and so does the system prompt. So what the money is for, once the room is full, is a room.
- **The sunroom ($150, up from $90), the microscope and `study_sample` are tier 0.** There are two
  rooms to build on the two sites, and samples can be studied (nature skill, mood, stimulation; each
  kind wears on its own), which is a reason to keep going back out.
- **Habituation recovers at 0.03 an hour, down from 0.04,** so a thing done two or three times a day
  wears down over days instead of resetting each night.
- **`lately` in his observation** (from day 2): what he finished most in the last 2 days that isn't
  routine, what he could do now but hasn't for 2 days or more (with the day he last did it), and
  what he could do and never has. `VerbTally` keeps a `lastTick`. A checkpoint from before that
  counts its repeated verbs as done at the checkpoint, so nothing is wrongly listed as long ago.
- A dev aid for trying late states: the CLI reads `LILGUY_START_ITEMS` as the host does, and
  `LILGUY_START_MONEY`.

**Why this and not a nudge.** Nothing tells him to go out or invent. The numbers say the room is
full, and the observation says what he's been doing; the model decides. A comfort ceiling keeps mood
off 100 so boredom shows in it again, and gates the decor-novelty flood that held stimulation up.

**Measured.** Two 2-day CLI runs, gemini-3.5-flash deciding, gemini-3.5-flash-lite writing, Jev
painting, from the live life's late state (its decor, books, records, guitar, workbench, an unbuilt lab
plan, $600; `LILGUY_START_ITEMS`, `LILGUY_START_MONEY`). One run each, so read it as a direction:

| | main | this change |
|---|---|---|
| bought | canvases, a poster, meals | the microscope, the sunroom plan, a canvas |
| built | nothing | the sunroom, day 1 |
| outdoors | first went out at 06:00 on day 3, once bored | the garden on day 1; forest, clearing and pond on day 2 |
| samples | 1 collected | 4 collected, 4 studied |
| invented | no | started one at the end (d3 06:06) |
| mood | 100 all of day 2 | 70-99 |
| choices | read 12, paint 4, play_record 4 | explore 4, collect 4, study_sample 4, read 5 |
| cost | $0.29 a day | $0.30 a day |

He tried guitar, sketching and inventing soon after `lately` listed them as "never yet".

**Not done.** Only two sites, so after the lab and the sunroom there's no third room; more land and
sites would be the next money sink. Samples aren't yet materials for inventing.
