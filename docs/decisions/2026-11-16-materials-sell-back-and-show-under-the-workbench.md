---
title: "Materials sell back for about half; his stock shows under the workbench; copper parts are drawn as wire; anything he invented can be taken apart"
date: 2026-11-16
status: active
related: 2026-11-15-inventions-are-built-from-bought-materials.md, 2026-11-15-wares-sell-for-less-the-more-he-sells.md
---

# Materials sell back, show under the workbench, and copper is wire

**Context.** PR #80 made inventions cost bought materials (wood, metal, copper wire, glass) and added
`dismantle`, which gives back half a thing's bill and frees its spot. Its "Not done" list left out:
selling materials back, taking apart things that don't stand anywhere, copper parts drawn thinner
than their designed block, and `world.make`'s API line reading the materials from the content. Nothing
in the room showed how much he had to build with.

**Decision.**

- **Selling back.** rules.yaml `invent.sell_back` (0.5) is the share of a material's unit price (its
  pack's price over its pack size) the shop pays for one, in whole dollars and at least 1
  (`InventRules.resale`). On the default content that's a wood plank $1, and a metal bar, a copper
  wire coil or a glass pane $2 each. The rounding makes metal and glass come back at 67%, which is
  still a loss on every round trip. 0 or no key, and the shop takes none back.
  - The content sets `ItemSpec.resale` on each material when it loads (`BedroomContent.resold`), and
    `ItemSpec.sellPrice` falls back to it. A material then answers to the tag `material`
    (`MATERIAL`).
  - So selling goes through the normal sell path: the `sell` op, `saleText`, and `world.sell` in his
    code. No ware pricing changes. PR #78's market covers wares only, so materials keep a flat price
    under it.
  - The verb is `sell_materials` (kind economic, at the mail hatch, `args: { what: material }`,
    `sell: what`). It has one option per material he holds and sells all of that one. Like
    `dismantle`, it's dropped where nothing can be built (`BedroomContent.unlocked`), so a run
    without a workbench never sees it. Jev finds it under menu.yaml `workshop`.
- **Taking apart what doesn't stand anywhere.** The `invention` tag now answers to any thing he
  invented and holds, records and wares included. A held thing gives back half its bill as a standing
  one does. A thing built before materials has an empty bill and gives nothing back.
  - A general rule in `BedroomWorld`'s planning makes this work: an `at` that names an argument bound
    to a thing with no spot is skipped, and with nothing left the verb is done where he is. So a
    record is taken apart wherever he is.
  - A record that's playing stops.
  - The verb's phrase is now "for half its materials back and any spot it took".
- **His stock, shown.** The web draws what he holds of each material on his workbench's lower shelf,
  wherever the workbench stands (`room/view.ts` `syncStock`, `room/parts.ts` `stockShown` and
  `stockParts`). It's built from the counts viewers already get (`LifeState.wallet.owns`), so the
  protocol is unchanged.
  - The pile is wood planks stacked at the back, metal bars piled front left, copper coils in the
    middle, and glass panes leaning on the right leg.
  - Each piece drawn stands for 2 units, up to a shelf's worth (8 planks, 6 bars, 4 coils, 6 panes).
  - Each piece is drawn in its own material, through the same `SURFACES` as invented parts.
  - It's built again only when what's shown changes, so it adds no per-frame work.
  - Nothing is drawn without a workbench or without materials.
  - The pile's look lives in the web, not in `parts/` part lists, because it's made from counts. The
    TV viewer (`:app`) doesn't draw it.
- **Copper is wire.** A part whose material is `copper_wire` is drawn as wire of a fixed gauge
  (0.009 m radius) inside its designed box (`clay/geometry.ts` `wire`):
  - a ring (a cylinder with a hole of at least half, flatter than half its width) is a loop of wire,
    keeping its arc;
  - a long thin shape (one side at least three times the next) is a straight length of wire;
  - anything else is a coil wound round its long axis (a cylinder's or capsule's own), its turns
    2.6 gauges apart, at most 14 turns.

  The geometry is built once per model and merged like any part, so it adds no passes and no
  per-frame work. The bill is still by the designed volume: a copper block costs its block's worth
  of wire.
- **`world.make`'s line reads the content.** `ScriptApi.reference(content)` fills in `make`'s uses
  and materials from the content's `invent` rules (`ScriptApi.makeDoc`). The coder's brief and the
  oracle pass their content.

**Why.**

- Selling back closes the loop #80 opened: a full world can be taken apart and sold for money as
  well as space, and leftovers aren't dead weight.
- Taking apart held things makes every invention reversible.
- The pile and the wire make the material economy visible to viewers without any new protocol.

**Consequences.**

- The `at` fallback applies to every verb, not just `dismantle`: any verb done at an argument bound
  to a thing that stands nowhere is done where he is.
- A run with a workbench and materials gains one `sell_materials` tool per material he holds, about
  200 characters of tool definitions each (see the PR for the measured sizes).
- The rounding in the sell-back knob means the share actually paid isn't exactly 0.5.
- Price changes to materials in items.yaml change their sell-back price with them.
