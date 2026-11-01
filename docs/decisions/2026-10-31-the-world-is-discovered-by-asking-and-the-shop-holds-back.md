---
title: "The world is discovered as he goes: the shop holds back what he can't use yet, and he can ask how things work"
date: 2026-10-31
status: active
---

# The world is discovered as he goes

The world keeps getting bigger, and every option can't go in front of him on every turn: it costs
context and makes decisions worse. In the TV run `app-bedroom-20261031-191910`, Jev bought a canvas
with no easel. So what he's shown grows as he goes, in two ways.

## The shop holds back what he can't use yet

- **What counts as unusable:** every verb the item is for happens at something he doesn't own (a
  canvas without an easel, a record without a record player).
- **Such an item is held back:**
  - It isn't in `shop`, so it's never a buy choice for Jev or a text model.
  - Buying it is refused: "the canvas is no use to you yet, so the shop won't sell it to you until
    you have an easel ($17)".
- **The thing a verb happens at is never held back**, so there's always something to buy first.
  - Its shop line says what it opens up: "then the shop sells you a jazz record ($20), …".
  - A locked verb's reason names that thing first (play_record "needs a record player").
- **Screens:** the catalog still lists everything, marked "not sold to you until you have …".

## He can ask how his world works (`ask`)

- **The verb:** `ask` is a verb like `think`.
  - It's expressive, `at: here`, 10 minutes, and can't be done twice in a row.
  - Its op is `write: { with: answer, into: [events, short_term], timing: before, shows_as: answer }`.
- **The deciding model only chooses to ask.** Then:
  1. **The question** is written by the writer model (Gemini), in his voice, from the decision prompt
     he was just shown (`Questioner`, the way `Thinker` writes a thought).
  2. **The oracle searches the docs** (`Oracle`, also the writer model). It reads their table of
     contents and opens the sections it needs. If that call fails, keyword matches on the question
     stand in.
  3. **The oracle answers** from the sections it opened and his situation, to him as "you", in
     under 60 words.
- **The docs are generated from the world's content as he knows it** (`WorldDocs`, from
  `BedroomWorld.knownContent()`): one section per rule, verb, item and book.
  - Generated, so they never drift from the content.
  - Nothing a book hasn't taught him yet. A hand-written world guide could be added as more sections
    later.
- **Where the answer goes:**
  - into the event stream, as an `answer` event ("I asked "…": …"), so memories and recall pick it
    up;
  - into the next prompts, as his latest 2 answers ("What you found out lately by asking how your
    world works"; `state.found_out` for a picking model);
  - into the run log, as `"kind": "answer"` lines with the question, the sections opened and the
    answer.
- **With no questioner and oracle configured** (no writer endpoint), `ask` isn't offered, like
  `think`. Its tokens are metered as `ask`.
