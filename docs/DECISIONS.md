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

## 2026-10-28: The Streamer needs armeabi-v7a; ship three ABIs

Supersedes "Android ships arm64-v8a and x86_64 natives only". The Streamer's userspace is
32-bit: `ro.product.cpu.abilist` is `armeabi-v7a,armeabi`, so an arm64-only APK failed with
`INSTALL_FAILED_NO_MATCHING_ABIS`. Natives are now armeabi-v7a, arm64-v8a and x86_64.

## 2026-10-28: Temporary 2D dev view before the 3D renderer

Until milestone 2's 3D world exists, `:app` draws a top-down 2D view (`DevView`) with panels
for the intent and its status, the last decision (rationale, raw output, latency), events,
the action space, tick and FPS. It is the observability surface on the TV. libGDX
`ShapeRenderer` + `SpriteBatch` + the built-in 15 px `BitmapFont` scaled up. That font is soft
but readable at 1080p; switch to a TTF via gdx-freetype if the dev view outlives milestone 2.
Remote: OK pauses the sim (and so the agent), Back toggles detail and is caught so it never
exits, D-pad up/down scrolls events, right/left step 1 tick / 1 s while paused. Home leaves.

## 2026-10-28: The render thread is the sim thread in the app

`SimSession` steps `RoomWorld` at a fixed 20 Hz from `render()` with an accumulator and
interpolates between the last two snapshots. A tick costs microseconds, so it doesn't threaten
the frame, and it keeps "one sim thread" trivially true. The agent runs on
`Dispatchers.Default`. The CLI and tests use `runLockstep`, which ticks only once the agent has
submitted, so headless runs are deterministic.

## 2026-10-28: Decision points fire only after the tick is published

A finished intent raises the decision point after `step()` publishes that tick's observation,
not when the intent finishes mid-tick. Raising it earlier let an agent on another thread wake
and read the previous tick's observation (the dev view showed a second `pick_up` of an
already-held key). Lockstep runs couldn't show this because they are single-threaded.

## 2026-10-28: Scripted policy reads the prompt, not the world

`KeyDoorPolicy` is a `DecisionModel` that parses the observation back out of the prompt
`PromptBuilder` built (`PromptBuilder.observationOf`) and replies with intent JSON decoded by
the same `ActionSchema` path a hosted backend will use. It can only know what an LLM would
know. It's used instead of `ScriptedDecisionModel`'s canned replies so it reacts to failures and
works for any start state. In the app it fakes 600 ms of latency to make the thinking visible.

## 2026-10-28: Perception and reach in the MVP room

Characters perceive entities within 12 tiles; walls don't block sight yet. Picking up and using
need the target on or beside your tile (Chebyshev distance 1). Movement is 8-connected A* with
no corner cutting at 4 tiles/s. The door is a locked entity on a floor tile that blocks until
the key is used on it; the exit is a tile outside the door, and reaching it solves the room.

## 2026-10-28: A `profile` build type for measuring on the TV

Release settings (not debuggable) signed with the debug key, installed by `make tv-profile`.
Debug builds overstate CPU time on the Streamer: 8.7 ms vs. 3.4 ms per frame for the same
dev view.

## 2026-10-28: On Ian's home screen through smarter-tv's dock, not HOME

The app gets onto the home screen as a tile in smarter-tv's dock: add `dev.lilguy.machine` to
"Dock packages" in the phone controller's launcher config. It already shows in the dock's
"All apps" grid because it's a LEANBACK_LAUNCHER activity. No smarter-tv code changes.
