---
title: "The Streamer needs armeabi-v7a; ship three ABIs"
date: 2026-10-28
status: active
supersedes: 2026-10-28-android-ships-arm64-v8a-and-x86-64-natives-only.md
---

# The Streamer needs armeabi-v7a; ship three ABIs

Supersedes "Android ships arm64-v8a and x86_64 natives only". The Streamer's userspace is
32-bit: `ro.product.cpu.abilist` is `armeabi-v7a,armeabi`, so an arm64-only APK failed with
`INSTALL_FAILED_NO_MATCHING_ABIS`. Natives are now armeabi-v7a, arm64-v8a and x86_64.
