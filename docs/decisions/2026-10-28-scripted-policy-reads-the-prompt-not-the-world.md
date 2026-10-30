---
title: "Scripted policy reads the prompt, not the world"
date: 2026-10-28
status: active
---

# Scripted policy reads the prompt, not the world

`KeyDoorPolicy` is a `DecisionModel` that parses the observation back out of the prompt
`PromptBuilder` built (`PromptBuilder.observationOf`) and replies with intent JSON decoded by
the same `ActionSchema` path a hosted backend will use. It can only know what an LLM would
know. It's used instead of `ScriptedDecisionModel`'s canned replies so it reacts to failures and
works for any start state. In the app it fakes 600 ms of latency to make the thinking visible.
