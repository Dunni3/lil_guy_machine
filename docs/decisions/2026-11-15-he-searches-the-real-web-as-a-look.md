---
title: He searches the real web as a look, read-only, through Gemini grounded on Google Search
date: 2026-11-15
status: active
---

**Context.** Everything he could learn came from inside the room: his memories (`recall`), the
world's docs (`ask`) and books. Ian wants him to get new information from the real internet,
read-only for now, and to see every search in the viewer's menu with what he was thinking.

**Decision.**

- **A look, not a verb.** For a model that calls tools, `search` is a third look
  (`ToolSet.looks`, `BedroomTools.looks`), like `recall` and `ask`: it takes no game time, is
  answered with what the results say and which sites said it, and counts toward the turn's
  `maxLooks`. Jev and the scripted policy don't search. Nothing he reads can act on the room.
- **The backend is pluggable** (`decision/.../WebSearch.kt`): `gemini`, a Gemini model with the
  `google_search` tool that writes what the top results say (under 120 words, no advice) and names
  the pages from its grounding; or `brave`, the Brave Search API's results as they are. Gemini is
  the default because the key is already there. Grounding's links are Google redirects; each is
  followed once (a HEAD, 5 s) so the viewer shows the real page.
- **Only grounded replies count.** Told only to "search", flash-lite answered one query in three
  from what it knew, with made-up sources; the prompt now says always to run the search and answer
  only from the results (3 of 3 on both models after that), and a reply with no pages behind it is
  asked once more, then the search fails rather than pass memory off as the web.
- **He's reminded the web is there.** Left alone, flash and flash-lite never searched in a dozen
  decisions or more (flash asked the oracle instead): his status says nothing that points outward.
  Until he has searched that day, his turn ends with a line asking what he's wondering about and to
  search it (`BedroomTools.SEARCH_NOTE`, `PromptBuilder.ToolTurn.note`). With it, gemini searched at
  his first decision and drew on what came back.
- **Configured in `config/endpoints.yaml`'s `search:` block**; absent or `kind: none`, he can't
  search. `LILGUY_SEARCH=none` (host) and `--search none` (CLI) turn it off for a run. `per_day`
  (20) caps searches a game day: past it the look answers that he's searched enough, and nothing is
  sent. Searches are metered as purpose `search`, with `price_per_search` on top of tokens.
- **What he finds stays with him.** A search that found something is a `search` event in his
  words ("I looked up ... online: ...") for summaries and recall, and his latest two are in
  `found_out` beside his latest answers.
- **The viewer sees all of it.** `AgentMemory.searches()` (kept in checkpoints) goes to viewers as
  the protocol's `logs.searches`: the query, what came back and from where, or why not; and once he
  has chosen, the `thought` he chose with and what he did `then`. The web menu's WEB tab lists them;
  THOUGHTS marks the thoughts that came after a search.

**Consequences.** A grounded search is two model calls' worth of latency (a few seconds) while he
stands still deciding. Reading whole pages is left for later: the summary and snippets are what he
gets. Queries go to Google (or Brave) as the model writes them.
