---
title: "Warmth, sound, water and lures are effects on the same engine as light; his code reads them with field() and gives them off with emit()"
date: 2026-11-15
status: active
---

# Warmth, sound, water and lures are effects on the same engine as light

**Context.** Light (`2026-11-15-light-is-an-effect-with-reach-the-sim-computes-on-tiles.md`) made
where a thing stands matter for what he sees and can do. Phase 4 of the world plan
(`research_notes/2026-11-15_1451_effects-with-reach-world-plan.md`) adds more kinds with the same
shape, a source, a reach and readers, so placing a heater, a feeder or a planter changes something
real, with no made-up cost. His code could only pretend (his pinecone lamp "glows softly every
hour" by adding a point of mood).

**Decision.**

- **One engine, more kinds** (`sim/.../Effects.kt`). `EffectKind` gains `warmth` (degrees),
  `sound`, `water` and `lure`. Each kind says what each tile lets through (`passes`): walls stop
  light, warmth, water and lures; trees stop light; **sound gets through a wall at 0.35 and carries
  out of a room** (`keepsToItsSide` is false only for sound), so a record in the bedroom is faint in
  the garden and reaches the next room. Falloff, caching and the field type are light's.
- **The new kinds live in `sim/.../Surroundings.kt`**, not `BedroomWorld`, behind a small `Host`
  interface: the fields, their readers, planters' soil, visitors, and what his code gives off.
- **Warmth.** The baseline is the weather's temperature outdoors and the house's own indoors
  (weather.yaml `temperature.indoors`, losing `leak` 0.25 of the gap to outside, about 16° on a cold
  night). Items with `warmth: { strength, radius }` add degrees (heater 6 over 4 tiles, fire pit 12
  over 3); one that stands nowhere (the blanket) warms him wherever he is outdoors. Readers:
  - the cold, `weather.cold { below: 8, mood_per_hour: -4, energy_per_hour: -2 }`, now read from the
    warmth on his tile (raised from 6, which seed 0's nights never reached);
  - sleep: rules.yaml `sleep { cold: 16, noisy: 2, rest: 0.8 }`, where a cold or noisy bed gives back
    0.8 of sleep's energy;
  - verbs: `needs: { warmth: N }`, checked like light, so explore needs 4°;
  - the station logs the warmth at its own tile, so a fire beside it shows.
- **Sound.** Items with `sound` (the record player only while a record plays on it, `playing: true`;
  the wind chime always) are heard at rules.yaml `sound.hear` (1) or more. The music's mood and
  stimulation gains are paid in proportion to what reaches him, in full at `full` (4), so music is no
  longer house-wide. A nice thing he can hear counts toward comfort as if he saw it.
- **Sun and water for growing things.** A planter (`grows: { yields, hours, count }`, the `growable`
  affordance) counts an hour when its soil is damp and the **sun** on it (daylight and windows from the
  light engine, no lamps) is rules.yaml `growing.sun` (7) or more. That is true by the bedroom's
  window or outdoors on a clear day, but not in its far corner (6.5 at noon) or under cloud outdoors.
  Rain (water baseline outdoors, mm in the hour) and spray (water emitters) wet the soil, and the
  `water_plant` verb (needs a watering can) fills it. `harvest` picks its yield. The harvest lock says
  how far along it is and why it isn't growing.
- **Lures and visitors.** world.yaml `visitors` (birds, moths, fireflies) come to the things whose
  `lure: { draws, strength, radius }` draws them (a feeder, the porch lantern, the reeds at the pond),
  when that thing's tile meets the visitor's `hours`, `above` and `below` (each an effect: moths need
  light 3 and 6°, so the porch lantern, a `lamp` on a 19:00-02:00 timer, draws none once switched off; birds daylight with no rain and no loud music, fireflies 10° and dark). The `watch`
  verb (perceptual) at a lure yields a sample (feather, silver moth, jar of fireflies), and they stay
  away `rest_hours`. Their coming is an event filed under its place. A weather station within a
  lure's reach logs how many came each hour under the visitor's id. Visitor finds are not
  world.yaml `finds` patches (those are hidden until explored); a lure the shop sells is known when bought.
- **His code.** `world.field(spot, kind)` reads light, warmth, sound, water or a visitor's lure at a
  spot, a thing of his, or `self`. `world.emit(id, kind, strength, radius)` has a thing of his give
  off light, warmth, sound or water until he changes it (0 stops it). Strength is capped at the kind's
  ceiling and the radius at 20. Emissions are real: they join the fields as emitters (light through
  `lightField`), are saved in `BedroomState.surroundings`, and are undone with the rest when his code
  breaks. Lures can't be emitted, so content decides what draws visitors. The canned sprinkler sprays
  water 3 over 2 tiles while it runs.
- **The prompt says an effect only when it limits or explains something.** `Observation.senses`
  holds at most a few lines: cold where he stands, music he hears faintly or not at all, a bed that
  would sleep badly (from 19:00). Visitors are not said there: the watch option says who is at a lure,
  and the arrival is an event. The system prompt gets one line, "Round you", built from what the
  content has.

**Cost.** Scripted 3-day runs (characters / 4, default content, against `origin/main` f0f887f): the
system prompt grows by 315 characters, about 79 tokens, the same on every call and so cacheable.
Activities grow 37 characters (about 9 tokens) a decision: one locked `watch` line. `senses` was on 4%
of decisions in a run where he owned everything new and went out at night, at about 15 tokens when
present (under 1 a decision). Without shopping trips (the scripted policy, or no shopper configured),
the eight new items add about 280 tokens to `shop`; with trips, as live, deciding models don't see
the shop.

**Not done.** The TV app has no models for the new items. A breeze for the chime, shelter from
rain, and his code emitting lures are possible next steps. Upkeep (seed for the feeder) is Phase 5's.
