---
title: "The 3D room is libGDX g3d, built in code, as the app's first page"
date: 2026-10-29
status: active
---

# The 3D room is libGDX g3d, built in code, as the app's first page

From the plan in `research_notes/2026-10-29_2053_3d-room-and-character-plan.md` (option C). Ian asked
to implement it; the spike is in `research_notes/2026-10-29_2121_3d-room-spike.md`.

- **Real-time 3D in libGDX `g3d`**, no other engine and no new dependency. A fixed front camera on a
  cutaway room (thin walls, no front wall, no ceiling), near-flat lighting (ambient plus one
  directional light, by game hour), no shadow maps or post-processing. The Android launcher's 4x
  MSAA stays on (it fit the budget). A blob shadow is the only blended 3D object.
- **Models are built in code for now** (`RoomModels.kt`), one per item id with a box fallback, each
  one vertex-coloured mesh part (one draw call). The asset conventions hold already, so a glTF can
  replace one later: 1 unit = 1 tile (1 m), pivot at the bottom centre of the tile, front faces +Z;
  wall items have their back at z = 0 and are placed against the wall face that looks into the room.
  `gdx-gltf` is not added yet; its compatibility with gdx 1.13.1 and armeabi-v7a is still unchecked.
- **Placement comes only from the snapshot** (per the item-positions decision): entity tile, and
  the terrain around it for wall items. Nothing reads `bedroom.yaml`.
- **The guy is procedural** (`Blob.kt`): face textures per expression painted into a Pixmap and
  wrapped on the body sphere (swap them for hand-painted ones later), animation in code. Expression
  and posture come from sim state (needs, activity, music, thinking), not the model's words.
- **It is a page, not a replacement**: `DevView.Page.Room`, first and the default, full screen; the
  remote's LEFT/RIGHT reach the 2D dev pages as before.
- **Measured on the Streamer** (`make tv-profile`, room page, run not started): 60 fps, 1920x1080
  backbuffer, about 3.3 ms CPU per frame, 17 draw calls, with the existing 4x MSAA left on.
