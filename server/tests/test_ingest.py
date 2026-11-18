"""Ingest is idempotent: a batch posted twice, or two batches out of order, leave the same rows."""

import gzip
import json
import os
import uuid

import psycopg
import pytest
from fastapi.testclient import TestClient

os.environ["LILGUY_INGEST_TOKENS"] = "test:secret"

from app.main import SCHEMA, app  # noqa: E402

COLUMNS = {d["x-table"]: list(d["properties"]) for d in json.loads(SCHEMA.read_text())["$defs"].values()}

AUTH = {"Authorization": "Bearer secret", "Content-Type": "application/json", "Content-Encoding": "gzip"}


def post(client, rows):
    # Writers send every column, the absent ones as null.
    rows = [{c: row.get(c) for c in COLUMNS[row["table"]]} for row in rows]
    r = client.post("/v1/ingest", content=gzip.compress(json.dumps({"client": "test", "rows": rows}).encode()), headers=AUTH)
    assert r.status_code == 200, r.text
    return r


def snapshot(run_id):
    """Every row of the run, by table, in key order."""
    with psycopg.connect(os.environ["DATABASE_URL"]) as conn:
        return {
            t: conn.execute(f"SELECT * FROM {t} WHERE {col} = %s ORDER BY 1, 2, 3", (run_id,)).fetchall()
            for t, col in [("runs", "id"), ("decisions", "run_id"), ("memory_events", "run_id"), ("ledger_entries", "run_id")]
        }


@pytest.fixture()
def client():
    with TestClient(app) as c:
        yield c


def batch(run_id, session_id, rev, significance):
    stamp = {"run_id": run_id, "session_id": session_id, "tick": 300, "day": 1, "minute_of_day": 540}
    return [
        {"table": "runs", "_rev": rev, "id": run_id, "scenario": "bedroom", "platform": "cli", "seed": 0, "ticks_per_minute": 5,
         "models": {"decision": {"id": "scripted:bedroom"}}, "granted": ["paint"], "started_at": "2026-11-06T12:00:00Z"},
        {"table": "decisions", "_rev": rev, "id": str(uuid.uuid5(uuid.NAMESPACE_URL, run_id)), **stamp, "seq": 1,
         "intent": {"type": "do", "activity": "sketch"}, "intent_label": "do:sketch", "fallback": False,
         "outcome_status": "succeeded" if rev > 1 else None},
        {"table": "memory_events", "_rev": rev, **stamp, "seq": 2, "id": 1, "time": "09:00", "kind": "outcome", "text": "sketched", "significance": significance},
        {"table": "ledger_entries", "_rev": 1, **stamp, "seq": 3, "amount": 5, "balance": 25, "reason": "sold 1 sketch"},
    ]


def test_the_same_batch_twice_leaves_the_same_rows(client):
    run_id, session_id = str(uuid.uuid4()), str(uuid.uuid4())
    rows = batch(run_id, session_id, rev=1, significance=None)
    post(client, rows)
    once = snapshot(run_id)
    post(client, rows)
    assert snapshot(run_id) == once
    assert len(once["decisions"]) == 1 and len(once["memory_events"]) == 1


def test_batches_out_of_order_keep_the_newer_rows(client):
    run_id, session_id = str(uuid.uuid4()), str(uuid.uuid4())
    older, newer = batch(run_id, session_id, 1, None), batch(run_id, session_id, 2, 0.8)
    post(client, older)
    post(client, newer)
    in_order = snapshot(run_id)
    # The other order, on a fresh run with the same shape.
    run_b = str(uuid.uuid4())
    post(client, batch(run_b, session_id, 2, 0.8))
    post(client, batch(run_b, session_id, 1, None))
    out_of_order = snapshot(run_b)

    def strip(snap):
        return {t: [tuple(v for v in row if str(v) not in (run_id, run_b) and not isinstance(v, uuid.UUID)) for row in rows] for t, rows in snap.items()}

    assert strip(in_order) == strip(out_of_order)
    with psycopg.connect(os.environ["DATABASE_URL"]) as conn:
        sig, rev = conn.execute("SELECT significance, _rev FROM memory_events WHERE run_id = %s", (run_b,)).fetchone()
    assert (sig, rev) == (0.8, 2)

