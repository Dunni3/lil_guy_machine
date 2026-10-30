---
title: "Keys live in ~/.config/lil-guy-machine/secrets.env, loaded by the Makefile"
date: 2026-10-29
status: active
---

# Keys live in ~/.config/lil-guy-machine/secrets.env, loaded by the Makefile

One file outside the repo (mode 600) that every worktree sees, holding `export NAME=value`
lines. The Makefile `-include`s it, so `make cli`, `make desktop` and `make tv-push-config` get
the keys in their environment. The app's own env-file reader accepts the same `export` lines.
Nothing prints the values.
