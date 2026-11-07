# host/

The sim host: runs a life headless in real time and streams its state over WebSocket. The debug
page is at `/`, the stream at `/v1/lives/{life}/stream`, and commands go to
`POST /v1/lives/{life}/commands`. Anyone can watch. Only the admin can send commands.

```bash
make host                 # scripted policy, http://localhost:8790/
make host ENDPOINT=jev    # a model from config/endpoints.yaml
```

Configuration is all environment (see `Main.kt`). The Makefile loads
`~/.config/lil-guy-machine/secrets.env`, so secrets go there as `export NAME=value` lines and never
in the repo.

## Who is the admin

There are two ways in (`AdminAuth.kt`, `GitHubSignIn.kt`):

- **GitHub sign-in.** "sign in with GitHub" on the debug page goes to `/auth/login`. GitHub sends
  the browser back to `/auth/callback`, which sets a signed session cookie for that GitHub user. Anyone can
  sign in, but only the account whose numeric id is `LILGUY_ADMIN_GITHUB_ID` is the admin. Everyone
  else just watches. `GET /auth/me` says who the caller is. Commands from a signed-in browser also
  need the `X-Lilguy-Csrf: 1` header and an `Origin` matching `LILGUY_PUBLIC_URL`. The page sends
  both.
- **The dev token**, `LILGUY_ADMIN_TOKEN`: `Authorization: Bearer <token>`, for curl and local runs.
  It's off unless set, and the host prints a `DEV:` line at startup when it's on. Don't set it on a
  server.

With neither set, every command is refused.

```bash
curl -X POST localhost:8790/v1/lives/main/commands -H "Authorization: Bearer $LILGUY_ADMIN_TOKEN" -d '{"type":"pause"}'
```

## Setting up GitHub sign-in on localhost

1. **Create an OAuth app**: GitHub → Settings → Developer settings → OAuth Apps → New OAuth App.
   (Not a GitHub App.)
   - Application name: `lil guy host (local)`
   - Homepage URL: `http://localhost:8790`
   - Authorization callback URL: `http://localhost:8790/auth/callback`

   Then **Generate a new client secret**. It asks for no scopes: the host reads only your public
   profile, for the id.
2. **Find your numeric user id**: `curl -s https://api.github.com/users/<your login> | grep '"id"'`.
3. **Add to `~/.config/lil-guy-machine/secrets.env`:**

   ```bash
   export LILGUY_GITHUB_CLIENT_ID=<the app's client id>
   export LILGUY_GITHUB_CLIENT_SECRET=<the client secret>
   export LILGUY_ADMIN_GITHUB_ID=<your numeric id>
   export LILGUY_SESSION_KEY=<openssl rand -hex 32>
   ```

   Set all four, or none. With only some of them set, the host stops and names the missing ones.
4. **`make host`**, then open **http://localhost:8790/**. Use `localhost`, not `127.0.0.1`: the
   cookie belongs to the host name in `LILGUY_PUBLIC_URL`, which defaults to
   `http://localhost:<port>`. With `PORT=` set, register that port's callback instead.

**The Secure cookie on http://localhost.** The session cookie is `Secure` with the `__Host-`
prefix whenever `LILGUY_PUBLIC_URL` is https. Not every browser accepts a Secure cookie over plain
http, so on an `http://localhost` (or `127.0.0.1`) URL the host drops both. It's still HttpOnly,
signed and SameSite=Lax. The host refuses any other http URL at startup.

## On a server (not deployed yet)

Make a second OAuth app whose callback is `https://<domain>/auth/callback`, and set
`LILGUY_PUBLIC_URL=https://<domain>` with the four variables above (placeholders are in
`server/.env.example`). Use a fresh `LILGUY_SESSION_KEY` per deployment. Rotating it signs everyone
out. Leave `LILGUY_ADMIN_TOKEN` unset.
