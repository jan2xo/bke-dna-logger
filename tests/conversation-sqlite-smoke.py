#!/usr/bin/env python3
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import uuid

if len(sys.argv) != 2:
    raise SystemExit("usage: conversation-sqlite-smoke.py <host-dll>")

host_dll = Path(sys.argv[1]).resolve()
conversation_id = "sqlite-logical-conversation-proof"
conversation_key = hashlib.sha256(conversation_id.encode()).hexdigest()
source_a_body = b"logical source A"
source_b_body = b"logical source B"
source_a = hashlib.sha256(source_a_body).hexdigest()
source_b = hashlib.sha256(source_b_body).hexdigest()


def revision(text, source_sha, observed_at):
    content_json = json.dumps({"content_type": "text", "parts": [text]}, separators=(",", ":"))
    return {
        "revisionSha256": hashlib.sha256(content_json.encode()).hexdigest(),
        "contentJson": content_json,
        "textParts": [text],
        "sourceSha256s": [source_sha],
        "firstObservedAt": observed_at,
        "lastObservedAt": observed_at,
    }


def run_host(root):
    env = os.environ.copy()
    env["BKE_DNA_CAPTURE_ROOT"] = str(root)
    return subprocess.run(
        ["dotnet", str(host_dll)],
        input=b"",
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env=env,
        check=False,
    )


