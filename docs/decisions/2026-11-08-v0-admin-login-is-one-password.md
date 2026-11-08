---
title: "v0's admin login is one password; GitHub sign-in waits"
date: 2026-11-08
status: active
supersedes: 2026-11-06-admin-sign-in-is-a-signed-cookie-and-commands-need-a-csrf-header-and-origin.md
---

**Context.** GitHub OAuth was built for the admin login. It needs an OAuth app per address, a client
secret and a signing key, for a v0 with exactly one admin. Ian chose to skip it for now.

**Decision.**

- **The admin login is one password,** `LILGUY_ADMIN_PASSWORD`, from the environment. Commands carry
  it as `Authorization: Bearer <password>`. With none set, every command is refused. Anyone can
  still watch.
- **At least 24 characters, or the host won't start.** Nothing limits guesses, so length is the
  protection. `openssl rand -hex 24` makes one.
- **No cookie, so no CSRF checks.** Browsers never add this header by themselves.
- **`GET /auth/me` returns `Viewer` with one field, `admin`.** The debug page uses it to show run
  controls only for the right password, and keeps a password that worked in `localStorage` until
  "forget password".
- **On a server it must be served over https,** since the password travels with every command.

**Accepted.** It's one shared secret with no per-person identity and no expiry. If it leaks, change
it and restart.

**If GitHub comes back.** The code is on the branch `archive/github-admin-sign-in` (commit `0f041c6`), never tested against
real GitHub. `AdminAuth` would need to take the call rather than the header, as it does there.
