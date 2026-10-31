---
title: "Stimulation, habituation and novelty; mood is the summary"
date: 2026-10-31
status: active
---

# Stimulation, habituation and novelty; mood is the summary

Step 1 ("cap fixes") of `research_notes/2026-10-30_1951_world-grammar-consolidated-plan.md`. The
point is rewards that don't run out, so "earn until the catalog is bought out" stops being the
whole game.

- **A fourth need, `stimulation`** (low = bored). It drains `stimulation_per_hour` (-3) while he's
  awake. Activities feed it through ordinary `effects`; there is no special case. It's in
  `NeedLevels` (default 60, always encoded, so older content and logs still load), the observation,
  the closed-choice state, memory event text, hour samples and both HUDs ("fun" in the room card).
- **Mood is the summary.** Below `bored` (40), each missing point of stimulation lowers the mood he
  drifts toward by `bored_mood` (0.4, so 16 at 0). Only lowers it, as the plan says. Hobbies that
  pushed mood directly now mostly feed stimulation (read mood +6 → mood +2, stimulation +15;
  listen +10 → +4, +12; music +4/h → +2/h, +6/h). Sketch and paint keep Ian's mood costs and get
  a little stimulation; journal keeps mood +12 until frustration arrives (step 3).
- **Habituation** (`habituation:` in the content). Each habit has a wear from 0 to 1: finishing it
  adds `per_repeat` (0.25), and wear fades `recover_per_hour` (0.04). Positive mood and
  stimulation gains (effects, rates, background) are paid at 1 - wear; costs and body needs aren't
  scaled. Finishing one that was already worn also costs `stale_stimulation` (-6) times the wear,
  which is how "repeating drains him faster" is done. Wear is computed lazily from the tick it was
  set, not stepped per minute.
- **Novelty** (`novelty:`). The first finish of a habit pays `effects` (stimulation +15, mood +4).
  Count-based for now; embedding distance from memory is later.
- **A habit** is the activity id, or for a use the use id plus the item (each record is its own).
  `read` can't tell books apart yet; that waits for the library in step 3.
- **`routine: true`** on eat, sleep, sell_art and hang_art: upkeep neither wears thin nor counts as
  new. Default false, so anything added later (including invented verbs) habituates.
- **What the model sees.** `gives` shows gains at current freshness, "done a lot lately: gains at
  60%, and stimulation -2" and "new to you: ...". The system prompt gets the stimulation rule and
  a "Variety" line; the closed-choice instructions get one sentence about boredom.
- **Not done:** curiosity as a per-run trait (waits for traits/affinities in step 2), stepped rent
  (optional in the plan), the uncapped-run flag (needs `invent`).
- The scripted baseline does nothing new, so it sits at stimulation 0: over six days its mood ran
  about 28-50 (lowest 26.6, just above the art block at 25) and it still feeds itself.
  `BedroomRunTest` and the CLI's `ticks_at_zero` leave stimulation out of "a need reached 0".
