---
title: "Going somewhere is implied by the verb, plus `go` to be somewhere; he perceives his place, the other places and the walk to each"
date: 2026-11-15
status: active
---

**Context.** With a house on a lot (`2026-11-15-the-world-is-one-map-with-places-as-regions.md`) he needs to
know where he is, what's near, what he can do here and what by going elsewhere, and to have a map to plan with.
Text models decide by calling his verbs as tools (`2026-11-15-a-text-model-decides-by-calling-his-verbs-as-tools.md`);
Jev's menu tree stays until that has proved itself.

**Decision.**

- **A verb walks him to where it's done,** as it did in the bedroom. Going first and then choosing would double
  the decisions (and tokens) for every trip, and a walk on its own shows a picker no payoff.
- **`go` is there to be somewhere:** `move_to` takes a new target, `Target.Place(id)` (wire name `place`); he walks
  to the place's spot (its walkable tile nearest its middle) and the outcome says "went to the garden (11 min walk)".
  For tool models it's a `go` tool whose description lists the places with the walk to each; for Jev, an option
  per place in the menu node marked `go: true` (`outdoors`).
- **Perception, built by the sim:** `Observation.place` ("your bedroom, by the desk": the place and the nearest
  thing in it); `places` (every other place, nearest first, with what it is and the walk there, cached per tile);
  and on each activity `where` and `walk` when it's done in another place than his. Jev's state gets `where`; a
  verb tool's description starts "in your bedroom, 22 min walk from here" when it's away.
- **The map is in the system prompt** (`agent/.../WorldMap.kt`): rooms first, then the land nearest the front door
  first, each with what it is, the walk from the front door and the verbs done there (from where their things
  stand). It changes only with what he knows and the house, so the prompt's prefix stays cacheable.
- **Walks are game minutes** at his walking speed and the clock's scale, by A* round walls, water and trees.

**Consequences.** The system prompt grows about 1.2k characters and the tools about 0.8k at home. A live game
day on gemini-3.5-flash with tools had 3.8k to 4.0k prompt tokens a decision, within the range measured before
places. Jev's state and options grow about 0.6k characters a request. With nothing to do outdoors yet, he
didn't go out in that day.
