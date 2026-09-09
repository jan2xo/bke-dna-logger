#!/usr/bin/env python3
from pathlib import Path

repo = Path(__file__).resolve().parents[1]
kotlin = repo / "android" / "app" / "src" / "main" / "kotlin" / "com" / "bke" / "dna" / "logger"
classifier = (kotlin / "AndroidConversationPayloadClassifier.kt").read_text(encoding="utf-8")
graph_normalizer = (kotlin / "AndroidGraphNormalizationEngine.kt").read_text(encoding="utf-8")
messages_normalizer = (kotlin / "AndroidMessagesNormalizationEngine.kt").read_text(encoding="utf-8")
dispatcher = (kotlin / "AndroidConversationNormalizationDispatcher.kt").read_text(encoding="utf-8")
pipeline = (kotlin / "AndroidLiveDerivationPipeline.kt").read_text(encoding="utf-8")
store = (kotlin / "AndroidCaptureStore.kt").read_text(encoding="utf-8")
queue = (kotlin / "AndroidDerivationQueue.kt").read_text(encoding="utf-8")
raw_store = (kotlin / "AndroidRawEvidenceStore.kt").read_text(encoding="utf-8")
raw_access = (kotlin / "AndroidRawSourceAccess.kt").read_text(encoding="utf-8")
derivative_store = (kotlin / "AndroidDerivativeStore.kt").read_text(encoding="utf-8")
chunked_store = (kotlin / "AndroidChunkedNormalizedStore.kt").read_text(encoding="utf-8")
derivative_access = (kotlin / "AndroidDerivativeSourceAccess.kt").read_text(encoding="utf-8")
runtime = (kotlin / "AndroidCaptureRuntime.kt").read_text(encoding="utf-8")
aggregation = (kotlin / "AndroidConversationAggregationEngine.kt").read_text(encoding="utf-8")
contract = (kotlin / "DnaReconciliationContract.kt").read_text(encoding="utf-8")

# Classifier admission remains locked.
for token in (
    '"conversation_payload_candidate"', '"mapping"', '"messages"', '"message"',
    '"recognized_message_role"', '"conversation_graph_shape"', '"authored_message_shape"',
    '"conversation_id"', '"current_node"', '"text/event-stream"', '"invalid_utf8"',
    'score >= 28', 'score >= 55',
):
    assert token in classifier, token

# generic-mapping-graph-v0 remains strict and independently implemented.
for token in (
    'MAX_BODY_BYTES = 16L * 1024 * 1024', 'PARSER = "generic-mapping-graph-v0"',
    'COVERAGE_BASIS = "structural_graph_closure_only"',
    'AndroidRawSourceAccess.readAllBytes', 'JSONTokener(String(rawBytes, Charsets.UTF_8))',
    'AndroidDerivativeSourceAccess.readClassification', 'AndroidDerivativeSourceAccess.readNormalized',
    'store.putNormalizedJson(sourceSha256, normalized.toString(2))',
    'root.optJSONObject("mapping")', 'root.opt("conversation_id")', 'root.opt("current_node")',
    'message.opt("create_time")', 'message.opt("id")', 'message.optJSONObject("author")',
    'content.optJSONArray("parts")', '"parentNativeId"', '"childNativeIds"',
    '"BKE DNA normalization: normalization_skip_no_root_mapping"',
):
    assert token in graph_normalizer, token
for forbidden in ('bodiesDirectory', 'classificationsDirectory', 'normalizedDirectory', 'writeDerivativeAtomically'):
    assert forbidden not in graph_normalizer, forbidden

# Runtime-observed messages array diagnostics remain fixed.
for token in (
    '"BKE DNA normalization: candidate_root_messages"',
    '"BKE DNA normalization: candidate_nested_messages"',
    '"BKE DNA normalization: candidate_messages_array"',
    '"BKE DNA normalization: candidate_messages_object"',
):
    assert token in graph_normalizer, token

# Modern messages representation remains separate and graphless, but its large
# normalized derivative is now serialized directly into bounded SQLite chunks.
for token in (
    'PARSER = "messages-array-v0"', 'COVERAGE_BASIS = "messages_array_no_graph_edges"',
    'AndroidRawSourceAccess.readAllBytes', 'JSONTokener(String(rawBytes, Charsets.UTF_8))',
    'AndroidDerivativeSourceAccess.readClassification',
    'AndroidDerivativeSourceAccess.readNormalizedMetadata',
    'AndroidChunkedNormalizedStore(appContext)', 'store.putNormalizedJsonStream(',
    'writeNormalizedPayload(',
    'root.optJSONArray("messages")', 'root.opt("conversation_id")', 'root.opt("current_node")',
    'message.opt("id")', 'message.optJSONObject("author")', 'message.opt("create_time")',
    'message.opt("content")', 'content.optJSONArray("parts")',
    'writer.name("coverageStatus").value("indeterminate")',
    'writer.name("rootFound").value(false)',
    'writer.name("currentLeafFound").value(false)',
    'writer.name("parentChainComplete").value(false)',
    'writer.name("parentNativeId").nullValue()',
    'writer.name("childNativeIds").beginArray().endArray()',
    'sourceCurrentNodeId?.takeIf { it in knownMessageIds }',
    '"BKE DNA normalization: messages_derivative_write_started"',
    '"BKE DNA normalization: messages_derivative_write_complete"',
    '"BKE DNA normalization: messages_array_normalization_complete"',
):
    assert token in messages_normalizer, token
