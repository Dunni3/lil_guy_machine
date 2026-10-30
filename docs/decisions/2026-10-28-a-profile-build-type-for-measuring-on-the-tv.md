---
title: "A `profile` build type for measuring on the TV"
date: 2026-10-28
status: active
---

# A `profile` build type for measuring on the TV

Release settings (not debuggable) signed with the debug key, installed by `make tv-profile`.
Debug builds overstate CPU time on the Streamer: 8.7 ms vs. 3.4 ms per frame for the same
dev view.
