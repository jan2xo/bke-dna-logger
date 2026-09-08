#!/usr/bin/env python3
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import uuid

if len(sys.argv) != 2:
    raise SystemExit("usage: conversation-aggregation-smoke.py <host-dll>")

host_dll = Path(sys.argv[1]).resolve()
conversation_id = "conversation-aggregation-proof"
conversation_key = hashlib.sha256(conversation_id.encode()).hexdigest()
partial_conversation_id = "conversation-partial-proof"
partial_key = hashlib.sha256(partial_conversation_id.encode()).hexdigest()


def sha(body):
    return hashlib.sha256(body).hexdigest()


def write_source(root, source_sha, captured_at, normalized):
    (root / "bodies" / f"{source_sha}.body").write_bytes(source_sha.encode())
    capture_id = str(uuid.uuid4())
    observation = {
        "capture": {
            "type": "capture_start",
            "captureId": capture_id,
            "pageUrl": f"https://chatgpt.com/c/{normalized['conversationNativeId']}",
            "requestUrl": f"https://chatgpt.com/response/{capture_id}",
            "method": "GET",
            "status": 200,
            "contentType": "application/json",
            "initiator": "fetch",
            "capturedAt": captured_at,
            "byteLength": 64,
            "fidelity": "browser-application-response-body",
        },
        "sha256": source_sha,
        "byteLength": 64,
        "bodyPath": f"bodies/{source_sha}.body",
        "storedAt": captured_at,
    }
    (root / "observations" / f"{capture_id}.json").write_text(
        json.dumps(observation, indent=2), encoding="utf-8"
    )
    (root / "normalized" / f"{source_sha}.json").write_text(
        json.dumps(normalized, indent=2), encoding="utf-8"
    )


def node(node_id, message_id, parent, children, role, text):
    content = json.dumps({"content_type": "text", "parts": [text]}, separators=(",", ":"))
    return {
        "nodeNativeId": node_id,
        "messageNativeId": message_id,
        "parentNativeId": parent,
        "childNativeIds": children,
        "role": role,
        "createdAt": "1000",
        "textParts": [text],
        "contentJson": content,
    }


