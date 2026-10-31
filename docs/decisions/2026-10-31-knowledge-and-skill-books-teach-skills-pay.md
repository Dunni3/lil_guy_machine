---
title: "Knowledge and skill: books are read a chapter a session and teach what they hide; named skills pay off"
date: 2026-10-31
status: active
---

# Knowledge and skill: books teach, skills pay

Step 3 of `research_notes/2026-10-30_1951_world-grammar-consolidated-plan.md`, with Ian's calls on
its open questions: one skill per named area, skills reset each run, and a book takes a fixed
number of sessions.

- **Books are data in a new file, `library.yaml` (`books:`).** One row per readable item: title,
  blurb, chapters (one sentence each), `skill` (gains per chapter studied) and `teaches` (verb and
  item ids). A book takes as many sessions as it has chapters. The item row (price, qualities)
  stays in items.yaml.
- **What a book teaches isn't in his world until he finishes it.** No tier, no grant: the world
  keeps a `known` set, the shop doesn't list unknown items, he can't do or buy them, and
  `BedroomWorld.knownContent()` is what the prompt is built from, so it's rebuilt when he learns
  something. The shop says what a book teaches, so a model has a reason to buy it.
- **Painting is learned.** `drawing_book` teaches `paint`, the easel and canvas. That is the
  content's first use of teaching, and it answers "painting dominates the economy". The music book
  teaches the guitar and `play_guitar`. Taking `paint` out of `teaches` restores day-one painting.
- **Two new ops.** `progress: { book: what, study }` reads the next chapter of the bound book;
  finishing teaches. `study: true` raises the book's skills, and is locked when the book has no
  skill or is finished; reading a finished book rereads it. `practice: { art: 0.1 }` raises skills.
  A new `study` kind allows them (with `need`, `rate`, `write`). Consumptive and generative may
  `practice`, and consumptive may `progress` and `write`.
- **Read is for the story, study is for the skill.** Both move the same chapter counter.
- **Skills** are a closed list in `vocabulary.skills` (art, music, nature), from 0 with no cap,
  per run. They pay off in three ways, set in `rules.yaml` `skills`: a verb's mood and stimulation
  gains grow `gain_per_level` with the skills it practises; a piece whose `sellable` names a skill
  sells for `price_per_level` more per level he had when he made it; a skill that `deepens` a
  quality raises his affinity for it (nature: natural, music: lively). No skill minimums on verbs
  yet. Nothing in the content needs one.
- **What stuck.** `write: { with: reading, optional: true }` on read and study: a text model writes
  a sentence of what stuck from the chapter into his events (`EventKind.Reading`, token purpose
  `reading`). `optional` is new on `write`: the verb is offered without a handler, so Jev-only runs
  still read.
- **Frustration is the fifth need.** A failed intent adds `per_failure` (10); finishing a
  non-routine verb adds `per_success` (-2); it fades `per_hour` (-3), and sleep's rate eases it
  faster. Above `above` (20) it lowers the mood he settles to by `mood` (0.3) a point. It's a
  `GridWorld.failed` hook.
- **Numbers retuned at the same time** (the fix list from the first TV run of the grammar,
  `research_notes/2026-10-31_1711_fix-list-from-first-grammar-tv-run.md`, items 14-16, 18 and 20):
  stimulation drains 5.5 an hour (was 3); wear recovers 1.5% an hour (was 4%); the first-time bonus
  is stimulation +8 and mood +3 (was +15 and +4); paint costs energy 8, mood 8 and gives stimulation
  6 (was 15, 10, 12); sketch costs energy 3 (was 6); think costs energy 1 (was 2); fullness drains 2
  an hour asleep (was 5); a painting sells for $30 plus skill (was $70); read gives mood 3 and
  stimulation 8 (was 2 and 15).
