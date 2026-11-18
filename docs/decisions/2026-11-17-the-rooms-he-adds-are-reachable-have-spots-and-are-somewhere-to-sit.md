---
title: "The rooms he adds are reachable, have free spots, and are somewhere to do things: no slot beside a doorway, where he sits is his pick (`seat`), and samples are studied at a `bench`"
date: 2026-11-17
status: active
---

**Context.** Ian (2026-11-17), after asking more than once: in the live run he has never gone into the rooms
he built or put a thing in one. The live snapshot (run `01a1e006`, days 1-49) shows three causes:

- **The lab was shut off.** The site doorway was at (20, 8), and the bedroom tile inside it is the fern pot's
  slot. Fern pots block. He bought one on day 35, and from then on the lab, and the sunroom behind it,
  couldn't be reached: "move_to lab failed: there is no way to reach the lab" (day 37). Places he can't reach
  aren't offered (`placeOptions`), so after that the rooms weren't even among his `go` choices.
- **Nothing he did happened there.** For the 33 days the lab could be reached he never went in, because no verb
  is done at the lab bench, the shelf, the armchair or the floor lamp's room. `read` and `listen` went to the
  nearest of the bed and the chair, so even a model that walked into the sunroom read in the bedroom.
- **The rooms had no free spots.** Their rows held only what comes with them, so `put` couldn't take anything
  into them. The sunroom lamp he invented "to light up the new sunroom" stands in the bedroom.

**Decision.**

- **No slot beside a doorway.** `Layout.of` refuses content with a slot on any of the four tiles round a door
  (the front door and every built room's). Every thing that stands is in a slot, so nothing he owns or puts
  down can shut a room off. The sites' doors move to row 10 (`[20, 10]`, `[27, 10]`), which has open floor on
  both sides. The checkpointed house is unchanged: the doorway is cut where the content says on load.
- **Free spots in the rooms.** The lab gets `by_lab_bench`, `lab_east_wall`, `lab_south_wall`, and the sunroom
  `by_armchair`, `sunroom_east_wall`, `sunroom_south_wall`. They're lower-case letters in the room's rows, ground
  spots like the land's, and a spot exists only while its room stands.
- **Where he sits is his pick.** A new affordance `seat` (the bed, the chair, the armchair). `read`, `play_guitar`
  and `listen` take `on: seat` and happen `at: [on]`, so the seat is a closed choice like any argument: tool
  models get it as an enum, Jev as one option per seat. `play_guitar` and `listen` wear as one habit
  (`wear: verb`), as they did with no arguments, so changing seat isn't a way round the bored lock for the verb.
  The seat still counts toward what doing it is like (`thing:`, `place:`), so reading somewhere new is fresher.
- **Samples are studied at a `bench`.** The microscope and the lab bench afford `bench`, and `study_sample` takes
  `on: bench`. Building the lab is enough to study a sample, with no microscope.
- **Unnamed, it's the nearest one he can use.** When `do` leaves out an argument the verb happens at, the sim
  takes the nearest reachable one that's lit and warm enough (`nearestOwned`), so the scripted policy and models
  that leave it out behave as the old `at` lists did.

- **He chooses where every new thing goes.** With world.yaml `carry.wait` (on in the shipped content), a thing
  that stands somewhere arrives unplaced: one he buys waits by the mail hatch, and one he builds by the workbench
  (`BedroomWorld.waiting`, saved with checkpoints). Until he puts it down it stands nowhere: no comfort, light,
  sound or lure, nothing blocks, and a verb done at it is locked with "the easel is new and waiting by the mail
  hatch; put it somewhere first (place)". `put` fetches it from where it waits. It goes only to a free spot (no
  swap), and the spot the content keeps for it counts as free, so its usual place is one choice among the rest.
  `Observation.put.waiting` lists what's waiting, and the place tool's description names each with where it waits. The scripted
  policy puts a new thing at the spot named after it when there is one. Things his code makes (`world.make`)
  still go straight to a spot; his code moves them with `world.move`. Tests of other mechanics turn `wait` off
  (`nothingWaits()`).

**Consequences.** More closed choices: read is books x seats and study_sample samples x benches. With 3 seats
that's 3x the read options at most. Checked on the live day-49 checkpoint: he walks into the lab (9 min), puts
the fern pot by the lab bench, studies there, and reads and plays in the armchair.

**Not done.** More reasons to be in a room than a seat and a bench (the desk verbs stay at the desk). The web
doesn't draw a waiting thing yet (a box by the hatch would show it).
