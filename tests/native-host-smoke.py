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
body = b'{"mapping":{"a":{"message":{"author":{"role":"user"},"content":{"parts":["GO ATTACK BRO"]}}}}}'
expected_sha = hashlib.sha256(body).hexdigest()


def frame(message):
    payload = json.dumps(message, separators=(",", ":")).encode("utf-8")
    return struct.pack("<I", len(payload)) + payload


def capture_messages(capture_id):
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


capture_ids = [str(uuid.uuid4()), str(uuid.uuid4())]
wire = b"".join(frame(message) for capture_id in capture_ids for message in capture_messages(capture_id))

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

    body_path = root / "bodies" / f"{expected_sha}.body"
    if body_path.read_bytes() != body:
        raise SystemExit("content-addressed body does not match captured bytes")

    body_files = list((root / "bodies").glob("*.body"))
    if len(body_files) != 1:
        raise SystemExit(f"expected deduplicated single body, found {len(body_files)}")

    for capture_id in capture_ids:
        observation_path = root / "observations" / f"{capture_id}.json"
        observation = json.loads(observation_path.read_text(encoding="utf-8"))
        if observation["sha256"] != expected_sha:
            raise SystemExit("observation SHA-256 mismatch")
        if observation["byteLength"] != len(body):
            raise SystemExit("observation byte length mismatch")

print("native host smoke PASS")
