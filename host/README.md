# host/

The sim host: runs a life headless in real time and streams its state over WebSocket. The debug
page is at `/`, the stream at `/v1/lives/{life}/stream`, and commands go to
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

## On a server (not set up yet)

The host can't run on the server yet: `server/` has no image, compose service or Caddy route for it.
When it does, it has to be served over https, because the password travels with every command. Use
a different password from the one on your Mac.

## GitHub sign-in (not in use)

A GitHub OAuth sign-in was built and then set aside for v0. The code is on the branch
`archive/github-admin-sign-in`, and the design is in
`docs/decisions/2026-11-06-admin-sign-in-is-a-signed-cookie-and-commands-need-a-csrf-header-and-origin.md`.
