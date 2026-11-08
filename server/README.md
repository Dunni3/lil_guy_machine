# server/

The run database's server: one Postgres that every writer's rows end up in, and the FastAPI
service that takes them. It runs on its own Lightsail instance (Small, 2 GB, $12 a month), with
Caddy in front for TLS. The sim host joins this stack in the next phase.

How rows get here: every writer (the CLI, the desktop app, the TV) records into a local SQLite
store (`runs/lilguy.db` on the Mac, the app's files dir on the TV) and never waits on the network.
A background worker in each process (`runlog/.../SyncWorker.kt`) sends what the server doesn't have:

- `HEAD /v1/blobs/{sha256}`, then `PUT` only if it's missing. Prompts, replies, observations and
  PNGs are stored once by the SHA-256 of their bytes, gzipped.
- `POST /v1/ingest` with `{"client": ..., "rows": [{"table": ..., "_rev": n, ...columns}]}`, gzipped.

Each row is upserted on its table's primary key, keeping the highest `_rev`. `_rev` counts how
many times the row was written on its device. So a batch sent twice, or after a newer one, leaves
the same rows (`tests/test_ingest.py`). There are no foreign keys, because batches can arrive in
any order.

| File | What |
|---|---|
| `app/main.py` | The ingest and blob endpoints. Every row is validated by its generated model |
| `schema/rows.schema.json` | The rows as JSON Schema, **generated** from the Kotlin row classes (`runlog/.../Rows.kt`) |
| `app/rows.py` | Pydantic models, **generated** from that schema by `gen_models.sh` |
| `sql/migrations/NNNN_*.sql` | The tables (`0001`) and views (`0002`: `v_runs`, `v_cost_by_day`, `v_verb_mix`, `v_needs_hourly`, `v_stuck`), each applied once, in order (`app/migrations.py`) |
| `docker-compose.yml` | `db` (Postgres 16), `api`, `caddy` |
| `docker-compose.dev.yml` | Local overrides: API on `localhost:8787`, Postgres on `localhost:5433`, no Caddy |
| `deploy/` | `bootstrap-instance.sh` (once per box), `deploy.sh` (`make server-deploy`), `backup.sh` (nightly cron) |

The Kotlin row classes are the only definition of the rows. Changing a column takes four steps:

1. Change the row class in `Rows.kt`.
2. Run `make server-schema`, which regenerates the schema and `app/rows.py`.
3. Add the next numbered migration with the `ALTER TABLE`.
4. Deploy.

Two checks fail if a step is missed: `make test` when the schema is stale, and `make server-test`
when the models are stale or the migrated tables don't match the schema. This service is ingest
and query only; the live control path belongs to the Kotlin sim host.

## Locally

```bash
make server-up      # Postgres + the API; ingest token "dev"
make server-test    # idempotent ingest; models and migrations match the rows
LILGUY_SYNC_URL=http://localhost:8787 LILGUY_SYNC_TOKEN=dev make cli ARGS="--scenario bedroom --days 1"
make server-psql    # then: select * from v_runs;
```

## The box (not deployed yet)

1. **Instance.** Lightsail → Ubuntu 24.04 → **Small** (2 GB). It's a box of its own, not
   smarter-tv's. Attach a static IP. Open 443/tcp and 443/udp; 22 and 80 are open by default.
2. **DNS.** Point an `A` record (e.g. `lilguy.<your domain>`) at the static IP.
3. **Bootstrap.** `ssh ubuntu@<ip> 'bash -s' < server/deploy/bootstrap-instance.sh`. This
   installs Docker, the firewall, 2 GB of swap and the nightly backup cron. Log out and back in.
4. **Secrets.** Create `~/lilguy-server/.env` on the box from `.env.example`: `DOMAIN`,
   `ACME_EMAIL`, a fresh `POSTGRES_PASSWORD`, and one token per writer in `LILGUY_INGEST_TOKENS`.
   The file never leaves the box, because `deploy.sh` excludes it.
5. **Deploy.** `DEPLOY_HOST=ubuntu@lilguy.<domain> make server-deploy`.
6. **Writers.** On the Mac, add `LILGUY_SYNC_URL=https://lilguy.<domain>` and
   `LILGUY_SYNC_TOKEN=<the mac token>` to `~/.config/lil-guy-machine/secrets.env`. For the TV,
   export the TV's token as `LILGUY_SYNC_TOKEN` and run `make tv-push-config`.
7. **Old runs.** `make import-runs` reads `runs/` and `runs/tv/` into `runs/lilguy.db`, then syncs.

## Querying from the Mac

`DEPLOY_HOST=ubuntu@lilguy.<domain> make server-tunnel` forwards the box's Postgres to
`localhost:5434`. Postgres listens on the box's localhost only and is never published.

- psql: `psql postgresql://lilguy:<password>@localhost:5434/lilguy`
- pandas: `pd.read_sql("select * from v_cost_by_day", "postgresql://lilguy:<password>@localhost:5434/lilguy")`
- DuckDB: `ATTACH 'host=localhost port=5434 dbname=lilguy user=lilguy password=<password>' AS lg (TYPE postgres);`

Blob columns (`request_blob`, `observation_blob`, `png_blob`, ...) hold a SHA-256. The body is in
`blobs.bytes`, gzipped: in SQL use `convert_from(...)` after decompressing in your client, or call
`GET /v1/blobs/{hash}` with a token.

A resumed run has one session per process lifetime. Resuming from a checkpoint replays the ticks
after it, so the abandoned branch's rows stay in the database. `sessions.from_checkpoint` says
where each branch starts. Filter on `session_id` to see one branch.

## Backups

`deploy/backup.sh` runs nightly from cron. It writes `pg_dump | gzip` into `~/backups` and keeps
14 days. With `BACKUP_BUCKET` set in `.env` (and the AWS CLI configured with a write-only key),
each dump is also copied to Lightsail object storage or S3. Lightsail instance snapshots are the
other option.
