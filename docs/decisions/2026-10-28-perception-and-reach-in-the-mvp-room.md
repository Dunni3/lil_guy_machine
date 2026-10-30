---
title: "Perception and reach in the MVP room"
date: 2026-10-28
status: active
---

# Perception and reach in the MVP room

Characters perceive entities within 12 tiles; walls don't block sight yet. Picking up and using
need the target on or beside your tile (Chebyshev distance 1). Movement is 8-connected A* with
no corner cutting at 4 tiles/s. The door is a locked entity on a floor tile that blocks until
the key is used on it; the exit is a tile outside the door, and reaching it solves the room.
