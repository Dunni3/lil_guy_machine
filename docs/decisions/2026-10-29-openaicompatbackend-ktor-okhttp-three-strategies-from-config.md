---
title: "OpenAiCompatBackend: Ktor + OkHttp, three strategies from config"
date: 2026-10-29
status: active
---

# OpenAiCompatBackend: Ktor + OkHttp, three strategies from config

The strategy comes from the endpoint's `structured_output` (json_schema, tools, text) via
`Capabilities`. Tools are one function per intent variant with `thought` folded into each
tool's parameters; a call is rebuilt into the envelope. 429 and 5xx are retried with backoff (at
most 8 s); other HTTP errors end the decision. The OkHttp engine works on the JVM and Android.
Ktor stays an implementation detail of `:decision` (the constructor that takes an `HttpClient` is
internal), so nothing else compiles against it. YAML is read with kaml 0.61.0, the last release
built on Kotlin 2.0 / serialization 1.7, which keeps the toolchain where it is.
