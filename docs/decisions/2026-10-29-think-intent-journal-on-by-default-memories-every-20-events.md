---
title: "Think intent; journal on by default; memories every 20 events"
date: 2026-10-29
status: active
---

# Think intent; journal on by default; memories every 20 events

The think-intent part is superseded by [the grammar redesign](2026-10-31-verbs-are-ops-with-arguments-authored-ops-and-think-is-a-verb.md): think is a verb in the bedroom.

On the TV (Jev deciding, Gemini writing memories) he never journaled: `journal_endpoint` was
unset, so the journal was left out of Jev's options. Ian asked for the journal to be a real
option, for a `think` intent, and for long-term memories every 20 events instead of 40.

- **The journal writer defaults to the memory writer.** An unset `journal_endpoint` falls back to
  `memory_endpoint` (the app, the TV and the CLI's `--journal-endpoint`); "none" turns it off.
- **Without a writer the prompt doesn't mention the journal.** The persona's "the only things you
  can do", the journal hint and Jev's state and options leave it out. The reply schema still takes
  `do(journal)`, as the sim does, because the scripted baseline journals with no writer to test
  the economy.
- **`think` is an intent (`Intent.Think`, no fields).** The sim only spends the time: he stands
  where he is for `think.minutes` (10) and it costs `think.energy` (-2) on top of the awake loss,
  in `bedroom.yaml`. Ian: thinking should always drain a little energy. Key-and-door leaves it out.
- **The thought is one text-model call in the agent loop**, made after the decision and before
  the intent is submitted, so he stands still meanwhile, as he does while deciding, and lockstep
  runs stay deterministic. `Thinker` sends the decision prompt's system prompt plus think
  instructions, and the decision prompt's user message with a new ask ("What do you want to do
  next, and what do you want to achieve?"), and gets back `{"thought"}`, under 80 words. The
  journal's model writes it, so there is no new endpoint, and without a journal writer think isn't
  offered.
- **Where the thought goes.** The event stream as `EventKind.Thought` (the summarizer reads these
  as "I thought" lines), and short-term memory (`Memory.Entry.thought`), so the next few prompts
  show it in the recent actions ("you thought: ...") and Jev in `state.recent_actions`. It's also
  in `DecisionRecord.thought`, the decision log (`think`), the CLI output and the dev view.
- **No two thinks in a row.** Think is left out of the schema (new `SchemaHints.omit`, since a
  variant without fields can't be dropped with an empty enum) and of the choice set straight after
  a think, so a picking model can't spend model calls thinking in a loop.
- **Long-term memories every 20 events** (`Summarizer.batchSize`, was 40), so the first memory
  comes sooner and each covers about half as long a stretch.
- First runs: in 37 decisions Jev never chose `think` or `journal`, though both were often its
  second choice (6 to 16%). Gemini deciding thought on its first turn and journaled (2 wants)
  before lunch on day 1.
- **Update, same day: a think is forced every 4 decisions** (`think.every` in `bedroom.yaml`;
  remove it to leave thinking to the model). On the TV Jev never chose think or journal, so
  Ian asked for a forced think. The agent loop makes him think after 4 decisions without one,
  without asking the decision model (logged with the rationale "Time to stop and think"). His
  latest thought now goes into every later prompt until the next one replaces it, under "What you
  decided the last time you stopped to think (your plan for now)", and for Jev into
  `state.latest_thought`, with the instruction to carry that plan forward unless his needs won't
  allow it. In a 30-decision Jev run, the first forced thought ended "then write in my journal",
  and Jev bought food, ate and journaled (p 0.69, 3 wants). Jev followed each thought closely
  (often p > 0.9), so what he does now depends mostly on what the thinker writes.
- **Update, same day: his goal is the journal.** The persona, Jev's instructions and the thinker
  now say his goal is to express himself in his journal, and that money only buys the food that
  gives him time to write (Ian). This replaces the earlier rule that the prompt states no goal.
- **Update, same day: going without journaling sinks his mood, and low mood stops art.** New
  `needs.neglect` rules in `bedroom.yaml`: after 24 game hours without finishing a journal entry
  (counted from the start of the run), mood falls 5 an hour instead of drifting, until he writes.
  Sketch and paint `block` below 25 mood ("too low to make art"), so going without journaling ends
  up costing him his income. The observation's new `neglect` field (and Jev's `state.neglect`)
  says how long it has been; an event marks when the slide starts; the prompt and Jev's
  instructions explain the rule. Without a journal writer the rule is off, so he isn't punished
  for skipping something that writes nothing. In a 3-day Jev run he journaled 8 times (12 wants)
  and the slide never started: knowing the rule seems to be enough.
- **Thoughts on the Memory tab.** A Thoughts section shows the last two in full, the newest
  marked as his plan for now; the Now page shows when his mood is sinking.
- **Update, same day: journaling lifts mood, work lowers it** (Ian: journaling should always make
  him happier, working should always cost mood, and not journaling should too). Journal is now
  mood +12 (was +3); sketch -4 and paint -10 each time they finish. The prompt says so ("Each time
  you finish one, sketch costs 4 mood and journal adds 12 mood"). In a 3-day Jev run he journaled
  10 times (22 wants); mood ranged 35 to 68 and the 24-hour slide never started. Energy did hit 0
  for a while: journaling costs 10 energy and he sometimes wrote when he was already tired.
