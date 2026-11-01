---
title: Jev walks a menu tree by beam search instead of picking from one flat list
date: 2026-11-01
status: active
---

**Context.** Jev picked each decision from one flat Choice over every open activity and purchase.
As the world grew (books, records, decor), that list got long, Jev leaned on its early entries, and
some options were never chosen at all: in a day of 28 decisions he never picked ask, listen or a
record, though they were offered every time.

**Decision.** The options come in a tree, and Jev walks down it.

- `content/bedroom/menu.yaml` is the tree: nodes (`upkeep`, `art`, `leisure` > `music` and
  `books`, `room`, `mind`), each with `says`, a description of what's under it, which is all Jev
  sees of a node until it opens it. A verb sits in at most one node. A purchase sits in the node of
  the first verb its item is for (a record with play_record), else the node whose `buys` names its
  item group (decor). Waiting stays at the top. The bedroom's choice set carries the tree as
  `ChoiceSet.groups` and each option's `group`; other backends ignore them.
- `decision/.../BeamSearch.kt` walks it. At each depth, one request to Jev holds one Choice
  question per path still at a group, each over that group's children (a group described by `says`
  plus the labels open under it). A path's score is the product of its picks' probabilities, and
  the best `beam` paths go on. When every path ends at an option, one last question over just those
  finalists picks the action. An empty group is dropped and a group of one is its option, so no
  question has one answer.
- `beam` on the TypeSafe endpoint sets the width (default 3); `beam: 0` puts the flat list as before.
- Every question carries the path to it (amended 2026-11-01). Inside a group, the question names
  every group settled on from the top ("leisure, and within that, books"); the last question
  prefixes each finalist with the groups it came through ("From leisure > books: ..."). The whole
  state (needs, activities with their payoffs, self-model, memories, latest thought) goes with
  every request, as before.

**Consequences.** A decision is three to five requests instead of one (a few hundred ms each); the
state is sent with each. Adding content means placing new verbs in `menu.yaml` (unplaced ones sit
at the top, which works but crowds it). The raw log line for a decision is the finalists, the last
answer and each depth's probabilities. The decision layer stays generic: any `ChoiceSet` with
groups can be walked.
