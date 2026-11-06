"""lil guy machine's run database server: takes rows synced from every writer into one Postgres.

Each device (the CLI, the desktop app, the TV, later the sim host) records into its own SQLite
store and a background worker posts what the server doesn't have yet:

- ``HEAD /v1/blobs/{hash}`` then ``PUT`` if missing: prompts, replies, observations, pictures,
  gzipped and keyed by the SHA-256 of the plain bytes.
- ``POST /v1/ingest``: ``{"client": ..., "rows": [{"table": ..., "_rev": n, ...columns}]}``.

Rows are upserted on their table's primary key, keeping the highest ``_rev`` (how many times the
row was written on its device), so a batch posted twice or after a newer one changes nothing.

The Kotlin row classes (``runlog/.../Rows.kt``) are the rows' one definition. They generate
``schema/rows.schema.json``, which generates the Pydantic models in ``app/rows.py`` that every row
is validated with (``make server-schema``). The tables are the numbered migrations in
``sql/migrations/``, applied at startup; the tests fail when either drifts from the schema.

This service is ingest and query only. The live control path (the state stream, start and stop)
belongs to the Kotlin sim host.

Auth for v0: a bearer token per writer, ``LILGUY_INGEST_TOKENS="mac:<token>,tv:<token>"``.
"""

from __future__ import annotations

import gzip
import hashlib
import hmac
import json
import logging
import os
from contextlib import asynccontextmanager
from dataclasses import dataclass
from pathlib import Path

import psycopg
from fastapi import Depends, FastAPI, HTTPException, Request, Response
from psycopg import sql
from psycopg_pool import ConnectionPool
from pydantic import BaseModel, ValidationError

from app import migrations, rows

log = logging.getLogger("lilguy")

ROOT = Path(__file__).resolve().parent.parent
MIGRATIONS = ROOT / "sql" / "migrations"
SCHEMA = ROOT / "schema" / "rows.schema.json"
MAX_BODY = 16 * 1024 * 1024
MAX_ROWS = 5000


@dataclass(frozen=True)
class Table:
    name: str
    key: tuple[str, ...]
    # column name -> Postgres type, as format_type() writes it ("uuid", "text[]", "jsonb"); _rev included
    columns: dict[str, str]
    model: type[BaseModel]


def tokens() -> dict[str, str]:
    """Writer name to token, from LILGUY_INGEST_TOKENS."""
    out = {}
    for pair in os.environ.get("LILGUY_INGEST_TOKENS", "").split(","):
        name, _, token = pair.strip().partition(":")
        if name and token:
            out[name] = token
    return out


def load_tables(schema_path: Path = SCHEMA) -> dict[str, Table]:
    """Every table in the rows' JSON Schema, with the generated model its rows are validated by."""
    schema = json.loads(schema_path.read_text())
    out = {}
    for class_name, d in schema["$defs"].items():
        columns = {c: p["x-pg-type"] for c, p in d["properties"].items() if c != "table"}
        out[d["x-table"]] = Table(d["x-table"], tuple(d["x-key"]), columns, getattr(rows, class_name))
    return out


def encode(value, typ: str):
    """A JSON value as psycopg should send it for a column of type [typ]; the SQL casts it."""
    if value is None:
        return None
    if typ == "jsonb":
        return json.dumps(value)
    return value


def upsert(conn, table: Table, batch: list[dict]) -> None:
    cols = list(table.columns)
    target = sql.Identifier(table.name)
    stmt = sql.SQL(
        "INSERT INTO {t} ({cols}) VALUES ({vals}) ON CONFLICT ({key}) DO UPDATE SET {set} WHERE {t}._rev < EXCLUDED._rev"
    ).format(
        t=target,
        cols=sql.SQL(", ").join(map(sql.Identifier, cols)),
        vals=sql.SQL(", ").join(sql.SQL("%s::" + table.columns[c]) for c in cols),
        key=sql.SQL(", ").join(map(sql.Identifier, table.key)),
        set=sql.SQL(", ").join(sql.SQL("{c} = EXCLUDED.{c}").format(c=sql.Identifier(c)) for c in cols if c not in table.key),
    )
    with conn.cursor() as cur:
        cur.executemany(stmt, [[encode(r.get(c), table.columns[c]) for c in cols] for r in batch])


