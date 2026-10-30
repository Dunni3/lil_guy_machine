---
title: "The provider is TypeSafe; its backend picks from a closed set"
date: 2026-10-29
status: active
---

# The provider is TypeSafe; its backend picks from a closed set

Supersedes "The decision-model provider backend waits for its docs". The provider is TypeSafe
(docs.typesafe.ai). Its System One API (`POST https://api.typesafe.ai/v1/systemone`, model
`jev-latest`) isn't OpenAI-compatible: it answers typed questions about a `state` with
calibrated probabilities and writes no text. So `TypesafeBackend` (`kind: typesafe`) asks one
Choice question per decision. A backend declares `Capabilities.closedChoice`, and then the
agent adds a `ChoiceSet` to the request: a compact JSON state (goal, rules, holding, what it
sees with `in_reach`, last and recent actions with results, events; no ASCII map, per
TypeSafe's advice that irrelevant detail costs accuracy) and the options. The options are every
entity-argument action the reply schema allows (pick up, use on, walk to each visible thing; use
a held item alone; wait 1 s). Tiles and `say` are left out: tiles are too many to list, and a
picker can't write text. Each option carries the exact reply it stands for, which still goes
through the schema. The rationale is the pick's probability, confidence and runner-up; the raw
log keeps the full distribution.

Options are ordered pick_up, use, move_to, wait, because TypeSafe documents a lean toward
earlier options. Not tested the other way round yet.
