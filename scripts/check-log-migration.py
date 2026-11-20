"""Check the app's actual outbox migration SQL against a legacy SQLite database."""
from datetime import datetime, timezone
from pathlib import Path
import re
import sqlite3

root = Path(__file__).resolve().parent.parent
source = root / "app/src/main/java/cn/daka/local/DailyLogs.kt"
statements = re.findall(r'"""(.*?)"""', source.read_text(encoding="utf-8"), re.S)
assert len(statements) == 4

database = sqlite3.connect(":memory:")
database.executescript("""
CREATE TABLE runs(id TEXT PRIMARY KEY, scheduled INTEGER);
CREATE TABLE sync(path TEXT PRIMARY KEY, revision INTEGER NOT NULL);
INSERT INTO sync VALUES('status.md',7),('logs/2026-11.md',4),('logs/2026-11-20.md',9);
""")
timestamps = ["2026-11-19T15:59:59+00:00", "2026-11-19T16:00:00+00:00", "2026-12-31T16:00:00+00:00"]
for index, timestamp in enumerate(timestamps):
    database.execute("INSERT INTO runs VALUES(?,?)", (str(index), int(datetime.fromisoformat(timestamp).timestamp() * 1000)))
expected = {"status.md": 7, "logs/README.md": 1, "logs/2026-11-19.md": 1, "logs/2026-11-20.md": 9, "logs/2027-01-01.md": 1}
for _ in range(2):
    with database:
        for statement in statements:
            database.execute(statement)
    assert dict(database.execute("SELECT path,revision FROM sync")) == expected
    assert database.execute("SELECT count(*) FROM runs").fetchone()[0] == 3
print("Legacy outbox migration passed: daily paths, timezone, preservation, and idempotency.")

store_source = (root / "app/src/main/java/cn/daka/local/Store.kt").read_text(encoding="utf-8")
schema_upgrades = re.findall(r'db\.execSQL\("(ALTER TABLE runs[^"\n]+)"\)', store_source)
assert len(schema_upgrades) == 2
with database:
    for statement in schema_upgrades:
        database.execute(statement)
assert database.execute("SELECT count(*) FROM runs").fetchone()[0] == 3
assert database.execute("SELECT DISTINCT attempts,cleanup FROM runs").fetchall() == [(0, "")]
print("Run-schema upgrade passed: existing records retained and recovery fields initialized.")
