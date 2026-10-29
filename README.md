# lil guy machine

A little guy lives in a little room. Every choice he makes, when to eat, when to sleep, what to
make and sell, is made by a model: an LLM behind any OpenAI-compatible endpoint, or a scripted
policy. He runs on a desktop window or ambiently on a TV (a Google TV Streamer, for now).

[<img src="https://www.buymeacoffee.com/assets/img/custom_images/orange_img.png" alt="Buy Me A Coffee" height="41">](https://www.buymeacoffee.com/iandunn)

## The idea

His world is small and a little tight. A day has a clock, his needs (energy, fullness, mood) run
down, and food costs money. He earns it by making art and selling it at the door. That takes time
and energy, and energy only comes from being fed, so every hour he spends is a trade.

He is told he is bigger than his world: intelligent, creative, capable of a full life, stuck for
now in a room where he can only eat, sleep, work and write. He keeps a **journal**, and the one
who made his world reads it. Things he wants may be granted, but only if he writes them down and
argues for them. The journal is the only door out. Writing in it costs the same hours and energy
as staying alive, so he has to budget for wanting more.

He also **remembers**. What happens to him is recorded, summarized into memories in the
background, and the relevant ones (and his own journal entries) come back when he decides what to
do next. Over a long run, the hope is that he becomes someone: with interests, plans and a history,
and a world that has grown because he asked for it.

## Where it stands

- **Working:** the bedroom life sim (clock, money, needs, a data-driven list of activities and a
  shop), decisions from a scripted policy or a model, long-term memory v0, the tighter economy,
  the persona and the journal described above, and a 2D dev view on desktop and TV for watching a
  run, stepping back through his decisions, and reading his memories and journal.
- **Later:** personality (interests, skills, wants of his own), granting requests to grow the
  world, and a proper 3D renderer in place of the dev view.

For the details: the original design spec is
[docs/agent-world-spec.html](docs/agent-world-spec.html) (its milestones have since been
reshuffled), and [docs/DECISIONS.md](docs/DECISIONS.md) logs every design decision along the way.

## How it fits together

```
:android ─┐
:desktop ─┴─> :app (libGDX) ─> :agent ─┬─> :sim       (pure Kotlin world)
:cli ────────────────────────> :agent  ├─> :decision  (models and their typed replies)
                                       └─> :memory-sqlite (his long-term memory)
```

The sim knows nothing about models or graphics; the agent layer joins them, and model calls never
block the world. The world's numbers (prices, activities, items) live in one content file,
[`bedroom.yaml`](sim/src/main/resources/content/bedroom.yaml).

## Quick start

Needs JDK 17 and, for the TV, the Android SDK (platform 34).

```bash
make test                                       # unit tests
make cli ARGS="--scenario bedroom --days 3"     # headless run, scripted policy
make desktop                                    # watch it in a window
```

To use a model, copy `config/endpoints.example.yaml` to `config/endpoints.yaml` (gitignored) and
put the API keys it names in `~/.config/lil-guy-machine/secrets.env` as `export NAME=value` lines
(the Makefile loads it). Then add `--endpoint <name>` to `make cli` or `ENDPOINT=<name>` to
`make desktop`. On a TV with network debugging on: `make tv-connect IP=<tv-ip>`, then
`make tv-run`. The [Makefile](Makefile) lists the rest.

## License

MIT
