---
title: Art is drawn as validated ops, and reaches the sim through annotate
date: 2026-10-29
status: active
---

From `research_notes/2026-10-29_2148_art-making-plan.md`.

- **The door is a mail hatch.** Item `mail_hatch` (slot `hatch`, letter `H`) in the same wall
  tile, where the shop delivers and `sell_art` sells. The bedroom has no door, so nothing hints at
  leaving. Key-and-door is untouched.
- **A drawing model draws each piece.** When he starts an activity whose product has a `canvas`
  (sketch: 16x16, 4 colors, 40 ops; painting: 32x32, 16 colors, 120 ops), `ArtWriter` asks the
  journal's model (the same one that thinks) for `{title, palette, tags, ops}`. Ops are `rect`,
  `circle`, `line`, `fill` (flood) and `pixel`. The schema bounds coordinates and color indices,
  and the reply is rasterized by `Artwork`, never run. Ops past the limit are dropped. A color is
  read from its leading six hex digits, because Gemini Flash often trails junk after a good one. A
  drawing is kept only if the activity succeeds, the same way as a journal entry. Its PNG goes in
  the run's `art-NN/` and its metadata goes in the event stream and the decision log (`"kind": "art"`).
  A failed drawing leaves the piece untitled. It still sells, at the base price.
- **Art is still a count, and each carried item is also a numbered piece.** `BedroomWorld` keeps
  the counts as before and mirrors each carried item as a `Piece` (held, hung or sold), shown in
  the observation as `art` and in `LifeSnapshot.art`.
- **Drawings reach the sim through `BedroomWorld.annotate(ArtNote)`.** This is a second inbox beside `submit`,
  thread-safe and applied at the start of the next tick. It carries a piece's title and tags, and a
  commission's wording. This is the one exception to "the agent only observes and submits": the sim
  needs the drawing's tags to price a commission, and it never waits for them.
- **Commissions are daily and matched by tags.** At `commissions.arrive` (09:00) a seeded pick of
  adjective + noun replaces the day before's request, and one model call words it in a customer's
  voice. The first piece finished while the request is open whose title or tags name both words
  earns `reward` ($12) when sold. No judge yet.
- **Hanging trades money for mood.** `hang_art` puts his newest held piece on the `picture_rail`
  (back wall), for +3 comfort each, up to 3 pieces. The 3D room shows hung pieces in frames, and his newest
  unsold sketch on the desk (a painting on the easel). The dev view has an ART page.
- Without a journal model nothing is drawn: pieces never match, and the scripted baseline's
  economy is unchanged.
