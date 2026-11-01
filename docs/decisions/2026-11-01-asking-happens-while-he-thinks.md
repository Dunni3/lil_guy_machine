---
title: Asking happens while he thinks; there is no ask verb
date: 2026-11-01
status: active
supersedes: 2026-10-31-the-world-is-discovered-by-asking-and-the-shop-holds-back.md (its `ask` verb only)
---

**Context.** `ask` was a verb Jev could choose. Over 28 flat decisions and 42 beam decisions he
never chose it, though it was offered nearly every time: next to food, money and books, "find
something out" never looked like the best next action.

**Decision.** Asking is part of thinking. `think` (forced every few decisions by `reflection`, and
choosable) is a short turn on the writer model: it may ask the oracle up to 2 questions, one at a
time, each answered from the world's docs and put back into the conversation, then it must say its
thought (`Thinker`, reply `{"ask", "thought"}`; after the last question only `{"thought"}`). The
`ask` verb, the questioner and the `answer` handler are gone; the oracle and `WorldDocs` are
unchanged.

Answers go where they did: an answer event before the thought in the event stream, the latest two in
later prompts under "What you found out lately", and an `answer` line in the run log. The oracle's
calls are still metered as `ask`.

**Consequences.** The deciding model never sees a question option. How often he asks is the writer's
call each time he thinks, at most 2 per think; a think that asks costs the oracle's two calls per
question on top of the thought.
