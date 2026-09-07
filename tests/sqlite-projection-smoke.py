#!/usr/bin/env python3
import base64
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import struct
import subprocess
import sys
import tempfile
import uuid

if len(sys.argv) != 2:
    raise SystemExit("usage: sqlite-projection-smoke.py <host-dll>")

host_dll = Path(sys.argv[1]).resolve()
page_url = "https://chatgpt.com/c/sqlite-proof"
request_url = "https://chatgpt.com/example-response?internal=value"
visible_phrase = "SQLITE LIVE INDEX CORROBORATION PROOF"

complete_body = json.dumps(
    {
        "conversation_id": "conversation-complete",
        "current_node": "assistant-node",
        "mapping": {
            "user-node": {
                "parent": None,
                "children": ["assistant-node"],
                "message": {
                    "id": "user-message",
                    "author": {"role": "user"},
                    "content": {"parts": [visible_phrase]},
                },
            },
            "assistant-node": {
                "parent": "user-node",
                "children": [],
                "message": {
                    "id": "assistant-message",
                    "author": {"role": "assistant"},
                    "content": {"parts": ["INDEXED"]},
                },
            },
        },
    },
    separators=(",", ":"),
).encode("utf-8")
partial_body = json.dumps(
    {
        "conversation_id": "conversation-partial",
        "current_node": "orphan-node",
        "mapping": {
            "orphan-node": {
                "parent": "missing-parent",
                "children": [],
                "message": {
                    "id": "orphan-message",
                    "author": {"role": "assistant"},
                    "content": {"parts": ["UNRELATED PARTIAL"]},
                },
            }
        },
    },
    separators=(",", ":"),
).encode("utf-8")
config_body = b'{"flags":{"sqlite_test":true},"account":{"tier":"test"}}'
complete_sha = hashlib.sha256(complete_body).hexdigest()
partial_sha = hashlib.sha256(partial_body).hexdigest()
config_sha = hashlib.sha256(config_body).hexdigest()
request_url_sha = hashlib.sha256(request_url.encode("utf-8")).hexdigest()


def frame(message):
    payload = json.dumps(message, separators=(",", ":")).encode("utf-8")
    return struct.pack("<I", len(payload)) + payload


def capture_messages(capture_id, body, captured_at):
    midpoint = len(body) // 2
    chunks = [body[:midpoint], body[midpoint:]]
    result = [
        {
            "type": "capture_start",
            "captureId": capture_id,
            "pageUrl": page_url,
            "requestUrl": request_url,
            "method": "GET",
            "status": 200,
            "contentType": "application/json",
            "initiator": "fetch",
            "capturedAt": captured_at,
            "byteLength": len(body),
            "fidelity": "browser-application-response-body",
        }
    ]
    for sequence, chunk in enumerate(chunks):
        result.append(
            {
                "type": "capture_chunk",
                "captureId": capture_id,
                "sequence": sequence,
                "base64": base64.b64encode(chunk).decode("ascii"),
            }
        )
    result.append({"type": "capture_end", "captureId": capture_id})
    return result


complete_capture_ids = [str(uuid.uuid4()), str(uuid.uuid4())]
partial_capture_id = str(uuid.uuid4())
config_capture_id = str(uuid.uuid4())
witness_id = str(uuid.uuid4())

messages = [
    {
        "type": "dom_witness",
        "witnessId": witness_id,
        "pageUrl": page_url,
        "observedAt": "2026-09-07T00:00:00Z",
        "snippets": [visible_phrase],
    }
]
messages.extend(capture_messages(complete_capture_ids[0], complete_body, "2026-09-07T00:00:01Z"))
messages.extend(capture_messages(complete_capture_ids[1], complete_body, "2026-09-07T00:00:02Z"))
messages.extend(capture_messages(partial_capture_id, partial_body, "2026-09-07T00:00:03Z"))
messages.extend(capture_messages(config_capture_id, config_body, "2026-09-07T00:00:04Z"))
wire = b"".join(frame(message) for message in messages)


