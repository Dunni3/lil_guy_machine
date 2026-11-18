---
title: A text model decides by calling his verbs as tools; Jev keeps recall, significance and painting
date: 2026-11-15
status: active
---

**Context.** Jev picks each decision from a closed set, walked as a menu tree by beam search
(`2026-11-01-jev-walks-a-menu-tree-by-beam-search`). It's cheap, but a picker can't reason, the
tree has to be kept by hand as content grows, and every decision is three to five requests with
the whole state each time. A text model could already decide, but as a form: one observation as
JSON and a generic `do(activity)` enum, with his recent actions as lines of text.

**Decision.** In the bedroom, a model that can call tools (`Capabilities.toolCalls`: an
`openai_compat` endpoint, not `text`) decides by calling one of his verbs as a tool.

- The tools are built per decision (`agent/.../BedroomTools.kt`) from the verbs he's offered and
  can do now: one tool per verb, named by its id, described by what it takes and gives, with `what`
  and `on` as enums of the things he could do it with. Plus `buy` (when there's no shopping trip)
  and `wait`. Every tool takes a `reason` first, which is the decision's rationale. Locked verbs
  aren't tools; the turn's message lists them with why they're locked.
- `DecisionRequest.tools` (`decision/.../ToolSet.kt`) carries them: each tool turns its call's
  arguments into the reply it stands for, which is then validated by the reply schema as before.
  The backend sends those tools in place of the schema's whenever a request has them.
- His recent actions are sent as his own earlier tool calls, each answered by how it ended (and
  for a think, what he thought), then one message with how things stand now: the observation
  without the map, coordinates or the open activities (they're the tools), plus what he recalls,
  his latest thought and what he found out.
- The system prompt and the tool list come first and change only when his self-model does or he
  learns a verb, so the prompt prefix stays cacheable.
- One action ends the turn (phase 1). Looking around first, with tools for recall, asking and
  looking at things, is phase 2.
- His art is drawn by `painter_endpoint` (`--painter-endpoint`), which defaults to the deciding
  one: two calls a stroke is cheap on Jev and not on a text model.
- Jev stays the recall and significance scorer. Jev as the decider (`BeamSearch`, `menu.yaml`,
  `BedroomPrompt.choices`) stays until the tool path has proved itself over a few days, then goes.
  Key-and-door keeps its old reply schema as the quick backend test.

**Consequences.** Decisions cost text-model tokens: in a 3-day CLI run on gemini-3.5-flash, about
30 decisions a game day at 3.7k–4.5k prompt tokens each, $0.18 a game day, against nearly nothing
on Jev. In that run he earned $315 to Jev's $126 over the same 3 days, never let fullness reach 0
(Jev's did for 731 ticks), used 14 verbs to Jev's 9, and had 1 failed intent to Jev's 6. Content needs no menu placement for a text
model. Old decision logs still replay: the reply envelope and the intents are unchanged.
