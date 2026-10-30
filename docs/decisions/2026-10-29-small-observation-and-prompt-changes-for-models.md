---
title: "Small observation and prompt changes for models"
date: 2026-10-29
status: active
---

# Small observation and prompt changes for models

`nearby` entries now carry their tile `x`/`y`, so `move_to` a tile doesn't require arithmetic.
Short-term memory records how each intent ended, and the prompt lists recent actions with their
results. The system prompt describes the world rules, the action space (from `ActionExamples`,
nulls shown explicitly), the reply format and every observation field. The wait gloss now says
"20 ticks = 1 s of game time", since in lockstep runs a second of game time takes no wall time.
No gameplay changed.
