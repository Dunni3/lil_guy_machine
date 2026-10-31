---
title: "Music starts with a record; listen hears whatever is playing (the `hear` op)"
date: 2026-10-31
status: active
---

# Music starts with a record; listen hears whatever is playing

Item 13 of `research_notes/2026-10-31_1711_fix-list-from-first-grammar-tv-run.md`. `listen` and
`play_record` both took a record, and Jev never chose `listen`.

- **Putting a record on (`play_record`) is how music starts.** `listen` takes no record argument:
  it's for whatever is on.
- **A `hear: <background>` op** ties a verb to a background another verb's `background` op starts.
  The verb is locked unless that background is playing with time left for the whole verb ("needs
  music playing; play_record puts some on", "the folk record won't last the 30 min listen takes").
  When it finishes it pays the playing item's value as mood, as `enjoy` does. Content is refused if
  no verb starts the background it names.
- **A `hear` op counts as asking for an item by affordance** in the consumptive kind's contract,
  since the verb that started the background asked for the item. So `listen` has no `requires`,
  and the shop lists records and the player as for `play_record` only.
- **The option lists are keyed on whether the music still has time for each `hear` verb**, as well
  as on the 10-minute step, so `listen` locks when it should and not up to 10 minutes late.
- **Habits:** `listen` wears and counts as new as one habit, whatever record is on, because its
  habit key is the verb and its arguments. Wear per record would need the playing item in the key.
- **Only one background plays at a time** (`BedroomWorld.music`). A second kind of background, or
  two at once, would need that generalised before more `hear` verbs.
- The numbers are unchanged: listen is 30 minutes in the chair for mood +4 and stimulation +12,
  plus the liking.
