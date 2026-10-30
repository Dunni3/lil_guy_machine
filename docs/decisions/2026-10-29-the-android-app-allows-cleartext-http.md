---
title: "The Android app allows cleartext HTTP"
date: 2026-10-29
status: active
---

# The Android app allows cleartext HTTP

Model servers on the LAN (vLLM, llama.cpp, a mock on the Mac) speak plain HTTP, and Android
blocks that by default: the first TV run fell back with "CLEARTEXT communication not permitted".
`usesCleartextTraffic="true"` in the manifest. Hosted endpoints are HTTPS regardless.
