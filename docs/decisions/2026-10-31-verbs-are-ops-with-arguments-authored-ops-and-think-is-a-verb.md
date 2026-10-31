---
title: "Verbs are ops with arguments; authored ops share one lifecycle; think is a verb"
date: 2026-10-31
status: active
supersedes: 2026-10-29-bedroom-content-is-one-yaml-file-on-the-classpath.md
---

# Verbs are ops with arguments; authored ops share one lifecycle; think is a verb

A redesign of the world grammar, built between step 2 and step 3 of the world-grammar plan, after
a review of step 2 (`research_notes/2026-10-31_1459_grammar-ops-redesign-plan.md`). Here's what
it supersedes:
- All of `2026-10-29-bedroom-content-is-one-yaml-file-on-the-classpath.md`.
- The kinds-and-contracts part of `2026-10-31-affordances-qualities-affinities-and-verb-kinds.md`.
- The think-intent part of `2026-10-29-think-intent-journal-on-by-default-memories-every-20-events.md`.

**Effects are typed ops.**
- What a verb does is `effects:`, a list of ops run in order: `need`, `rate`, `consume`, `produce`,
  `sell`, `set_state`, `background`, `sleep`, `enjoy`, and the authored `write` and `draw`.
- Each op is a class in `sim/.../Ops.kt` (authored ones in `Authored.kt`). The class owns its
  content checks, lock reason, apply and prompt text. `BedroomWorld` runs ops through a private
  `OpWorld` and holds no mechanic itself.
- A new mechanic is one class plus its row in `OpEntry`. Step 3 (study, teach, skill, `knows`)
  adds its mechanics as ops.

**Kinds list what's allowed.**
- `VerbKind` lists the ops a kind may use and the ones it must use. An op no kind lists is
  refused everywhere, so a new op is closed until a kind allows it on purpose.
- The not-ready perceptual and study kinds are gone. They come back with their ops.
- Kind stays a declared field: an invented verb needs a category to be checked against.

**Items' affordances take parameters.**
- `sellable{price}` and `drawable{size,colors,ops}` replace `sell_price` and `canvas`. Which
  affordances take which parameters is fixed in code (`Affordance.PARAMS`), since ops read them.
- The item's display `kind` is now `group`.

**Verbs take arguments, and there is one row type.**
- `activities:` and `uses:` merged into `verbs:`. A verb declares argument slots: `what` and `on`,
  a closed list matching `Intent.Do`'s fields, each filled by an owned item answering to an id or
  affordance.
- `at` can name a slot or `here`, and ops can name a slot (`enjoy: what`).
- `Intent.Do(activity, what, on)`: a slot left out is filled with the first owned match.
- The observation lists a verb once per way to fill its arguments, and Jev's choices do the same
  (`do:read:novel`). Habits and novelty key on the verb and its bound items.
- `read` and `listen` now enjoy what they read or play, so books' qualities pay.

**`Intent.Use` and `Intent.Think` stay, for key-and-door only.**
- Key-and-door is the quick test every backend must pass, and its action space uses them.
- The bedroom refuses both with a pointer to `do(...)`, and its reply schema leaves them out.
- Old bedroom logs replay through `Scenario.upgrade`: `use(item, on)` becomes the verb those items
  fill, and `think` becomes `do(think)`.

**Authored ops share one lifecycle.**
- Content says that a model writes something, where it goes and its parameters. Code says how:
  a closed set of handlers (`journal`, `thought`, `draw`), each with the destinations it can write
  to.
- The destinations are a closed list: `events`, `short_term`, `journal` and `pieces`.
- `agent/.../authored/Authors.kt` runs every handler the same way:
  - **Timing.** `before` runs after the decision and before the intent is submitted (he stands
    still). `during` runs in the background from when the verb starts.
  - **Keep.** A result is kept only if the verb succeeds.
  - **Hold.** The app holds the clock while a `during` call outlasts its verb. This generalizes
    5f60e58, which did it for art only.
- The sim records an authored op of a successful verb with an id (`AuthoredView`, on the
  observation but not shown to models). The agent hands the result back with `deliver(id, result)`,
  which replaces `annotate` for drawings. `annotate` stays only for commission wording.
- A verb whose essential op (`write`) has no handler isn't offered to models, and its neglect rule
  is dropped. The sim still runs it, so the scripted baseline journals with no writer. `draw` isn't
  essential: without an artist, sketching is still offered and the piece is made, just never drawn.
- The checks on `JOURNAL_ACTIVITY` and the `canvas` trigger are gone. The journal is "the verb that
  writes into `journal`".

**Think is a verb** (Ian's decision).
- `think` is an expressive verb with `at: [here]`, 10 minutes, `routine`, `no_repeat`,
  `need: { energy: -2 }`, and `write: { with: thought, into: [events, short_term], timing: before,
  shows_as: plan }`.
- `no_repeat` is a general lock, "you just did; do something else first". It replaces the
  `SchemaHints.omit` special case.
- The forced cadence is a decision-loop policy in `rules.yaml` (`reflection: { verb: think,
  every: 4 }`), not a property of the verb. Think stays routine, so it doesn't habituate.

**The content is six files with generated tooling.**
- The files are `content/bedroom/{room,rules,vocabulary,personality,items,verbs}.yaml`. Each may
  hold only its own top-level keys (`BedroomContent.FILES`).
- `make content-docs` writes one JSON Schema per file, generated from the serial descriptors
  through `JsonSchemaGenerator`'s editor mode. Each file names its schema for yaml-language-server,
  and a test fails if the schemas are stale.
- The same command writes `build/grammar-reference.html` from the loaded content and the kind and
  op definitions. The hand-written reference is retired.
- Comments say what a field means. Tuning history moved here:
  - **Starting money:** 40, then 20.
  - **Meals:** $18 a pack, then $24.
  - **Easel:** $120, then $17.
  - **Sketch:** sold for $12, then $3.
  - **Decor comfort:** was a fixed `comfort` (4, 3, 6). Qualities reproduce it.
  - **Selling art:** took 60 minutes, then 1.
  - **Journal:** gave mood +3, then +12.
  - **Read and listen:** gave mood +6 and +10. Most of it moved to stimulation.
  - **Sleep:** stops when fullness falls below 10.
  - **Music:** gave mood +4 an hour. It's now mood +2 and stimulation +6.
  - **The hatch:** H was a door, which kept suggesting he could leave.
- Rejected: a Kotlin DSL or a database. Invented content has to be data a model can write, in the
  same format as authored content.
