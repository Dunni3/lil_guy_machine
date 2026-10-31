---
title: Art is a run of closed picks on the deciding model
date: 2026-10-31
status: active
---

Replaces the "A drawing model draws each piece" bullet of
`2026-10-29-art-is-drawn-as-validated-ops-and-reaches-the-sim-through-annotate.md`; the rest of
that record (the hatch, `annotate`, commissions, hanging) stands.

- **The model that decides for him draws his art.** No separate text model writes a piece in one
  shot. A piece is a run of closed choices (`Painter`): the subject, a box of crayons, then for each
  of the canvas's strokes which stroke and then which crayon. TypeSafe's Jev can do all of it; the
  one-shot Gemini call per piece (1.5–2.4k prompt tokens, 12–17 s a sketch) often broke on the
  palette and could never run on the picking model.
- **Every call adds to the picture.** There is no `done` and no stroke-free step: a picking model
  takes a way out the moment it's offered (it said done at the first chance, and with done withheld
  it changed crayons back and forth until the call cap, 62 calls a painting). So a piece costs
  exactly 2 × strokes calls plus the subject and the box, and the stroke just made isn't offered
  again. The crayons are listed with the paper last, since a picker leans to early options.
- **The drawing spec is cut into a menu** (`Strokes`): bands at an edge, filled squares and discs
  in a three-by-three grid of named regions at three sizes, lines across, down and slanting, a
  sprinkle of dots, bucket fills and a frame. About a hundred options on any canvas, under a
  Choice question's 255, described in a few words each because the descriptions are most of what a
  call costs. The strokes become the same `DrawOp`s as before and `Artwork.draw` rasterizes them; a
  model still never writes code or coordinates.
- **Palettes are fixed and named** (`Palettes`): nine sketch boxes of four crayons (the paper
  first) and one painting box of sixteen. A picker can't write a hex color, so it picks a box and
  crayons by name; the hex-parsing leniency is gone with the one-shot writer.
- **Title and tags are the subject's words.** The subject is the customer's request or one of
  eight seeded adjective-noun pairs from the content's commission lists, so a piece drawn for a
  request matches it honestly and a piece of his own is tagged with what it is. He chooses with
  his situation (the decision prompt's user message) in the state.
- **`Menu` puts one pick to any model.** A picking model gets a `ChoiceSet`; a text model gets the
  state and options in a prompt and replies `{"pick": label}` under a schema that holds it to the
  labels. The painter is model-agnostic through it, and it's the piece recall can reuse.
- **`CanvasSpec.ops` is now `strokes`:** sketch 6, painting 12, each two model calls, so a
  painting is twice a sketch's work as it is twice the hours. The decision log's art line records
  `calls` instead of `dropped`, and `raw` is the run of picks.
- **Who draws:** the CLI draws when the endpoint isn't `scripted` and it isn't a replay; the app
  when `ModelSetup.paints` (a real endpoint). The scripted policy doesn't draw, so the scripted
  baseline's economy is unchanged. The journal's text model, when there is one, still words each
  day's request (`CommissionVoice`) and writes thoughts and entries; without it the request shows
  as "wants a tiny moon" and art is drawn all the same.