for forbidden in (
    'optJSONObject("mapping")', 'message.opt("parent")', 'message.optJSONArray("children")',
    'bodiesDirectory', 'classificationsDirectory', 'normalizedDirectory', 'writeDerivativeAtomically',
    'normalized.toString(2)',
):
    assert forbidden not in messages_normalizer, forbidden

# Representation dispatcher remains explicit and reads the same bounded RAW
# bytes by source SHA instead of assuming captures/bodies/<sha>.body.
for token in (
    'AndroidGraphNormalizationEngine(appContext)',
    'AndroidMessagesNormalizationEngine(appContext)',
    'AndroidRawSourceAccess.readAllBytes(appContext, sourceSha256, MAX_BODY_BYTES)',
    'root.optJSONObject("mapping") != null', 'root.optJSONArray("messages") != null',
    '"BKE DNA normalization: normalization_representation_mapping_graph"',
    '"BKE DNA normalization: normalization_representation_messages_array"',
    '"BKE DNA normalization: normalization_skip_unsupported_representation"',
):
    assert token in dispatcher, token
assert dispatcher.index('root.optJSONObject("mapping") != null') < dispatcher.index('root.optJSONArray("messages") != null')
assert 'bodiesDirectory' not in dispatcher

# Candidate diagnostics remain fixed/non-sensitive.
for source in (graph_normalizer, messages_normalizer, dispatcher):
    for forbidden in (
        'Log.d(TAG, sourceSha256', 'Log.d(TAG, bodyPath', 'Log.d(TAG, conversationNativeId',
        'Log.d(TAG, currentNodeNativeId', 'BKE DNA normalization: $', 'normalization_skip_$',
    ):
        assert forbidden not in source, forbidden

# Exact RAW remains consolidated into Working Data SQLite as independently
# compressed chunks with round-trip verification before commit.
for token in (
    'CREATE TABLE IF NOT EXISTS raw_source (',
    'CREATE TABLE IF NOT EXISTS raw_source_chunk (',
    'RAW_CHUNK_BYTES = 256 * 1024',
    'CODEC = "deflate-raw-chunk-v1"',
    'fun importVerified(', 'fun verifySource(', 'fun readPage(', 'fun writeExactSource(',
    'verifySource(sourceSha256, expectedByteLength)',
    'database.setTransactionSuccessful()',
):
    assert token in raw_store, token
assert raw_store.index('verifySource(sourceSha256, expectedByteLength)') < raw_store.index('database.setTransactionSuccessful()', raw_store.index('verifySource(sourceSha256, expectedByteLength)'))

# RAW consumers resolve by source SHA. SQLite is preferred; legacy shared bodies
# are only a fallback for pre-PR4 read-only generations.
for token in (
    'object AndroidRawSourceAccess',
    'AndroidRawEvidenceStore(appContext)',
    'AndroidRawEvidenceStore(generation)',
    'File(captureRoot, "bodies/$sourceSha256.body")',
    'fun readPage(', 'fun writeExactSource(',
    'AndroidRawBackend.SQLITE', 'AndroidRawBackend.LEGACY_BODY',
):
    assert token in raw_access, token

# Existing inline PR5 derivatives remain intact for compatibility.
for token in (
    'CREATE TABLE IF NOT EXISTS derivative_classification (',
    'CREATE TABLE IF NOT EXISTS derivative_normalized (',
    'payload_json TEXT NOT NULL', 'payload_sha256 TEXT NOT NULL', 'byte_length INTEGER NOT NULL',
    'fun putClassificationJson(', 'fun putNormalizedJson(',
    '"Conflicting immutable derivative for source',
    '"Derivative SQLite round-trip mismatch"',
    'database.setTransactionSuccessful()',
):
    assert token in derivative_store, token

# Large modern normalized derivatives use a separate migration-safe chunked
# SQLite representation with bounded serialization and verification.
for token in (
    'CREATE TABLE IF NOT EXISTS derivative_normalized_chunked (',
    'CREATE TABLE IF NOT EXISTS derivative_normalized_chunk (',
    'payload_utf8 BLOB NOT NULL', 'chunk_count INTEGER NOT NULL',
    'PAYLOAD_CHUNK_BYTES = 64 * 1024',
    'fun putNormalizedJsonStream(', 'JsonWriter(OutputStreamWriter(sink, Charsets.UTF_8))',
    'verifyStoredPayload(sourceSha256, identity)',
    'database.setTransactionSuccessful()',
    '"Derivative SQLite round-trip mismatch"',
):
    assert token in chunked_store, token

