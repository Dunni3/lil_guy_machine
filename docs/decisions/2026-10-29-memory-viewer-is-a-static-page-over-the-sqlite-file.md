---
title: "Memory viewer is a static page over the SQLite file"
date: 2026-10-29
status: active
---

# Memory viewer is a static page over the SQLite file

`tools/memory-viewer.html` reads a memory `.db` in the browser with sql.js (from a CDN), so there
is no server or build step. It shows memories beside the event stream and highlights the events a
memory covers. The note is `research_notes/2026-10-29_1306_memory-v0.md`.
