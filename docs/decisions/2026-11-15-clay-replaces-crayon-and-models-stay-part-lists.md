---
title: "Clay replaces crayon; models stay part lists, with clay fields; the web app is built first and the TV viewer rebuilt later"
date: 2026-11-15
status: active
supersedes: 2026-10-31-models-are-crayon-part-lists.md
---

Supersedes `2026-10-31-models-are-crayon-part-lists.md` and the one-world-light half of
`2026-10-31-the-part-list-format-and-one-world-light.md`. Amends
`2026-11-11-the-web-renderer-is-the-only-renderer-and-the-tv-runs-it-in-a-webview.md` and the
"textures are painted in the page" part of
`2026-11-08-the-web-renderer-is-vite-and-three-the-part-lists-are-exported-and-textures-are-painted-in-the-page.md`.

**Context.** Ian picked the Field Lab sketch's look (`world_sketches/fable_field_lab.html`, branch
`worktree-world-aesthetics`) for the game: smooth clay solids in flat pastels, soft shadows, lamps at
night, an isometric cutaway. Steps 1 and 2 of `research_notes/2026-11-15_0140_field-lab-look-research-plan.md`
put today's bedroom life in that look with no sim or protocol change. What was built and measured is in
`research_notes/2026-11-15_0230_clay-room-built.md`.

**Decision.**

- **Clay replaces crayon for the room and the guy.** No outlines, hatching, lumps or line boil. Floor,
  walls and rug are clean flat textures painted once with the browser's 2D canvas (`web/src/clay/textures.ts`),
  not crayon strokes (Ian, 2026-11-15). His paintings keep their own pixels. `web/src/crayon/` is gone.
- **Light is Three's, as the sketch's.** `MeshStandardMaterial` everywhere (one shared material for plain
  parts, colour in the vertices); a sun with a PCF soft shadow map; a hemisphere light; the sketch's day
  keys (`web/src/clay/day.ts`), wrapped round 24 hours, driven by the game clock. Glowing parts brighten at
  night, and the brightest four lamps in the room light it through a fixed pool of point lights (so shaders
  never recompile), plus one that follows his lantern. The shadow map is drawn again only when the sun has
  moved 0.25 m or something that casts a shadow moved; the guy and what he holds cast none (a soft blob
  instead). Output is sRGB with colour management on.
- **Models stay part lists in `:parts`.** One source, the export and the stale check stay. `Part` gains
  clay fields with defaults: `cut` (a sphere's top part, for domes), `hole` and `arc` (a cylinder as a ring,
  torus or arc, turned on a lathe), `glow`, `opacity`. They are exported only when not their defaults, so
  the crayon lists' export is byte-identical, and `:app`, which never reads them, draws what it did.
  `Part.Shape` is unchanged, because `:app`'s exhaustive `when` over it would break. The clay models are
  `ClayParts`, exported as `clay_items`, `clay_props` and `clay_constants`; the web draws an item's clay
  list where there is one and `RoomParts`' crayon list otherwise (drawn in clay, in the crayon palette).
  `PartList` gains `ring` and `dome`. The guy is part lists too: body (the face is its texture, painted per
  expression), arm, foot, sprout, lantern, rotor, animated in code as before.
- **The room is an isometric cutaway.** An orthographic camera at the sketch's angle (from the front right);
  the back and left walls stand full height and the right and front walls are a low sill; the room sits on a
  plinth of soil on the paper background. `room` frames the whole room in any window shape; `follow` is
  nearer and eases after him. The walls, floor and plinth are part data made in the page (`room/shell.ts`),
  as they were not part lists before either.
- **The bedroom is 12 x 6 inside** (Ian, 2026-11-15; `room.yaml`). Wall slots sit in the two walls that stand.
- **The web app is built first; the TV viewer is rebuilt later** (Ian, 2026-11-15). Amending 2026-11-11: the
  WebView rules (one canvas, no animated DOM over it, a render scale, remote keys) and the Streamer's
  performance don't constrain web work. `:app` stays frozen on the TV until the rebuild, which starts by
  measuring the finished page in a WebView (the plan's step 5). The page keeps `?scale=`, `?fps=` and the
  remote's keys, since they cost nothing.

**Not decided.** Whether clay furniture should outgrow its tile (the sketch's bed is 1.5 x 2.4 tiles); how
the TV draws this, if the page is too heavy for it.
