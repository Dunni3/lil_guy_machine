---
title: "Keys on the TV come from a pushed secrets.env (dev only)"
date: 2026-10-29
status: active
---

# Keys on the TV come from a pushed secrets.env (dev only)

The TV has no shell environment, so `make tv-push-config` copies `config/endpoints.yaml` and a
`secrets.env` holding the variables its `api_key_env` entries name (taken from the developer's
shell, never from a file in the repo) to the app's external files dir. The app reads keys only
from there. Anyone with adb access can read that file, so it's for development only, with
low-limit keys; `make tv-clear-config` removes it. The endpoint is picked with
`make tv-run ENDPOINT=<name>` or the config's first `agents` entry.
