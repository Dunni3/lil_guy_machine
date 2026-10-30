---
title: "Decision logs are JSONL, one line per decision, and replayable"
date: 2026-10-29
status: active
---

# Decision logs are JSONL, one line per decision, and replayable

`DecisionLog` writes, per decision: run, model, tick, observation, request (system prompt,
messages, temperature, the per-request schema), every attempt with its error, raw reply, thought,
submitted intent, latency, tokens, error and whether it fell back. Keys are snake_case for
pandas. `ReplayDecisionModel` feeds the logged replies back through the schema, and lockstep
determinism makes the replay retrace the run tick for tick; a tick mismatch is an error. The CLI
writes `runs/<time>_<endpoint>/run-NN.jsonl` plus `summary.json`; the app writes one file per
episode.
