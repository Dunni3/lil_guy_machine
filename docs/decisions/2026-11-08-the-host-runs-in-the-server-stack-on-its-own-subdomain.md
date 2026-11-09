---
title: "The sim host runs in the server stack, on its own subdomain, built on the Mac"
date: 2026-11-08
status: active
---

**Context.** The host ran only through `make host`. It has to be reachable from anywhere, over
https, because the admin password travels with every command.

**Decision.**

- **A `host` service in `server/docker-compose.yml`,** on the same Lightsail box as Postgres and the
  ingest service. It publishes no port, so only Caddy reaches it. The broadcaster stays elsewhere
  (it needs more than this box has).
- **Its own subdomain, `HOST_DOMAIN`,** beside the ingest service's `DOMAIN`. That takes a second DNS
  record, and Caddy gets the certificate by itself. It keeps the public page apart from the API
  writers sync to, and keeps paths from colliding at `/v1/`.
- **Built on the Mac.** `deploy.sh` runs Gradle's `installDist`, stages the result in
  `server/host/dist/` and rsyncs it. The image only copies it onto a JRE. So the box never runs
  Gradle, which a 2 GB box would struggle with next to Postgres, and the Mac's CPU type doesn't
  matter, since the app is JVM bytecode and sqlite-jdbc carries natives for each platform.
- **The host's secrets are in their own file, `.env.host`,** read only by that container: the admin
  password, its sync token and model keys. The keys' names come from `endpoints.yaml`, so compose
  doesn't list them, and the host doesn't see the Postgres password. `deploy.sh` refuses to go ahead
  without an admin password there, and never overwrites the file.
- **`config/endpoints.yaml` goes into the image** when it exists. It holds no keys.
- **Runs live in the `host-data` volume and sync to Postgres on the same box** (`http://api:8787`).
  Only Postgres is backed up nightly, so a host without a sync token isn't backed up.
- **`LILGUY_RESUME=latest` in the example,** so a restart carries on from the last morning.
  `LILGUY_AUTOSTART` stays off: a restart shouldn't start spending on a model unless asked.
- **`-Xmx512m` and an 800 MB container limit.** A first guess: the scripted policy used about 160 MB
  over a few game days. Neither a model-driven life nor a long run has been measured.

**Not decided.** The real subdomain, and whether the page is listed anywhere.
