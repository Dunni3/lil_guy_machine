---
title: "He writes letters back, and on his own, to whoever has written to him"
date: 2026-11-15
status: active
---

**Context.** Letters reached him (`2026-11-15-anyone-with-an-account-can-write-him-letters`), but he only
read them: the words went into an event, so at most they surfaced in a later thought. Ian wants him to
answer letters properly, and to write first too, without being written to.

**Decision.**

- **`write_letter`, a `social` verb at the desk** (30 minutes, not routine, so it wears like a pastime).
  Its `write: { with: letter, timing: before }` has the writer write the letter before he sits down,
  staged in the sim like a shopping basket; its `post_letter` op puts it through the hatch as he
  finishes. No letter, or one to someone he can't write to, fails the verb.
- **Only to someone who has written to him.** A letter carries its sender's account id (`Letter.sender`,
  never the email) from the host, so he writes back to the same person whatever name they sign. He
  can't write to strangers or invent recipients.
- **The deciding model chooses when.** Replying and writing first are the same verb. The option says
  "Sam's letter is waiting for an answer" while one is, and "write to Sam, who wrote to you before" when
  none is, so a reply is put to him but writing first is up to him.
- **How often: rules.yaml `letters`.** At most `per_day` (1) a game day to each person, and at most
  `unanswered` (2) in a row before they write back, so nobody gets a pile. A letter is at most
  `max_length` (1000) characters, the same as theirs.
- **The writer answers what was said** (`agent/.../mail/LetterWriter.kt`). It sees the decision prompt
  plus every letter between him and each person he may write to now (the newest 8 each, up to 4 people,
  those waiting for an answer first), quoted in full. It's told to answer point by point, then tell them
  something real from his days. It picks who to write to from an enum. A text model only: with a
  closed-choice writer the verb isn't offered. Tokens are metered as `letter`.
- **He remembers what he wrote:** a kept letter is an `EventKind.Letter` event in his words. The opened
  letters and the ones he wrote are in `BedroomState` (`read`, `sent`), so a save keeps the exchange.
- **The host keeps his letters for the account they're to** (`his_letters` in accounts.db, one per run
  and `seq`), and `GET /v1/lives/{life}/letters` returns them as `replies` beside yours. The web panel
  shows both in one thread, and the button says "A letter from him!" when one comes (it checks every
  minute while signed in and closed).

**Not done.** No email when he writes: there's no mail sending, and the email isn't confirmed. A run left
for an earlier save may write letters whose `seq` the host already has; those are skipped.
