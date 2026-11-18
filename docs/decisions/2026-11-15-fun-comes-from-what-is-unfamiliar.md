---
title: "Fun comes from how unfamiliar what he does is, by what it's like, not what it's called; he gets used to what he owns"
date: 2026-11-15
status: active
---

# Fun comes from how unfamiliar what he does is, by what it's like, not what it's called

Amended by `2026-11-17-the-bored-lock-reads-what-he-has-done-lately.md`: the bored lock reads the verb's own
feature, not the whole event, and `stale_stimulation` is −3.

Amends `2026-10-31-stimulation-habituation-and-novelty-mood-is-the-summary.md` and
`2026-11-01-boredom-locks-what-he-has-worn-thin-and-new-things-feed-him.md` for content that has
`familiarity` (the bedroom does). Without it, both stand as written.

**Why.** The live run (life `main`, day 40, 914 decisions, Gemini deciding) had mood pinned at 100,
frustration 0 and stimulation near 50, above the bored line at 40, so nothing pushed him to look
for anything new. He painted most days: each commission sold for about $320 against a $15 canvas.
Wear by verb id faded 0.03 an hour, so a once-a-day habit was always fresh again, and the
first-time bonus was paid per verb id, so he collected it with verbs he wrote that were the same thing
under new names (`bask_in_pinecone_lamp_glow`, `bask_in_mossy_lantern_glow`,
`bask_in_amber_lamp_glow`, `gaze_into_starry_jar`, `gaze_into_terrarium`). Ian wanted fun to come
from what was new in the event stream, whatever the activity is called.

**Events are made of features the sim knows** (`sim/.../Familiarity.kt`, `BedroomWorld.features`):
the verb, its kind, the place it's done in (where the first thing it's done at or with stands, else
where he is), the sky there when it's outdoors, each thing it's done at, with, needs or uses, plus that thing's
group and qualities, the skills it practises (a book's, for reading) and what it makes. Never text he
wrote, which is why this isn't an embedding of the event text: his coded verbs write their own
`says`.

**Familiarity.** Finishing a non-routine verb adds 1 to each of its features' levels, and levels
halve every `half_life_days` (3). A feature's freshness is 1 / (1 + level); an event's is the
weighted mean over families of the mean within each family (rules.yaml `familiarity.weights`; sky
0.5, the rest 1), so three qualities weigh as much as one place.
- A daily habit settles near 0.15-0.2. In the test, sketching goes 100%, 50%, 30%; a copy of
  sketching under another name, never done, is already at 40% or less.
- Listening to a record just after putting it on shares the record and player, so it's 70% fresh, not 100%.

**Looking after his body isn't an experience** (`BedroomWorld.upkeep`): routine verbs, and any verb
that feeds energy or fullness, are neither fresh nor familiar. In a Jev run before this, eating plain oats
grew familiar and cost him 3 stimulation a bowl.

**What it pays.** Its mood and stimulation gains are paid at its freshness (as wear used to scale them),
and every finish pays novelty `effects` (stimulation 8, mood 3) times its freshness, plus
`stale_stimulation` (-6) times 1 minus it. That breaks even around 0.43: what's familiar costs fun,
and what's fresh gives some. The first-time-by-id bonus is gone; the drawn-subject bonus
(`2026-11-15-fun-drains-in-proportion-*`) and `novelty.things` stay as they were.

**Bored lock.** Below 40 stimulation, a verb at 30% fresh or less (`bored_lock.worn` 0.5 → 0.7) is
refused. What he has never done still never locks, nor routine verbs or anything that feeds energy or
fullness. A lock now lasts about a day rather than hours.
- **`bored_lock.keep` (24).** Work stays open until he could buy something new and still keep about a
  day of meals. Without it, the scripted 14-day run starved: bored, with $10 and decor he liked at $10,
  sketching stayed locked for days and the policy never buys anything new.

**He gets used to what he owns** (`comfort.adapt: { days: 14, floor: 0.4 }`): a placed thing's comfort
falls from full when he first has it to 40% over 14 days (`BedroomWorld.usedTo`, `ownedSince`). Hung
art, his collection and rooms don't adapt.

**What models see.** Each option's `gives` ends "fresh to you (gains at 80%), stimulation +5, mood +2"
or "familiar (gains at 20%), stimulation -3, mood +1" (`FRESH` 0.5 is where the wording turns), and the
finish event says what it paid. The prompt's Variety line explains the rule, saying that something done
every day stays familiar and that a new name for the same thing isn't new; the oracle has
`rules:familiarity`.

**Saves.** `BedroomState.familiar` and `owned_since`. A save from before this has neither: familiarity
is rebuilt from his verb tallies (each verb's finishes spread evenly from its first to its last, without
the items each was done with), and everything he owns counts as had since the start. So resuming the
live run starts with his familiar days already familiar, and his room at its adapted comfort.

**Not done.**
- Money. Painting's price still grows 12 dollars an art level with no cap, and nothing he can buy opens up new
  things to do. That's the other half of the diagnosis, and a separate decision.
- The live run hasn't been resumed under this rule, and no text model has been tried: the Gemini
  project's prepaid credits ran out (HTTP 402), which has also stopped the live host deciding since about day 40.
- **Checked with Jev, 3 days from a fresh start, against `main`.** He went further afield (the pond, the forest,
  the garden and the clearing, 37 walks, against 0 on `main`), and every repeat paid less, as meant.
  Fun runs out sooner: lowest stimulation 4 against 26, mood 25-64 against 43-74. A fresh life has little
  in reach, so by day 3 most of what's at home is familiar. Mood 25 is where art blocks, so on a long
  fresh run `half_life_days` or `stale_stimulation` may need to come down. Both runs bought decor
  every day and kept their money near 0, and both had `devise` fail every time under Jev (a separate bug).
- Time of day isn't a feature, and neither is who a letter is to.
