#!/usr/bin/env python3
from pathlib import Path

repo = Path(__file__).resolve().parents[1]
kotlin = repo / "android" / "app" / "src" / "main" / "kotlin" / "com" / "bke" / "dna" / "logger"
classifier = (kotlin / "AndroidConversationPayloadClassifier.kt").read_text(encoding="utf-8")
normalizer = (kotlin / "AndroidGraphNormalizationEngine.kt").read_text(encoding="utf-8")
pipeline = (kotlin / "AndroidLiveDerivationPipeline.kt").read_text(encoding="utf-8")
store = (kotlin / "AndroidCaptureStore.kt").read_text(encoding="utf-8")
aggregation = (kotlin / "AndroidConversationAggregationEngine.kt").read_text(encoding="utf-8")
contract = (kotlin / "DnaReconciliationContract.kt").read_text(encoding="utf-8")

for token in (
    '"conversation_payload_candidate"',
    '"mapping"',
    '"message"',
    '"recognized_message_role"',
    '"conversation_graph_shape"',
    '"authored_message_shape"',
    '"conversation_id"',
    '"current_node"',
    '"text/event-stream"',
    '"invalid_utf8"',
    'score >= 28',
    'score >= 55',
):
    assert token in classifier, token

for token in (
    'MAX_BODY_BYTES = 16L * 1024 * 1024',
    'PARSER = "generic-mapping-graph-v0"',
    'COVERAGE_BASIS = "structural_graph_closure_only"',
    'root.optJSONObject("mapping")',
    'root.opt("conversation_id")',
    'root.opt("current_node")',
    'message.opt("create_time")',
    'message.opt("id")',
    'message.optJSONObject("author")',
    'content.optJSONArray("parts")',
    '"contentJson"',
    '"messageNativeId"',
    '"parentNativeId"',
    '"childNativeIds"',
    '"normalizedAt"',
    'output.fd.sync()',
    'StandardCopyOption.ATOMIC_MOVE',
):
    assert token in normalizer, token

for token in (
    'MAX_CLASSIFICATION_BYTES = 16L * 1024 * 1024',
    'CANDIDATE_KIND = "conversation_payload_candidate"',
    'AndroidConversationPayloadClassifier.classify',
    'AndroidGraphNormalizationEngine(appContext)',
    'engine.aggregateConversation(normalized.conversationNativeId)',
    'classification_size_limit',
    'classifier_error',
    'readClassificationOutcome(sourceSha256)',
    'logClassificationOutcome(readClassificationOutcome(sourceSha256))',
    'classification.getJSONArray("signals")',
    'Raw evidence and its immutable observation are already durable',
    'Log.d(TAG, "BKE DNA derivation: started")',
    '"classification_candidate_high"',
    '"classification_candidate_medium"',
    '"classification_other_size_limit"',
    '"classification_other_error"',
    '"classification_other_non_textual"',
    '"classification_other_invalid_utf8"',
    '"classification_other_no_json"',
    '"classification_other_low_score"',
    'Log.d(TAG, "BKE DNA derivation: normalization_skipped")',
    'Log.d(TAG, "BKE DNA derivation: normalization_complete")',
    'Log.d(TAG, "BKE DNA derivation: reconciliation_complete")',
    'Log.d(TAG, "BKE DNA derivation: derivative_failed")',
):
    assert token in pipeline, token

# Classification reason diagnostics must remain fixed categories derived only
# from persisted classifier metadata. They must not expose evidence identity or content.
for forbidden in (
    'Log.d(TAG, sourceSha256',
    'Log.d(TAG, bodyFile',
    'Log.d(TAG, contentType',
    'Log.d(TAG, normalized.conversationNativeId',
    'Log.d(TAG, outcome.signals',
    'Log.d(TAG, outcome.kind',
    'Log.d(TAG, outcome.confidence',
    '"classification_other")',
    '"classification_candidate")',
):
    assert forbidden not in pipeline, forbidden

assert pipeline.index('ensureClassification(') < pipeline.index('readClassificationOutcome(sourceSha256)')
assert pipeline.index('readClassificationOutcome(sourceSha256)') < pipeline.index('normalizer.normalizeCandidate(sourceSha256)')
assert pipeline.index('normalizer.normalizeCandidate(sourceSha256)') < pipeline.index('engine.aggregateConversation(normalized.conversationNativeId)')
assert pipeline.index('engine.aggregateConversation(normalized.conversationNativeId)') < pipeline.index('Log.d(TAG, "BKE DNA derivation: reconciliation_complete")')

assert 'engine.aggregateAll()' not in pipeline
assert 'fun aggregateConversation(conversationNativeId: String)' in aggregation
assert 'aggregateConversationState(conversationNativeId, snapshots)' in aggregation
assert 'AndroidLiveDerivationPipeline(context.applicationContext)' in store
assert 'derivation.processCompletedCapture(' in store
assert store.index('index.record(') < store.index('derivation.processCompletedCapture(')

# Live derivation must not silently alter the owner-controlled export/storage policy.
assert 'AUTOMATIC_DNA_EXPORT = false' in contract
assert 'AUTOMATIC_MARKDOWN_EXPORT = false' in contract
assert 'MERGE_SQLITE_ACROSS_DEVICES = false' in contract
assert 'STORAGE_WARNING_BYTES = 1_073_741_824L' in contract

# Fixture mirrors the minimum graph shape the native classifier/normalizer is
# intended to admit: preserve mapping-node ID separately from message ID,
# parent/children topology, role/content, and original create_time.
fixture = {
    "conversation_id": "conv-live-1",
    "current_node": "node-assistant",
    "mapping": {
        "node-user": {
            "id": "node-user",
            "parent": None,
            "children": ["node-assistant"],
            "message": {
                "id": "message-user",
                "author": {"role": "user"},
                "create_time": 1788839000.125,
                "content": {"content_type": "text", "parts": ["hello logger"]},
            },
        },
        "node-assistant": {
            "id": "node-assistant",
            "parent": "node-user",
            "children": [],
            "message": {
                "id": "message-assistant",
                "author": {"role": "assistant"},
                "create_time": 1788839001.5,
                "content": {"content_type": "text", "parts": ["captured"]},
            },
        },
    },
}
assert fixture["conversation_id"] == "conv-live-1"
assert fixture["mapping"]["node-user"]["message"]["id"] == "message-user"
assert fixture["mapping"]["node-user"]["children"] == ["node-assistant"]
assert fixture["mapping"]["node-assistant"]["parent"] == "node-user"
assert fixture["mapping"]["node-user"]["message"]["create_time"] == 1788839000.125

print("android live classification and normalization smoke PASS")
