#!/usr/bin/env python3
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile

if len(sys.argv) != 2:
    raise SystemExit("usage: sqlite-schema-smoke.py <host-dll>")

host_dll = Path(sys.argv[1]).resolve()
expected_tables = {
    "schema_metadata",
    "raw_capture",
    "capture_observation",
    "conversation_snapshot",
    "message_node",
    "message_edge",
    "message_revision",
    "dom_witness",
    "reconciliation",
    "durability_state",
}

with tempfile.TemporaryDirectory(prefix="bke-dna-sqlite-") as temp:
    root = Path(temp) / "captures"
    env = os.environ.copy()
    env["BKE_DNA_CAPTURE_ROOT"] = str(root)

    result = subprocess.run(
        ["dotnet", str(host_dll)],
        input=b"",
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env=env,
        check=False,
    )
    if result.returncode != 0:
        sys.stderr.write(result.stderr.decode("utf-8", errors="replace"))
        raise SystemExit(f"native host exited {result.returncode}")

    database_path = root / "dna.sqlite3"
    if not database_path.exists():
        raise SystemExit("SQLite live index was not created")

    connection = sqlite3.connect(database_path)
    try:
        # SQLite foreign-key enforcement is connection-local. The .NET live index
        # enables it when opening its connection; this separate verifier connection
        # must enable it independently before testing enforcement.
        connection.execute("PRAGMA foreign_keys = ON")

        tables = {
            row[0]
            for row in connection.execute(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'"
            )
        }
        missing = expected_tables - tables
        if missing:
            raise SystemExit(f"SQLite schema missing tables: {sorted(missing)}")

        schema_version = connection.execute(
            "SELECT value FROM schema_metadata WHERE key = 'schema_version'"
        ).fetchone()
        if schema_version != ("1",):
            raise SystemExit(f"unexpected schema metadata version: {schema_version}")

        user_version = connection.execute("PRAGMA user_version").fetchone()[0]
        if user_version != 1:
            raise SystemExit(f"unexpected PRAGMA user_version: {user_version}")

        journal_mode = connection.execute("PRAGMA journal_mode").fetchone()[0].lower()
        if journal_mode != "wal":
            raise SystemExit(f"SQLite journal mode must be WAL, got {journal_mode}")

        foreign_keys = connection.execute("PRAGMA foreign_keys").fetchone()[0]
        if foreign_keys != 1:
            raise SystemExit("verifier SQLite connection could not enable foreign keys")

        observation_fks = connection.execute(
            "PRAGMA foreign_key_list(capture_observation)"
        ).fetchall()
        if not any(row[2] == "raw_capture" for row in observation_fks):
            raise SystemExit("capture_observation does not declare raw_capture foreign key")

        durability_fks = connection.execute(
            "PRAGMA foreign_key_list(durability_state)"
        ).fetchall()
        if not any(row[2] == "raw_capture" for row in durability_fks):
            raise SystemExit("durability_state does not declare raw_capture foreign key")

        connection.execute(
            """
            INSERT INTO raw_capture(
                sha256, byte_length, content_type, body_path,
                first_seen_at, last_seen_at, seen_count
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
            (
                "a" * 64,
                1,
                "application/json",
                "bodies/test.body",
                "2026-09-07T00:00:00Z",
                "2026-09-07T00:00:00Z",
                1,
            ),
        )

        try:
            connection.execute(
                "INSERT INTO durability_state(raw_sha256, clearable) VALUES (?, 1)",
                ("a" * 64,),
            )
        except sqlite3.IntegrityError:
            pass
        else:
            raise SystemExit("durability gate allowed clearable=1 without verified .dna state")

        connection.execute(
            """
            INSERT INTO durability_state(
                raw_sha256, dna_archive_id, dna_archive_sha256, archived_at, clearable
            ) VALUES (?, ?, ?, ?, 1)
            """,
            (
                "a" * 64,
                "archive-test",
                "b" * 64,
                "2026-09-07T00:00:01Z",
            ),
        )
        clearable = connection.execute(
            "SELECT clearable FROM durability_state WHERE raw_sha256 = ?",
            ("a" * 64,),
        ).fetchone()[0]
        if clearable != 1:
            raise SystemExit("valid verified .dna state did not permit clearable=1")
    finally:
        connection.close()

print("sqlite schema smoke PASS")