with tempfile.TemporaryDirectory(prefix="bke-dna-conversation-sqlite-") as temp:
    root = Path(temp) / "captures"
    for directory in ["bodies", "observations", "conversations"]:
        (root / directory).mkdir(parents=True, exist_ok=True)

    for source_sha, body, captured_at in [
        (source_a, source_a_body, "2026-09-07T00:00:01.000Z"),
        (source_b, source_b_body, "2026-09-07T00:00:02.000Z"),
    ]:
        (root / "bodies" / f"{source_sha}.body").write_bytes(body)
        capture_id = str(uuid.uuid4())
        observation = {
            "capture": {
                "type": "capture_start",
                "captureId": capture_id,
                "pageUrl": "https://chatgpt.com/c/sqlite-logical-proof",
                "requestUrl": f"https://chatgpt.com/response/{capture_id}",
                "method": "GET",
                "status": 200,
                "contentType": "application/json",
                "initiator": "fetch",
                "capturedAt": captured_at,
                "byteLength": len(body),
                "fidelity": "browser-application-response-body",
            },
            "sha256": source_sha,
            "byteLength": len(body),
            "bodyPath": f"bodies/{source_sha}.body",
            "storedAt": captured_at,
        }
        (root / "observations" / f"{capture_id}.json").write_text(
            json.dumps(observation, indent=2), encoding="utf-8"
        )

    user_revisions = [
        revision("ORIGINAL", source_a, "2026-09-07T00:00:01.000Z"),
        revision("EDITED", source_b, "2026-09-07T00:00:02.000Z"),
    ]
    assistant_a_revision = revision("ANSWER A", source_a, "2026-09-07T00:00:01.000Z")
    assistant_b_revision = revision("ANSWER B", source_b, "2026-09-07T00:00:02.000Z")

    state = {
        "conversationKey": conversation_key,
        "conversationNativeId": conversation_id,
        "currentNodeNativeId": "assistant-b",
        "stateObservedThrough": "2026-09-07T00:00:02.000Z",
        "coverageStatus": "complete",
        "coverageBasis": "multi_snapshot_structural_union",
        "rootFound": True,
        "currentNodeFound": True,
        "currentLeafFound": True,
        "parentChainComplete": True,
        "cycleDetected": False,
        "unresolvedParentNativeIds": [],
        "unresolvedChildNativeIds": [],
        "sources": [
            {
                "sourceSha256": source_a,
                "observedAt": "2026-09-07T00:00:01.000Z",
                "currentNodeNativeId": "assistant-a",
                "coverageStatus": "complete",
                "coverageBasis": "structural_graph_closure_only",
            },
            {
                "sourceSha256": source_b,
                "observedAt": "2026-09-07T00:00:02.000Z",
                "currentNodeNativeId": "assistant-b",
                "coverageStatus": "complete",
                "coverageBasis": "structural_graph_closure_only",
            },
        ],
        "nodes": [
            {
                "nodeNativeId": "user-root",
                "messageNativeIds": ["message-user"],
                "parentNativeIds": [],
                "childNativeIds": ["assistant-a", "assistant-b"],
                "roles": ["user"],
                "revisions": user_revisions,
            },
            {
                "nodeNativeId": "assistant-a",
                "messageNativeIds": ["message-assistant-a"],
                "parentNativeIds": ["user-root"],
                "childNativeIds": [],
                "roles": ["assistant"],
                "revisions": [assistant_a_revision],
            },
            {
                "nodeNativeId": "assistant-b",
                "messageNativeIds": ["message-assistant-b"],
                "parentNativeIds": ["user-root"],
                "childNativeIds": [],
                "roles": ["assistant"],
                "revisions": [assistant_b_revision],
            },
        ],
    }
    (root / "conversations" / f"{conversation_key}.json").write_text(
        json.dumps(state, indent=2), encoding="utf-8"
    )

    first = run_host(root)
    if first.returncode != 0:
        sys.stderr.write(first.stderr.decode("utf-8", errors="replace"))
        raise SystemExit("host failed projecting logical conversation")

    database = root / "dna.sqlite3"
    connection = sqlite3.connect(database)
    try:
        conversation = connection.execute(
            "SELECT conversation_native_id, current_node_native_id, state_observed_through, coverage_status, coverage_basis "
            "FROM logical_conversation WHERE conversation_key = ?",
            (conversation_key,),
        ).fetchone()
        if conversation != (
            conversation_id,
            "assistant-b",
            "2026-09-07T00:00:02.000Z",
            "complete",
            "multi_snapshot_structural_union",
        ):
            raise SystemExit(f"logical conversation projection mismatch: {conversation}")

        source_rows = connection.execute(
            "SELECT source_sha256, observed_at FROM conversation_source WHERE conversation_key = ? ORDER BY observed_at",
            (conversation_key,),
        ).fetchall()
        if source_rows != [
            (source_a, "2026-09-07T00:00:01.000Z"),
            (source_b, "2026-09-07T00:00:02.000Z"),
        ]:
            raise SystemExit("logical conversation lost ordered raw-source lineage")

        counts = {
            "nodes": connection.execute(
                "SELECT COUNT(*) FROM logical_message_node WHERE conversation_key = ?", (conversation_key,)
            ).fetchone()[0],
            "edges": connection.execute(
                "SELECT COUNT(*) FROM logical_message_edge WHERE conversation_key = ?", (conversation_key,)
            ).fetchone()[0],
            "revisions": connection.execute(
                "SELECT COUNT(*) FROM logical_message_revision WHERE conversation_key = ?", (conversation_key,)
            ).fetchone()[0],
            "revision_sources": connection.execute(
                "SELECT COUNT(*) FROM logical_message_revision_source WHERE conversation_key = ?", (conversation_key,)
            ).fetchone()[0],
        }
        if counts != {"nodes": 3, "edges": 2, "revisions": 4, "revision_sources": 4}:
            raise SystemExit(f"logical conversation graph/revision counts mismatch: {counts}")

        user_revision_texts = {
            json.loads(row[0])["parts"][0]
            for row in connection.execute(
                "SELECT content_json FROM logical_message_revision "
                "WHERE conversation_key = ? AND node_native_id = 'user-root'",
                (conversation_key,),
            )
        }
        if user_revision_texts != {"ORIGINAL", "EDITED"}:
            raise SystemExit("SQLite logical conversation flattened prompt revisions")

        user_children = json.loads(
            connection.execute(
                "SELECT child_native_ids_json FROM logical_message_node "
                "WHERE conversation_key = ? AND node_native_id = 'user-root'",
                (conversation_key,),
            ).fetchone()[0]
        )
        if set(user_children) != {"assistant-a", "assistant-b"}:
            raise SystemExit("SQLite logical conversation flattened branch edges")
    finally:
        connection.close()

    # Replaying filesystem state must replace the same logical rows, not duplicate them.
    second = run_host(root)
    if second.returncode != 0:
        sys.stderr.write(second.stderr.decode("utf-8", errors="replace"))
        raise SystemExit("host failed replaying logical conversation projection")

    connection = sqlite3.connect(database)
    try:
        if connection.execute("SELECT COUNT(*) FROM logical_conversation").fetchone()[0] != 1:
            raise SystemExit("logical conversation replay duplicated conversation rows")
        if connection.execute("SELECT COUNT(*) FROM conversation_source").fetchone()[0] != 2:
            raise SystemExit("logical conversation replay duplicated source rows")
        if connection.execute("SELECT COUNT(*) FROM logical_message_revision").fetchone()[0] != 4:
            raise SystemExit("logical conversation replay duplicated revision rows")
    finally:
        connection.close()

print("conversation sqlite smoke PASS")
