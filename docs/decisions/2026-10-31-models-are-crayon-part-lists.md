---
title: "Models are crayon part lists; big surfaces get painted textures, not the hatching shader"
date: 2026-10-31
status: active
---

# Models are crayon part lists; big surfaces get painted textures, not the hatching shader

From `research_notes/2026-10-31_1519_crayon-room-and-part-lists-plan.md`, approved by Ian after
the guy became a hand-drawn butter tater (`Blob.kt`, `Crayon.kt`, `CrayonCanvas.kt`).

- **The hand-drawn look is the game's look.** The guy has it now. The room and its items move to it next.
- **A model is a part list:** simple shapes (sphere, box, cylinder, capsule, panel), each with a
  size, transform, optional lumps, a colour or painted texture, and an optional ink outline. One
  crayon renderer draws any part list. The asset conventions of the 3D-room decision still hold.
- **Floor, walls and other large surfaces don't run the per-pixel hatching shader.** Their crayon
  look is painted once into textures (`CrayonCanvas`) and drawn with a cheap shader, because the
  Streamer's fill rate can't afford a full-screen noise shader. Any per-frame effect on them must
  be about one texture sample's cost, and be measured on the TV.
- **Turning a description into a model** means writing a part list (a model writes JSON, it's
  validated, and the renderer draws it), the same way art is drawn as validated ops. Not generated meshes.
