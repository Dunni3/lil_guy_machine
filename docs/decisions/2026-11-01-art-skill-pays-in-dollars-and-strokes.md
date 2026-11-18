---
title: "Art skill pays in dollars and strokes, set per item"
date: 2026-11-01
status: active
---

# Art skill pays in dollars and strokes, set per item

Follows `2026-10-31-knowledge-and-skill-books-teach-skills-pay.md`. Its rule that a piece sells for
`skills.price_per_level` (15%) more per level barely showed: a $3 sketch at art 1.1 is $3.50,
which rounds back to $3, and the drawing never read his skill (every sketch was 6 strokes). Ian
wants him to make more money from art, so that once a stronger novelty penalty pushes him toward
new things, he has the money to do them.

- **The price rule is on the item, in dollars.** `sellable: { price, skill, price_per_level }`:
  a piece sells for `price + price_per_level × level`, rounded, at the level he had when he made
  it. The global `skills.price_per_level` fraction is gone. A sketch is $3 + $2 a level and a
  painting $30 + $10 a level. The drawing book (+2) takes a sketch from $3 to $7 and a painting to
  $50.
- **Skill adds strokes.** `drawable: { ..., skill, strokes_per_level, max_strokes }`: a piece is
  drawn in `strokes_per_level` more strokes per whole level, up to `max_strokes`. A sketch is 6 + 2
  a level up to 16, and a painting 12 + 3 a level up to 30. Strokes cost model calls (two each), so
  the cap bounds what one piece costs. Size and colours don't grow: the sketch crayon boxes are
  four crayons, and the painting box already has all sixteen.
- **The canvas is set as he starts the piece**, from the observation's skill (`ArtStudio`). The
  price uses the exact skill when the piece is made. Both come before that verb's own practice.
- **No skill cap.** Prices keep climbing with practice (a sketch at art 10 is $23). If that loosens
  the economy too much, lower `price_per_level` rather than add a cap.
- **The economy test records the change; nothing was retuned.** In the scripted economy, sketching
  for food fell from 4 to 5 hours a day to about 2.6, because each sketch earns more as he practises.
