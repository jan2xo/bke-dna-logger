#!/usr/bin/env python3
import base64
import hashlib
import json
import os
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import uuid

if len(sys.argv) != 2:
    raise SystemExit("usage: native-host-smoke.py <host-dll>")

host_dll = Path(sys.argv[1]).resolve()
visible_phrase = "GO ATTACK BRO NETWORK DOM WITNESS PROOF"
conversation_body = json.dumps(
    {
        "conversation_id": "conversation-native-1",
        "current_node": "node-assistant",
        "mapping": {
            "node-user": {
                "parent": None,
                "children": ["node-assistant"],
                "message": {
                    "id": "message-user",
                    "author": {"role": "user"},
                    "create_time": 1000.5,
                    "content": {"content_type": "text", "parts": [visible_phrase]},
                },
            },
            "node-assistant": {
                "parent": "node-user",
                "children": [],
                "message": {
                    "id": "message-assistant",
                    "author": {"role": "assistant"},
                    "create_time": 1001,
                    "content": {"content_type": "text", "parts": ["ACKNOWLEDGED"]},
                },
            },
        },
    },
    separators=(",", ":"),
).encode("utf-8")
partial_body = json.dumps(
    {
        "conversation_id": "conversation-partial",
        "current_node": "node-orphan",
        "mapping": {
            "node-orphan": {
                "parent": "missing-parent",
                "children": [],
                "message": {
                    "id": "message-orphan",
                    "author": {"role": "assistant"},
                    "create_time": 2001,
                    "content": {"content_type": "text", "parts": ["PARTIAL GRAPH EVIDENCE"]},
                },
            }
        },
    },
    separators=(",", ":"),
).encode("utf-8")
config_body = b'{"flags":{"new_navigation":true,"experiment_bucket":"A"},"account":{"tier":"test"}}'
conversation_sha = hashlib.sha256(conversation_body).hexdigest()
partial_sha = hashlib.sha256(partial_body).hexdigest()
config_sha = hashlib.sha256(config_body).hexdigest()


def frame(message):
    payload = json.dumps(message, separators=(",", ":")).encode("utf-8")
    return struct.pack("<I", len(payload)) + payload


def capture_messages(capture_id, body):
    midpoint = len(body) // 2
    chunks = [body[:midpoint], body[midpoint:]]
    messages = [
        {
            "type": "capture_start",
            "captureId": capture_id,
            "pageUrl": "https://chatgpt.com/c/example",
            "requestUrl": "https://chatgpt.com/example-response",
            "method": "GET",
            "status": 200,
            "contentType": "application/json",
            "initiator": "fetch",
            "capturedAt": "2026-09-07T00:00:00.000Z",
            "byteLength": len(body),
            "fidelity": "browser-application-response-body",
        }
    ]
    for sequence, chunk in enumerate(chunks):
        messages.append(
            {
                "type": "capture_chunk",
                "captureId": capture_id,
                "sequence": sequence,
                "base64": base64.b64encode(chunk).decode("ascii"),
            }
        )
    messages.append({"type": "capture_end", "captureId": capture_id})
    return messages


conversation_capture_ids = [str(uuid.uuid4()), str(uuid.uuid4())]
partial_capture_id = str(uuid.uuid4())
config_capture_id = str(uuid.uuid4())
witness_id = str(uuid.uuid4())

# Intentionally send the witness first. It begins unmatched, then later capture_end
# events must re-run reconciliation and upgrade it to corroborated.
messages = [
    {
        "type": "dom_witness",
        "witnessId": witness_id,
        "pageUrl": "https://chatgpt.com/c/example",
        "observedAt": "2026-09-07T00:00:01.000Z",
        "snippets": [
            visible_phrase,
            "This visible text sample exists only as corroborating DOM evidence.",
        ],
    }
]
for capture_id in conversation_capture_ids:
    messages.extend(capture_messages(capture_id, conversation_body))
messages.extend(capture_messages(partial_capture_id, partial_body))
messages.extend(capture_messages(config_capture_id, config_body))
wire = b"".join(frame(message) for message in messages)

