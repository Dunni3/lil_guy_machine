---
title: "Gated tiers and a tighter economy: food takes most of his working day"
date: 2026-10-29
status: active
---

# Gated tiers and a tighter economy: food takes most of his working day

From `research_notes/2026-10-29_1644_journal-and-scarcity-plan.md`. Money never bound (a day's
food was about $16 against $12 a sketch), so nothing he chose had weight, and the prompt gave him
a situation but no self.

- **Tiers, not deletion.** Items, activities and uses take an optional `tier` (default 0). Above
  0 they stay in `bedroom.yaml`, still tuned, but the world only has tier 0 plus the ids in
  `granted` (`BedroomWorld.create(granted = ...)`, `bedroomScenario(granted = ...)`, CLI
  `--grant`). `BedroomContent.unlocked(granted)` is the visible content and is validated like the
  file, so a grant must bring what it needs (paint needs easel, canvas and painting) and a tier-0
  activity can't depend on a gated item. Only gated ids can be granted. Tier 0: eat, sketch,
  sell_art, journal, sleep, and a shop of meals. Granting is how a want gets answered later; the
  mechanism for deciding grants is out of scope.
- **Numbers.** Start money $40 -> $20, meals $18 -> $24 a pack of 3 (food ~$21 a day), sketch
  $12 -> **$3**. The plan proposed $5, but its own budget undercounted: at $5 the survive-only
  policy idled 7-9 waking hours a day, at $4 still 7-8. The target is stated as an outcome, so the
  price was tuned to it: at $3 surviving takes ~7.5 hours of sketching a day and leaves 3-5 spare
  waking hours (averaging ~4.5 over days 2-6, swinging with sleep), and he never holds more than
  about a pack's worth of money. A policy that journals whenever it can is starving by day 3.
  Tests pin both (`BedroomRunTest`).
- **The scripted baseline changed.** It earns only until cash plus held art would buy the next
  pack of meals, then spends spare time per `BedroomPolicy.Journaling`: `Never` (waits; the
  economy's slack), `Spare` (default: journals when rested and fed) or `Always`. It no longer
  saves for the easel, which is gated. It lives 14 days with no need at 0.
- **The prompt is generated from the unlocked content.** Examples, the mood line (decor only when
  there is decor), `uses` and music only when they exist, "for anything you want to own" only when
  the shop sells more than food. It opens with a persona (`Persona.kt`, shared with the journal):
  intelligent, aspirational, confined to a small world, told that the world's creator reads the
  journal and may grant what he writes down with reasons. "The only things you can do are ..." is
  built from the activities' new `phrase` field, so it stays true after a grant. Picking models
  get a short third-person version in the choice set's `situation`.
