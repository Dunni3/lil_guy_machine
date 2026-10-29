---
name: research-note
description: Write a research note in research_notes/ recording whatever the user describes (a feature just built, a decision, debugging findings, device/ADB discoveries). Use when the user runs /research-note or asks to record/log/write up something as a note.
argument-hint: <what to record, e.g. "describe the work we just did">
---

# /research-note

Write one markdown note in `research_notes/` following the format in CLAUDE.md ("Research notes"). The user's request: **$ARGUMENTS**

## Steps

1. **Gather the content.** Interpret the request against this conversation first; it usually refers to work just done. Fill gaps with targeted reads: `git log`/`git show --stat` for commits, the relevant files, existing notes (`ls research_notes/`) to link related ones. If the request is empty or too ambiguous to act on, ask one short question.
2. **Get the timestamp** from the shell, don't guess: `date +%Y-%m-%d_%H%M` for the filename and `date +%Y-%m-%dT%H:%M%z` for `date:` (insert the colon in the offset, e.g. `-04:00`).
3. **Write** `research_notes/<YYYY-MM-DD_HHMM>_<kebab-slug>.md` (create the directory if missing; slug is 2-5 words). Frontmatter must include `title` and `summary`; also fill `date`, `author` (your model name, or the human if they dictated it), `tags`, `related` (commit hashes, file paths, other note filenames), and `status`.
4. **Body:** context first, then the substance: what and why, how it works (with `file:line` / commit refs), decisions and rejected alternatives, gotchas, open questions / follow-ups. Be concrete and skimmable (headings, short bullets). Record what's true, including mistakes and things left unverified.
5. **Never include** secrets, `.env` contents, device tokens/pairing codes, or API keys.
6. If the note records a decision, also add the entry to `docs/DECISIONS.md` (the note is the narrative, DECISIONS.md is the ledger).
7. Tell the user the note's path and its one-line summary. Don't commit unless asked.
