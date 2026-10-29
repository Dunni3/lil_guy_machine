# CLAUDE.md: lil guy machine

A 3D sim with one character driven entirely by a pluggable decision model (LLM over an
OpenAI-compatible endpoint, or scripted/RL). Kotlin + libGDX, runs on desktop and Android TV.
The spec is `docs/agent-world-spec.html` (milestones in section 10); decisions are in
`docs/DECISIONS.md`. Current state: milestone 3 (connect a decision model): the key-and-door
room driven by the scripted policy or an OpenAI-compatible endpoint, shown in a temporary 2D
dev view in `:app`. The 3D renderer is deferred; milestones 4-6 are the bedroom, memory and
personality (`research_notes/2026-10-29_0313_bedroom-proposal-and-roadmap-v2.html`).

## Commands

```bash
make test        # JVM unit tests (all modules)
make cli         # headless runner (ARGS="...")
make desktop     # LWJGL3 window
make tv-connect IP=10.0.0.51   # wireless adb to the Streamer
make tv-run      # build + install + launch debug APK (ENDPOINT=<name> to pick a model)
make tv-push-config  # push config/endpoints.yaml + the keys it names (dev only)
make tv-logs     # logcat, tag LilGuy
make tv-profile  # release-like build (not debuggable) for measuring performance
```

The Makefile exports `JAVA_HOME` (JDK 17 at `~/Library/Java/JavaVirtualMachines/temurin-17.jdk`)
and `ANDROID_HOME` (`~/Library/Android/sdk`). Outside it, set them before `./gradlew` or `adb`.

## Where things are

- Action space: `sim/.../Intent.kt` (`Intent`, `Target`, `IntentStatus`). It is the wire format;
  changing it changes what models see.
- World boundary: `sim/.../World.kt`; observations and snapshots: `sim/.../Observation.kt`.
  The room, its controller and the intent lifecycle: `sim/.../RoomWorld.kt` (map text in
  `TileMap.kt`, A* in `Pathfinder.kt`).
- Scenario wiring, scripted policy, lockstep runner: `agent/.../Scenario.kt`, `KeyDoorPolicy.kt`.
- Decision layer: `decision/.../DecisionModel.kt`, `ActionSchema.kt` (schema + reply parser),
  `JsonSchemaGenerator.kt`, `SchemaValidator.kt`, `OpenAiCompatBackend.kt`, `Endpoints.kt`
  (config loader and backend factory).
- Agent loop: `agent/.../AgentRuntime.kt`, `PromptBuilder.kt` (system prompt with the action
  space), `AgentReply.kt` (reply envelope, `ActionExamples`, per-observation `replySchema`),
  `Memory.kt`, `DecisionLog.kt` (JSONL log + `ReplayDecisionModel`).
- Renderer: `app/.../LilGuyGame.kt` (2D dev view in `DevView.kt`, sim clock in `SimSession.kt`,
  remote keys in `RemoteInput.kt`). Launchers: `desktop/`, `android/`.
  `LILGUY_SCREENSHOT=out.png LILGUY_SCREENSHOT_AT=<s> make desktop` saves one frame and quits.
- Endpoint config example: `config/endpoints.example.yaml` (real one is gitignored). The app's
  model choice: `app/.../ModelSetup.kt`. Run logs go in `runs/` (gitignored).
- ADB helpers: `tools/adb/`.

## Rules of the road

- `:sim`, `:decision` and `:agent` stay pure Kotlin/JVM: no `com.badlogic` imports, no Android.
- Never block a frame or a tick on a model call; decisions are async intents.
- API keys come from the environment, never from committed files. Not on the TV long-term.
- Keep toolchain versions in `gradle/libs.versions.toml` in step with smarter-tv unless there is a
  reason (record it in `docs/DECISIONS.md`).
- Log decisions in `docs/DECISIONS.md`.

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
