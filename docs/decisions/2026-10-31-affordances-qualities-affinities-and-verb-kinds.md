---
title: "Affordances, qualities and affinities; every verb has a kind with a contract"
date: 2026-10-31
status: active
---

# Affordances, qualities and affinities; every verb has a kind with a contract

Step 2 ("grammar foundation") of `research_notes/2026-10-30_1951_world-grammar-consolidated-plan.md`.
It is meant to change little on its own; steps 3 to 6 (library and skill, craft, letters and
perception, invent) build on it.

- **`tags` split into `affords` and `qualities`**, each from a closed list under `vocabulary:` in
  `bedroom.yaml`. `BedroomContent.validate()` rejects any other word, and an affordance can't share
  a name with an item.
  - **Affordances** (8): readable, playable, edible, sellable, hangable, viewable, combinable,
    wearable. Activities ask for an item by id or by affordance (`read` requires `readable`, `eat`
    consumes `edible`, `sell_art` sells `sellable`, `play_record` takes a `playable`). A new item
    with an affordance works with every verb that asks for it. Combinable and wearable have no
    users yet; viewable is on decor and art, for "look at" verbs later.
  - **Qualities** (10): cozy, calm, warm, soft, bright, pretty, natural, lively, strange, loud.
    Each has a strength above 0 and at most 3.
  - Text that used to print a tag now prints a noun: the item's name, or for an affordance the
    `kind` its items share ("needs a book", "you have no art to sell").
- **An item's value is computed from his affinities**: the sum of strength × affinity over its
  qualities. It's paid two ways (`quality_value:`):
  - *in view*: a slot item adds value × `in_view` (1) comfort. This replaces `comfort:` on items.
    Items without a slot (records, books) are out of sight and add nothing. Hung art keeps the
    gallery's fixed `comfort_each`, since what a piece is like is whatever he drew.
  - *in use*: a **consumptive** verb with a known item adds value × `in_use` (1) mood when it
    finishes, at the habit's freshness. Today that is only `play_record`, since `read` and
    `listen` don't know which book or record; they get it with the library in step 3.
- **Personality** (`personality:` in the content, swappable per run with
  `BedroomContent.withPersonality` and the CLI's `--affinities calm=2,loud=-1` and `--curiosity`):
  - **affinities** from -1 to 2 per quality, 0 when left out. The defaults (1 for most, 0.5 for
    lively and strange, -0.5 for loud) and the decor's qualities are chosen so comfort comes out
    where it was: plant 4, poster 3, rug 6. The scripted 6-day baseline is identical to step 1's.
  - **curiosity** (default 1) multiplies the stimulation drain and the novelty bonus. It was left
    out of step 1 to land here.
  - The app runs the default; only the CLI varies it so far.
- **Every activity and use has a `kind`** (`VerbKind.kt`), and its row must keep that kind's
  contract: sets of fields it must set (at least one of each) and fields it can't. Checked in
  `validate()` like ids, so an invented verb can later be held to the kind it claims.
  - consumptive: an affordance, and effects, rates or background; no produces, sells, hangs or asleep.
    `eat`, `read`, `listen`, `play_record`.
  - generative: produces. `sketch`, `paint`.
  - transformative: hangs (the only state change the engine has). `hang_art`.
  - expressive: writes nothing into the world (no produces, consumes, sells, hangs). `journal`;
    `think` isn't a row.
  - economic: sells, and no need changes. `sell_art`; buying is the built-in `buy`.
  - rest: asleep. `sleep`.
  - perceptual and study are defined but refused: the engine has nothing for them to do until
    steps 5 and 3.
- **`routine`** stays a flag (eat and hang_art set it), but economic and rest kinds are always
  routine, so `sell_art` and `sleep` no longer need it. `ActivitySpec.isRoutine` is what the
  engine reads. Open whether eat should become its own upkeep kind.
- **What models see**: shop lines end with the item's qualities ("cozy, soft, warm") and show
  comfort from his affinities (negative when he dislikes it); a use he likes says "you like it:
  mood +2". The persona text gets one sentence of taste from his affinities, naming only qualities
  some item in his world has. Kinds aren't shown to models yet.
- **Not done:** habits still key on the activity (per-item only for uses); keying consumptive
  verbs on (verb, item) and per-book reading wait for the library. Whether affinities should also
  decide how much a quality feeds stimulation versus mood is still open.
