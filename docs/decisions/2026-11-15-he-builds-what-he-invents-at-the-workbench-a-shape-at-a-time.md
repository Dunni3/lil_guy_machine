---
title: He builds what he invents at the workbench a shape at a time, with the furniture's part features
date: 2026-11-15
status: active
---

**Context.** In the first live days of inventing (run 01a1de0f, days 2 to 9), Flash-Lite wrote each
design as one JSON object, and its looks were 1 to 4 plain shapes: a terrarium was a green cylinder,
an ornament a brown sphere. A look could only be a box, sphere, cylinder or capsule, while the room's
furniture also uses taper, cut, hole, arc, glow and opacity. Five of ten designs were cups or teapots
filed as food, which he then ate.

**Decision.**

- A look's shapes (`LookPart`) carry the clay part features: `taper`, `cut`, `hole`, `arc`, `glow`,
  `opacity`, plus `part`, the name of what each shape is. The sim checks their ranges
  (`InventRules.partProblem`); the protocol's `LookShape` carries them; the web room draws them.
  Saved inventions without them load with the defaults.
- A writer that calls tools (`Capabilities.toolCalls`) invents with workbench tools
  (`agent/.../invent/Workbench.kt`), as looks of a `ToolSet`: `add_part`, `redo_part`,
  `remove_part`, `look_at_it`, then `build` with name, use, qualities and why. `build` is refused
  with the rules' reasons when it can't be built, and the first buildable one is sent back once to
  be looked over (its size, shapes that touch nothing, a floor thing under 0.4 tall). The steps are
  recorded in the authored row (`steps`).
- A writer without tools still writes one JSON design, redone once when refused, then looked over
  once; the improved design is kept only if it can still be built (`before_look_over`).
- The brief asks for the real thing's named parts, sizes against the room and him, and shows a
  lantern as a worked example. Food is "something to eat or drink, which is used up".

**Consequences.** On Gemini 3.5 Flash-Lite, with decor only, nine builds came out 3 to 7 shapes,
0.44 to 0.70 tall: a toadstool lamp, a fern in a pot, a terrarium with a lid. Each took 5 to 8 tool
calls and 6 to 9 seconds, at 60k to 90k prompt tokens (about a cent; the one-JSON way was about
12k). The writer gets twice the inventor's timeout at the workbench, during which the clock is held.
The subjects are still alike (moss, pots, lamps); showing him what he already made is the next
lever if that holds in real runs.