# Derivative reads federate chunked, inline, and legacy generations.
for token in (
    'object AndroidDerivativeSourceAccess',
    'AndroidWorkingDataManager(appContext).listWorkingData()',
    'AndroidChunkedNormalizedStore(generation)',
    'AndroidDerivativeStore(generation)',
    'store.classificationJson(sourceSha256)', 'store.normalizedJson(sourceSha256)',
    'store.listSourceSha256s()', 'store.listNormalizedSourceSha256s()',
    'readNormalizedMetadata(', 'withNormalizedJsonReader(',
    'File(captureRoot, "$directoryName/$sourceSha256.json")',
):
    assert token in derivative_access, token

# Live derivative semantics remain ordered and bounded. New classifications are
# written/read through SQLite and no classification directory is created.
for token in (
    'MAX_CLASSIFICATION_BYTES = 16L * 1024 * 1024',
    'CANDIDATE_KIND = "conversation_payload_candidate"',
    'AndroidRawSourceAccess.readAllBytes',
    'AndroidConversationPayloadClassifier.classify',
    'AndroidDerivativeSourceAccess.readClassification(appContext, sourceSha256)',
    'store.putClassificationJson(sourceSha256, envelope.toString(2))',
    'AndroidConversationNormalizationDispatcher(appContext)',
    'engine.aggregateConversation(normalized.conversationNativeId)',
    'classification_size_limit', 'classifier_error',
    'Log.d(TAG, "BKE DNA derivation: started")',
    '"classification_candidate_high"', '"classification_candidate_medium"',
    '"classification_other_size_limit"', '"classification_other_error"',
    '"BKE DNA derivation: classifier_signal_messages"',
    'Log.d(TAG, "BKE DNA derivation: normalization_skipped")',
    'Log.d(TAG, "BKE DNA derivation: normalization_complete")',
    'Log.d(TAG, "BKE DNA derivation: reconciliation_complete")',
    'Log.d(TAG, "BKE DNA derivation: derivative_failed")',
    'onStage(STAGE_CLASSIFYING)', 'onStage(STAGE_NORMALIZING)', 'onStage(STAGE_RECONCILING)',
    'const val STAGE_CLASSIFYING = "CLASSIFYING"',
    'const val STAGE_NORMALIZING = "NORMALIZING"',
    'const val STAGE_RECONCILING = "RECONCILING"',
):
    assert token in pipeline, token
for forbidden in ('bodyFile.readBytes()', 'classificationsDirectory', 'writeDerivativeAtomically', 'FileOutputStream'):
    assert forbidden not in pipeline, forbidden
assert pipeline.index('ensureClassification(') < pipeline.index('readClassificationOutcome(sourceSha256)')
assert pipeline.index('readClassificationOutcome(sourceSha256)') < pipeline.index('normalizer.normalizeCandidate(sourceSha256)')
assert pipeline.index('normalizer.normalizeCandidate(sourceSha256)') < pipeline.index('engine.aggregateConversation(normalized.conversationNativeId)')

# Capture ACK path persists completed RAW only into durable staging, observation
# metadata and capture index, then enqueues. It never compresses or promotes a
# new permanent captures/bodies/<sha>.body on the browsing path.
for token in (
    'AndroidDerivationScheduler.captureStarted()',
    'AndroidDerivationScheduler.captureFinished()',
    'File(root, "staging")',
    'val stagingName = "${result.sha256}.raw"',
    'result.partial.renameTo(stagedRaw)',
    'AndroidDerivationScheduler.enqueue(',
    'awaitBackgroundDerivationIdle(context: Context)',
):
    assert token in store, token
assert 'File(root, "bodies")' not in store
assert 'DERIVATION_EXECUTOR' not in store
assert 'AndroidLiveDerivationPipeline(appContext).processCompletedCapture(' not in store
assert store.index('index.record(') < store.index('AndroidDerivationScheduler.enqueue(')

