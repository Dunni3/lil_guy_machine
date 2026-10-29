# CLAUDE.md: lil guy machine

A 3D sim with one character driven entirely by a pluggable decision model (LLM over an
OpenAI-compatible endpoint, or scripted/RL). Kotlin + libGDX, runs on desktop and Android TV.
The spec is `docs/agent-world-spec.html` (milestones in section 10); decisions are in
`docs/DECISIONS.md`. Current state: milestone 0 (rotating cube; interfaces only).

## Commands

```bash
make test        # JVM unit tests (all modules)
make cli         # headless runner (ARGS="...")
make desktop     # LWJGL3 window
make tv-connect IP=10.0.0.51   # wireless adb to the Streamer
make tv-run      # build + install + launch debug APK
make tv-logs     # logcat, tag LilGuy
```

The Makefile exports `JAVA_HOME` (JDK 17 at `~/Library/Java/JavaVirtualMachines/temurin-17.jdk`)
and `ANDROID_HOME` (`~/Library/Android/sdk`). Outside it, set them before `./gradlew` or `adb`.

## Where things are

- Action space: `sim/.../Intent.kt` (`Intent`, `Target`, `IntentStatus`). It is the wire format;
  changing it changes what models see.
- World boundary: `sim/.../World.kt`; observations and snapshots: `sim/.../Observation.kt`.
- Decision layer: `decision/.../DecisionModel.kt`, `ActionSchema.kt`, `ScriptedDecisionModel.kt`.
- Agent loop: `agent/.../AgentRuntime.kt`, `PromptBuilder.kt`, `Memory.kt`.
- Renderer: `app/.../LilGuyGame.kt`. Launchers: `desktop/`, `android/`.
- Endpoint config example: `config/endpoints.example.yaml` (real one is gitignored).
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

- Device: `kirkwood`, Android 14, 4 GB RAM. Panel 3840x2160 @ 60 Hz with the UI at 1920x1080.
  Usually at `10.0.0.51:5555` over wireless adb; accept the "Allow USB debugging?" prompt on
  the TV the first time.
- If the emulator is also attached, set `ANDROID_SERIAL=10.0.0.51:5555`.
- **Never** change HOME, disable launcher/setup packages, or run debloat from this repo.
  smarter-tv's launcher is HOME on the Streamer, and changing HOME once took the TV offline.
  This app is a plain LEANBACK_LAUNCHER entry.
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
