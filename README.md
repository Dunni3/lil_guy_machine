# lil guy machine

A small 3D world with one little guy in it, and every move he makes is chosen by a decision
model: an LLM behind any OpenAI-compatible endpoint, or a scripted/RL policy. It runs on a
desktop window or an Android TV (first target: the Google TV Streamer).

Design spec: [docs/agent-world-spec.html](docs/agent-world-spec.html).

## Status

**Milestone 0 (scaffold).** The Gradle modules build; desktop and Android show a lit, rotating
cube with an FPS readout; the action space (`Intent`) and the decision/agent interfaces exist
but no world or model backend does yet.

| # | Milestone | Exit criterion |
|---|---|---|
| 0 | Scaffold | Rotating cube on the TV and on desktop |
| 1 | Headless sim | `:cli` runs the key-and-door room with a scripted policy, with tests |
| 2 | Render the sim | Scripted run visible on TV at 60 fps with camera and HUD |
| 3 | Decision layer | `:cli` gets a parsed `Intent` back from a hosted endpoint |
| 4 | LLM drives it | Hosted model solves the room on the TV; JSONL decision log |
| 5 | Local swap | Same run against vLLM / llama.cpp by changing config only |

## How it fits together

```
:android ─┐
:desktop ─┴─> :app (libGDX) ─> :agent ─┬─> :sim       (pure Kotlin world, 20 Hz tick)
:cli ────────────────────────> :agent  └─> :decision  (DecisionModel, typed output)
```

`:sim` and `:decision` know nothing about each other or about graphics; `:agent` joins them.
The model picks *intents* (`MoveTo`, `PickUp`, `Use`, `Say`, `Wait`) defined once as a sealed
Kotlin type, which is both the JSON schema the model sees and the parser for its reply.

## Quick start

Needs JDK 17 and, for the TV, the Android SDK (platform 34).

```bash
make test       # JVM unit tests
make cli        # headless runner
make desktop    # desktop window
```

On the TV (Developer options and network debugging on):

```bash
make tv-connect IP=<tv-ip>
make tv-run
make tv-logs
```

## License

MIT
