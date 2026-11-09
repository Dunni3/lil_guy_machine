"""The server's Postgres schema: numbered SQL files in sql/migrations/, each applied once, in order.

A migration's columns follow the Kotlin row classes (runlog/.../Rows.kt), the one definition of the
rows; tests/test_schema.py fails when the migrated tables and schema/rows.schema.json disagree.
"""

from __future__ import annotations

import logging
from pathlib import Path

log = logging.getLogger("lilguy")

# Any constant: taken while migrating, so two processes starting at once don't both apply a file.
LOCK = 7_115_310


def apply(conn, directory: Path) -> list[str]:
    """Applies the migrations not yet applied; returns their names."""
    applied = []
    with conn.transaction():
        conn.execute("SELECT pg_advisory_xact_lock(%s)", (LOCK,))
        conn.execute("CREATE TABLE IF NOT EXISTS schema_migrations (version text PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())")
        done = {r[0] for r in conn.execute("SELECT version FROM schema_migrations").fetchall()}
        for f in sorted(directory.glob("[0-9][0-9][0-9][0-9]_*.sql")):
            if f.name in done:
                continue
            conn.execute(f.read_text())
            conn.execute("INSERT INTO schema_migrations (version) VALUES (%s)", (f.name,))
            applied.append(f.name)
            log.info("applied migration %s", f.name)
    return applied
