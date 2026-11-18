---
title: "He writes his own verbs, and what his things do, as JavaScript against a world API; nothing caps it"
date: 2026-11-15
status: active
---

# He writes his verbs and what his things do, as code

The "Uncapped" part is superseded by [machines are fed and wear out](2026-11-15-machines-are-fed-and-wear-out-instead-of-uncapped.md): his code makes nothing from nothing, and machines have inputs and upkeep.

**Context.** In the live life on day 18 he owned all 35 catalog items and had $2,510, and he painted
for the commission every day. He had invented things (a moss terrarium and others), but they only
sat in the room: an invention is one item with one use, and with the house's comfort full, something
to look at gives nothing. Ian: he could be doing so much (making things, new verbs, customizing his
world), and the things he invents should have a function, be interactive, and affect the world
around them. Ian's calls (2026-11-15): verbs trigger code he writes against an API for how verbs
interact with the world and objects; JavaScript; **uncapped**; the API covers his needs, skills and
things, state stored on objects, customizing the room (move, recolour, rename), and making things.

This is rung 3 of the world-grammar plan (items, verbs, rules or scripts). It skips rung 2 (verbs
assembled from the content's ops within a kind's contract) because code is the more general form.

**Decision.**

- **Code runs in the sim, sandboxed** (`sim/.../Scripts.kt`): Rhino 1.7.15 in interpreted mode
  (pure Java, runs on Android), ES6, safe standard objects only, a class shutter that refuses every
  Java class, no `Date`, and at most 2,000,000 instructions a call. A loop that never ends breaks
  the verb; it can't hang a tick.
- **The world API is one table** (`ScriptApi.FUNCTIONS`), each entry with its signature, its doc line
  and whether it writes. The coder's brief and the oracle's docs come from that table, so what he's
  told is what there is. Reads: time, needs, money, skills, owns, thing, catalog, spots, here,
  state. Writes: need, earn, spend, practice, give, take, make, setState, move, recolor, rename,
  and say. Arguments are checked, and a bad call throws a JavaScript `Error` with a reason he can act on.
- **Uncapped.** Code can add money, set needs and give him things. The only limits are
  physical ones: things go in spots that exist, art comes from drawing, fixtures belong to the house,
  names are unique, and state is small (20 keys a thing). The one rule kept from the world is that a
  verb's mood and stimulation gains shrink as it wears thin, like any verb's.
- **A verb of his** is a `VerbSpec` of a new kind, `scripted`, whose one op is `script`, with
  its source on `VerbSpec.code`. `canDo(world)` is its lock (a string back is why not; it may only
  look); `finish(world)` runs as he finishes, and what it changed and said is the outcome. Code that
  throws fails the verb with the error and line; what it changed before that stays changed.
- **A thing's behavior** is `hourly(world, thing)`, run every game hour while he owns it. What it
  changes is a world event ("your music box: stimulation +2"). After three broken hours in a row it
  stops until he writes it again. Behaviors live in the world state, not the content, so the
  world's own things (the guitar) can have them too.
- **Where code comes from.**
  - `devise` (expressive, at the desk, 60 min): a coder writes a `CodeDesign`, which is new verbs or
    rewrites of his (named the same), and behaviors for any of his things. `define` checks it and puts in what passes.
  - `invent` now also writes the thing's function: once the design is settled, the coder writes its
    verbs and behavior, naming the thing `this`, and `build` puts them in with the thing. That is how
    every invention gets a function.
  - The coder (`agent/.../code/Coder.kt`) is the deciding model when it's a text model, else the writer.
    It checks its design against the sim's rules and redoes it once with the reasons, as the inventor does.
- **The content grows during a run, as with inventions.** His verbs (`BedroomState.coded`), object
  state (`thing_state`), customizations (`custom`: slot, name, colour) and behaviors are saved with
  the world and put back after the inventions on a restore. Old checkpoints load with none.
- **The protocol** carries an entity's `color`. The web tints each part of its model with it and
  keeps the parts' light and dark. Moves and renames reach the web through the entity's position
  and the content as before.

**Consequences.** The system prompt gains a "Code" line. His verbs reach tool models as tools,
described by what they say they do. Jev's menu doesn't list them yet. A run can now be broken by
his own code, for example by printing money, and that's intended: this is the uncapped experiment.
Replays stay deterministic because code sees only the game clock.

**Not done.** Code that runs each minute while a verb runs; verbs that take a place or a second
thing (`on`); his verbs in Jev's menu; a cap or audit mode if uncapped turns out to be too much.
