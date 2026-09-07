#!/usr/bin/env python3
import hashlib
import json
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import uuid
import zipfile

if len(sys.argv) != 2:
    raise SystemExit("usage: dna-archive-smoke.py <host-dll>")

host_dll = Path(sys.argv[1]).resolve()
page_url = "https://chatgpt.com/c/dna-archive-proof"
visible_phrase = "BKE DNA ARCHIVE DURABILITY PROOF"
body = json.dumps(
    {
        "conversation_id": "conversation-dna-1",
        "current_node": "node-assistant",
        "mapping": {
            "node-user": {
                "parent": None,
                "children": ["node-assistant"],
                "message": {
                    "id": "message-user",
                    "author": {"role": "user"},
                    "content": {"content_type": "text", "parts": [visible_phrase]},
                },
            },
            "node-assistant": {
                "parent": "node-user",
                "children": [],
                "message": {
                    "id": "message-assistant",
                    "author": {"role": "assistant"},
                    "content": {"content_type": "text", "parts": ["ARCHIVE ACK"]},
                },
            },
        },
    },
    separators=(",", ":"),
).encode("utf-8")
source_sha = hashlib.sha256(body).hexdigest()


def run_host(root, *args, stdin=b""):
    env = os.environ.copy()
    env["BKE_DNA_CAPTURE_ROOT"] = str(root)
    return subprocess.run(
        ["dotnet", str(host_dll), *args],
        input=stdin,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env=env,
        check=False,
    )


