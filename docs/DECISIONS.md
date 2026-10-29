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
exits, D-pad up/down scrolls events, right/left step 1 tick forward / back while paused. Home leaves.

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

## 2026-10-29: Stepping back scrubs a snapshot history

LEFT used to step forward a second, because the world can only move forward and the agent runs
against it live. `SimSession` now keeps a rolling two-minute history of snapshots. While paused,
LEFT moves the view one tick back through it and RIGHT replays forward, advancing the real world
only at the live tick. Only the view rewinds; the world and the agent stay live. Each recorded
tick also keeps the agent's latest decision and how long it had been waiting on the model, so the
side panel and speech bubble match the frame on screen. Resuming (OK) jumps back to the live tick.

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

## 2026-10-29: The provider is TypeSafe; its backend picks from a closed set

Supersedes "The decision-model provider backend waits for its docs". The provider is TypeSafe
(docs.typesafe.ai). Its System One API (`POST https://api.typesafe.ai/v1/systemone`, model
`jev-latest`) isn't OpenAI-compatible: it answers typed questions about a `state` with
calibrated probabilities and writes no text. So `TypesafeBackend` (`kind: typesafe`) asks one
Choice question per decision. A backend declares `Capabilities.closedChoice`, and then the
agent adds a `ChoiceSet` to the request: a compact JSON state (goal, rules, holding, what it
sees with `in_reach`, last and recent actions with results, events; no ASCII map, per
TypeSafe's advice that irrelevant detail costs accuracy) and the options. The options are every
entity-argument action the reply schema allows (pick up, use on, walk to each visible thing; use
a held item alone; wait 1 s). Tiles and `say` are left out: tiles are too many to list, and a
picker can't write text. Each option carries the exact reply it stands for, which still goes
through the schema. The rationale is the pick's probability, confidence and runner-up; the raw
log keeps the full distribution.

Options are ordered pick_up, use, move_to, wait, because TypeSafe documents a lean toward
earlier options. Not tested the other way round yet.

## 2026-10-29: `nearby` carries `inReach`

Computed by the sim (Chebyshev distance 1, the same test pick_up and use apply). TypeSafe's docs
say to keep arithmetic in code, and it saves chat models the same comparison. No gameplay change.

## 2026-10-29: Keys live in ~/.config/lil-guy-machine/secrets.env, loaded by the Makefile

One file outside the repo (mode 600) that every worktree sees, holding `export NAME=value`
lines. The Makefile `-include`s it, so `make cli`, `make desktop` and `make tv-push-config` get
the keys in their environment. The app's own env-file reader accepts the same `export` lines.
Nothing prints the values.

## 2026-10-29: Rooms share a GridWorld base

Milestone 4 adds a second room, so the intent lifecycle (inbox, validate, run, finish, the
decision-point flag), A* walking, speech, events and publishing moved from `RoomWorld` into an
abstract `GridWorld`. A room supplies `plan` and `publish`, plus hooks (`afterWork`, `workEnded`,
`entered`, `passable`, `stamp`). Work gained `Until` (start, run until a condition, finish) and
`Steps` (walk, then do). `RoomWorld` behaves exactly as before: the scripted run still takes 212
ticks and Jev 148. Key-and-door stays as the quick CLI test.

## 2026-10-29: A game clock on top of ticks; 1 game minute = 5 ticks by default

`GameClock` maps ticks to day and hh:mm, starting day 1 at 08:00. Bedroom rules are written in game
minutes and hours and converted once, so `ticksPerMinute` is the single clock-scale knob: CLI
`--minute-ticks`, desktop `MINUTE_TICKS=`, TV `--ei minute_ticks`. At 5, a game day is 7200 ticks,
6 real minutes. Walking stays at 4 tiles a second of real time, so a smaller scale makes walks
cheaper in game minutes; at 5, a tile is a game minute.

## 2026-10-29: Bedroom content is one YAML file on the classpath

`sim/src/main/resources/content/bedroom.yaml` holds the room (rows plus slot letters), the start
state, need rules, items, activities and uses. It's read with kaml (now also a `:sim` dependency;
pure JVM, already used on Android by `:decision`) in strict mode, and `BedroomContent` checks
cross-references at load. A job, a hobby, a purchase or decor is a row; `BedroomWorld` only knows
the fields: `at`, `requires`, `consumes`, `produces`, `sells`, `effects`, `rates`, `asleep`, `until`,
`stop_below`, `block`, `work`, and for uses `item`, `on`, `background`.

## 2026-10-29: Money and needs change only through logged entries

