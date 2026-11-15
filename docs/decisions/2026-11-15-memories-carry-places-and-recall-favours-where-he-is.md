---
title: "Memories carry places, and recall favours memories of where he is"
date: 2026-11-15
status: active
---

Amends `2026-10-31-memory-redesign-outcomes-days-self-model-scored-recall.md`.

**Context.** Ian (2026-11-15): he needs a spatial memory, so he can tie events and things to places, and what comes
back to him can depend on where he is.

**Decision.**

- **Every event has a place:** `MemoryEvent.place`, the place's name ("the forest clearing"). The one store every
  writer shares is wrapped in `PlacedStore`, which stamps each event with where the sim last published him, so
  outcomes, world events, thoughts, readings, art and trips all carry it without each writer knowing.
  `AgentMemory.connect(world)` points it at the world. Null where there are no places.
- **Memories carry the places they cover,** most of it first: a stretch from its events, a day from its stretches.
  The summarizer sees "(in the garden)" in its log where the place changes, so the memory can say where he was.
- **Recall:** `Recall.selectRanked` adds `placeWeight` (0.5) for a recollection of the place he's in; the keyword
  rule adds the place's name to what it matches; the Jev relevance question's state says `where`. A stretch or a
  thought says where it happened when it's rendered ("Day 2 10:00-11:30 in the forest clearing: ...").
- **Knowledge of where things are** stays in the sim, not prose: what he has found and explored (`BedroomWorld`
  `known` and `explored`) fills his map (`WorldMap`), so it can't drift from the world.
- **Storage:** `memory_events.place` and `memories.places` (joined with "; "), nullable. The local database adds
  the columns itself; the server has migration `0003_memory_places.sql` and regenerated models.

**Not done.** Where a thought was isn't kept across a checkpoint (it's re-read from nothing); places on the
`/room/` memory pages; weights not tuned.

**Measured.** A live game day (gemini-3.5-flash deciding, flash-lite writing, Jev scoring): 52 events, 47 in his
bedroom, 3 in the forest, 2 in the garden; the day memory carried all three places. $0.22 a game day.
