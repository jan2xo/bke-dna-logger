#!/usr/bin/env python3
from pathlib import Path

repo = Path(__file__).resolve().parents[1]
kotlin = repo / "android" / "app" / "src" / "main" / "kotlin" / "com" / "bke" / "dna" / "logger"
contract = (kotlin / "DnaWireContract.kt").read_text(encoding="utf-8")
ingress = (kotlin / "AndroidWireIngress.kt").read_text(encoding="utf-8")
runtime = (kotlin / "AndroidCaptureRuntime.kt").read_text(encoding="utf-8")
store = (kotlin / "AndroidCaptureStore.kt").read_text(encoding="utf-8")
index = (kotlin / "AndroidCaptureIndex.kt").read_text(encoding="utf-8")
host = (kotlin / "GeckoViewHost.kt").read_text(encoding="utf-8")
bridge = (repo / "android" / "app" / "src" / "main" / "assets" / "dna-extension" / "bridge.js").read_text(encoding="utf-8")
interceptor = (repo / "extension" / "main-interceptor.js").read_text(encoding="utf-8")

for token in ("capture_start", "capture_chunk", "capture_end", "MAX_MESSAGE_BYTES"):
    assert token in contract, token

for forbidden in ("authorization", "cookie", "requestHeaders", "responseHeaders", "headers"):
    assert f'"{forbidden}"' in contract, forbidden

for token in (
    "AndroidCaptureStore(context.applicationContext)",
    "DnaWireContract.parse(rawMessage)",
    "store.accept(json)",
):
    assert token in ingress, token

for token in (
    "AndroidWireIngress(appContext)",
    "pauseForStorageMutation",
    "resumeAfterStorageMutation",
    "withStorageMutationPause",
    "AndroidCaptureStore.awaitBackgroundDerivationIdle()",
):
    assert token in runtime, token

for token in (
    'MessageDigest.getInstance("SHA-256")',
    "output.fd.sync()",
    "expected sequence",
    "declaredLength: Long?",
    "finalDeclaredLength",
    "bodies/$bodyName",
    "observations",
    "DERIVATION_EXECUTOR.execute",
    "awaitBackgroundDerivationIdle",
    "insertOrThrow",
):
    assert token in store + index, token

assert 'dna_archived INTEGER NOT NULL DEFAULT 0' in index

for token in (
    "AndroidCaptureRuntime.start(appContext)",
    "AndroidCaptureRuntime.accept(",
    "message !is JSONObject",
    "message.toString().toByteArray",
    "AndroidCaptureRuntime.stop()",
):
    assert token in host, token

for token in (
    "clone.body?.getReader()",
    "await reader.read()",
    'kind: "capture_start"',
    'kind: "capture_chunk"',
    'kind: "capture_end"',
    "waitForAck",
):
    assert token in interceptor, token
assert "clone.arrayBuffer()" not in interceptor

for token in (
    "forwardStreamStart",
    "forwardStreamChunk",
    "forwardStreamEnd",
    'kind: "capture_ack"',
    'type: "capture_start"',
    'type: "capture_chunk"',
    'type: "capture_end"',
    "byteLength: packet.byteLength",
):
    assert token in bridge, token

for forbidden in ("Authorization", "Cookie", "requestHeaders", "responseHeaders"):
    assert forbidden not in bridge, forbidden

print("android streamed native ingress/background derivation smoke PASS")
