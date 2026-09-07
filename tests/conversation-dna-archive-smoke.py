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
sources = [source_a, source_b]


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


def content(text):
    return json.dumps({"content_type": "text", "parts": [text]}, separators=(",", ":"))


with tempfile.TemporaryDirectory(prefix="bke-dna-conversation-archive-") as temp:
    root = Path(temp) / "captures"
    for directory in [
        "bodies",
        "observations",
        "normalized",
        "classifications",
        "conversations",
        "witnesses",
        "reconciliations",
    ]:
        (root / directory).mkdir(parents=True, exist_ok=True)

    (root / "bodies" / f"{source_a}.body").write_bytes(body_a)
    (root / "bodies" / f"{source_b}.body").write_bytes(body_b)

    capture_ids = {}
    for index, (source_sha, body) in enumerate([(source_a, body_a), (source_b, body_b)]):
        capture_id = str(uuid.uuid4())
        capture_ids[source_sha] = capture_id
        captured_at = f"2026-09-07T00:00:0{index + 1}.000Z"
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

        normalized = {
            "sourceSha256": source_sha,
            "parser": "generic-mapping-graph-v0",
            "conversationNativeId": conversation_id,
            "currentNodeNativeId": "assistant-a" if index == 0 else "assistant-b",
            "coverageStatus": "complete",
            "coverageBasis": "structural_graph_closure_only",
            "rootFound": True,
            "currentNodeFound": True,
            "currentLeafFound": True,
            "parentChainComplete": True,
            "cycleDetected": False,
            "unresolvedParentNativeIds": [],
            "unresolvedChildNativeIds": [],
            "nodes": [],
            "normalizedAt": captured_at,
        }
        (root / "normalized" / f"{source_sha}.json").write_text(
            json.dumps(normalized, indent=2), encoding="utf-8"
        )

        classification = {
            "sha256": source_sha,
            "byteLength": len(body),
            "contentType": "application/json",
            "classifiedAt": captured_at,
            "classification": {
                "kind": "conversation_payload_candidate",
                "confidence": "high",
                "score": 10,
                "signals": ["conversation_graph_shape"],
            },
            "errorType": None,
        }
        (root / "classifications" / f"{source_sha}.json").write_text(
            json.dumps(classification, indent=2), encoding="utf-8"
        )

    original_content = content("ORIGINAL PROMPT")
    edited_content = content("EDITED PROMPT")
    answer_a_content = content("ANSWER A")
    answer_b_content = content("ANSWER B")
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
                "revisions": [
                    {
                        "revisionSha256": hashlib.sha256(original_content.encode()).hexdigest(),
                        "contentJson": original_content,
                        "textParts": ["ORIGINAL PROMPT"],
                        "sourceSha256s": [source_a],
                        "firstObservedAt": "2026-09-07T00:00:01.000Z",
                        "lastObservedAt": "2026-09-07T00:00:01.000Z",
                    },
                    {
                        "revisionSha256": hashlib.sha256(edited_content.encode()).hexdigest(),
                        "contentJson": edited_content,
                        "textParts": ["EDITED PROMPT"],
                        "sourceSha256s": [source_b],
                        "firstObservedAt": "2026-09-07T00:00:02.000Z",
                        "lastObservedAt": "2026-09-07T00:00:02.000Z",
                    },
                ],
            },
            {
                "nodeNativeId": "assistant-a",
                "messageNativeIds": ["message-assistant-a"],
                "parentNativeIds": ["user-root"],
                "childNativeIds": [],
                "roles": ["assistant"],
                "revisions": [{
                    "revisionSha256": hashlib.sha256(answer_a_content.encode()).hexdigest(),
                    "contentJson": answer_a_content,
                    "textParts": ["ANSWER A"],
                    "sourceSha256s": [source_a],
                    "firstObservedAt": "2026-09-07T00:00:01.000Z",
                    "lastObservedAt": "2026-09-07T00:00:01.000Z",
                }],
            },
            {
                "nodeNativeId": "assistant-b",
                "messageNativeIds": ["message-assistant-b"],
                "parentNativeIds": ["user-root"],
                "childNativeIds": [],
                "roles": ["assistant"],
                "revisions": [{
                    "revisionSha256": hashlib.sha256(answer_b_content.encode()).hexdigest(),
                    "contentJson": answer_b_content,
                    "textParts": ["ANSWER B"],
                    "sourceSha256s": [source_b],
                    "firstObservedAt": "2026-09-07T00:00:02.000Z",
                    "lastObservedAt": "2026-09-07T00:00:02.000Z",
                }],
            },
        ],
    }
    (root / "conversations" / f"{conversation_key}.json").write_text(
        json.dumps(state, indent=2), encoding="utf-8"
    )

    witness_id = str(uuid.uuid4())
    witness = {
        "witnessId": witness_id,
        "pageUrl": page_url,
        "observedAt": "2026-09-07T00:00:03.000Z",
        "snippets": ["EDITED PROMPT", "ANSWER B"],
        "fingerprintSha256": hashlib.sha256(b"EDITED PROMPT\nANSWER B").hexdigest(),
        "storedAt": "2026-09-07T00:00:03.000Z",
    }
    (root / "witnesses" / f"{witness_id}.json").write_text(
        json.dumps(witness, indent=2), encoding="utf-8"
    )
    reconciliation = {
        "witnessId": witness_id,
        "pageUrl": page_url,
        "status": "corroborated",
        "matches": [{
            "sha256": source_b,
            "captureIds": [capture_ids[source_b]],
            "classifierScore": 10,
            "matchedSnippets": ["EDITED PROMPT", "ANSWER B"],
            "matchRatio": 1.0,
        }],
        "reconciledAt": "2026-09-07T00:00:03.000Z",
    }
    (root / "reconciliations" / f"{witness_id}.json").write_text(
        json.dumps(reconciliation, indent=2), encoding="utf-8"
    )

    initialized = run_host(root)
    if initialized.returncode != 0:
        sys.stderr.write(initialized.stderr.decode("utf-8", errors="replace"))
        raise SystemExit("host failed initializing conversation archive proof state")

    database = root / "dna.sqlite3"
    connection = sqlite3.connect(database)
    try:
        before = connection.execute(
            "SELECT raw_sha256, dna_archive_id, clearable FROM durability_state "
            "WHERE raw_sha256 IN (?, ?) ORDER BY raw_sha256",
            (source_a, source_b),
        ).fetchall()
        if before != [(source, None, 0) for source in sorted(sources)]:
            raise SystemExit(f"conversation sources were clearable before archive verification: {before}")

        # Force source B's durability update to fail. Source A is updated first inside
        # the same transaction, so rollback proves all-source atomicity.
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
        expected_uncommitted = [(source, None, None, None, 0) for source in sorted(sources)]
        if states != expected_uncommitted:
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
    if result["sourceSha256s"] != sorted(sources):
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
        if archived_state["conversationNativeId"] != conversation_id:
            raise SystemExit("conversation .dna state lost native conversation identity")
        user = next(node for node in archived_state["nodes"] if node["nodeNativeId"] == "user-root")
        if set(user["childNativeIds"]) != {"assistant-a", "assistant-b"}:
            raise SystemExit("conversation .dna flattened branch graph")
        if {tuple(revision["textParts"]) for revision in user["revisions"]} != {
            ("ORIGINAL PROMPT",), ("EDITED PROMPT",)
        }:
            raise SystemExit("conversation .dna lost prompt revision archaeology")
        manifest = json.loads(archive.read("manifest.json"))
        if manifest["formatVersion"] != 2 or manifest["scope"] != "conversation":
            raise SystemExit("conversation .dna manifest format mismatch")
        if manifest["sourceSha256s"] != sorted(sources):
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
            for source in sorted(sources)
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
