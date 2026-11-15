---
title: "Anyone with an account can write him letters; accounts are an email and a password on the host"
date: 2026-11-15
status: active
---

**Context.** Ian wants any viewer to be able to send him messages that he finds in a mailbox, but only
after making an account, and an account should be no more than an email and a password.

**Decision.**

- **Accounts live on the sim host,** in their own SQLite file (`LILGUY_ACCOUNTS_DB`, `runs/accounts.db`;
  `/data/accounts.db` on the server), apart from the run database, which syncs to the API. Emails and
  password hashes never leave the host. `host/.../Accounts.kt`.
- **An email and a password, nothing more.** No confirmation email, so the address is only a login
  name. Passwords are PBKDF2-SHA256 (210,000 rounds, a salt each); 8 to 200 characters.
- **Sessions are random tokens sent as `Authorization: Bearer`,** kept as their SHA-256, for 30 days.
  Like the admin's password, a browser never adds the header by itself, so there's no CSRF check. The
  admin login is unchanged and separate.
- **Guessing is slowed per email:** 5 wrong passwords in a row lock that email for 15 minutes. Signing
  in says "wrong email or password" either way; signing up does say when an email is taken.
- **Letters are at most 1000 characters, with no cap on how many** (there was 5 an account a day at first), signed with a name the sender
  picks (at most 40 characters). He sees the name, never the email.
- **They come in through the mail hatch.** The life polls the store every 2 s (at once after a send)
  and, only while a run is going, posts waiting letters into the world (`BedroomWorld.post`), which
  marks them delivered to that run. The world keeps them in its mailbox and in its saves, and drops a
  letter id it already has.
- **He opens them with `read_mail`,** a `social` verb (a new kind) at the mail hatch whose
  `open_mail` op opens the oldest waiting letter: the letter's words go into the finish event, so they
  reach his memory like anything else. Offered only while one is waiting; routine, so letters never
  wear thin. The scripted policy opens a letter as soon as it isn't hungry or sleepy.
- **The sender sees whether he opened it.** The world reports the ids it has opened; the host marks
  them, and `GET /v1/lives/{life}/letters` says waiting, delivered or opened.
- **On the web it's a panel of DOM** (`web/src/ui/mail.ts`), opened by a "Write to him" button, the
  second piece of DOM over the room after the admin's password box. Not shown in broadcast mode.

**Accepted.** Letters are text from strangers that a model reads. The sim quotes them as a letter
from someone outside his world, which is all the guard there is: no moderation and no filtering
beyond length and control characters. Anyone can make many accounts. A letter delivered to a run
that's later left for an earlier save is not delivered again.

**Backed up nightly beside the Postgres dump.** `backup.sh` runs the compose service `sqlite-backup`
(profile `backup`), which mounts the host's volume as the host's user and writes a gzipped copy made
with SQLite's online backup API, so the API container never mounts the accounts.
