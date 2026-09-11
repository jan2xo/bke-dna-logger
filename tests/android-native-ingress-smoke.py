#!/usr/bin/env python3
from pathlib import Path

repo = Path(__file__).resolve().parents[1]
kotlin = repo / "android" / "app" / "src" / "main" / "kotlin" / "com" / "bke" / "dna" / "logger"
contract = (kotlin / "DnaWireContract.kt").read_text(encoding="utf-8")
ingress = (kotlin / "AndroidWireIngress.kt").read_text(encoding="utf-8")
runtime = (kotlin / "AndroidCaptureRuntime.kt").read_text(encoding="utf-8")
store = (kotlin / "AndroidCaptureStore.kt").read_text(encoding="utf-8")
queue = (kotlin / "AndroidDerivationQueue.kt").read_text(encoding="utf-8")
raw_store = (kotlin / "AndroidRawEvidenceStore.kt").read_text(encoding="utf-8")
index = (kotlin / "AndroidCaptureIndex.kt").read_text(encoding="utf-8")
host = (kotlin / "GeckoViewHost.kt").read_text(encoding="utf-8")
bridge = (repo / "android" / "app" / "src" / "main" / "assets" / "dna-extension" / "bridge.js").read_text(encoding="utf-8")
interceptor = (repo / "extension" / "main-interceptor.js").read_text(encoding="utf-8")

for token in ("capture_start", "capture_chunk", "capture_end", "capture_abort", "MAX_MESSAGE_BYTES"):
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
    "AndroidDerivationScheduler.start(appContext)",
    "AndroidCaptureStore.awaitBackgroundDerivationIdle(appContext)",
):
    assert token in runtime, token

for token in (
    'MessageDigest.getInstance("SHA-256")',
    "output.fd.sync()",
    "expected sequence",
    "declaredLength: Long?",
    "finalDeclaredLength",
    'File(root, "staging")',
    'val stagingName = "${result.sha256}.raw"',
    'result.partial.renameTo(stagedRaw)',
    '"staging/$stagingName"',
    "observations",
    "AndroidDerivationScheduler.enqueue(",
    "awaitBackgroundDerivationIdle(context: Context)",
    "insertOrThrow",
):
    assert token in store + index, token
assert 'File(root, "bodies")' not in store
assert "DERIVATION_EXECUTOR" not in store
assert store.index("index.record(") < store.index("AndroidDerivationScheduler.enqueue(")

# A stream that started natively but failed before capture_end must explicitly abort.
# Abort closes/deletes its partial session and releases the capture-priority counter.
for token in (
    '"capture_abort" -> abort(json)',
    "private fun abort(json: JSONObject): String",
    "sessions.remove(captureId)",
    "session.close()",
    "AndroidDerivationScheduler.captureFinished()",
):
    assert token in store, token

# Capture remains the hard priority gate, while foreground browsing only throttles derivation.
for token in (
    "CREATE TABLE IF NOT EXISTS derivation_queue",
    "recoverInterrupted()",
    "recoverStagedRaw(context)",
    'STAGE_RAW_INGEST = "RAW_INGEST"',
    "rawStore.importVerified(",
    "Executors.newSingleThreadExecutor",
    "Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)",
    "waitForCaptureIdle()",
    "browserForeground.get()",
    "profile.browserRestMillis",
    "profile.restMillis",
    "SLOW(500L, 1_500L)",
    "BALANCED(150L, 750L)",
    "FAST(25L, 300L)",
):
    assert token in queue, token
for forbidden in ("waitForBrowserQuiet()", "BROWSER_QUIET_MS"):
    assert forbidden not in queue, forbidden
assert queue.index("rawStore.importVerified(") < queue.index("stagedRaw.delete()")

for token in (
    "CREATE TABLE IF NOT EXISTS raw_source (",
    "CREATE TABLE IF NOT EXISTS raw_source_chunk (",
    "deleteUnpublishedChunks(sourceSha256)",
    "verifyUnpublishedSource(",
    'database.insertOrThrow("raw_source_chunk", null, values)',
    'database.insertOrThrow("raw_source", null, sourceValues)',
    "database.setTransactionSuccessful()",
):
    assert token in raw_store, token

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
    'kind: "capture_abort"',
    'postWithAck({ source: SOURCE, kind: "capture_abort", captureId }, [], "abort")',
    "waitForAck",
):
    assert token in interceptor, token
assert "clone.arrayBuffer()" not in interceptor

for token in (
    "forwardStreamStart",
    "forwardStreamChunk",
    "forwardStreamEnd",
    "forwardStreamAbort",
    'kind: "capture_ack"',
    'type: "capture_start"',
    'type: "capture_chunk"',
    'type: "capture_end"',
    'type: "capture_abort"',
    "byteLength: packet.byteLength",
):
    assert token in bridge, token

for forbidden in ("Authorization", "Cookie", "requestHeaders", "responseHeaders"):
    assert forbidden not in bridge, forbidden

print("android streamed native ingress/browser-first SQLite RAW nonstarving + abort-release smoke PASS")
