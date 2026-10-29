# lil guy machine

A small 3D world with one little guy in it, and every move he makes is chosen by a decision
model: an LLM behind any OpenAI-compatible endpoint, or a scripted/RL policy. It runs on a
desktop window or an Android TV (first target: the Google TV Streamer).

Design spec: [docs/agent-world-spec.html](docs/agent-world-spec.html).

## Status

**Milestone 3 (connect a decision model).** Two backends: TypeSafe's Jev, which picks the next
action from a closed set built each turn, and any OpenAI-compatible chat model, which writes
`{thought, intent}` against a JSON schema generated from the Kotlin types with ids limited to
what the guy can see. Jev solves the key-and-door room in 10 of 10 lockstep runs (148 ticks,
5 decisions). `make cli` runs N lockstep runs with the scripted policy or any configured
endpoint and reports the solve rate. Desktop and the TV show the same run in a temporary
top-down 2D view. The 3D renderer is deferred (see `docs/DECISIONS.md`).

| # | Milestone | Status |
|---|---|---|
| 0 | Scaffold: Gradle modules, cube on desktop and TV | done |
| 1 | Headless sim: key-and-door room, scripted policy | done |
| 2 | 3D renderer | deferred; the 2D dev view covers the TV until then |
| 3 | Connect a decision model: schema, OpenAI-compatible and TypeSafe backends, logs | done on desktop; TV run pending |
| 4 | The bedroom: clock, money, needs, a painting job, a shop | planned |
| 5 | Memory v0 and saves | planned |
| 6 | Personality v0: interests, skill, wants | planned |

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
make cli        # headless runner, scripted policy
make desktop    # desktop window
```

To use a model, copy `config/endpoints.example.yaml` to `config/endpoints.yaml` (gitignored).
Each endpoint names the environment variable holding its key in `api_key_env`. Put
`export NAME=value` lines in `~/.config/lil-guy-machine/secrets.env` (outside the repo; the
Makefile loads it), or export the key in your shell:

```bash
make cli ARGS="--endpoint typesafe --runs 10"  # solve rate and median ticks; logs in runs/
make cli ARGS="--replay runs/<dir>/run-01.jsonl"  # replay a logged run without the model
make desktop ENDPOINT=typesafe
```

On the TV (Developer options and network debugging on):

```bash
make tv-connect IP=<tv-ip>
make tv-run
make tv-logs
make tv-profile # release-like build; judge performance on this
```

To drive it with a model on the TV, `make tv-push-config` copies `config/endpoints.yaml` and the
API keys it names (from your shell) to the app's files on the device, then
`make tv-run ENDPOINT=typesafe`. **Dev only:** the key then sits on the TV where anyone with adb
access can read it. `make tv-clear-config` removes it; `make tv-pull-logs` fetches the decision
logs.

Remote: OK pauses, Back toggles panel detail (it never exits; Home does), up/down scroll the
event list, right/left step one tick / one second while paused.

## License

MIT
