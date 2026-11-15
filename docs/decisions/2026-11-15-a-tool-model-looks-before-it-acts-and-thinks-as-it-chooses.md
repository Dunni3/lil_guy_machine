---
title: A model that calls tools can look before it acts, and thinks as it chooses; no think verb for it
date: 2026-11-15
status: active
---

**Context.** With his verbs as tools (`2026-11-15-a-text-model-decides-by-calling-his-verbs-as-tools`),
the deciding model saw only what recall pushed into the turn and gave a one-line `reason`. Thinking
was a verb of its own (think, forced every few decisions) that a second model wrote, and asking how
the world works happened only inside it.

**Decision.** For a model that calls tools, in the bedroom:

- **Looks.** `ToolSet.looks` are tools that answer instead of acting: the backend answers a call to
  one, puts the answer back and asks again, up to `maxLooks` (3) a turn, then offers only the tools
  that act. The bedroom's are `recall` (`AgentMemory.search`: Jev scores his day memories,
  memories and thoughts, the latest 150, against what he's trying to remember, keeping those that at
  least partly answer it; keywords without a scorer) and `ask` (the oracle, as think used it; the
  answer goes into the event stream and his later prompts). Looks take no game time; he stands
  still while the model decides, as before.
- **Thinking as he chooses.** Every tool that acts takes `thought` (it was `reason`): what goes
  through his head as he chooses it, first person, under 80 words. It's the decision's rationale,
  and `AgentMemory.thought` keeps it as a thought event and his plan, so it reaches the summaries,
  recall, the self-model and the viewer's bubble like a think did.
- **No think verb for it.** The reflection verb isn't offered and he isn't made to think every few
  decisions. Jev and the scripted policy keep think as before.
- Thoughts older than yesterday are no longer recall candidates (they live on in day memories, as
  memories do), so 30 a day don't crowd recall. `recall` still searches all of them.

Gemini's own thinking can be returned (`include_thoughts` with a `thinking_level`), but as a narrated
summary, not in his voice; the `thought` argument is his.

**Consequences.** A turn is one to four calls. Looks are logged with the decision (`looks` in the
JSONL log); their own calls are metered as recall and ask. The writer still writes reading, the
self-model, shopping and inventions.
