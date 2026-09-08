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
import zipfile

if len(sys.argv) != 2:
    raise SystemExit("usage: conversation-dna-archive-smoke.py <host-dll>")

host_dll = Path(sys.argv[1]).resolve()
conversation_id = "conversation-dna-v2-proof"
conversation_key = hashlib.sha256(conversation_id.encode()).hexdigest()
page_url = "https://chatgpt.com/c/conversation-dna-v2-proof"
body_a = b'{"conversation_id":"conversation-dna-v2-proof","snapshot":"A"}'
body_b = b'{"conversation_id":"conversation-dna-v2-proof","snapshot":"B"}'
source_a = hashlib.sha256(body_a).hexdigest()
source_b = hashlib.sha256(body_b).hexdigest()
sources = sorted([source_a, source_b])


def run_host(root, *args):
    env = os.environ.copy()
    env["BKE_DNA_CAPTURE_ROOT"] = str(root)
    return subprocess.run(
        ["dotnet", str(host_dll), *args],
        input=b"",
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env=env,
        check=False,
    )


def content_json(text):
    return json.dumps({"content_type": "text", "parts": [text]}, separators=(",", ":"))


def node(node_id, message_id, parent, children, role, text):
    return {
        "nodeNativeId": node_id,
        "messageNativeId": message_id,
        "parentNativeId": parent,
        "childNativeIds": children,
        "role": role,
        "createdAt": "1000",
        "textParts": [text],
        "contentJson": content_json(text),
    }


def normalized_snapshot(source_sha, current_node, nodes, observed_at):
    return {
        "sourceSha256": source_sha,
        "parser": "generic-mapping-graph-v0",
        "conversationNativeId": conversation_id,
        "currentNodeNativeId": current_node,
        "coverageStatus": "complete",
        "coverageBasis": "structural_graph_closure_only",
        "rootFound": True,
        "currentNodeFound": True,
        "currentLeafFound": True,
        "parentChainComplete": True,
        "cycleDetected": False,
        "unresolvedParentNativeIds": [],
        "unresolvedChildNativeIds": [],
        "nodes": nodes,
        "normalizedAt": observed_at,
    }


