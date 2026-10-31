---
title: Art strokes come in stages, with shapes for the subject
date: 2026-10-31
status: active
---

Replaces the "drawing spec is cut into a menu" bullet of
`2026-10-31-art-is-a-run-of-closed-picks-on-the-deciding-model.md`, and its rule that only the
stroke just made isn't offered again. The rest of that record stands: Jev draws with closed picks,
every call adds to the picture, and the title and tags are the subject's words.

**Why:** in the first TV run (`runs/tv/app-bedroom-20261031-164216`), Jev couldn't draw a thing.
The old menu could make only bands, squares, circles, lines through the whole canvas, dots and
bucket fills. A request for a glowing sailboat came out as a yellow disc, because four bucket fills
flooded what its lines had divided. Two paintings were both titled "Wild Mountain".

- **Shapes** (`Shapes.kt`):
  - 23 one-crayon, 12×12 stencils (mountain, sailboat, owl, castle and more), drawn scaled as
    `DrawOp.Stamp`.
  - `#` is drawn and `.` is left as it was, so eyes and windows show what's underneath.
  - A subject's noun names its shapes (`Shapes.forNoun`); every commission noun has at least one.
    The scenery set (sun, moon, star, cloud, bird, tree, flower, wave) goes around the subject.
- **Stages** (`Strokes.Stage`), each with its own menu, and `state.now` tells the model which one
  it's in:
  - **Background:** a sixth of the strokes, at least one. Bands and a wash.
  - **Subject:** the same share. The subject's shapes, at any region and size.
  - **Detail:** the rest. Small shapes only: up to 3 more copies of the subject (`MAX_COPIES`), the
    scenery, small discs and dots, and the frame.
  - A noun without shapes skips the subject stage.
- **The subject stays in front.** Strokes from the subject stage are drawn after everything else,
  so details added later never cover the subject.
- **Removed from the menus:**
  - Bucket fills: on a washed canvas, one floods everything.
  - Lines right across the canvas: they cut through the picture.
  
  `DrawOp.Fill` and `DrawOp.Line` stay in the drawing spec.
- **Only strokes that would show are offered.**
  - A crayon is offered for a stroke only if at least half the cells the stroke covers would change.
    A green tree on green grass, or the paper on the paper, isn't offered.
  - A stroke that can't show in any crayon isn't offered at all.
  - A stroke already made isn't offered again for the rest of the piece.
- **Shuffled order.** Strokes, crayons (with the paper still last) and boxes are shuffled with a
  seed from the piece and the step. The subjects keep their order: the request first, then his own.
  In the run, 3 of 4 sketches used the box listed first.
- **No repeated subjects.** `ArtStudio` passes the titles he's already drawn, and his own subjects
  leave them out until every pair has been used.

**Tried on Jev** (four pieces per round, about 50k prompt tokens a painting and 18k a sketch, which
is less than before):
- The glowing sailboat came out as a sailboat on the sea under a sun and clouds.
- The sleepy owl and the quiet garden are recognizable.

Still weak:
- Shapes in the same crayon run together when they touch.
- Jev likes to fill the details with copies of the subject, hence the cap.
