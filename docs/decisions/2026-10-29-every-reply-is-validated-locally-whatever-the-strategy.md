---
title: "Every reply is validated locally, whatever the strategy"
date: 2026-10-29
status: active
---

# Every reply is validated locally, whatever the strategy

`ActionSchema.decode` checks JSON syntax, then the schema (via `SchemaValidator`, the same
subset), then decodes. Its errors are written to be sent back to the model ("intent.itemId:
"gold" is not one of "key", "apple""). json_schema, tools and text replies all go through it,
because servers that claim schema support don't always enforce it. A rejected reply is retried
with the error at most twice (`max_retries`), then `DecisionFailed` makes the agent loop submit
its fallback, `wait 20`. Retries are inside one decision, so lockstep runs stay deterministic.
