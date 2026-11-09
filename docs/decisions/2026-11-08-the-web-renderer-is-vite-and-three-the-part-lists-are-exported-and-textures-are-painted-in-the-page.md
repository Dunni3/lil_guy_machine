---
title: "The web renderer is Vite and Three.js; part lists are exported from Kotlin, and textures are painted in the page"
date: 2026-11-08
status: active
---

**Context.** Phase 4 of the sim-host plan
(`docs/decisions/2026-11-06-one-sim-host-state-streamed-browser-renders-admin-only-v0.md`): the
browser draws the room live from the state stream, and the libGDX app retires once this matches it.
What was built and how it compares is in `research_notes/2026-11-08_1750_web-renderer-room-built.md`.

**Decision.**

- **`web/` is TypeScript, Three.js 0.170.0 and Vite 6, every version exact.** Three.js is a scene graph,
  matrices and the WebGL state; all the look is our own `ShaderMaterial`s, ported from `Crayon.kt`.
  Protocol types only ever come from `protocol/ts/protocol.ts`.
- **Node runs in the same container as the protocol types** (`NODE_IMAGE`), with `node_modules` in a Docker
  volume. Nothing needs Node on the Mac, and macOS and Linux builds of the packages don't meet.
  Docker missing or a failed build doesn't stop `make host`; `/room` then says it isn't built.
- **The host serves the page from its own jar.** `:host`'s resources take `web/dist` when it exists, and
  `/room/` serves it, so a deploy (`stage-host.sh` runs `make web-build`) ships one artifact and adds no
  service. `/` stays the debug page. The page takes `?life=`, reads the same origin's stream, and sends nothing.
- **Part lists have one source, a new pure-Kotlin module `:parts`.** `Part`, `PartList` and every model moved out
  of `:app`, which keeps only what makes meshes from them. `make parts` writes `parts/export/parts.json`,
  checked in, and `make test` fails when it's stale (`:parts:checkParts`), as with the protocol schema.
  It's in its own module so the models outlast `:app`.
- **What varies is exported as pieces, not as code.** The window and clock at x = 0; the shelf's boards, a book per
  spine colour and a record per sleeve colour and place; props with `$color` for a colour chosen as he lives.
  The page places them and counts seeds up across the parts, as `PartList` does. Only that small layout, and `String.hashCode`, are written twice.
- **Textures are painted in the page, a port of `CrayonCanvas` and `RoomPaint`, not baked.** Same seeds and float
  compositing, so the pictures match the app's. Baking would make the look depend on `:app` right as it retires,
  and add binary files; the app's own painter proved cheap enough to run at startup. Not the browser's canvas:
  thousands of faint strokes round differently in 8 bits, and differently per browser, and a broadcaster must look the same as anyone's tab.
- **The floor, walls and the guy's geometry and animation are ported, not exported.** They aren't part lists
  (`2026-10-31-models-are-crayon-part-lists.md`), and they retire with the app.
- **The stream's furniture moved out of `fast`** into a new section, `things`, sent when any of it changes, so
  `fast.entities` holds only the characters. `PROTOCOL_VERSION` is 2.
- **The page draws 150 ms behind the newest position,** a bit over the 100 ms between publishes, and interpolates; the
  host sends no velocities. This answers the protocol decision's open question.
- **Any window size:** at 16:9 or wider the framing is the app's; narrower keeps the room's full width and shows more
  above and below, which is the app's letterbox without bars. Paper grain keeps its size relative to the frame.
- **A page older than the host reloads itself** 15 seconds after being refused a version, so an unattended broadcaster recovers from a deploy.

**Not decided.** Whether a dead connection needs a watchdog (a paused life sends nothing, so silence alone proves nothing).
