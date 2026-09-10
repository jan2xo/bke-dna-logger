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
    "AndroidDerivationScheduler.start(appContext)",
    "AndroidCaptureStore.awaitBackgroundDerivationIdle(appContext)",
):
    assert token in runtime, token

# Native ingress still fsyncs exact bytes before ACK, but completed sources now
# promote only to durable staging. Permanent RAW ownership moves to SQLite in
# the background-priority browser-quiet queue, not on the Gecko capture path.
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

for token in (
    "CREATE TABLE IF NOT EXISTS derivation_queue",
    "recoverInterrupted()",
    "recoverStagedRaw(appContext)",
    'STAGE_RAW_INGEST = "RAW_INGEST"',
    "rawStore.importVerified(",
    "Executors.newSingleThreadExecutor",
    "Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)",
    "waitForBrowserQuiet()",
    "BROWSER_QUIET_MS = 3_000L",
    "SLOW(500L)",
    "BALANCED(150L)",
    "FAST(25L)",
):
    assert token in queue, token
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

print("android streamed native ingress/browser-first SQLite RAW queue smoke PASS")