def normalized(source_sha, conversation, current, nodes, status="complete"):
    return {
        "sourceSha256": source_sha,
        "parser": "generic-mapping-graph-v0",
        "conversationNativeId": conversation,
        "currentNodeNativeId": current,
        "coverageStatus": status,
        "coverageBasis": "structural_graph_closure_only",
        "rootFound": status == "complete",
        "currentNodeFound": True,
        "currentLeafFound": True,
        "parentChainComplete": status == "complete",
        "cycleDetected": False,
        "unresolvedParentNativeIds": [],
        "unresolvedChildNativeIds": [],
        "nodes": nodes,
        "normalizedAt": "2026-09-07T00:00:00.000Z",
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


with tempfile.TemporaryDirectory(prefix="bke-dna-aggregate-") as temp:
    root = Path(temp) / "captures"
    for directory in ["bodies", "observations", "normalized"]:
        (root / directory).mkdir(parents=True, exist_ok=True)

    body_a = b"conversation snapshot A"
    body_b = b"conversation snapshot B"
    source_a = sha(body_a)
    source_b = sha(body_b)
    (root / "bodies" / f"{source_a}.body").write_bytes(body_a)
    (root / "bodies" / f"{source_b}.body").write_bytes(body_b)

    snapshot_a = normalized(
        source_a,
        conversation_id,
        "assistant-a",
        [
            node("user-root", "message-user", None, ["assistant-a"], "user", "ORIGINAL PROMPT"),
            node("assistant-a", "message-assistant-a", "user-root", [], "assistant", "ANSWER A"),
        ],
    )
    snapshot_b = normalized(
        source_b,
        conversation_id,
        "assistant-b",
        [
            node("user-root", "message-user", None, ["assistant-b"], "user", "EDITED PROMPT"),
            node("assistant-b", "message-assistant-b", "user-root", [], "assistant", "ANSWER B"),
        ],
    )

    # Write matching observations manually so observation time, not file order, selects latest state.
    for source_sha, captured_at in [
        (source_a, "2026-09-07T00:00:01.000Z"),
        (source_b, "2026-09-07T00:00:02.000Z"),
    ]:
        capture_id = str(uuid.uuid4())
        observation = {
            "capture": {
                "type": "capture_start",
                "captureId": capture_id,
                "pageUrl": "https://chatgpt.com/c/aggregation-proof",
                "requestUrl": f"https://chatgpt.com/response/{capture_id}",
                "method": "GET",
                "status": 200,
                "contentType": "application/json",
                "initiator": "fetch",
                "capturedAt": captured_at,
                "byteLength": len((root / "bodies" / f"{source_sha}.body").read_bytes()),
                "fidelity": "browser-application-response-body",
            },
            "sha256": source_sha,
            "byteLength": len((root / "bodies" / f"{source_sha}.body").read_bytes()),
            "bodyPath": f"bodies/{source_sha}.body",
            "storedAt": captured_at,
        }
        (root / "observations" / f"{capture_id}.json").write_text(
            json.dumps(observation, indent=2), encoding="utf-8"
        )

    (root / "normalized" / f"{source_a}.json").write_text(json.dumps(snapshot_a, indent=2), encoding="utf-8")
    (root / "normalized" / f"{source_b}.json").write_text(json.dumps(snapshot_b, indent=2), encoding="utf-8")

    # A separate conversation proves aggregate coverage remains honest about a missing parent.
    orphan_body = b"orphan conversation snapshot"
    orphan_sha = sha(orphan_body)
    (root / "bodies" / f"{orphan_sha}.body").write_bytes(orphan_body)
    orphan_snapshot = normalized(
        orphan_sha,
        partial_conversation_id,
        "orphan-node",
        [node("orphan-node", "orphan-message", "missing-parent", [], "assistant", "ORPHAN")],
        status="partial",
    )
    orphan_snapshot["rootFound"] = False
    orphan_snapshot["parentChainComplete"] = False
    orphan_snapshot["unresolvedParentNativeIds"] = ["missing-parent"]
    orphan_capture = str(uuid.uuid4())
    (root / "observations" / f"{orphan_capture}.json").write_text(
        json.dumps(
            {
                "capture": {
                    "type": "capture_start",
                    "captureId": orphan_capture,
                    "pageUrl": "https://chatgpt.com/c/partial-proof",
                    "requestUrl": "https://chatgpt.com/response/orphan",
                    "method": "GET",
                    "status": 200,
                    "contentType": "application/json",
                    "initiator": "fetch",
                    "capturedAt": "2026-09-07T00:00:03.000Z",
                    "byteLength": len(orphan_body),
                    "fidelity": "browser-application-response-body",
                },
                "sha256": orphan_sha,
                "byteLength": len(orphan_body),
                "bodyPath": f"bodies/{orphan_sha}.body",
                "storedAt": "2026-09-07T00:00:03.000Z",
            },
            indent=2,
        ),
        encoding="utf-8",
    )
    (root / "normalized" / f"{orphan_sha}.json").write_text(
        json.dumps(orphan_snapshot, indent=2), encoding="utf-8"
    )

    first = run_host(root)
    if first.returncode != 0:
        sys.stderr.write(first.stderr.decode("utf-8", errors="replace"))
        raise SystemExit("host failed during first conversation aggregation")

    aggregate_path = root / "conversations" / f"{conversation_key}.json"
    partial_path = root / "conversations" / f"{partial_key}.json"
    if not aggregate_path.exists() or not partial_path.exists():
        raise SystemExit("conversation aggregation did not create expected state files")

    first_bytes = aggregate_path.read_bytes()
    aggregate = json.loads(first_bytes)
    if aggregate["conversationNativeId"] != conversation_id:
        raise SystemExit("aggregate lost native conversation ID")
    if aggregate["currentNodeNativeId"] != "assistant-b":
        raise SystemExit("aggregate did not select latest current node by observation time")
    if aggregate["stateObservedThrough"] != "2026-09-07T00:00:02.000Z":
        raise SystemExit("aggregate observed-through watermark mismatch")
    if aggregate["coverageStatus"] != "complete":
        raise SystemExit(f"closed union graph should be complete: {aggregate['coverageStatus']}")
    if aggregate["coverageBasis"] != "multi_snapshot_structural_union":
        raise SystemExit("aggregate coverage basis is not explicit")
    if [source["sourceSha256"] for source in aggregate["sources"]] != [source_a, source_b]:
        raise SystemExit("aggregate did not preserve ordered source payload lineage")

    nodes = {item["nodeNativeId"]: item for item in aggregate["nodes"]}
    if set(nodes) != {"user-root", "assistant-a", "assistant-b"}:
        raise SystemExit("aggregate flattened or lost branch nodes")
    user = nodes["user-root"]
    if user["messageNativeIds"] != ["message-user"]:
        raise SystemExit("aggregate lost stable native message identity")
    if set(user["childNativeIds"]) != {"assistant-a", "assistant-b"}:
        raise SystemExit("aggregate did not preserve both observed branches")
    if user["roles"] != ["user"]:
        raise SystemExit("aggregate role evidence mismatch")
    if len(user["revisions"]) != 2:
        raise SystemExit("edited node did not retain both content revisions")
    revision_texts = {tuple(revision["textParts"]) for revision in user["revisions"]}
    if revision_texts != {("ORIGINAL PROMPT",), ("EDITED PROMPT",)}:
        raise SystemExit("aggregate revision texts do not preserve original and edited prompt")
    revision_sources = {source for revision in user["revisions"] for source in revision["sourceSha256s"]}
    if revision_sources != {source_a, source_b}:
        raise SystemExit("aggregate revisions lost raw-source lineage")

    partial = json.loads(partial_path.read_text(encoding="utf-8"))
    if partial["coverageStatus"] != "partial":
        raise SystemExit("orphan aggregate incorrectly claimed completeness")
    if partial["unresolvedParentNativeIds"] != ["missing-parent"]:
        raise SystemExit("orphan aggregate lost exact missing parent evidence")

    # Replay must be deterministic: no generated timestamp is allowed to churn state bytes.
    second = run_host(root)
    if second.returncode != 0:
        sys.stderr.write(second.stderr.decode("utf-8", errors="replace"))
        raise SystemExit("host failed during aggregate replay")
    if aggregate_path.read_bytes() != first_bytes:
        raise SystemExit("conversation aggregation is not deterministic across replay")

print("conversation aggregation smoke PASS")
