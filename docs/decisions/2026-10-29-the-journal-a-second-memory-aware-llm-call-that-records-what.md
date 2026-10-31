---
title: "The journal: a second, memory-aware LLM call that records what he wants"
date: 2026-10-29
status: superseded
---

# The journal: a second, memory-aware LLM call that records what he wants

- **Activity.** `journal` (tier 0) needs the starting `journal` item, takes an hour at the desk,
  is `work` (refused below the work floor, so only when fed and rested), and costs energy -10 for
  mood +3. The sim stays pure: it doesn't know a model writes anything.
- **When an entry is written.** `AgentMemory.acted` starts a `JournalWriter` session when he
  chooses `do(journal)` and it isn't locked; in the background when there's a scope (the app),
  inline otherwise (lockstep runs, so they stay deterministic). At the next decision point
  `observed` keeps the entry only if the activity succeeded, and throws it away if it failed or
  was interrupted. An entry still being written when the activity ends is stored when it lands.
- **The writer is a step loop, not native tool calls.** Each reply is schema-constrained,
  `{"step": search | write}`, so it works with every backend and strategy. `search(source:
  memories | events | journal, query)` returns up to 5 hits as the next user message (the
  transcript uses the assistant role); after 3 searches the `search` variant is dropped from the
  schema (an empty enum on its query), forcing `write`. The writer sees the persona and the
  decision prompt's own user message (observation, recalled memories and journal, recent actions).
- **Wants are structured and must be argued.** `write` returns `entry` (under 150 words) and
  `wants: [{kind, text, justification}]`, kind one of activity, item, place, sense, other.
  `Want` refuses a blank justification, so a bare want is a validation error the backend retries
  with, not something to hope the model remembers.
- **Storage.** A `journal` table/list in `MemoryStore` (SQLite stores wants as JSON) and
  `search(run, source, query, limit)`, keyword-scored by the new `Keywords` (shared with
  `Recall`): shared words, newest first on ties. SQLite narrows events with `LIKE` and ranks in
  Kotlin so both stores rank the same; FTS5 would rank differently and isn't needed at this size.
  Only "wrote in my journal" goes in the event stream (new `EventKind.Journal`), never the entry,
  so memories don't paraphrase his entries back to him.
- **Entries reach every decision.** `Recall.selectJournal`: the latest 2 entries always, then
  older ones sharing words with the situation, within their own 800-character budget (memories
  keep 1200), so neither crowds the other out; only the latest is guaranteed. They go in the user
  message under "What you wrote in your journal, oldest first:", after the memories and like
  them, since the system prompt is fixed text; the system prompt says the journal is his own
  words and should shape how he spends his hours. Picking models get `state.journal`.
  `DecisionRecord.recalledJournal` keeps what each decision was shown.
- **Who writes.** `agents[0].journal_endpoint` in the config (`JOURNAL_ENDPOINT=<name|none>` for
  `make desktop` and `make tv-run`, `--journal-endpoint` for the CLI, which keeps entries in
  `--memory FILE` or in memory). It must write text. Without one, journaling still costs time and
  energy but writes nothing, and it is left out of a picking model's choices.
- **Reading it.** A **Journal** page in the dev view (between Memory and History). Journal
  sessions are logged in the decision log as `"kind": "journal"` lines (calls, search results,
  entry, wants, tokens); `DecisionLog.read` skips them, so replays still work. CLI bedroom runs
  print the entries and the collected wants (repeats folded, with the days they recurred) and
  write `journal-NN.txt`; `summary.json` counts entries, failures, wants and journal tokens.
- Nothing grants wants yet. Before the persona's promise is made in earnest, someone has to read
  the wants and answer some (a grant via `granted`, or a "not yet, because").
