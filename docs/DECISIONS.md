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

## 2026-10-29: Revised milestones: 3D deferred, milestone 3 is "connect a decision model"

Supersedes the spec's milestone list (section 10). The 3D renderer (old milestone 2) is
deferred; the 2D dev view covers desktop and the TV until then. Milestone 3 is now "connect a
decision model": the spec's decision layer plus a backend for the decision-model provider Ian
is choosing, proven on the key-and-door room (at least 8 of 10 lockstep CLI solves, mock-server
fallback test, watchable on the TV). The spec's "LLM drives it on the TV" folds into it and
"local swap" moves to the roadmap. Then 4 the bedroom (clock, money, needs, a painting job, a
shop), 5 memory v0 and saves, 6 personality v0. Plan:
`research_notes/2026-10-29_0313_bedroom-proposal-and-roadmap-v2.html`.

## 2026-10-29: The reply is an envelope, `{thought, intent}`

Models reply with `AgentReply(thought, intent)`, not a bare intent, so their reasoning has a
field (it becomes `Decision.rationale`). `thought` comes first so a model reasons before it
commits, and is a non-null string defaulting to "" so the schema requires it while old replies
still decode. Milestone 5 adds `remember` and `plan` as fields with defaults.

## 2026-10-29: The schema is generated from serial descriptors, per request, in the strict subset

`JsonSchemaGenerator` walks the kotlinx.serialization descriptor. The output fits OpenAI-style
strict structured outputs, which most other providers also accept: no `$ref`/`oneOf`, every
object `additionalProperties: false` with all properties required, nullable fields as
`["string", "null"]`. Sealed types become `anyOf` with a one-value `type` enum, which is how the
existing class discriminator already looks on the wire, so the `Intent` type didn't change.
Extras the type can't carry (descriptions, id enums, ranges) come from `SchemaHints`, keyed by
serial name.

The schema is rebuilt every decision from the observation: ids are enums of what's in `nearby`,
and `use.itemId` is the inventory. A variant with no valid id is dropped (no `use` with empty
hands; no entity target with nothing in view). Order of variants follows kotlinx (class name).

## 2026-10-29: Every reply is validated locally, whatever the strategy

`ActionSchema.decode` checks JSON syntax, then the schema (via `SchemaValidator`, the same
subset), then decodes. Its errors are written to be sent back to the model ("intent.itemId:
"gold" is not one of "key", "apple""). json_schema, tools and text replies all go through it,
because servers that claim schema support don't always enforce it. A rejected reply is retried
with the error at most twice (`max_retries`), then `DecisionFailed` makes the agent loop submit
its fallback, `wait 20`. Retries are inside one decision, so lockstep runs stay deterministic.

## 2026-10-29: OpenAiCompatBackend: Ktor + OkHttp, three strategies from config

The strategy comes from the endpoint's `structured_output` (json_schema, tools, text) via
`Capabilities`. Tools are one function per intent variant with `thought` folded into each
tool's parameters; a call is rebuilt into the envelope. 429 and 5xx are retried with backoff (at
most 8 s); other HTTP errors end the decision. The OkHttp engine works on the JVM and Android.
Ktor stays an implementation detail of `:decision` (the constructor that takes an `HttpClient` is
internal), so nothing else compiles against it. YAML is read with kaml 0.61.0, the last release
built on Kotlin 2.0 / serialization 1.7, which keeps the toolchain where it is.

## 2026-10-29: Small observation and prompt changes for models

`nearby` entries now carry their tile `x`/`y`, so `move_to` a tile doesn't require arithmetic.
Short-term memory records how each intent ended, and the prompt lists recent actions with their
results. The system prompt describes the world rules, the action space (from `ActionExamples`,
nulls shown explicitly), the reply format and every observation field. The wait gloss now says
"20 ticks = 1 s of game time", since in lockstep runs a second of game time takes no wall time.
No gameplay changed.

## 2026-10-29: Decision logs are JSONL, one line per decision, and replayable

`DecisionLog` writes, per decision: run, model, tick, observation, request (system prompt,
messages, temperature, the per-request schema), every attempt with its error, raw reply, thought,
submitted intent, latency, tokens, error and whether it fell back. Keys are snake_case for
pandas. `ReplayDecisionModel` feeds the logged replies back through the schema, and lockstep
determinism makes the replay retrace the run tick for tick; a tick mismatch is an error. The CLI
writes `runs/<time>_<endpoint>/run-NN.jsonl` plus `summary.json`; the app writes one file per
episode.

## 2026-10-29: CLI runs are capped at 60 decisions

A model that keeps choosing failing intents costs a tick each, so a 2000-tick cap alone could
mean 2000 calls. `--max-decisions` (default 60) bounds cost; a run that hits it counts as unsolved.
While a network model thinks, `runLockstep` waits with a 2 ms delay instead of spinning; it still
ticks only between decisions.

## 2026-10-29: Keys on the TV come from a pushed secrets.env (dev only)

The TV has no shell environment, so `make tv-push-config` copies `config/endpoints.yaml` and a
`secrets.env` holding the variables its `api_key_env` entries name (taken from the developer's
shell, never from a file in the repo) to the app's external files dir. The app reads keys only
from there. Anyone with adb access can read that file, so it's for development only, with
low-limit keys; `make tv-clear-config` removes it. The endpoint is picked with
`make tv-run ENDPOINT=<name>` or the config's first `agents` entry.

## 2026-10-29: The Android app allows cleartext HTTP

Model servers on the LAN (vLLM, llama.cpp, a mock on the Mac) speak plain HTTP, and Android
blocks that by default: the first TV run fell back with "CLEARTEXT communication not permitted".
`usesCleartextTraffic="true"` in the manifest. Hosted endpoints are HTTPS regardless.

## 2026-10-29: The decision-model provider backend waits for its docs

The provider Ian is choosing gets its own backend once its docs and examples arrive; nothing is
guessed. It plugs in as another `DecisionModel` and another `kind` in `Endpoints.create`. If it
picks from a closed set, the per-request schema already enumerates the choices that matter (the
variants and their ids), and a candidate list can be derived from it.
