---
title: "No commissions: he draws what he likes, when he likes; and what he couldn't do reaches what he writes looking back"
date: 2026-11-17
status: active
supersedes: 2026-10-31-commissions-are-judged-by-the-shape-drawn.md
---

# No commissions

On the live run (days 47-49, Opus 5.5 deciding) nearly every thought, reflection and self-model line
was about the day's request: the request event, the `commission` field in every observation, the
art line in the system prompt and the painter's "the customer's request" option kept it in front of
him all day, and he judged each day by whether he met it. Ian: commissions are gone.

- **Nothing asks him for a piece.** No daily request, reward, wording (`CommissionVoice`), prompt
  line, observation field, painter option or protocol field. Art is drawn when he chooses a verb
  that draws, on a subject of his own choosing, and sold at its price. The economy loses the $5 a
  day the request paid; the numbers in `2026-11-15-art-gets-him-by-machines-get-him-ahead.md` are
  otherwise unchanged.
- **The subject lists stay**, as rules.yaml `subjects` (`SubjectRules`): the painter's menu of
  subjects is drawn from them, and the first piece of a noun is new to him (novelty), as
  `2026-11-15-fun-drains-in-proportion-and-new-subjects-are-new.md` says.
- **Old data reads as before.** Checkpoints with `commission`/`commissions`/piece `commission` load
  (unknown keys are ignored) and drop them; the `art_pieces.commission` column stays for old rows and
  is null from now on. The protocol's `Gallery.commission`, `PieceView.commission`, `CommissionView`
  and `DrawingView.commission` are gone without a version bump: the web renderer never read them,
  and the TV viewer is rebuilt later.

# What he couldn't do reaches what he writes looking back

The deciding call sees every verb's lock and why ("sketch: you're bored, and it's all familiar to
you"), and writes his in-the-moment thought in the same call. The reflection, stretch, day and
self-model calls saw only what he did and thought. So "then the sailboat" followed by tea, a walk
and a lamp read to them as stalling, and his self-model came to say he freezes at the threshold of
real work, when paint and sketch were locked in 35 of his 37 decisions.

- **Crossing the bored lock is news.** When stimulation goes under `bored_lock.below` and that locks
  anything, the sim says so and names what ("you've grown bored (stimulation 38, under 40): what
  you've done a lot lately is refused until something fresh lifts you: sketch, paint, devise"), and says
  when he's back over. It's an ordinary world event: the deciding model sees it in `recentEvents`,
  and every memory writer sees it in the log.
- **A reflection is told what's out of reach now** (`Describe.outOfReach`): each lock reason his
  options show that locks more than one verb, with those verbs, from the observation the deciding
  model last saw.
