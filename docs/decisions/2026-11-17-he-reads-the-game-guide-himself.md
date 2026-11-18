---
title: He reads the game's docs himself (search_docs, read_docs), and the hand-written guide covers every mechanic
date: 2026-11-17
status: active
---

**Context.** How the world works reached a model that calls tools only through `ask`: a second
model opened up to 6 doc sections and answered in under 60 words. He never saw the docs himself, and
`ask` exists only with a writer endpoint. The docs were the content as JSON (`WorldDocs`) plus five
short hand-written sections, so most of what the content files' comments explain (familiarity,
comfort by sight, light and lamps, warmth, machines and wear, the market, inventing, code) wasn't in
them in words.

**Decision.**

- **Two looks, always offered to a tool model:** `search_docs` (a few words give the best 8
  sections with how each begins; no words give the list of every section) and `read_docs` (up to 4
  sections by id, in full; an unknown id gets the sections like it). They read `WorldDocs.of(world.knownContent())`
  directly: no model call, no tokens, no endpoint needed. Like other looks they take no game time
  and are logged with the decision.
- **The search is local keyword ranking** (`WorldDocs.search`): a word counts most in a section's id,
  then its title, then its text, with plurals and -ing folded and short words such as "eat" kept
  (memory's `Keywords` drops them, and drops "energy" and "mood"). The oracle's fallback uses it too.
- **The hand-written guide (`agent/src/main/resources/docs/bedroom-guide.md`) covers every mechanic in
  prose,** one `guide:` section each, to him as "you", with no numbers: each ends naming the
  generated `rules:`/`verb:`/`item:` sections that hold them, so the numbers can't drift.
- **A guide section can wait for what he knows:** a heading ending `[when: a, b]` is left out until
  every one of those verb or item ids is in his world, as the generated sections already do for what
  a book teaches. Painting and the guitar wait so.
- `WorldDocs.of` keeps the docs for the last content it was given (the known content changes only
  when he learns something).

The developer docs (`docs/decisions/`, the spec, code comments) aren't searchable by him: they're
written about him, carry history and code paths, and would tell him what he hasn't found. What in
them is a mechanic belongs in the guide.

**Consequences.** A new mechanic needs a guide section, or a line in one, as well as its content and
code. The tools' descriptions and the system prompt's paragraph on looks say what the docs hold.
`ask` stays for questions that want an answer fitted to his situation.
