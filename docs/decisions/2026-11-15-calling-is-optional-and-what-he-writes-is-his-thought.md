---
title: Calling is optional for a tool model; what he writes is his thought, and a turn that runs out of steps is think
date: 2026-11-15
status: active
---

**Context.** A model that calls tools had to call one every reply (`tool_choice: required`), with a
required `thought` argument (`2026-11-15-a-tool-model-looks-before-it-acts-and-thinks-as-it-chooses`).
With that he almost never looked: 5 of about 420 turns across six 3-day Gemini lives, recall once.
A quick test with `tool_choice: auto` and a prompt inviting him to think, remember or act had him
recall in 4 of 6 calls and write his thinking as text once, and he never stalled. Lives with
nothing open but wait (no think verb for tool models) waited 10 to 20 times in 3 days.

**Decision.** For a model that calls tools, in the bedroom:

- **Calling is optional.** The bedroom's tools go with `tool_choice: auto` (`ToolSet.ponder` set),
  whatever the endpoint's `tool_choice`. A reply may be text, looks, or both, and an action ends the
  turn. A reply of only text is him thinking: it's put back with "Go on." and he goes on, with no game
  time passing.
- **What he writes is his thought.** The text of the reply that acts, else his latest text this turn,
  fills the action's `thought` when the call leaves it out (`ToolSet.thoughtArg`). `thought` is now
  optional. His earlier actions are replayed as his own text plus the call, not as a `thought` argument.
- **One step budget.** A turn has `BedroomTools.MAX_STEPS` (5) replies that don't act, of thinking,
  looks or both; there is no separate looks cap. When they run out with no action, the turn ends as
  the reflection verb (think, 10 game minutes), with what he wrote as its thought and no writer call.
  If think is locked (he just did it), the turn is the fallback wait.
- **The same tools at every step** of a turn, looks included, so a server's prompt cache keeps the
  prefix while he thinks and looks. A look past the budget is refused as before.
- **Accounting.** Usage carries cached prompt tokens (`prompt_tokens_details.cached_tokens`, or
  DeepSeek's `prompt_cache_hit_tokens`), priced by an endpoint's `price_cached`. A decision's calls
  count its steps. The decision log has `musings`, `pondered` and `cached_tokens`.

The workbench's looks (its own `ToolSet`, no `ponder`) still require a call every reply; `maxLooks`
is now `maxSteps`.

**First lives.** Two Gemini Flash lives (3 days and 2 days, 111 turns, `reasoning_effort: low`): he
wrote his thinking beside the call in about 40% of turns, looked in 2 (ask, no recall), never
replied with text alone and never ran out of steps; decisions cost $0.16-0.17 a game day, as before.
Optional calling alone doesn't make Flash look, likely because the recalled memories are already in
the status message.

**Consequences.** A turn is one to six calls, so a model that thinks and looks costs more a game day; providers that
cache prompt prefixes (DeepSeek, DeepInfra) charge little for the extra steps. Two DeepSeek V4.1
Flash endpoints are in the example config (`deepseek`, `deepseek-deepinfra`), strict off, thinking on
at `reasoning_effort: low`; DeepSeek's `reasoning_content` is sent back within a turn.
