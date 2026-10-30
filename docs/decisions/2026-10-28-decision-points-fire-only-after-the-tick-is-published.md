---
title: "Decision points fire only after the tick is published"
date: 2026-10-28
status: active
---

# Decision points fire only after the tick is published

A finished intent raises the decision point after `step()` publishes that tick's observation,
not when the intent finishes mid-tick. Raising it earlier let an agent on another thread wake
and read the previous tick's observation (the dev view showed a second `pick_up` of an
already-held key). Lockstep runs couldn't show this because they are single-threaded.
