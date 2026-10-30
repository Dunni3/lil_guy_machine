---
title: "The reply is an envelope, `{thought, intent}`"
date: 2026-10-29
status: active
---

# The reply is an envelope, `{thought, intent}`

Models reply with `AgentReply(thought, intent)`, not a bare intent, so their reasoning has a
field (it becomes `Decision.rationale`). `thought` comes first so a model reasons before it
commits, and is a non-null string defaulting to "" so the schema requires it while old replies
still decode. Milestone 5 adds `remember` and `plan` as fields with defaults.
