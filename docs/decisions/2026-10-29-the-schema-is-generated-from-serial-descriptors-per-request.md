---
title: "The schema is generated from serial descriptors, per request, in the strict subset"
date: 2026-10-29
status: active
---

# The schema is generated from serial descriptors, per request, in the strict subset

`JsonSchemaGenerator` walks the kotlinx.serialization descriptor. The output fits OpenAI-style
strict structured outputs, which most other providers also accept: no `$ref`/`oneOf`, every
object `additionalProperties: false` with all properties required, nullable fields as
`["string", "null"]`. Sealed types become `anyOf` with a one-value `type` enum, which is how the
existing class discriminator already looks on the wire, so the `Intent` type didn't change.
Extras the type can't carry (descriptions, id enums, ranges) come from `SchemaHints`, keyed by
serial name.

The schema is rebuilt every decision from the observation: ids are enums of what's in `nearby`,
and `use.itemId` is the inventory. A variant with no valid id is dropped (no `use` with empty
hands; no entity target with nothing in view). Order of variants follows kotlinx (class name).