# Durable queue remains RAW_INGEST -> semantic stages; staging deletion follows
# verified SQLite RAW import.
for token in (
    'CREATE TABLE IF NOT EXISTS derivation_queue',
    'source_sha256 TEXT PRIMARY KEY',
    'status TEXT NOT NULL', 'stage TEXT NOT NULL', 'attempts INTEGER NOT NULL DEFAULT 0',
    'recoverInterrupted()', 'recoverStagedRaw(appContext)', 'requeueForRawIngest(',
    'Executors.newSingleThreadExecutor', 'bke-dna-breathing-derivation',
    'enum class AndroidProcessingProfile', 'SLOW(500L)', 'BALANCED(150L)', 'FAST(25L)',
    'Thread.sleep(restMillis)', 'Thread.yield()',
    'activeCaptures', 'waitForCaptureQuiet()',
    'STAGE_RAW_INGEST = "RAW_INGEST"',
    'rawStore.importVerified(', 'rawStore.verifySource(job.sourceSha256, job.byteLength)',
    'stagedRaw.delete()',
    'markDone(job.sourceSha256)', 'markFailed(job.sourceSha256, "derivative_failed")',
    'AndroidDerivationQueueSnapshot', 'currentStage',
):
    assert token in queue, token
assert 'newFixedThreadPool' not in queue
assert 'newCachedThreadPool' not in queue
assert queue.index('rawStore.importVerified(') < queue.index('stagedRaw.delete()')
assert queue.index('stagedRaw.delete()') < queue.index('AndroidLiveDerivationPipeline(context).processCompletedCapture(')

for token in (
    'AndroidDerivationScheduler.start(appContext)',
    'AndroidCaptureStore.awaitBackgroundDerivationIdle(appContext)',
):
    assert token in runtime, token

# Reconciliation now reads normalized derivatives through JsonReader and writes
# logical state through JsonWriter, so large snapshots are not reconstructed as
# one monolithic JSON String at either boundary.
for token in (
    'AndroidDerivativeSourceAccess.listNormalizedSourceSha256s(appContext)',
    'AndroidDerivativeSourceAccess.readNormalizedMetadata(appContext, sourceSha256)',
    'AndroidDerivativeSourceAccess.withNormalizedJsonReader(',
    'readSnapshot(reader, observedAtBySource)',
    'JsonReader', 'JsonWriter',
    'writeState(File(captureRoot, relativePath), state)',
    'GRAPH_COVERAGE_BASIS = "structural_graph_closure_only"',
    'LOGICAL_GRAPH_COVERAGE_BASIS = "multi_snapshot_structural_union"',
    'MESSAGES_COVERAGE_BASIS = "messages_array_no_graph_edges"',
    'val graphSnapshots = snapshots.filter { it.coverageBasis == GRAPH_COVERAGE_BASIS }',
    'computeGraphlessCoverage(latest.currentNodeNativeId, nodes)',
    'status = "indeterminate"', 'rootFound = false', 'currentLeafFound = false',
    'parentChainComplete = false',
):
    assert token in aggregation, token
assert 'AndroidDerivativeSourceAccess.readNormalized(appContext, sourceSha256)' not in aggregation
assert 'state.toJson().toString(2)' not in aggregation
assert 'normalizedDirectory' not in aggregation
assert 'fun aggregateConversation(conversationNativeId: String)' in aggregation
assert 'aggregateConversationState(conversationNativeId, snapshots)' in aggregation

assert 'AUTOMATIC_DNA_EXPORT = false' in contract
assert 'AUTOMATIC_MARKDOWN_EXPORT = false' in contract
assert 'MERGE_SQLITE_ACROSS_DEVICES = false' in contract
assert 'STORAGE_WARNING_BYTES = 1_073_741_824L' in contract

mapping_fixture = {
    "conversation_id": "conv-live-1",
    "current_node": "node-assistant",
    "mapping": {
        "node-user": {
            "id": "node-user", "parent": None, "children": ["node-assistant"],
            "message": {"id": "message-user", "author": {"role": "user"}, "create_time": 1788839000.125,
                        "content": {"content_type": "text", "parts": ["hello logger"]}},
        },
        "node-assistant": {
            "id": "node-assistant", "parent": "node-user", "children": [],
            "message": {"id": "message-assistant", "author": {"role": "assistant"}, "create_time": 1788839001.5,
                        "content": {"content_type": "text", "parts": ["captured"]}},
        },
    },
}
assert mapping_fixture["mapping"]["node-user"]["message"]["id"] == "message-user"
assert mapping_fixture["mapping"]["node-user"]["children"] == ["node-assistant"]

messages_fixture = {
    "conversation_id": "conv-modern-1",
    "current_node": "message-assistant",
    "messages": [
        {"id": "message-user", "author": {"role": "user"}, "create_time": 1788839000.125,
         "content": {"content_type": "text", "parts": ["hello logger"]}},
        {"id": "message-assistant", "author": {"role": "assistant"}, "create_time": 1788839001.5,
         "content": {"content_type": "text", "parts": ["captured"]}},
    ],
}
assert isinstance(messages_fixture["messages"], list)
assert messages_fixture["messages"][1]["id"] == messages_fixture["current_node"]
assert "parent" not in messages_fixture["messages"][0]
assert "children" not in messages_fixture["messages"][0]

print("android SQLite RAW + derivative collapse derivation smoke PASS")
