---
title: "Temporary 2D dev view before the 3D renderer"
date: 2026-10-28
status: active
---

# Temporary 2D dev view before the 3D renderer

Until milestone 2's 3D world exists, `:app` draws a top-down 2D view (`DevView`) with panels
for the intent and its status, the last decision (rationale, raw output, latency), events,
the action space, tick and FPS. It is the observability surface on the TV. libGDX
`ShapeRenderer` + `SpriteBatch` + the built-in 15 px `BitmapFont` scaled up. That font is soft
but readable at 1080p; switch to a TTF via gdx-freetype if the dev view outlives milestone 2.
Remote: OK pauses the sim (and so the agent), Back toggles detail and is caught so it never
exits, D-pad up/down scrolls events, right/left step 1 tick forward / back while paused. Home leaves.
