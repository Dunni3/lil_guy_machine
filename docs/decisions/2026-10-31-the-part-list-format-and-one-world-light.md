---
title: "The part-list format, and one world light for everything"
date: 2026-10-31
status: active
---

# The part-list format, and one world light for everything

Follows `2026-10-31-models-are-crayon-part-lists.md`. Built in `CrayonModel.kt` (`Part`, `PartList`),
`CrayonRenderer.kt` and `RoomModels.kt`; narrative in
`research_notes/2026-10-31_1545_crayon-room-built.md`.

- **A `Part` is flat data** that a model could write as JSON later: `shape` (sphere, box, cylinder,
  capsule, panel), full size `w`/`h`/`d`, bottom-centre `x`/`y`/`z`, rotations in degrees applied
  Y then X then Z about that point, `color` as sRGB hex, `texture` by painter name, `round` (edge
  radius, metres; negative picks a soft default), `lump` (metres along the normal) with `seed`,
  `outline` (ink thickness, metres; 0 for none), and `taper` for cylinders. Metres throughout, with
  the 3D-room asset conventions (pivot bottom centre, front +Z, wall items' backs at z = 0).
- **Each model is merged into one mesh per texture plus one outline hull per boil frame**, so an item
  costs two or three draw calls whatever its part count. Colour is a vertex attribute.
- **One light, in world space, for the guy and every item:** the workshop's camera-fixed light
  turned into world space with the room's fixed camera. The guy looks exactly as before. If the
  camera ever moves, the light stays put in the world rather than following it.
- **Floor, walls and the wall section are not parts.** They are paper-shaded meshes (texture ×
  vertex colour × tint), with each side's two-tone shade and the floor's corner shade baked into
  vertex colours once, from the same light.
