# host/

The sim host: runs a life headless in real time and streams its state over WebSocket. The debug
page is at `/`, the web renderer (`web/`, read-only) at `/room/`, the stream at `/v1/lives/{life}/stream`, and commands go to
`POST /v1/lives/{life}/commands`. Anyone can watch. Only the admin can send commands.

```bash
make host                 # scripted policy, http://127.0.0.1:8790/
make host ENDPOINT=jev    # a model from config/endpoints.yaml
```

Configuration is all environment (see `Main.kt`). The Makefile loads
`~/.config/lil-guy-machine/secrets.env`, so secrets go there as `export NAME=value` lines and never
in the repo.

## The admin password

v0 has one admin login: a password in `LILGUY_ADMIN_PASSWORD`. Without it, every command is refused.

1. **Make one and add it to `~/.config/lil-guy-machine/secrets.env`.** Run `openssl rand -hex 24`,
   then add:

   ```bash
   export LILGUY_ADMIN_PASSWORD=<what that printed>
   ```

   Nothing limits how many guesses someone can make, so the host won't start with a password under
   24 characters.
2. **Run `make host` and open the page.** Paste the password into the box. The run controls appear,
   and the browser remembers the password until you press "forget password". Everyone else sees the
   same state with no controls.

From curl, send it as a bearer header:

```bash
curl -X POST 127.0.0.1:8790/v1/lives/main/commands -H "Authorization: Bearer $LILGUY_ADMIN_PASSWORD" -d '{"type":"pause"}'
```

If it leaks, change it and restart the host. The old one stops working at once.

## Runs are kept

Every run is recorded in the run database and saved as it goes: each morning, and in one rolling save per session,
rewritten at his decision points every 10 game minutes and when the run is left. So nothing throws a run away. On
`/room/`, the admin's RUN tab has **New run** (leave this one for a fresh one) and **Saved runs** (LEFT/RIGHT picks a
recorded run, OK twice goes back to it, from its save, waiting for Start). The list is `GET /v1/lives/main/runs`, with
the admin's bearer header.

When the host starts, it carries on the run it was last on (`LILGUY_RESUME` unset or `latest`), and starts it again if
it was running when the host stopped. `LILGUY_RESUME=none` starts fresh; a run id carries that run on. Going back
replays whatever came after the save: at most the last 10 game minutes, plus what he was partway through when left.

## On a server

The host runs in the server's Docker stack, on its own https address. Its settings are in
`.env.host` on the box, with a different password from the one on your Mac. Setup steps are in
`server/README.md`. Nothing is deployed yet.

## GitHub sign-in (not in use)

A GitHub OAuth sign-in was built and then set aside for v0. The code is on the branch
`archive/github-admin-sign-in`, and the design is in
`docs/decisions/2026-11-06-admin-sign-in-is-a-signed-cookie-and-commands-need-a-csrf-header-and-origin.md`.
