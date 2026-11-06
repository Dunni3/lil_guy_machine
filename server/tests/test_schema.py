"""The server's two copies of the rows stay what the Kotlin row classes say: the generated Pydantic
models, and the tables the migrations make."""

import json
import os
import subprocess
from pathlib import Path

import psycopg
from fastapi.testclient import TestClient

from app.main import SCHEMA, app

ROOT = Path(__file__).resolve().parent.parent


def test_the_models_are_generated_from_the_current_schema(tmp_path):
    out = tmp_path / "rows.py"
    subprocess.run(["sh", str(ROOT / "gen_models.sh"), str(out)], check=True)
    assert out.read_text() == (ROOT / "app" / "rows.py").read_text(), "app/rows.py is stale: run `make server-schema`"


def test_the_migrations_make_the_tables_the_rows_describe():
    with TestClient(app):  # applies the migrations
        pass
    schema = json.loads(SCHEMA.read_text())
    with psycopg.connect(os.environ["DATABASE_URL"]) as conn:
        for d in schema["$defs"].values():
            table = d["x-table"]
            got = {
                name: (typ, not_null)
                for name, typ, not_null in conn.execute(
                    "SELECT a.attname, format_type(a.atttypid, a.atttypmod), a.attnotnull FROM pg_attribute a "
                    "WHERE a.attrelid = %s::regclass AND a.attnum > 0 AND NOT a.attisdropped",
                    (table,),
                ).fetchall()
            }
            want = {c: (p["x-pg-type"], p.get("x-not-null", True)) for c, p in d["properties"].items() if c != "table"}
            assert got == want, f"{table}: the migrations and the rows disagree; add a migration. Differs: " + str(
                {c: (got.get(c), want.get(c)) for c in got.keys() | want.keys() if got.get(c) != want.get(c)}
            )
            key = [r[0] for r in conn.execute(
                "SELECT a.attname FROM pg_index i JOIN LATERAL unnest(i.indkey) WITH ORDINALITY k(n, o) ON true "
                "JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.n WHERE i.indrelid = %s::regclass AND i.indisprimary ORDER BY k.o",
                (table,),
            ).fetchall()]
            assert key == d["x-key"], f"{table}: primary key {key}, rows say {d['x-key']}"
