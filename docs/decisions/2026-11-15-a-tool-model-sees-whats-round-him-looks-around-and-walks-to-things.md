---
title: "A tool model sees what's round him (`here`), can look around (`look_around`) and walk over to a thing (`walk_to`)"
date: 2026-11-15
status: active
---

**Context.** `2026-11-15-going-somewhere-is-implied-by-the-verb-plus-go.md` gave every model `place` and `places`,
but the tool turn's status (`BedroomTools.view`) dropped `nearby`, so a model that calls tools (gemini on the live
host) knew which place it was in but not what stood round it, and could walk only to whole places (`go`). Ian asked
for him to walk around and get the state of the space he's in: what's near, where he is, where he could go.

**Decision.**

- **`here` in every status:** `Observation.here`, set only by the tool view, is `nearby` in a few words each
  ("bed: 5 tiles north", "garden_bench: beside you", ", too dark to see"), nearest first, at most 12
  (`agent/.../Surroundings.kt`). It's what the sim already lets him see: what's in his place that he knows of, lit.
- **`look_around` is a look,** like `recall`: free, no game time, always offered to tool models (it reads the
  observation the sim made, so it costs no model call). It answers where he is, the weather and light, each thing
  round him with which way, how far, whether it's within reach, its state and the verbs he can do there, the other
  places with the walk to each, and what he could do elsewhere.
- **`walk_to` takes him over to a thing:** `move_to` with `Target.Entity`, a tool whose `to` is an enum of `nearby`'s
  ids. In the bedroom world it now walks to any placed thing he knows of, anywhere on the land, and the outcome
  says "walked over to the garden bench in the garden (17 min walk)", or "you're already by the bed".
- Text models that reply with JSON and Jev are unchanged: they already see `nearby` and `places`.

**Consequences.** About 60 tokens of `here` a decision and about 150 of tool definitions. In a 25-decision gemini
check he didn't call `look_around` or `walk_to` unprompted; he had `here` in front of him each turn.
