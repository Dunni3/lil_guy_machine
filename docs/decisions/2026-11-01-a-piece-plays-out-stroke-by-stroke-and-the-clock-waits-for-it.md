---
title: "A piece plays out stroke by stroke as the model draws it, and the clock waits for it to finish on screen"
date: 2026-11-01
status: active
---

# A piece plays out stroke by stroke as the model draws it, and the clock waits for it to finish on screen

Since art skill adds strokes, a painting's drawing calls (about 2 minutes on Jev) outlast its verb
(45 s at the default speed), and the clock holds for the rest. Ian decided to keep the hold
(`research_notes/2026-11-01_1550_painting-holds-the-clock-animate-it.md`) and make it watchable. Before
this, during the hold the room showed him idle beside a blank canvas: the drawing left `Authors`'
pending list when the verb ended, and the easel only ever revealed a finished drawing by the verb's progress.

- **The Painter reports as it goes.** `Painter.draw` takes `onProgress`, called once the crayons are
  picked and after every stroke with an `Underway` (picture so far, strokes of total, the last
  crayon). `ArtStudio.underway` keeps the latest, marked `Done` or `Dropped` when the drawing ends,
  and kept until the next piece starts, so a viewer that polls sees how it ended.
  `AgentMemory.underway()` replaces `drawingInProgress()`; `Authors.inProgress` is gone.
- **The room plays the real strokes, at the model's pace** (`app/.../PaintScene.kt`), not the verb's
  progress: each stroke's cells go on along a brush path in its crayon with the brush tip on them,
  a dip at the palette before a new colour, stepping back to consider between strokes (head tilt,
  brush to chin, brush held up to size it), and a flourish when it's done (hops, sparkles in its
  colours, its title over his head). It's the same with the clock running or held. The desk's
  sketches use the same scene with a crayon.
- **He stays at his piece through the hold**, posed at it although the verb has ended.
- **The view may hold the clock** (`SimSession.linger`), asked every frame while the piece's verb has
  ended and its drawing is still out or still going on screen. It's counted in updates, not seconds,
  so a slow frame can't let a tick through (it did, at 0.35 s), and it lapses after two updates
  when the room stops asking (another page, the piece done). It adds the last strokes plus the
  1.8 s flourish to a hold, about 3 s at most.

Dev aids: `LILGUY_PAINTER=<seconds>` draws with `RandomPicker` (random picks at that latency) so he
paints with no endpoint; `LILGUY_SCREENSHOT_FRAMES`/`_EVERY` save a burst of frames.
