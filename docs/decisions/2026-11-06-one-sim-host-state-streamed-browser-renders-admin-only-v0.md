---
title: "One sim host, state streamed to clients, the browser renders; v0 is admin-only"
date: 2026-11-06
status: active
---

**Context.** Ian wants sims shown on a web app and live-streaming services, not only on the TV.
The narrative and alternatives are in
`research_notes/2026-11-06_1422_sim-host-and-remote-renderers.md`.

**Decision.**

- **One authoritative sim host, in Kotlin.** A headless server runs each life (sim, agent, memory,
  model calls). Renderers are clients and never run the world. `:sim`, `:decision` and `:agent`
  stay Kotlin.
- **Clients get world state, not frames.** Snapshot on join, then deltas and events over
  WebSocket, with types generated from the Kotlin side. Presentation timing (the drawing hold,
  quiet-stretch speedup) moves to the host, since every viewer shares one clock.
- **The browser is the canonical renderer:** TypeScript + Three.js.
- **Video is a client.** A headless browser running the web renderer feeds ffmpeg for Twitch,
  YouTube, WebRTC or HLS. It runs on AWS.
- **The TV plays the video stream** for now. A WebView running the web renderer waits for a
  WebGL measurement on the Streamer. The libGDX app retires once the web renderer matches it.
- **v0 is admin-only.** Ian is the only one who can start, stop, restart or change a sim. Everyone
  else is read-only.
- **Admin login is one password** (changed from GitHub OAuth:
  `2026-11-08-v0-admin-login-is-one-password.md`). The command endpoint needs it; the state stream
  doesn't. Viewer input (a closed set of gifts and events, and
  moderated free text) comes later.
- **The run database comes first.** The sim host's store is that plan's local store.

**Not decided.** Whether the TV holds an admin token, whether links are public, the AWS layout.