with tempfile.TemporaryDirectory(prefix="bke-dna-archive-") as temp:
    root = Path(temp) / "captures"
    for directory in [
        "bodies",
        "observations",
        "classifications",
        "normalized",
        "witnesses",
        "reconciliations",
    ]:
        (root / directory).mkdir(parents=True, exist_ok=True)

    (root / "bodies" / f"{source_sha}.body").write_bytes(body)

    capture_ids = [str(uuid.uuid4()), str(uuid.uuid4())]
    for index, capture_id in enumerate(capture_ids):
        observation = {
            "capture": {
                "type": "capture_start",
                "captureId": capture_id,
                "pageUrl": page_url,
                "requestUrl": f"https://chatgpt.com/response/{index}",
                "method": "GET",
                "status": 200,
                "contentType": "application/json",
                "initiator": "fetch",
                "capturedAt": f"2026-09-07T00:00:0{index}.000Z",
                "byteLength": len(body),
                "fidelity": "browser-application-response-body",
            },
            "sha256": source_sha,
            "byteLength": len(body),
            "bodyPath": f"bodies/{source_sha}.body",
            "storedAt": f"2026-09-07T00:00:1{index}.000Z",
        }
        (root / "observations" / f"{capture_id}.json").write_text(
            json.dumps(observation, indent=2), encoding="utf-8"
        )

    classification = {
        "sha256": source_sha,
        "byteLength": len(body),
        "contentType": "application/json",
        "classifiedAt": "2026-09-07T00:00:20.000Z",
        "classification": {
            "kind": "conversation_payload_candidate",
            "confidence": "high",
            "score": 12,
            "signals": ["conversation_graph_shape"],
        },
        "errorType": None,
    }
    (root / "classifications" / f"{source_sha}.json").write_text(
        json.dumps(classification, indent=2), encoding="utf-8"
    )

    normalized = {
        "sourceSha256": source_sha,
        "parser": "generic-mapping-graph-v0",
        "conversationNativeId": "conversation-dna-1",
        "currentNodeNativeId": "node-assistant",
        "coverageStatus": "complete",
        "coverageBasis": "structural_graph_closure_only",
        "rootFound": True,
        "currentNodeFound": True,
        "currentLeafFound": True,
        "parentChainComplete": True,
        "cycleDetected": False,
        "unresolvedParentNativeIds": [],
        "unresolvedChildNativeIds": [],
        "nodes": [
            {
                "nodeNativeId": "node-user",
                "messageNativeId": "message-user",
                "parentNativeId": None,
                "childNativeIds": ["node-assistant"],
                "role": "user",
                "createdAt": "1000",
                "textParts": [visible_phrase],
                "contentJson": json.dumps({"content_type": "text", "parts": [visible_phrase]}, separators=(",", ":")),
            },
            {
                "nodeNativeId": "node-assistant",
                "messageNativeId": "message-assistant",
                "parentNativeId": "node-user",
                "childNativeIds": [],
                "role": "assistant",
                "createdAt": "1001",
                "textParts": ["ARCHIVE ACK"],
                "contentJson": json.dumps({"content_type": "text", "parts": ["ARCHIVE ACK"]}, separators=(",", ":")),
            },
        ],
        "normalizedAt": "2026-09-07T00:00:30.000Z",
    }
    (root / "normalized" / f"{source_sha}.json").write_text(
        json.dumps(normalized, indent=2), encoding="utf-8"
    )

    witness_id = str(uuid.uuid4())
    witness = {
        "witnessId": witness_id,
        "pageUrl": page_url,
        "observedAt": "2026-09-07T00:00:40.000Z",
        "snippets": [visible_phrase],
        "fingerprintSha256": hashlib.sha256(visible_phrase.encode()).hexdigest(),
        "storedAt": "2026-09-07T00:00:41.000Z",
    }
    (root / "witnesses" / f"{witness_id}.json").write_text(
        json.dumps(witness, indent=2), encoding="utf-8"
    )

    reconciliation = {
        "witnessId": witness_id,
        "pageUrl": page_url,
        "status": "corroborated",
        "matches": [
            {
                "sha256": source_sha,
                "captureIds": capture_ids,
                "classifierScore": 12,
                "matchedSnippets": [visible_phrase],
                "matchRatio": 1.0,
            }
        ],
        "reconciledAt": "2026-09-07T00:00:42.000Z",
    }
    (root / "reconciliations" / f"{witness_id}.json").write_text(
        json.dumps(reconciliation, indent=2), encoding="utf-8"
    )

    # Initialize/replay filesystem evidence into SQLite, but do not archive yet.
    initialized = run_host(root)
    if initialized.returncode != 0:
        sys.stderr.write(initialized.stderr.decode("utf-8", errors="replace"))
        raise SystemExit("host failed while initializing archive proof state")

    database = root / "dna.sqlite3"
    connection = sqlite3.connect(database)
    try:
        durability = connection.execute(
            "SELECT dna_archive_id, dna_archive_sha256, archived_at, clearable "
            "FROM durability_state WHERE raw_sha256 = ?",
            (source_sha,),
        ).fetchone()
        if durability != (None, None, None, 0):
            raise SystemExit(f"raw capture became clearable before .dna verification: {durability}")

        try:
            connection.execute(
                "UPDATE durability_state SET clearable = 1 WHERE raw_sha256 = ?",
                (source_sha,),
            )
        except sqlite3.IntegrityError:
            connection.rollback()
        else:
            raise SystemExit("SQLite allowed clearable=1 before verified .dna metadata")
    finally:
        connection.close()

    first = run_host(root, "--archive", source_sha)
    if first.returncode != 0:
        sys.stderr.write(first.stderr.decode("utf-8", errors="replace"))
        raise SystemExit(".dna build/verify command failed")
    first_result = json.loads(first.stdout.decode("utf-8"))
    archive_path = Path(first_result["archivePath"])
    if archive_path.suffix != ".dna" or not archive_path.exists():
        raise SystemExit(".dna archive file was not created")
    if first_result["sourceSha256"] != source_sha:
        raise SystemExit("archive result source SHA mismatch")
    if len(first_result["archiveSha256"]) != 64:
        raise SystemExit("archive result does not contain SHA-256")

    with zipfile.ZipFile(archive_path, "r") as archive:
        names = set(archive.namelist())
        required = {
            "manifest.json",
            "raw/body.body",
            "normalized/conversation.json",
            "classification.json",
            "SHA256SUMS",
        }
        if not required.issubset(names):
            raise SystemExit(f".dna missing required entries: {sorted(required - names)}")
        if len([name for name in names if name.startswith("observations/")]) != 2:
            raise SystemExit(".dna did not preserve both capture observations")
        if len([name for name in names if name.startswith("witnesses/")]) != 1:
            raise SystemExit(".dna did not preserve relevant DOM witness")
        if len([name for name in names if name.startswith("reconciliations/")]) != 1:
            raise SystemExit(".dna did not preserve relevant reconciliation")
        manifest = json.loads(archive.read("manifest.json"))
        if manifest["format"] != "bke-dna" or manifest["formatVersion"] != 1:
            raise SystemExit("unexpected .dna manifest format")
        if manifest["sourceSha256"] != source_sha:
            raise SystemExit(".dna manifest source SHA mismatch")
        if manifest["coverageStatus"] != "complete":
            raise SystemExit(".dna manifest lost structural coverage status")
        if archive.read("raw/body.body") != body:
            raise SystemExit(".dna raw body bytes changed")

    verified = run_host(root, "--verify-dna", str(archive_path))
    if verified.returncode != 0:
        sys.stderr.write(verified.stderr.decode("utf-8", errors="replace"))
        raise SystemExit("fresh .dna failed independent verification")
    verification = json.loads(verified.stdout.decode("utf-8"))
    if verification["archiveSha256"] != first_result["archiveSha256"]:
        raise SystemExit("independent verifier produced different archive SHA")

    # Rebuilding the same evidence set must be byte-deterministic.
    second = run_host(root, "--archive", source_sha)
    if second.returncode != 0:
        sys.stderr.write(second.stderr.decode("utf-8", errors="replace"))
        raise SystemExit("second deterministic .dna build failed")
    second_result = json.loads(second.stdout.decode("utf-8"))
    if second_result["archiveId"] != first_result["archiveId"]:
        raise SystemExit("same evidence produced a different deterministic archive ID")
    if second_result["archiveSha256"] != first_result["archiveSha256"]:
        raise SystemExit("same evidence produced different .dna bytes")

    connection = sqlite3.connect(database)
    try:
        durability = connection.execute(
            "SELECT dna_archive_id, dna_archive_sha256, archived_at, clearable "
            "FROM durability_state WHERE raw_sha256 = ?",
            (source_sha,),
        ).fetchone()
        if durability[0] != first_result["archiveId"]:
            raise SystemExit("SQLite durability archive ID mismatch")
        if durability[1] != first_result["archiveSha256"]:
            raise SystemExit("SQLite durability archive SHA mismatch")
        if not durability[2] or durability[3] != 1:
            raise SystemExit("verified .dna did not make raw capture cleanup-eligible")
    finally:
        connection.close()

    # Corrupt a copy while preserving the original checksum file. Verification must fail.
    corrupted_path = root / "archives" / "corrupted-proof.dna"
    with zipfile.ZipFile(archive_path, "r") as source, zipfile.ZipFile(
        corrupted_path, "w", compression=zipfile.ZIP_STORED
    ) as target:
        for info in source.infolist():
            payload = source.read(info.filename)
            if info.filename == "raw/body.body":
                payload += b"CORRUPTION"
            target.writestr(info.filename, payload)

    corrupted = run_host(root, "--verify-dna", str(corrupted_path))
    if corrupted.returncode == 0:
        raise SystemExit("corrupted .dna incorrectly passed verification")
    if b"Checksum mismatch" not in corrupted.stderr:
        raise SystemExit("corrupted .dna failed for an unexpected reason")

print("dna archive smoke PASS")
