---
title: "Bedroom content is one YAML file on the classpath"
date: 2026-10-29
status: superseded
---

# Bedroom content is one YAML file on the classpath

Superseded by [the grammar redesign](2026-10-31-verbs-are-ops-with-arguments-authored-ops-and-think-is-a-verb.md): the content is six files with generated schemas.

`sim/src/main/resources/content/bedroom.yaml` holds the room (rows plus slot letters), the start
state, need rules, items, activities and uses. It's read with kaml (now also a `:sim` dependency;
pure JVM, already used on Android by `:decision`) in strict mode, and `BedroomContent` checks
cross-references at load. A job, a hobby, a purchase or decor is a row; `BedroomWorld` only knows
the fields: `at`, `requires`, `consumes`, `produces`, `sells`, `effects`, `rates`, `asleep`, `until`,
`stop_below`, `block`, `work`, and for uses `item`, `on`, `background`.