@asynccontextmanager
async def lifespan(app: FastAPI):
    url = os.environ["DATABASE_URL"]
    pool = ConnectionPool(url, min_size=1, max_size=4, open=True)
    with pool.connection() as conn:
        migrations.apply(conn, MIGRATIONS)
    app.state.pool = pool
    app.state.tables = load_tables()
    log.info("tables: %s", ", ".join(sorted(app.state.tables)))
    yield
    pool.close()


app = FastAPI(title="lil guy machine run database", lifespan=lifespan)


def writer(request: Request) -> str:
    """The writer the bearer token belongs to; 401 for anything else."""
    auth = request.headers.get("authorization", "")
    given = auth[7:] if auth.lower().startswith("bearer ") else ""
    for name, token in tokens().items():
        if given and hmac.compare_digest(given.encode(), token.encode()):
            return name
    raise HTTPException(401, "bad or missing token")


async def body(request: Request) -> bytes:
    raw = await request.body()
    if len(raw) > MAX_BODY:
        raise HTTPException(413, "body too large")
    if request.headers.get("content-encoding", "").lower() == "gzip":
        raw = gzip.decompress(raw)
        if len(raw) > 8 * MAX_BODY:
            raise HTTPException(413, "body too large")
    return raw


@app.get("/healthz")
def healthz():
    return {"ok": True}


@app.post("/v1/ingest")
async def ingest(request: Request, who: str = Depends(writer)):
    try:
        payload = json.loads(await body(request))
    except (ValueError, OSError) as e:
        raise HTTPException(400, f"not JSON: {e}") from e
    batch = payload.get("rows")
    if not isinstance(batch, list) or len(batch) > MAX_ROWS:
        raise HTTPException(400, f"rows must be a list of at most {MAX_ROWS}")
    tables: dict[str, Table] = request.app.state.tables
    by_table: dict[str, list[dict]] = {}
    for r in batch:
        name = r.get("table") if isinstance(r, dict) else None
        table = tables.get(name)
        if table is None:
            raise HTTPException(400, f"unknown table {name!r}")
        try:
            row = table.model.model_validate(r)
        except ValidationError as e:
            raise HTTPException(400, f"{name}: {e.errors(include_url=False)[:3]}; if the columns changed, run `make server-schema` and redeploy") from e
        by_table.setdefault(name, []).append(row.model_dump(mode="json", by_alias=True))
    try:
        with request.app.state.pool.connection() as conn:
            with conn.transaction():
                for name, batch in by_table.items():
                    upsert(conn, tables[name], batch)
    except psycopg.DataError as e:
        raise HTTPException(400, f"bad row: {e}") from e
    except psycopg.IntegrityError as e:
        raise HTTPException(400, f"bad row: {e}") from e
    log.info("ingest from %s (%s): %d rows", who, payload.get("client"), len(batch))
    return {"ok": True, "rows": len(batch)}


@app.head("/v1/blobs/{digest}")
def has_blob(digest: str, request: Request, who: str = Depends(writer)):
    with request.app.state.pool.connection() as conn:
        found = conn.execute("SELECT 1 FROM blobs WHERE hash = %s", (digest,)).fetchone()
    return Response(status_code=200 if found else 404)


@app.put("/v1/blobs/{digest}")
async def put_blob(digest: str, request: Request, who: str = Depends(writer)):
    raw = await request.body()
    if len(raw) > MAX_BODY:
        raise HTTPException(413, "blob too large")
    if request.headers.get("x-blob-encoding", "gzip") != "gzip":
        raise HTTPException(400, "blobs are sent gzipped")
    try:
        plain = gzip.decompress(raw)
    except OSError as e:
        raise HTTPException(400, f"not gzip: {e}") from e
    if hashlib.sha256(plain).hexdigest() != digest:
        raise HTTPException(400, "hash doesn't match the bytes")
    media = request.headers.get("x-blob-media-type", "application/octet-stream")
    with request.app.state.pool.connection() as conn:
        conn.execute(
            "INSERT INTO blobs (hash, encoding, media_type, bytes, size) VALUES (%s, 'gzip', %s, %s, %s) ON CONFLICT (hash) DO NOTHING",
            (digest, media, raw, len(plain)),
        )
    return {"ok": True}


@app.get("/v1/blobs/{digest}")
def get_blob(digest: str, request: Request, who: str = Depends(writer)):
    with request.app.state.pool.connection() as conn:
        row = conn.execute("SELECT media_type, bytes FROM blobs WHERE hash = %s", (digest,)).fetchone()
    if row is None:
        raise HTTPException(404, "no such blob")
    return Response(gzip.decompress(row[1]), media_type=row[0])
