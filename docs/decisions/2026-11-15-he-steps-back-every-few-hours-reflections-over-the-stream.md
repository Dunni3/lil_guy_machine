---
title: He steps back every few hours; reflections over the event stream sit beside his thoughts of the moment
date: 2026-11-15
status: active
---

**Context.** Since a model that calls tools thinks as it chooses
(`2026-11-15-a-tool-model-looks-before-it-acts-and-thinks-as-it-chooses`), his only thoughts are the
deciding model's `thought` for each call: why the next thing, mostly read off his needs ("My
stimulation is down to 33, so sketching is locked. I'll reread..."). The think verb, which made him
stop every few decisions and work out what he wants, isn't offered to tool models. The hope was that
an agent always thinking would also step back on its own; across Gemini lives it didn't. Nothing
drew a pattern or a lesson out of a stretch of his life: level-1 and day memories say what happened,
and the self-model, once a night, is the only interpretation.

**Decision.**

- **Reflections.** `agent/.../memory/Reflector.kt`: every so often the writer reads what has
  happened since the last reflection (outcomes, the world, his thoughts of the moment, up to the
  latest 80 events), with the core, his self-model, his latest day memory and his last four
  reflections, and writes what it adds up to, first person, under 130 words: a pattern and why, a
  lesson said generally enough to hold another day, how he feels about how it's going, a question
  or something to try differently. It's told to go deep on one or two, to build on or change what he
  concluded before rather than repeat it, and not to recap or talk in numbers.
- **When.** With a scorer, once the significance of the events since the last one adds up to
  `Reflector.THRESHOLD` (4.5, about three of the summarizer's stretches; not tuned), with at least 12
  events; without one, every 30 events. It runs in the background after a choice and never holds up a
  decision.
- **Where they go.** A reflection is a memory of level 4 (`Reflector.LEVEL`) covering the events it
  read, so where the next starts and every one so far are in the store, and checkpoints need nothing
  new. The latest two are in every decision prompt under their own header (a picking model gets
  `state.reflections`); older ones are recall candidates of their own kind (`Recollection.Kind.Reflection`),
  with no day cut-off; `recall` searches them all. The night's self-model revision reads the day's
  reflections beside its thoughts. Viewers see them in THOUGHTS, marked "stepping back"
  (`ThoughtView.reflection`).
- **Who writes them.** The writer endpoint, for any deciding model (Jev and the scripted policy too);
  none without a writer. Token purpose `reflect`.
- **The thought of the moment** is told to think as himself, not read out his numbers: what he
  notices, feels, wants or wonders, and what he's after (the tool system prompt and the `thought`
  argument's description).

**Consequences.** Three or four more writer calls a game day, a few thousand prompt tokens each.
Reflections are interpretation, like the self-model; the summarizer still never sees either, so the
memories stay the evidence.

**First life.** One Gemini Flash day (writer and decider, Jev scoring): two reflections by mid-afternoon,
about $0.003 each. The first named a pattern (rushing into an offer, selling work that missed it) and a
lesson (observe first); the second tested that against what he did since and found he hadn't kept it
("I tell myself I'm slowing down, yet I keep jumping..."). The night's self-model took it up as what he'd
learned about himself. His thoughts of the moment stopped quoting needs.
