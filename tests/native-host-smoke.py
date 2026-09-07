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
conversation_body = b'{"mapping":{"a":{"parent":null,"children":[],"message":{"author":{"role":"user"},"content":{"parts":["GO ATTACK BRO"]}}}}}'
config_body = b'{"flags":{"new_navigation":true,"experiment_bucket":"A"},"account":{"tier":"test"}}'
conversation_sha = hashlib.sha256(conversation_body).hexdigest()
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
config_capture_id = str(uuid.uuid4())
messages = []
for capture_id in conversation_capture_ids:
    messages.extend(capture_messages(capture_id, conversation_body))
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

    config_path = root / "bodies" / f"{config_sha}.body"
    if config_path.read_bytes() != config_body:
        raise SystemExit("content-addressed config body does not match captured bytes")

    body_files = list((root / "bodies").glob("*.body"))
    if len(body_files) != 2:
        raise SystemExit(f"expected two deduplicated bodies, found {len(body_files)}")

    for capture_id in conversation_capture_ids:
        observation_path = root / "observations" / f"{capture_id}.json"
        observation = json.loads(observation_path.read_text(encoding="utf-8"))
        if observation["sha256"] != conversation_sha:
            raise SystemExit("conversation observation SHA-256 mismatch")
        if observation["byteLength"] != len(conversation_body):
            raise SystemExit("conversation observation byte length mismatch")

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

    config_classification = json.loads(
        (root / "classifications" / f"{config_sha}.json").read_text(encoding="utf-8")
    )
    if config_classification["classification"]["kind"] != "other":
        raise SystemExit(f"config payload false-positive classification: {config_classification}")

    classification_files = list((root / "classifications").glob("*.json"))
    if len(classification_files) != 2:
        raise SystemExit(f"classification should deduplicate by body hash, found {len(classification_files)} files")

print("native host smoke PASS")
