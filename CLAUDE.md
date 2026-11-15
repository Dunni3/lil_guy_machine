# CLAUDE.md: lil guy machine

A 3D sim with one character driven entirely by a pluggable decision model (LLM over an
OpenAI-compatible endpoint, or scripted/RL). Kotlin + libGDX, runs on desktop and Android TV.
The spec is `docs/agent-world-spec.html` (milestones in section 10); decisions are in
`docs/decisions/`. Current state: milestone 4 (the bedroom): a life sim with a game clock,
money ledger, needs and data-driven activities and shop, driven by the scripted policy, TypeSafe's
Jev (closed-choice) or an OpenAI-compatible endpoint, shown in `:app` as a 3D cutaway room (the
spike of `research_notes/2026-10-29_2053_3d-room-and-character-plan.md`) with the 2D dev view's
pages beside it. Key-and-door stays as the quick backend test. Milestones 5-6 are
memory and personality (`research_notes/2026-10-29_0313_bedroom-proposal-and-roadmap-v2.html`).

## Commands

```bash
make test        # JVM unit tests (all modules)
make cli         # headless runner (ARGS="--scenario bedroom --days 3 --endpoint typesafe")
                 # every run records into runs/lilguy.db (--db FILE, --no-db; --resume RUN|latest
                 # carries a run on from its latest checkpoint); add --memory-endpoint NAME for memories,
                 # --writer-endpoint NAME for thoughts and the self-model, --recall-endpoint NAME (typesafe)
                 # for scored recall, --painter-endpoint NAME (typesafe) to draw his art when a text model decides, --grant paint,easel,... to unlock tiers,
                 # --affinities calm=2,loud=-1 / --curiosity 1.5 for another personality
make server-up   # local Postgres + the ingest API (token "dev"); make server-test, server-psql
make host        # the sim host: a life in real time, its state over WebSocket, debug page at
                 # http://127.0.0.1:8790/ (scripted unless ENDPOINT=; PAINTER=2 paints; INVENTOR=1 invents from fixed designs; commands need LILGUY_ADMIN_PASSWORD, see host/README.md;
                 # it carries on the run it was last on, RESUME=none for a fresh one)
make protocol-types  # protocol JSON Schema + TypeScript from :protocol (make test fails when stale)
make web-build   # the web renderer into web/dist, in a Node container; make host does it when stale and serves it at /room/
make web-dev     # the same with live reload at http://localhost:5173/, against a make host; make web-test for its test
make parts       # parts/export/parts.json from the part lists (make test fails when stale)
make import-runs # old JSONL run logs into runs/lilguy.db; make sync sends unsynced rows
make desktop     # LWJGL3 window (RESUME=<run id|latest> carries a run on)
make tv-connect IP=10.0.0.51   # wireless adb to the Streamer
make tv-run      # build + install + launch debug APK (ENDPOINT=<name>, MEMORY_ENDPOINT=<name|none>, WRITER_ENDPOINT=, RECALL_ENDPOINT=,
                 # SCENARIO=key-door, MINUTE_TICKS=<n>, DAYS=<n>); runs wait for OK on the remote
make tv-push-config  # push config/endpoints.yaml + the keys it names (dev only)
make tv-pull-logs    # copy the TV's run logs into runs/tv/; never overwrites earlier pulls (tools/adb/pull-runs.sh)
make tv-logs     # logcat, tag LilGuy
make tv-profile  # release-like build (not debuggable) for measuring performance
```

The Makefile exports `JAVA_HOME` (JDK 17 at `~/Library/Java/JavaVirtualMachines/temurin-17.jdk`)
and `ANDROID_HOME` (`~/Library/Android/sdk`). Outside it, set them before `./gradlew` or `adb`.

## Where things are

- Action space: `sim/.../Intent.kt` (`Intent`, `Target`, `IntentStatus`). It is the wire format;
  changing it changes what models see.
- World boundary: `sim/.../World.kt`; observations and snapshots: `sim/.../Observation.kt`.
  Intent lifecycle, walking, publishing: `sim/.../GridWorld.kt`. Key-and-door: `RoomWorld.kt`
  (map text in `TileMap.kt`, A* in `Pathfinder.kt`).
