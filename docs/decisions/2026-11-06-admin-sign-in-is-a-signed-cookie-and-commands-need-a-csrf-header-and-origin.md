---
title: "Admin sign-in is a signed GitHub session cookie; cookie commands need a CSRF header and a matching Origin"
date: 2026-11-06
status: superseded
---

**Superseded by `2026-11-08-v0-admin-login-is-one-password.md`.** This was built but not kept for v0.
The code is on the branch `archive/github-admin-sign-in`; this file records its design for when it
comes back.

**Context.** v0 is admin-only, and the admin signs in with GitHub
(`2026-11-06-one-sim-host-state-streamed-browser-renders-admin-only-v0.md`). Phase 2 left
`AdminAuth` as a dev token. Phase 3 builds the sign-in. Narrative:
`research_notes/2026-11-06_1902_github-admin-sign-in-built.md`.

**Decision.**

- **The OAuth web flow with no scopes,** through Ktor's OAuth plugin. `/user`'s public profile
  carries the numeric id. The access token reads `/user` once and is dropped, never stored.
- **The `state` is bound to the browser.** `/auth/login` puts a fresh 256-bit state in a signed
  10-minute cookie as well as in the redirect. The callback refuses any state that doesn't match
  that cookie before it makes a session. (The plugin exchanges the code first. That's harmless,
  since nothing is kept unless the states match.)
- **The session is the cookie, with no server-side store.** It holds the GitHub id, login and an
  expiry (7 days). It's signed with HMAC-SHA256 under `LILGUY_SESSION_KEY`, and set HttpOnly,
  SameSite=Lax, Path=/. It's also Secure with the `__Host-` prefix, except on an http://localhost
  public URL, where browsers may not keep a Secure cookie. Any other http URL is refused.
  - Logging out clears the cookie. A copied cookie stays valid until it expires. Rotating the key
    signs everyone out.
  - Accepted for one admin. A server-side session table is the fix if revocation ever matters.
- **Anyone can sign in; admin is checked per command.** The check compares the session's id with
  `LILGUY_ADMIN_GITHUB_ID`, a number from the environment, never a login name. So the page can say
  "signed in as X, watching", and changing the id takes effect at once.
- **CSRF: a cookie-authenticated command needs both:**
  - `X-Lilguy-Csrf: 1`. A cross-origin page can't send a custom header without a CORS preflight,
    and the host answers none.
  - An `Origin` equal to `LILGUY_PUBLIC_URL`'s.

  SameSite=Lax already keeps the cookie off cross-site POSTs. These two checks are the layers that
  don't depend on the browser's cookie policy.
- **The dev token stays, as a separate path.** `LILGUY_ADMIN_TOKEN` is a bearer header, off unless
  set, and the host prints a `DEV:` line at startup when it's on. It needs no CSRF header, since
  browsers never attach `Authorization` on their own. With neither path configured, every command
  is refused.
- **The protocol has a `Viewer` type** for `GET /auth/me` (`admin`, `login`, `sign_in`,
  `dev_token`), so the web renderer reads it from the generated TypeScript. It's an HTTP response,
  not a stream message, so `PROTOCOL_VERSION` stays 1.

**Not decided.** How the TV authenticates, if it ever sends commands. Where the host sits behind
Caddy, and its public URL.