def run_host(root, input_bytes):
    env = os.environ.copy()
    env["BKE_DNA_CAPTURE_ROOT"] = str(root)
    result = subprocess.run(
        ["dotnet", str(host_dll)],
        input=input_bytes,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env=env,
        check=False,
    )
    if result.returncode != 0:
        sys.stderr.write(result.stderr.decode("utf-8", errors="replace"))
        raise SystemExit(f"native host exited {result.returncode}")


def scalar(connection, sql, parameters=()):
    row = connection.execute(sql, parameters).fetchone()
    return None if row is None else row[0]


with tempfile.TemporaryDirectory(prefix="bke-dna-projection-") as temp:
    root = Path(temp) / "captures"
    run_host(root, wire)

    database_path = root / "dna.sqlite3"
    connection = sqlite3.connect(database_path)
    try:
        raw_rows = connection.execute(
            "SELECT sha256, seen_count FROM raw_capture ORDER BY sha256"
        ).fetchall()
        raw_counts = dict(raw_rows)
        if raw_counts != {complete_sha: 2, partial_sha: 1, config_sha: 1}:
            raise SystemExit(f"raw capture projection counts mismatch: {raw_counts}")

        if scalar(connection, "SELECT COUNT(*) FROM capture_observation") != 4:
            raise SystemExit("capture observation projection count mismatch")

        request_hashes = {
            row[0] for row in connection.execute("SELECT request_url_sha256 FROM capture_observation")
        }
        if request_hashes != {request_url_sha}:
            raise SystemExit(f"request URL hashing mismatch: {request_hashes}")

        snapshots = dict(
            connection.execute(
                "SELECT source_sha256, coverage_status FROM conversation_snapshot"
            ).fetchall()
        )
        if snapshots != {complete_sha: "complete", partial_sha: "partial"}:
            raise SystemExit(f"conversation coverage projection mismatch: {snapshots}")

        if scalar(connection, "SELECT COUNT(*) FROM message_node") != 3:
            raise SystemExit("message node projection count mismatch")
        if scalar(connection, "SELECT COUNT(*) FROM message_edge") != 1:
            raise SystemExit("message edge projection count mismatch")
        if scalar(connection, "SELECT COUNT(*) FROM message_revision") != 3:
            raise SystemExit("message revision projection count mismatch")

        if scalar(connection, "SELECT COUNT(*) FROM dom_witness") != 1:
            raise SystemExit("DOM witness projection count mismatch")
        reconciliation_status = scalar(
            connection,
            "SELECT status FROM reconciliation WHERE witness_id = ?",
            (witness_id,),
        )
        if reconciliation_status != "corroborated":
            raise SystemExit(f"reconciliation projection mismatch: {reconciliation_status}")

        durability = connection.execute(
            "SELECT raw_sha256, clearable FROM durability_state ORDER BY raw_sha256"
        ).fetchall()
        if len(durability) != 3 or any(clearable != 0 for _, clearable in durability):
            raise SystemExit(f"new evidence must remain non-clearable: {durability}")
    finally:
        connection.close()

    # Restart with no new browser messages. Startup replay must rebuild/idempotently
    # reproject the same filesystem evidence without inflating counts or revisions.
    run_host(root, b"")

    connection = sqlite3.connect(database_path)
    try:
        raw_counts_after_restart = dict(
            connection.execute("SELECT sha256, seen_count FROM raw_capture").fetchall()
        )
        if raw_counts_after_restart != {complete_sha: 2, partial_sha: 1, config_sha: 1}:
            raise SystemExit(
                f"startup replay inflated raw capture counts: {raw_counts_after_restart}"
            )
        if scalar(connection, "SELECT COUNT(*) FROM capture_observation") != 4:
            raise SystemExit("startup replay duplicated observations")
        if scalar(connection, "SELECT COUNT(*) FROM message_revision") != 3:
            raise SystemExit("startup replay duplicated revisions")
        if scalar(connection, "SELECT COUNT(*) FROM dom_witness") != 1:
            raise SystemExit("startup replay duplicated DOM witnesses")
        if scalar(connection, "SELECT COUNT(*) FROM reconciliation") != 1:
            raise SystemExit("startup replay duplicated reconciliation records")
    finally:
        connection.close()

print("sqlite projection smoke PASS")
