# Decisions

Append-only ledger. Newest at the bottom. Research notes hold the narrative; this file holds
the outcome.

## 2026-10-28: Own repo, public, MIT

Answers the spec's open question "existing TV monorepo or its own repo?": its own public repo
(`dunni3/lil_guy_machine`), MIT licensed. It shares a TV with smarter-tv but no code; device
lessons were copied over as notes in `CLAUDE.md` and the ADB helpers in `tools/adb/`.

## 2026-10-28: Toolchain pinned to smarter-tv's known-good versions

Gradle 8.7, AGP 8.5.2, Kotlin 2.0.20, JDK 17, compileSdk 34 / minSdk 31: the same as
smarter-tv's `tv-app`, which already builds on this Mac and runs on the Streamer. libGDX 1.13.1.
Upgrade deliberately, not incidentally.

## 2026-10-28: Names

Gradle root `lil-guy-machine`; Kotlin packages `dev.lilguy.<module>`; Android application ID
`dev.lilguy.machine`. The spec's working title "Agent World" is kept for the spec document.

## 2026-10-28: The app is a LEANBACK_LAUNCHER entry, never HOME

It shows in the TV's app list and is launched like any app. smarter-tv owns HOME on the
Streamer, and changing HOME there caused an outage (see smarter-tv's notes, 2026-10-23).

## 2026-10-28: Android ships arm64-v8a and x86_64 natives only

The Streamer and Apple Silicon emulators are arm64; x86_64 covers Intel emulators. Add
armeabi-v7a only if a 32-bit device turns up.
