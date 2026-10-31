---
title: "A commission pays for a piece that drew its noun's shape; the drawing reports what it shows"
date: 2026-10-31
status: active
---

# A commission pays for a piece that drew its noun's shape

Item 6 of `research_notes/2026-10-31_1711_fix-list-from-first-grammar-tv-run.md`. A yellow disc
titled "Glowing Sailboat" earned the request's $12, because the sim matched on the title and tags.

- **The noun is matched on what was drawn.** `AuthoredResult.Drawn` carries `shows`: the noun the
  piece drew a subject shape for, or null. `BedroomWorld` pays the reward only when `shows` is the
  request's noun. The adjective is still matched on the title or tags, since nothing draws
  adjectives yet.
- **The painter decides `shows`.** It's the subject's noun when a subject-stage stroke stamped one
  of `Shapes.showing(noun)`: the shapes that are the thing itself. `Shapes.forNoun` is those plus
  what goes with them, so a sailboat or lighthouse request's wave doesn't count, and a garden's
  flower or tree does. Shapes drawn as details (small copies, scenery) don't count.
- **The sim knows nothing of shapes.** It takes the noun the agent layer reports, as it takes the
  title. Any other way of drawing (a text model writing ops, a judge model) has to report `shows` the
  same way, or its pieces never meet a request.
- A piece that doesn't match says why: in its event ("there's no sailboat drawn in it") and in its
  `art` note.
- `shows` is in the art log line and the CLI's drawing line.
