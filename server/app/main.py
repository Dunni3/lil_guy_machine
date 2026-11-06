"""lil guy machine's run database server: takes rows synced from every writer into one Postgres.

Each device (the CLI, the desktop app, the TV, later the sim host) records into its own SQLite
store and a background worker posts what the server doesn't have yet:

- ``HEAD /v1/blobs/{hash}`` then ``PUT`` if missing: prompts, replies, observations, pictures,
  gzipped and keyed by the SHA-256 of the plain bytes.
- ``POST /v1/ingest``: ``{"client": ..., "rows": [{"table": ..., "_rev": n, ...columns}]}``.

Rows are upserted on their table's primary key, keeping the highest ``_rev`` (how many times the
row was written on its device), so a batch posted twice or after a newer one changes nothing.
Column names and types are read from Postgres itself; the tables are generated from the Kotlin
side (``runlog/.../Tables.kt``) into ``sql/001_tables.sql``.

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

from fastapi import Depends, FastAPI, HTTPException, Request, Response
import psycopg
from psycopg import sql
from psycopg_pool import ConnectionPool

log = logging.getLogger("lilguy")

SQL_DIR = Path(os.environ.get("LILGUY_SQL_DIR", Path(__file__).resolve().parent.parent / "sql"))
MAX_BODY = 16 * 1024 * 1024
MAX_ROWS = 5000


@dataclass(frozen=True)
class Table:
    name: str
    key: tuple[str, ...]
    # column name -> Postgres type, as format_type() writes it ("uuid", "text[]", "jsonb")
    columns: dict[str, str]


def tokens() -> dict[str, str]:
    """Writer name to token, from LILGUY_INGEST_TOKENS."""
    out = {}
    for pair in os.environ.get("LILGUY_INGEST_TOKENS", "").split(","):
        name, _, token = pair.strip().partition(":")
        if name and token:
            out[name] = token
    return out


def migrate(pool: ConnectionPool) -> None:
    """Runs every sql/*.sql in order. Each is idempotent (IF NOT EXISTS, CREATE OR REPLACE)."""
    with pool.connection() as conn:
        for f in sorted(SQL_DIR.glob("*.sql")):
            conn.execute(f.read_text())
            log.info("applied %s", f.name)


def load_tables(pool: ConnectionPool) -> dict[str, Table]:
    with pool.connection() as conn:
        cols = conn.execute(
            """
            SELECT c.relname, a.attname, format_type(a.atttypid, a.atttypmod)
            FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'public' AND c.relkind = 'r' AND a.attnum > 0 AND NOT a.attisdropped
            ORDER BY c.relname, a.attnum
            """
        ).fetchall()
        keys = conn.execute(
            """
            SELECT c.relname, a.attname
            FROM pg_index i JOIN pg_class c ON c.oid = i.indrelid JOIN pg_namespace n ON n.oid = c.relnamespace
            JOIN LATERAL unnest(i.indkey) WITH ORDINALITY AS k(attnum, ord) ON true
            JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = k.attnum
            WHERE n.nspname = 'public' AND i.indisprimary
            ORDER BY c.relname, k.ord
            """
        ).fetchall()
    columns: dict[str, dict[str, str]] = {}
    for table, col, typ in cols:
        columns.setdefault(table, {})[col] = typ
    key: dict[str, list[str]] = {}
    for table, col in keys:
        key.setdefault(table, []).append(col)
    return {t: Table(t, tuple(key[t]), c) for t, c in columns.items() if t != "blobs" and t in key and "_rev" in c}


def encode(value, typ: str):
    """A JSON value as psycopg should send it for a column of type [typ]; the SQL casts it."""
    if value is None:
        return None
    if typ == "jsonb":
        return json.dumps(value)
    return value


def upsert(conn, table: Table, rows: list[dict]) -> None:
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
        cur.executemany(stmt, [[encode(r.get(c), table.columns[c]) if c != "_rev" else int(r.get("_rev") or 1) for c in cols] for r in rows])


@asynccontextmanager
async def lifespan(app: FastAPI):
    url = os.environ["DATABASE_URL"]
    pool = ConnectionPool(url, min_size=1, max_size=4, open=True)
    migrate(pool)
    app.state.pool = pool
    app.state.tables = load_tables(pool)
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
    rows = payload.get("rows")
    if not isinstance(rows, list) or len(rows) > MAX_ROWS:
        raise HTTPException(400, f"rows must be a list of at most {MAX_ROWS}")
    tables: dict[str, Table] = request.app.state.tables
    by_table: dict[str, list[dict]] = {}
    for r in rows:
        name = r.get("table") if isinstance(r, dict) else None
        table = tables.get(name)
        if table is None:
            raise HTTPException(400, f"unknown table {name!r}")
        unknown = set(r) - set(table.columns) - {"table"}
        if unknown:
            raise HTTPException(400, f"{name}: unknown columns {sorted(unknown)}; run `make server-schema` and redeploy")
        by_table.setdefault(name, []).append(r)
    try:
        with request.app.state.pool.connection() as conn:
            with conn.transaction():
                for name, batch in by_table.items():
                    upsert(conn, tables[name], batch)
    except psycopg.DataError as e:
        raise HTTPException(400, f"bad row: {e}") from e
    except psycopg.IntegrityError as e:
        raise HTTPException(400, f"bad row: {e}") from e
    log.info("ingest from %s (%s): %d rows", who, payload.get("client"), len(rows))
    return {"ok": True, "rows": len(rows)}


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