with tempfile.TemporaryDirectory(prefix="bke-dna-conversation-archive-") as temp:
    root = Path(temp) / "captures"
    for directory in ["bodies", "observations", "normalized"]:
        (root / directory).mkdir(parents=True, exist_ok=True)

    (root / "bodies" / f"{source_a}.body").write_bytes(body_a)
    (root / "bodies" / f"{source_b}.body").write_bytes(body_b)

    snapshots = [
        (
            source_a,
            body_a,
            "2026-09-07T00:00:01.000Z",
            normalized_snapshot(
                source_a,
                "assistant-a",
                [
                    node("user-root", "message-user", None, ["assistant-a"], "user", "ORIGINAL PROMPT"),
                    node("assistant-a", "message-assistant-a", "user-root", [], "assistant", "ANSWER A"),
                ],
                "2026-09-07T00:00:01.000Z",
            ),
        ),
        (
            source_b,
            body_b,
            "2026-09-07T00:00:02.000Z",
            normalized_snapshot(
                source_b,
                "assistant-b",
                [
                    node("user-root", "message-user", None, ["assistant-b"], "user", "EDITED PROMPT"),
                    node("assistant-b", "message-assistant-b", "user-root", [], "assistant", "ANSWER B"),
                ],
                "2026-09-07T00:00:02.000Z",
            ),
        ),
    ]

    for source_sha, body, captured_at, normalized in snapshots:
        capture_id = str(uuid.uuid4())
        observation = {
            "capture": {
                "type": "capture_start",
                "captureId": capture_id,
                "pageUrl": page_url,
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
        (root / "normalized" / f"{source_sha}.json").write_text(
            json.dumps(normalized, indent=2), encoding="utf-8"
        )

    initialized = run_host(root)
    if initialized.returncode != 0:
        sys.stderr.write(initialized.stderr.decode("utf-8", errors="replace"))
        raise SystemExit("host failed initializing conversation archive proof state")

    state_path = root / "conversations" / f"{conversation_key}.json"
    if not state_path.exists():
        raise SystemExit("real aggregation did not create conversation state")
    state = json.loads(state_path.read_text(encoding="utf-8"))
    if state["conversationNativeId"] != conversation_id:
        raise SystemExit("aggregated fixture lost native conversation identity")
    if [source["sourceSha256"] for source in state["sources"]] != [source_a, source_b]:
        raise SystemExit("aggregated fixture did not preserve observation-order source lineage")
    user = next(node for node in state["nodes"] if node["nodeNativeId"] == "user-root")
    if set(user["childNativeIds"]) != {"assistant-a", "assistant-b"}:
        raise SystemExit("aggregated fixture did not preserve both branches")
    if {tuple(revision["textParts"]) for revision in user["revisions"]} != {
        ("ORIGINAL PROMPT",),
        ("EDITED PROMPT",),
    }:
        raise SystemExit("aggregated fixture did not preserve prompt revisions")

    database = root / "dna.sqlite3"
    connection = sqlite3.connect(database)
    try:
        before = connection.execute(
            "SELECT raw_sha256, dna_archive_id, clearable FROM durability_state "
            "WHERE raw_sha256 IN (?, ?) ORDER BY raw_sha256",
            (source_a, source_b),
        ).fetchall()
        if before != [(source, None, 0) for source in sources]:
            raise SystemExit(f"conversation sources were clearable before archive verification: {before}")

        connection.execute(
            f"""
            CREATE TRIGGER reject_source_b_archive
            BEFORE UPDATE OF clearable ON durability_state
            WHEN NEW.raw_sha256 = '{source_b}' AND NEW.clearable = 1
            BEGIN
                SELECT RAISE(ABORT, 'forced source B durability failure');
            END;
            """
        )
        connection.commit()
    finally:
        connection.close()

    atomic_failure = run_host(root, "--archive-conversation", conversation_id)
    if atomic_failure.returncode == 0:
        raise SystemExit("conversation archive durability incorrectly survived forced source B failure")

    connection = sqlite3.connect(database)
    try:
        states = connection.execute(
            "SELECT raw_sha256, dna_archive_id, dna_archive_sha256, archived_at, clearable "
            "FROM durability_state WHERE raw_sha256 IN (?, ?) ORDER BY raw_sha256",
            (source_a, source_b),
        ).fetchall()
        if states != [(source, None, None, None, 0) for source in sources]:
            raise SystemExit(f"failed all-source durability transaction partially committed: {states}")
        connection.execute("DROP TRIGGER reject_source_b_archive")
        connection.commit()
    finally:
        connection.close()

    first = run_host(root, "--archive-conversation", conversation_id)
    if first.returncode != 0:
        sys.stderr.write(first.stderr.decode("utf-8", errors="replace"))
        raise SystemExit("conversation .dna build/verify failed")
    result = json.loads(first.stdout.decode("utf-8"))
    if result["conversationKey"] != conversation_key or result["conversationNativeId"] != conversation_id:
        raise SystemExit("conversation archive identity mismatch")
    if result["sourceSha256s"] != sources:
        raise SystemExit("conversation archive source set mismatch")

    archive_path = Path(result["archivePath"])
    with zipfile.ZipFile(archive_path, "r") as archive:
        names = set(archive.namelist())
        required = {
            "manifest.json",
            "conversation/state.json",
            "SHA256SUMS",
            f"sources/{source_a}/raw.body",
            f"sources/{source_b}/raw.body",
            f"sources/{source_a}/normalized.json",
            f"sources/{source_b}/normalized.json",
        }
        if not required.issubset(names):
            raise SystemExit(f"conversation .dna missing required entries: {sorted(required - names)}")
        if archive.read(f"sources/{source_a}/raw.body") != body_a:
            raise SystemExit("conversation .dna changed source A raw bytes")
        if archive.read(f"sources/{source_b}/raw.body") != body_b:
            raise SystemExit("conversation .dna changed source B raw bytes")

        archived_state = json.loads(archive.read("conversation/state.json"))
        archived_user = next(
            node for node in archived_state["nodes"] if node["nodeNativeId"] == "user-root"
        )
        if set(archived_user["childNativeIds"]) != {"assistant-a", "assistant-b"}:
            raise SystemExit("conversation .dna flattened branch graph")
        if {tuple(revision["textParts"]) for revision in archived_user["revisions"]} != {
            ("ORIGINAL PROMPT",),
            ("EDITED PROMPT",),
        }:
            raise SystemExit("conversation .dna lost prompt revision archaeology")

        manifest = json.loads(archive.read("manifest.json"))
        if manifest["formatVersion"] != 2 or manifest["scope"] != "conversation":
            raise SystemExit("conversation .dna manifest format mismatch")
        if manifest["sourceSha256s"] != sources:
            raise SystemExit("conversation .dna manifest source set mismatch")

    verified = run_host(root, "--verify-conversation-dna", str(archive_path))
    if verified.returncode != 0:
        sys.stderr.write(verified.stderr.decode("utf-8", errors="replace"))
        raise SystemExit("fresh conversation .dna failed independent verification")
    verification = json.loads(verified.stdout.decode("utf-8"))
    if verification["archiveSha256"] != result["archiveSha256"]:
        raise SystemExit("conversation independent verifier produced different archive SHA")

    second = run_host(root, "--archive-conversation", conversation_key)
    if second.returncode != 0:
        raise SystemExit("deterministic conversation .dna rebuild failed")
    second_result = json.loads(second.stdout.decode("utf-8"))
    if second_result["archiveId"] != result["archiveId"] or second_result["archiveSha256"] != result["archiveSha256"]:
        raise SystemExit("same conversation evidence produced different deterministic .dna bytes")

    connection = sqlite3.connect(database)
    try:
        rows = connection.execute(
            "SELECT raw_sha256, dna_archive_id, dna_archive_sha256, clearable "
            "FROM durability_state WHERE raw_sha256 IN (?, ?) ORDER BY raw_sha256",
            (source_a, source_b),
        ).fetchall()
        expected = [
            (source, result["archiveId"], result["archiveSha256"], 1)
            for source in sources
        ]
        if rows != expected:
            raise SystemExit(f"verified conversation .dna did not mark every source durable: {rows}")
    finally:
        connection.close()

    corrupted_path = root / "archives" / "corrupted-conversation-proof.dna"
    with zipfile.ZipFile(archive_path, "r") as source_zip, zipfile.ZipFile(
        corrupted_path, "w", compression=zipfile.ZIP_STORED
    ) as target_zip:
        for info in source_zip.infolist():
            payload = source_zip.read(info.filename)
            if info.filename == f"sources/{source_b}/raw.body":
                payload += b"CORRUPTION"
            target_zip.writestr(info.filename, payload)

    corrupted = run_host(root, "--verify-conversation-dna", str(corrupted_path))
    if corrupted.returncode == 0:
        raise SystemExit("corrupted conversation .dna incorrectly passed verification")
    if b"Checksum mismatch" not in corrupted.stderr:
        raise SystemExit("corrupted conversation .dna failed for an unexpected reason")

print("conversation dna archive smoke PASS")
