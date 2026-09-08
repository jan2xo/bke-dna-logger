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
    raise SystemExit("usage: cross-device-dna-import-smoke.py <host-dll>")

host_dll = Path(sys.argv[1]).resolve()


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


def node(node_id, message_id, parent, children, role, text, created_at):
    return {
        "nodeNativeId": node_id,
        "messageNativeId": message_id,
        "parentNativeId": parent,
        "childNativeIds": children,
        "role": role,
        "createdAt": created_at,
        "textParts": [text],
        "contentJson": json.dumps({"content_type": "text", "parts": [text]}, separators=(",", ":")),
    }


def build_device(base, conversation_id, suffix, captured_at):
    root = base / f"device-{suffix}" / "captures"
    for directory in ["bodies", "observations", "normalized"]:
        (root / directory).mkdir(parents=True, exist_ok=True)

    body = json.dumps({"conversation_id": conversation_id, "device": suffix}, separators=(",", ":")).encode()
    source = hashlib.sha256(body).hexdigest()
    capture_id = str(uuid.uuid4())
    page_url = f"https://chatgpt.com/c/{conversation_id}"
    (root / "bodies" / f"{source}.body").write_bytes(body)
    (root / "observations" / f"{capture_id}.json").write_text(
        json.dumps(
            {
                "capture": {
                    "type": "capture_start",
                    "captureId": capture_id,
                    "pageUrl": page_url,
                    "requestUrl": f"https://chatgpt.com/backend-api/conversation/{conversation_id}",
                    "method": "GET",
                    "status": 200,
                    "contentType": "application/json",
                    "initiator": "fetch",
                    "capturedAt": captured_at,
                    "byteLength": len(body),
                    "fidelity": "browser-application-response-body",
                },
                "sha256": source,
                "byteLength": len(body),
                "bodyPath": f"bodies/{source}.body",
                "storedAt": captured_at,
            },
            indent=2,
        ),
        encoding="utf-8",
    )
    assistant = f"assistant-{suffix}"
    normalized = {
        "sourceSha256": source,
        "parser": "generic-mapping-graph-v0",
        "conversationNativeId": conversation_id,
        "currentNodeNativeId": assistant,
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
            node("user-root", "message-user", None, [assistant], "user", "SAME PROMPT", "1000"),
            node(assistant, f"message-{assistant}", "user-root", [], "assistant", f"ANSWER {suffix}", "1001"),
        ],
        "normalizedAt": captured_at,
    }
    (root / "normalized" / f"{source}.json").write_text(json.dumps(normalized, indent=2), encoding="utf-8")

    init = run_host(root)
    if init.returncode != 0:
        sys.stderr.write(init.stderr.decode(errors="replace"))
        raise SystemExit(f"device {suffix} initialization failed")
    archived = run_host(root, "--archive-conversation", conversation_id)
    if archived.returncode != 0:
        sys.stderr.write(archived.stderr.decode(errors="replace"))
        raise SystemExit(f"device {suffix} archive failed")
    result = json.loads(archived.stdout.decode())
    return Path(result["archivePath"]), source, capture_id


with tempfile.TemporaryDirectory(prefix="bke-dna-cross-device-") as temp:
    base = Path(temp)
    conversation_id = "cross-device-conversation-proof"
    archive_a, source_a, capture_a = build_device(base, conversation_id, "android", "2026-09-08T01:00:00.000Z")
    archive_b, source_b, capture_b = build_device(base, conversation_id, "mac", "2026-09-08T01:01:00.000Z")

    target = base / "reconciler" / "captures"
    reconciled = run_host(target, "--reconcile-conversation-dna", str(archive_a), str(archive_b))
    if reconciled.returncode != 0:
        sys.stderr.write(reconciled.stderr.decode(errors="replace"))
        raise SystemExit("cross-device reconciliation import failed")
    result = json.loads(reconciled.stdout.decode())
    if result["contractId"] != "bke-dna-reconciliation-v1":
        raise SystemExit("reconciliation contract identity drifted")
    if result["conversationNativeId"] != conversation_id or result["archiveCount"] != 2:
        raise SystemExit("reconciliation result lost conversation/archive identity")
    if result["sourceSha256s"] != sorted([source_a, source_b]):
        raise SystemExit("reconciliation did not deduplicate/order source identities")

    conversation_key = hashlib.sha256(conversation_id.encode()).hexdigest()
    state_path = target / "conversations" / f"{conversation_key}.json"
    state = json.loads(state_path.read_text(encoding="utf-8"))
    root_node = next(item for item in state["nodes"] if item["nodeNativeId"] == "user-root")
    if set(root_node["childNativeIds"]) != {"assistant-android", "assistant-mac"}:
        raise SystemExit("cross-device aggregation flattened or lost branches")
    if {item["nodeNativeId"] for item in state["nodes"]} != {"user-root", "assistant-android", "assistant-mac"}:
        raise SystemExit("cross-device node identity union mismatch")
    if not (target / "observations" / f"{capture_a}.json").exists() or not (target / "observations" / f"{capture_b}.json").exists():
        raise SystemExit("cross-device reconciliation lost provenance observations")

    database = target / "dna.sqlite3"
    with sqlite3.connect(database) as connection:
        rows = connection.execute(
            "SELECT raw_sha256, clearable FROM durability_state WHERE raw_sha256 IN (?, ?) ORDER BY raw_sha256",
            (source_a, source_b),
        ).fetchall()
    if rows != [(source, 0) for source in sorted([source_a, source_b])]:
        raise SystemExit(f"imported evidence became clearable without manual archive: {rows}")

    if list(target.rglob("*.dna")):
        raise SystemExit("reconciliation import auto-exported .dna")

    manual = run_host(target, "--archive-conversation", conversation_id)
    if manual.returncode != 0:
        sys.stderr.write(manual.stderr.decode(errors="replace"))
        raise SystemExit("manual canonical conversation archive failed after reconciliation")
    manual_result = json.loads(manual.stdout.decode())
    if manual_result["sourceSha256s"] != sorted([source_a, source_b]):
        raise SystemExit("manual canonical archive omitted reconciled sources")

    other_archive, _, _ = build_device(base, "different-conversation-proof", "windows", "2026-09-08T01:02:00.000Z")
    rejected_target = base / "mixed-rejected" / "captures"
    mixed = run_host(rejected_target, "--reconcile-conversation-dna", str(archive_a), str(other_archive))
    if mixed.returncode == 0:
        raise SystemExit("mixed conversationNativeId archives were incorrectly reconciled")
    bodies = rejected_target / "bodies"
    if bodies.exists() and any(bodies.iterdir()):
        raise SystemExit("mixed-conversation rejection wrote imported raw evidence")

print("cross-device DNA import reconciliation smoke PASS")