- The world: `sim/.../Layout.kt` stitches the lot (`content/bedroom/world.yaml`: ground letters, the named
  outdoor places, where the bedroom stands and its front door; `WorldContent.kt`) and the bedroom into one map,
  and says which place a tile is in. Content without `world.yaml` is the bedroom alone. What he perceives of it
  (`Observation.place`, `places`, an activity's `where` and `walk`) is the sim's; his map in the system prompt is
  `agent/.../WorldMap.kt`; `move_to` a `Target.Place` (the `go` tool) walks him to a place.
- The bedroom: `sim/.../BedroomWorld.kt` (engine), `Spots.kt` (where things stand and can be put: the `put` intent,
  the `place` tool, free spots indoors and on the land), `BedroomContent.kt` (schema of the content files),
  `GameClock.kt` (clock scale knob), `Ledger.kt` (money ledger and needs). Content, which is where
  numbers and new jobs, items and hobbies go: `sim/src/main/resources/content/bedroom/*.yaml`
  (`BedroomContent.FILES` says which keys each file holds). Anything with `tier` above 0 is gated
  until its id is granted (`BedroomContent.unlocked`). Items use a closed `vocabulary` of
  affordances (what verbs ask for; `sellable` and `drawable` take parameters) and qualities (scored
  by his `personality` affinities into comfort and mood). What a verb does is its `effects`, a list
  of ops (`Ops.kt`: each op owns its checks, lock reason, apply and prompt text); every verb has a
  `kind` that lists the ops it may use (`VerbKind.kt`). Books are `library.yaml`: read a chapter a
  session (the `progress` op, which raises the book's skills); finishing one teaches ids that aren't in his world until then
  (`BedroomContent.taught`, `BedroomWorld.knownContent()`, which the prompt is built from).
  A model that calls tools (an `openai_compat` endpoint) decides by calling one of his verbs as a tool
  (`agent/.../BedroomTools.kt`, `PromptBuilder.ToolTurn`; `docs/decisions/2026-11-15-a-text-model-decides-*`): his recent actions
  go back as his own calls with their outcomes as results; each call's `thought` is his thought of the moment (`AgentMemory.thought`), and before
  acting he may look (`ToolSet.looks`: `recall`, `AgentMemory.search`; `ask`, the oracle; `search`, the real web: `agent/.../web/Searcher.kt` over
  `decision/.../WebSearch.kt`, Gemini grounded on Google Search or Brave, from endpoints.yaml's `search:` block, shown in the web menu's WEB tab;
  `docs/decisions/2026-11-15-he-searches-the-real-web-*`), so he isn't offered think. `menu.yaml` groups his options into a tree for Jev, who walks down it by beam search
  (`decision/.../BeamSearch.kt`, `beam` on the endpoint) instead of picking from one flat list. Skills
  (`vocabulary.skills`, rules.yaml `skills`) rise with `practice` and reading. Needs are energy,
  fullness, mood, stimulation and frustration. Comfort is what he can see lit from where he stands (rules.yaml `comfort`: near
  and far, in tiles; `Lighting.viewWeight`), and his observation's `lately` says what he's done most, and what he hasn't for days or ever. Weather: `sim/.../Weather.kt` (a seeded hourly sky
  chain, temperature, a hidden pressure that leads rain; numbers in `weather.yaml`) and `Records.kt` (what a
  `recorder` logs each hour, and `StationLog`, the summary `check_station` reads standing at it). `make content-docs` regenerates the content's
  JSON Schemas (`content/bedroom/schema/`, checked by a test) and `build/grammar-reference.html`
  (`agent/.../ContentDocs.kt`).
- Effects with reach (`docs/decisions/2026-11-15-light-is-an-effect-with-reach-*`): `sim/.../Effects.kt` (the engine: an
  `EffectKind`, `Emitter`s, a `Field` per kind over the map's tiles, walls block, recomputed only when its inputs change) and
  `sim/.../Lighting.kt` (light, the first kind: rules.yaml `light`, daylight by hour and through room `windows`, items' `light`,
  his lantern; a `lamp` (the affordance) is lit on its timer unless he switches it, the `switch` op and `BedroomWorld.lampLit`, `docs/decisions/2026-11-15-lamps-run-on-timers-*`; the weather's `daylight()` dims it, and a station logs it at its tile; the landmark phrases in `Observation.light`; `viewWeight` for comfort).
  `BedroomWorld.lightField()`; a verb's `needs: { light: N }` locks it where it would be done (`darkLock`); at night `nearby`
  holds what's lit, the rest `remembered` if he knows it's there. The protocol's `Entity.light` is what the web lights the scene with.
- Scenario wiring, scripted policies, lockstep runner: `agent/.../Scenario.kt` (`Scenario` enum),
  `KeyDoorPolicy.kt`, `BedroomPolicy.kt`. Bedroom prompt, schema, choice set: `BedroomPrompt.kt`;
  who he is (core and taste from content, plus his self-model): `Persona.kt`; run summaries and the self-model report: `BedroomReport.kt`.
- Decision layer: `decision/.../DecisionModel.kt`, `ActionSchema.kt` (schema + reply parser), `ToolSet.kt` (a decision as tools, each call mapped to a reply),
  `JsonSchemaGenerator.kt`, `SchemaValidator.kt`, `OpenAiCompatBackend.kt`, `TypesafeBackend.kt`
  (System One, picks from `DecisionRequest.choices`), `Endpoints.kt` (config loader and factory).
- Agent loop: `agent/.../AgentRuntime.kt`, `PromptBuilder.kt` (system prompt with the action
  space), `AgentReply.kt` (reply envelope, `ActionExamples`, per-observation `replySchema`),
  `ActionChoices.kt` (the closed set for picking models), `Memory.kt`, `DecisionLog.kt` (JSONL
  log + `ReplayDecisionModel`).
- Long-term memory (milestone 5, in progress; `docs/decisions/2026-10-31-memory-redesign-*`): `agent/.../memory/`:
  `MemoryStore.kt` (storage interface; events are outcomes, world events, a daily snapshot, thoughts and art;
  memories are level 1 stretches, level 2 days, level 3 the self-model; `InMemoryStore`), `AgentMemory.kt`
  (what `AgentRuntime` talks to: records outcomes, scores and ranks in the background, writes the day and the
  self-model at bedtime), `Describe.kt` (event text), `Summarizer.kt` (level-1 memories by significance,
  day memories), `SelfModeler.kt` (the nightly self-model), `MemoryScorer.kt` (Jev Score questions for recall
  relevance and event significance, through `decision/.../Scorer.kt`), `Recall.kt` (candidates and the
  keyword or ranked rule), `Keywords.kt` (keyword scoring), `Thinker.kt` (the `think` verb: a short turn that may ask `Oracle.kt` how the world works, from `WorldDocs`, before the thought),
  `Handlers.kt` (the `thought` and `reading` handlers), `Reader.kt` (what stuck from a chapter). The fixed
  core and the self-model seed are in `personality.yaml`.
- Authored ops (a model writes the result of a `write` or `draw` op): `agent/.../authored/Authors.kt`, the
  handler registry and the one lifecycle they share (timing before or during the verb, kept only if it
  succeeds, the clock held while a `before` call is out or a `during` call outlasts its verb, results back to the sim through
  `BedroomWorld.deliver`). The sim side is `sim/.../Authored.kt`. A verb whose `write` has no handler
  isn't offered to models, unless the write is `optional`. Think is a verb; `reflection` in rules.yaml
  makes him think every few decisions. Shopping trips: the `shop` verb's `shopping` write
  (`agent/.../shop/Shopper.kt`: the writer picks from the whole catalog with reasons, or Jev picks one) is
  staged in the sim before he goes (`AuthorHandler.staged`, `BedroomWorld.stage`) and its `basket` op buys
  it at the finish; while there's a trip, everything (food too) is bought on trips and models deciding
  see no `shop` (`BedroomPrompt.deciding`).
- Inventing (`docs/decisions/2026-11-13-he-invents-items-*`): at the workbench the `invent` verb's `invention` write has the
  writer design one new item (`agent/.../invent/Inventor.kt`: one use, some qualities, a look of named shapes with the clay part
  features; a writer that calls tools builds it a shape at a time with the workbench tools in `Workbench.kt`, one without writes it
  as JSON with one retry on the sim's reasons; either way it's looked over once; `docs/decisions/2026-11-15-he-builds-what-he-invents-*`;
  `CannedInventor` for `LILGUY_INVENTOR=1`), staged like a trip's basket, and the `build` op (`Ops.kt`) checks it,
  prices its materials and adds it to the run's content (`sim/.../Invention.kt`: `Design`, `InventRules` from rules.yaml
  `invent`, `Invention`; `BedroomContent.plus`). `BedroomWorld.content` grows during a run, so read it when needed. Inventions
  are saved in `BedroomState.inventions`, go to viewers in the protocol's `shop.inventions`, and the web renderer builds their
  models from the look (`web/src/room/parts.ts`). Things to look at stand in room.yaml's spare `spots`.
- Code he writes (`docs/decisions/2026-11-15-he-writes-his-verbs-and-what-his-things-do-as-code.md`): `sim/.../Scripts.kt`
  (the Rhino sandbox and the world API table, `ScriptApi.FUNCTIONS`, which the coder's brief and the oracle are made from),
  `sim/.../CodedVerbs.kt` (verb and behavior designs, `CodeRules`, the `script` and `define` ops); the world side is
  `BedroomWorld`'s `ScriptView`, `putCode` and `runBehaviors`. `devise` writes code at his desk; `invent` writes each thing's
  function with it. The coder: `agent/.../code/Coder.kt` (deciding model if it's a text model, else the writer).
- Art: `agent/.../art/`: `Painter.kt` (the deciding model draws each piece as a run of closed picks:
  subject, crayon box, then a stroke a call; `CommissionVoice` words each day's commission on a text
  model), `Strokes.kt` (the drawing spec as menus of strokes by stage: background, subject,
  details), `Shapes.kt` (stencils for each subject noun and the scenery), `Palettes.kt` (named crayon boxes),
  `Artwork.kt` (ops rasterized to a grid, and a PNG encoder with no ImageIO), `ArtStudio.kt` (the `draw`
  handler: saves `art-NN/piece-NNN.png`, delivers title and tags to the piece; commission wording goes
  through `BedroomWorld.annotate`). `agent/.../Menu.kt` puts one pick to any model (a choice set
  for Jev, a prompt plus enum schema for a text model). The sim's pieces, commissions and
  hanging are in `BedroomWorld.kt`. The app's textures for them are in `ArtTextures.kt`. The piece
  under way is reported stroke by stroke (`Underway`, `ArtStudio.underway`) and played out in the room
  by `app/.../PaintScene.kt` (brush on the real cells, dips, stepping back, a flourish), fed stroke by stroke at the pace
  of the session's `ArtShow`, which holds the clock until the piece has finished on screen; `RoomView` keeps him posed at it.
  Memory is stored in the run database (`RunMemoryStore`); `InMemoryStore` only without one (`--no-db`).
- Run database (roadmap phase 1, the sim host's store; `docs/decisions/2026-11-06-runs-record-locally-*`):
  `runlog/` (pure Kotlin): `Rows.kt` (the `@Serializable` rows, the tables' only definition: `@Pg`, `@Key`), `Tables.kt`
  (read from their descriptors; the SQLite DDL), `RowSchema.kt` (`make server-schema`: `server/schema/rows.schema.json`, then
  Pydantic `server/app/rows.py`; checked by `RowSchemaTest` and `server/tests/test_schema.py`), `RunStore.kt` (the
  device's SQLite store, many runs, `_rev`/`_dirty` for sync, prune after 30 days), `RunRecorder.kt` (`record` returns at
  once; an IO coroutine writes; blobs by SHA-256, gzipped), `SyncWorker.kt` (blobs then `POST /v1/ingest`; env
  `LILGUY_SYNC_URL`, `LILGUY_SYNC_TOKEN`). Drivers: `runlog-jdbc/` (JVM), `android/.../AndroidSqlDriver.kt` (TV).
  `agent/.../LifeRecorder.kt` turns a run into rows (decisions with outcomes, model calls with prompts via
  `TokenLedger.record`, ledger, needs, hourly state, authored ops, art) and takes the morning checkpoint around each
  tick (`beforeStep`/`afterStep`; `AgentRuntime.hold`), plus each session's rolling save (one row rewritten at decision
  points every 10 game minutes, and when the run is left; `docs/decisions/2026-11-15-runs-are-kept-*`); saves are `BedroomWorld.exportState`/`restore`
  (`sim/.../BedroomState.kt`) plus `AgentState`. CLI: `cli/.../RunDb.kt`, `Importer.kt`. App: `RunDatabase.kt`,
  `SimSession`. Server: `server/` (FastAPI ingest and query only, in `app/main.py`; numbered migrations in `sql/migrations/`, compose, `deploy/`
  for its own Lightsail Small box; see `server/README.md`; `server/host/` is the sim host's image, on its own subdomain,
  `docs/decisions/2026-11-08-the-host-runs-in-*`).
- The run loop both the app and the sim host drive: `agent/.../live/` (`SimSession`: start, pause, restart, replay,
  resume, configure, `update(delta)` from one thread; `RunSettings` with the per-life presentation timing: `quiet`,
  `quietVerbs`, `holdForArt`; `ArtShow`, how a piece plays out on screen and when the clock waits for it; `ModelSetup`,
  `RunDatabase`).
- Sim host (phase 2; `docs/decisions/2026-11-06-the-protocol-is-*`): `protocol/` is the wire format, pure Kotlin
  (`Protocol.kt`: messages, commands, `PROTOCOL_VERSION`, `Wire`; `State.kt`: `LifeState` split by rate, with `diff`/`apply`
  and the logs' merge; `ProtocolSchema.kt` writes `protocol/schema/protocol.schema.json`, from which `gen_types.sh` makes
  `protocol/ts/protocol.ts`). `host/` is the Ktor server: `Life.kt` (one actor per life: a `SimSession`, commands between
  ticks, publishing deltas that never wait on a viewer), `StateReader.kt` (session and agent objects to protocol types),
  `HostServer.kt` (routes: stream, commands, debug page in `resources/debug/index.html`), `AdminAuth.kt` (who may send commands: one password,
  `docs/decisions/2026-11-08-v0-admin-login-is-*`; setup in `host/README.md`),
  `Main.kt` (env config, one `SyncWorker` per process).
- Letters (`docs/decisions/2026-11-15-anyone-with-an-account-*`): viewers' accounts (an email and a password) and the letters they
  write him, in `host/.../Accounts.kt` (its own SQLite file, `LILGUY_ACCOUNTS_DB`, never synced) and `MailRoutes.kt`, with the wire
  types in `protocol/.../Mail.kt`. `Life` polls for waiting letters and posts them into the running world (`BedroomWorld.post`); the
  mailbox and the ids he has opened are in `BedroomState`; `read_mail` (kind `social`, op `open_mail`, `sim/.../Mail.kt`) opens one at
  the mail hatch, its words in the event. He writes back with `write_letter` (op `post_letter`, rules.yaml `letters`), to anyone whose
  letter he has opened, replying or on his own: the writer writes it (`agent/.../mail/LetterWriter.kt`, handler `letter`), the world
  keeps it in `lettersSent`, and the host keeps it for that account (`his_letters`, the mailbag's `replies`).
  On the web: `src/ui/mail.ts`, a DOM panel from the "Write to him" button, both ways in one thread.
- Web renderer (phase 4; `docs/decisions/2026-11-08-the-web-renderer-is-*`): `web/`, TypeScript, Three.js and Vite, served by the
  host at `/room/`, or from anywhere with `make web-dev`. URL settings in `src/settings.ts`: `life`, `broadcast` (no menu, keys or cursor, the camera
  follows him, the HUD in a 16:9 frame's title-safe area), `scale` (canvas resolution, CSS scales it up), `fps` (frame cap), `camera` (`room` or `follow`),
  `hour` (light the room as at that hour, for looking). `lilguy.draw()` in the console draws one frame: a hidden tab (browser-use's) gets no animation frames.
  `src/stream/`: `state.ts` (snapshot and deltas in seq order, the mirror of `LifeState.apply`), `client.ts`
  (socket, version check, rejoin, the content cache behind `?have=`, the heartbeat that rejoins a silent socket), `track.ts` (his position between publishes). `src/clay/`: the clay look of
  `world_sketches/fable_field_lab.html` (`docs/decisions/2026-11-15-clay-replaces-crayon-*`): `renderer.ts` (`ClayRenderer`: standard materials, the sun's shadow map
  drawn again only when something moved, sky light, lamp lights from glowing parts; `ClayModel`, a part list merged per material), `geometry.ts` (each shape),
  `day.ts` (the light by hour), `textures.ts` (planks, rug, poster, dial, his face per expression). `src/room/`:
  `view.ts` (`RoomView.kt`: things, shelf, music, lamps), `camera.ts` (the viewer's own isometric camera: the whole room or following him), `shell.ts` (plinth, floor, the two standing walls and the two cut to a sill, window), `parts.ts` (the export as
  models: an item's clay list, else its crayon one), `room.ts` (the room's layout the map doesn't give: walls' height, window, clock, shelf, guitar peg, stands where pieces are made, wall spots where they hang),
  `art.ts` (hung frames, the pieces on the desk and easel, brush, palette and sparkles; `PaintScene.kt`), `easel.ts` (the piece under way played out
  stroke by stroke at the host's pace from `state.easel`). `src/guy/`: `blob.ts` (`Blob.kt`'s animation, his clay pieces and the lantern that follows him). `src/ui/`: what's drawn over the room, in the
  same canvas as panels from 2D canvases redrawn only when they change (`layer.ts`; no DOM over the room but the sign-in box): `hud.ts` (needs card, bubble,
  banner, the catalogue while he buys; `RoomView.kt`), `menu.ts` and `pages.ts` (the menu's keys, and clicks and the wheel on the spots `menuCard.ts` records as it draws, and its RUN, SHOP, MADE (his inventions, the verbs and behaviors he wrote with their code, and what the code changed; the protocol's `code` section), THOUGHTS, WEB, MEMORY, SELF and TRIPS tabs;
  `GameMenu.kt`), `menuCard.ts` (drawing it), `run.ts` (`RunControls.kt`), `keys.ts` (`RemoteInput.kt`'s keys), `admin.ts` (the password sign-in and commands), `mail.ts` (accounts and letters).
- Part lists: `parts/` (pure Kotlin): `Part.kt` (`Part`, `PartList`; the clay fields `cut`, `hole`, `arc`, `glow`, `opacity` and `ring`/`dome`, which `:app` ignores),
  `ClayParts.kt` (the clay models and the guy's pieces), `RoomParts.kt` (the crayon furniture, props, the room's constants), `DecorParts.kt`, `PartExport.kt`
  (writes `parts/export/parts.json`, the web renderer's copy, checked by `make test`). `:app` builds meshes from the crayon lists.
- Token accounting: `agent/.../TokenLedger.kt`. `ledger.meter(model, purpose)` wraps a model so every call
  (decision, think, summary, self, recall, significance, art) is recorded with its sim tick and game day; `TokenReport` prints
  tokens per game day and the 30-day projection. The CLI and the app (`SimSession`, the TOKENS page) wire it and
  write `tokens-NN.jsonl`; `price_in`/`price_out` on an endpoint add cost.
- Renderer: `app/.../LilGuyGame.kt`. The first page is the 3D room, `RoomView.kt` (camera, light by
  game hour, HUD, bubble, the shop's catalogue card on DOWN), his books, records and guitar on a wall shelf
  (`RoomParts.shelf`, from `LifeSnapshot.catalog`), with furniture as part lists keyed by item id in `parts/.../RoomParts.kt`, built by `RoomModels.kt` (1 unit = 1 tile,
  pivot bottom centre, front +Z) and the guy in `Blob.kt` (face textures per expression, animation in
  code from needs and activity). All of it is drawn in the hand-drawn look by `CrayonRenderer.kt`:
  `CrayonModel.kt` holds the merged meshes, `Crayon.kt` the
  shape builders and shaders, and `RoomPaint.kt` the floor, wall and item textures painted once with `CrayonCanvas.kt`. Then the 2D dev view in
  `DevView.kt`: map plus Now/Shop/Verbs/Memory/Art/Tokens/History/Log/Run pages (Shop and Verbs read `LifeSnapshot.catalog`,
  `verbs` and `done`, built with the world's cached option lists); run control, decision history and the app's memory in
  `agent/.../live/SimSession.kt`; the Run page's knobs (days, speed, how much faster quiet stretches run, how long the clock
  waits on the model, resolution) in `live/RunSettings.kt` (reading and sleeping are its `quietVerbs`: the clock runs `quiet`
  times faster through them); remote keys in
  `RemoteInput.kt`: in the room OK opens the in-game menu, PLAY/PAUSE starts or pauses anywhere, LEFT/RIGHT pages,
  UP/DOWN scroll or pick, BACK twice restarts). The in-game menu is `GameMenu.kt`: a card over the room with tabs
  RUN (start/pause, settings, restart, replay; rows and texts shared with the Run page in `RunControls.kt`), SHOP,
  THOUGHTS, MEMORY, SELF (core, taste, every night's self-model revision) and TRIPS (shopping transcripts, from
  `AgentMemory.trips()`), each a list with the picked entry in full beside it. Launchers: `desktop/`, `android/`.
  `LILGUY_SCREENSHOT=out.png LILGUY_SCREENSHOT_AT=<s> make desktop` saves one frame and quits
  (add `LILGUY_CONFIG=/nonexistent` for the scripted policy, no API calls; `MINUTE_TICKS=1` for a faster clock)
  (`LILGUY_SCREENSHOT_KEYS="Enter,@60,Right"` presses keys first; `LILGUY_START_ITEMS=novel,folk_record,guitar` adds to
  what he starts with, and what a book teaches with it; `LILGUY_SCRIPT=read,play_guitar` has the scripted policy do those first;
  `LILGUY_PAINTER=3.5` draws his art with random picks at that latency, so he paints with no endpoint;
  `LILGUY_SCREENSHOT_FRAMES=n LILGUY_SCREENSHOT_EVERY=0.5` saves a burst as out-001.png and on).
- Endpoint config example: `config/endpoints.example.yaml` (real one is gitignored). The app's
  and host's model choice: `agent/.../live/ModelSetup.kt`. Run logs go in `runs/` (gitignored).
- ADB helpers: `tools/adb/`.

## Rules of the road

- `:sim`, `:decision` and `:agent` stay pure Kotlin/JVM: no `com.badlogic` imports, no Android.
- Never block a frame or a tick on a model call; decisions are async intents.
- API keys come from the environment, never from committed files. Not on the TV long-term.
  Locally they live in `~/.config/lil-guy-machine/secrets.env`, which the Makefile loads. Never
  print them.
- Keep toolchain versions in `gradle/libs.versions.toml` in step with smarter-tv unless there is a
  reason (record it in `docs/decisions/`).
- Decisions: one file per choice that constrains future work, in `docs/decisions/` (format and index in its README). Skim the index before related changes; skip routine implementation detail.

## The TV (Google TV Streamer)

Lessons carried over from `~/projects/smarter-tv` (its `research_notes/` has the detail):

- Device: `kirkwood`, Android 14, 4 GB RAM, PowerVR GE9215. Userspace is 32-bit
  (`armeabi-v7a` only), so the APK must carry armeabi-v7a natives. Panel 3840x2160 @ 60 Hz with the UI at 1920x1080.
  Usually at `10.0.0.51:5555` over wireless adb; accept the "Allow USB debugging?" prompt on
  the TV the first time.
- If the emulator is also attached, set `ANDROID_SERIAL=10.0.0.51:5555`.
- **Never** change HOME, disable launcher/setup packages, or run debloat from this repo.
  smarter-tv's launcher is HOME on the Streamer, and changing HOME once took the TV offline.
  This app is a plain LEANBACK_LAUNCHER entry. To put it on the home screen, add
  `dev.lilguy.machine` to "Dock packages" in smarter-tv's phone controller (launcher config).
- The GPU is weak: each full-screen layer costs ~7 ms and per-frame full-screen gradients or AGSL
  shaders blew the 16.7 ms budget. Keep fill rate low and log the GL backbuffer size
  (`LilGuy` tag) to confirm whether the surface is 1080p or 4K.
- Judge performance on release-like builds, not on debug feel.

## Research notes

`research_notes/` (gitignored, local-only) holds notes from agents and Ian: work done and why,
decisions, device findings, open questions. Skim titles before related work; write one with
`/research-note <what to record>`.

- **Filename:** `research_notes/YYYY-MM-DD_HHMM_<kebab-slug>.md`, local time. Flat directory.
- **Frontmatter:** `title` and `summary` required; optional `date` (ISO with offset), `author`,
  `tags`, `related`, `status` (active | done | superseded | abandoned).
- Never put secrets, keys or tokens in a note. Don't rewrite old notes; add a new one and mark
  the old `status: superseded`.