`Ledger` keeps whole dollars and refuses to go below zero; every credit and debit is a
`Transaction` (tick, day, time, amount, balance, reason), and each one is also a world event, so it
shows in the observation's recentEvents and the decision log. Needs are doubles 0 to 100 shown to
models as whole numbers rounded down. Rates are applied every game minute but logged as one
`NeedChange` per need and reason per game hour (a day is a few dozen lines, not thousands); one-off
effects are logged as they happen. The CLI writes `hours-NN.jsonl` (money and needs every game
hour), `money-NN.jsonl` and `needs-NN.jsonl` beside each bedroom decision log.

## 2026-10-29: The bedroom's action space and observation

`do(activity)` and `buy(item)` are new `Intent` variants; the sim walks him to the nearest owned
spot (`at`) or to the door for the shop. `use(item, on)` is item-on-item (a record on the record
player) and needs only ownership, not holding. `move_to`, `say` and `wait` work as before;
`pick_up` is refused with a reason, since owned things are already his. Key-and-door rejects `do`
and `buy`, and its schema leaves them out.

The observation adds clock, money, needs, holding (carried art), owns, music, `activities` (every
`do` activity: takes and gives, or `locked` with a reason such as "needs an easel ($120)"),
`uses` and `shop` (price, affordable, what it's for). These fields are left out of the JSON when
null, so the key-and-door observation is unchanged. The per-request schema and the TypeSafe
choice set include exactly the unlocked activities, the offered uses and the affordable purchases.
Choice options are ordered activities (content order: eat, sketch, paint, sell_art, read, listen,
sleep), uses, purchases, then wait (30 game minutes).

## 2026-10-29: Bedroom numbers: what changed from the plan's first guesses

Kept: start ($40, bed, desk, chair, sketchbook, 2 meals; energy 80, fullness 70, mood 60), the
activity and shop tables, energy -4/h awake, fullness -5/h, mood drift 3/h toward 50 plus comfort,
low at 15, work refused below 5.

Changed or filled in:
- Below 15 energy or fullness, mood falls 5/h *instead of* drifting; otherwise drift cancels most
  of it.
- Sleep also ends when fullness falls below 10 (`stop_below`). Both the scripted policy and Jev
  went to sleep at fullness around 40 and slept it down to 0.
- Sleep is refused above energy 94 ("not tired") and eating above fullness 90 ("not hungry").
- Sell art is not `work`, so a hungry guy can still sell what he holds.
- Unspecified in the plan: comfort plant 4, poster 3, rug 6; three records and three books, each
  one of a kind; buying takes 10 minutes at the door; the day starts at 08:00.

Economy as built: sketching nets about $60–90 a day, the easel comes on day 2 (scripted) or day 3
(Jev), and painting then makes about $150–300 a day. That is more than the plan guessed, and the
whole shop costs about $500, so money stops being a constraint within about three days of buying
the easel. That is the first thing to tune, probably alongside milestone 6's wants.

## 2026-10-29: The bedroom prompt describes his situation, plus two facts for closed-choice models

The system prompt opens "You live in this bedroom. You need money for food and for anything you
want to own. You'll be living here for a long time." There is no objective; the rules, prices and
options come from the content file. Jev, given only the situation, looped sketch → sell every hour
and never bought the easel in two 4-day runs (buy:easel peaked as the runner-up at p ≈ 0.2). Two
changes, both factual rather than objectives: the choice instructions say "choose what serves him
best over the coming days, not just the next hour", and shop entries say what an item would
unlock and what that pays ("unlocks paint (3h: 1 painting, sells for $70; uses a canvas)"). After
both, Jev bought the easel on day 3 in all five runs and painted.

## 2026-10-29: The app runs the bedroom by default; the CLI picks a scenario

The app and the TV start the bedroom with a clock, money and needs panel (bars; a tick mark for
where mood settles), what he's doing with progress, music, what he owns, the last decision,
events stamped with game time, and recent transactions. `SCENARIO=key-door` (desktop and
`tv-run`) brings back the old room. The CLI defaults to key-door (`--scenario bedroom --days N`
for the life sim); bedroom runs cap decisions at 60 a game day and count as passed if they live
their days without hitting the cap.

## 2026-10-29: Bedroom option lists are cached between ticks

Building the observation's activities, uses and shop text every tick cost about 107 µs on the Mac
JIT (key-and-door: 2.6 µs). On the TV's debug build, frame CPU was 8–13 ms against 4.4 ms. The
lists are now rebuilt only when what he owns or plays changes, a visible need level changes, or
the clock passes a 10-minute step; sleep's duration estimate is rounded to 10 minutes to match.
The room map is cached by cell. About 15 µs a tick now. No behaviour change.

## 2026-10-29: Milestone 4 runs on the TV

`make tv-run ENDPOINT=typesafe` ran the bedroom on the Streamer at 60 fps. Jev answered 18
decisions in a median of 202 ms (max 1.06 s) with no fallbacks. The guy sketched and sold, bought
and ate meals, and slept, the same pattern as the headless runs. Ian's next levers: a richer
world and simulation, and a memory system (with an LLM writing memories) for milestone 5.
