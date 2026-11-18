---
title: "The web renderer is the only renderer; the TV runs it in a WebView, with video as the fallback"
date: 2026-11-11
status: active
---

Amends the TV part of `2026-11-06-one-sim-host-state-streamed-browser-renders-admin-only-v0.md`.

**Context.** Ian wants the renderer and the game's visuals built once, in code, for both the web and
the TV. While the libGDX app and the web renderer both exist, every visual change is made twice, and
the shaders and textures were ported by hand, so the two drift. The 2026-11-06 plan had the TV play
the broadcaster's video stream. That puts a GPU server on the path to watching on the TV, gives up
offline runs, and needs a native menu, so the UI would be built twice as well. Narrative:
`research_notes/2026-11-11_2200_sim-host-remaining-plan.md` (revised 22:06).

**Decision.**

- **`web/` is the only renderer.** Every visual and UI change goes there: room, guy, painting, HUD,
  menus.
- **The libGDX app (`:app`) is frozen.** No new visuals, features or ports go into it. It stays on the
  TV as it is until the WebView client replaces it, then it retires, along with the TV half of
  `:android`.
- **TV work waits for the web renderer.** Nothing new goes onto the TV until the web renderer has art,
  the HUD and the menus (steps 1 and 2 of the plan).
- **The TV runs the web renderer in a WebView.** A thin Android activity, a LEANBACK_LAUNCHER entry as
  today, holds a full-screen WebView on the host's `/room/`. The remote's keys reach the page as key
  events. So the web renderer must:
  - work from the arrow keys, Enter and Back alone, in the layout of `RemoteInput.kt`;
  - take a render scale (draw the canvas smaller and let CSS scale it up) and a frame cap;
  - stay one canvas, with no animated DOM over it, since each full-screen layer costs about 7 ms on the
    Streamer.
- **Measure before committing.** On a release-like build (`make tv-profile`), at render scales 1.0,
  0.67 and 0.5 and at 60 and 30 fps. The bar is the libGDX app's: 60 fps, or a steady 30 where that
  looks right.
- **Video is the fallback.** Only if the WebView can't hold that does the TV play the broadcast stream
  (Media3), with the remote's keys sent as commands. That brings back a native menu.
- **The broadcaster is for Twitch and YouTube.** The TV no longer waits on it.

**Not decided.** Whether the TV may send commands. If it may, the WebView signs in with the admin
password, as the web page does.