with tempfile.TemporaryDirectory(prefix="bke-dna-") as temp:
    root = Path(temp) / "captures"
    env = os.environ.copy()
    env["BKE_DNA_CAPTURE_ROOT"] = str(root)

    result = subprocess.run(
        ["dotnet", str(host_dll)],
        input=wire,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env=env,
        check=False,
    )

    if result.returncode != 0:
        sys.stderr.write(result.stderr.decode("utf-8", errors="replace"))
        raise SystemExit(f"native host exited {result.returncode}")

    conversation_path = root / "bodies" / f"{conversation_sha}.body"
    if conversation_path.read_bytes() != conversation_body:
        raise SystemExit("content-addressed conversation body does not match captured bytes")

    partial_path = root / "bodies" / f"{partial_sha}.body"
    if partial_path.read_bytes() != partial_body:
        raise SystemExit("content-addressed partial body does not match captured bytes")

    config_path = root / "bodies" / f"{config_sha}.body"
    if config_path.read_bytes() != config_body:
        raise SystemExit("content-addressed config body does not match captured bytes")

    body_files = list((root / "bodies").glob("*.body"))
    if len(body_files) != 3:
        raise SystemExit(f"expected three deduplicated bodies, found {len(body_files)}")

    for capture_id in conversation_capture_ids:
        observation_path = root / "observations" / f"{capture_id}.json"
        observation = json.loads(observation_path.read_text(encoding="utf-8"))
        if observation["sha256"] != conversation_sha:
            raise SystemExit("conversation observation SHA-256 mismatch")
        if observation["byteLength"] != len(conversation_body):
            raise SystemExit("conversation observation byte length mismatch")

    partial_observation = json.loads(
        (root / "observations" / f"{partial_capture_id}.json").read_text(encoding="utf-8")
    )
    if partial_observation["sha256"] != partial_sha:
        raise SystemExit("partial observation SHA-256 mismatch")

    config_observation = json.loads(
        (root / "observations" / f"{config_capture_id}.json").read_text(encoding="utf-8")
    )
    if config_observation["sha256"] != config_sha:
        raise SystemExit("config observation SHA-256 mismatch")

    conversation_classification = json.loads(
        (root / "classifications" / f"{conversation_sha}.json").read_text(encoding="utf-8")
    )
    result_classification = conversation_classification["classification"]
    if result_classification["kind"] != "conversation_payload_candidate":
        raise SystemExit(f"conversation payload was not classified as candidate: {result_classification}")
    if result_classification["confidence"] not in {"medium", "high"}:
        raise SystemExit(f"conversation confidence unexpectedly weak: {result_classification}")
    if "conversation_graph_shape" not in result_classification["signals"]:
        raise SystemExit("conversation graph signal missing")

    partial_classification = json.loads(
        (root / "classifications" / f"{partial_sha}.json").read_text(encoding="utf-8")
    )
    if partial_classification["classification"]["kind"] != "conversation_payload_candidate":
        raise SystemExit("partial graph should still classify as a conversation candidate")

    config_classification = json.loads(
        (root / "classifications" / f"{config_sha}.json").read_text(encoding="utf-8")
    )
    if config_classification["classification"]["kind"] != "other":
        raise SystemExit(f"config payload false-positive classification: {config_classification}")

    normalized = json.loads(
        (root / "normalized" / f"{conversation_sha}.json").read_text(encoding="utf-8")
    )
    if normalized["sourceSha256"] != conversation_sha:
        raise SystemExit("normalized graph source SHA mismatch")
    if normalized["conversationNativeId"] != "conversation-native-1":
        raise SystemExit("native conversation ID was not preserved")
    if normalized["currentNodeNativeId"] != "node-assistant":
        raise SystemExit("native current-node ID was not preserved")
    if normalized["coverageStatus"] != "complete":
        raise SystemExit(f"closed synthetic graph should be structurally complete: {normalized}")
    if normalized["coverageBasis"] != "structural_graph_closure_only":
        raise SystemExit("coverage basis must explicitly limit completeness semantics")
    if not normalized["rootFound"]:
        raise SystemExit("complete graph root was not found")
    if not normalized["currentNodeFound"] or not normalized["currentLeafFound"]:
        raise SystemExit("complete graph current node/leaf evidence missing")
    if not normalized["parentChainComplete"] or normalized["cycleDetected"]:
        raise SystemExit("complete graph parent chain evidence is invalid")
    if normalized["unresolvedParentNativeIds"] or normalized["unresolvedChildNativeIds"]:
        raise SystemExit("fully linked synthetic graph unexpectedly has unresolved edges")
    if len(normalized["nodes"]) != 2:
        raise SystemExit("normalized graph did not preserve both mapping nodes")

    nodes = {node["nodeNativeId"]: node for node in normalized["nodes"]}
    user_node = nodes["node-user"]
    assistant_node = nodes["node-assistant"]
    if user_node["messageNativeId"] != "message-user" or user_node["role"] != "user":
        raise SystemExit("user native message identity/role was not preserved")
    if user_node["parentNativeId"] is not None or user_node["childNativeIds"] != ["node-assistant"]:
        raise SystemExit("user graph edges were not preserved")
    if user_node["textParts"] != [visible_phrase]:
        raise SystemExit("user normalized text parts mismatch")
    if assistant_node["messageNativeId"] != "message-assistant" or assistant_node["role"] != "assistant":
        raise SystemExit("assistant native message identity/role was not preserved")
    if assistant_node["parentNativeId"] != "node-user" or assistant_node["childNativeIds"]:
        raise SystemExit("assistant graph edges were not preserved")

    partial_normalized = json.loads(
        (root / "normalized" / f"{partial_sha}.json").read_text(encoding="utf-8")
    )
    if partial_normalized["coverageStatus"] != "partial":
        raise SystemExit(f"missing-parent graph must be partial: {partial_normalized}")
    if partial_normalized["coverageBasis"] != "structural_graph_closure_only":
        raise SystemExit("partial coverage basis mismatch")
    if partial_normalized["rootFound"]:
        raise SystemExit("orphan-only partial graph must not report a root")
    if not partial_normalized["currentNodeFound"] or not partial_normalized["currentLeafFound"]:
        raise SystemExit("partial graph should still find its present current leaf")
    if partial_normalized["parentChainComplete"] or partial_normalized["cycleDetected"]:
        raise SystemExit("partial missing-parent chain state is incorrect")
    if partial_normalized["unresolvedParentNativeIds"] != ["missing-parent"]:
        raise SystemExit("partial graph did not report exact missing parent")
    if partial_normalized["unresolvedChildNativeIds"]:
        raise SystemExit("partial graph unexpectedly has missing child IDs")

    classification_files = list((root / "classifications").glob("*.json"))
    if len(classification_files) != 3:
        raise SystemExit(f"classification should deduplicate by body hash, found {len(classification_files)} files")
    normalized_files = list((root / "normalized").glob("*.json"))
    if len(normalized_files) != 2:
        raise SystemExit("only the two conversation candidates should produce normalized graphs")

    witness_path = root / "witnesses" / f"{witness_id}.json"
    witness = json.loads(witness_path.read_text(encoding="utf-8"))
    if witness["pageUrl"] != "https://chatgpt.com/c/example":
        raise SystemExit("DOM witness page URL mismatch")
    if visible_phrase not in witness["snippets"]:
        raise SystemExit("DOM witness lost visible conversation sample")
    if len(witness["fingerprintSha256"]) != 64:
        raise SystemExit("DOM witness fingerprint is not SHA-256")

    reconciliation_path = root / "reconciliations" / f"{witness_id}.json"
    reconciliation = json.loads(reconciliation_path.read_text(encoding="utf-8"))
    if reconciliation["status"] != "corroborated":
        raise SystemExit(f"network/DOM witness did not become corroborated: {reconciliation}")
    matched_shas = {match["sha256"] for match in reconciliation["matches"]}
    if conversation_sha not in matched_shas:
        raise SystemExit("reconciliation did not link DOM witness to conversation body")
    if partial_sha in matched_shas:
        raise SystemExit("unrelated partial graph was incorrectly corroborated by DOM witness")
    if config_sha in matched_shas:
        raise SystemExit("ordinary config JSON was incorrectly used as corroborating conversation evidence")
    conversation_match = next(
        match for match in reconciliation["matches"] if match["sha256"] == conversation_sha
    )
    if visible_phrase not in conversation_match["matchedSnippets"]:
        raise SystemExit("reconciliation lost the exact visible witness phrase")
    if set(conversation_match["captureIds"]) != set(conversation_capture_ids):
        raise SystemExit("reconciliation did not retain all observations of the matched body")

print("native host smoke PASS")
