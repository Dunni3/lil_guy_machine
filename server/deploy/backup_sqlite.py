"""Writes a gzipped copy of a live SQLite database (the sim host's accounts.db) to stdout.

Run by backup.sh through the compose service `sqlite-backup`, which mounts the host's volume at /data and
runs as the same user as the host; backup.sh redirects stdout into ~/backups. SQLite's online backup API
reads a consistent copy while the host keeps writing (the database is in WAL mode, so most of it can be in
the -wal file), which copying the file would not. Usage: backup_sqlite.py SRC. Exits 3 when SRC doesn't
exist yet (no one has made an account), writing nothing.
"""

import gzip
import os
import shutil
import sqlite3
import sys
import tempfile


def main(src: str) -> None:
    if not os.path.exists(src):
        print(f"{src} doesn't exist yet: nothing to back up", file=sys.stderr)
        sys.exit(3)
    # Read-only: the backup never writes to the host's database.
    source = sqlite3.connect(f"file:{src}?mode=ro", uri=True)
    with tempfile.NamedTemporaryFile(suffix=".db") as tmp:
        copy = sqlite3.connect(tmp.name)
        source.backup(copy)
        source.close()
        ok = copy.execute("PRAGMA integrity_check").fetchone()[0]
        accounts = copy.execute("SELECT COUNT(*) FROM accounts").fetchone()[0]
        letters = copy.execute("SELECT COUNT(*) FROM letters").fetchone()[0]
        copy.close()
        if ok != "ok":
            sys.exit(f"the copy of {src} failed its integrity check: {ok}")
        with open(tmp.name, "rb") as f, gzip.GzipFile(fileobj=sys.stdout.buffer, mode="wb") as out:
            shutil.copyfileobj(f, out)
    print(f"{src}: {accounts} accounts, {letters} letters", file=sys.stderr)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("usage: backup_sqlite.py SRC > DEST.gz")
    main(sys.argv[1])
