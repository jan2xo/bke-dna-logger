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
    'JSONTokener(bodyPath.readText(Charsets.UTF_8))',
    'logCandidateStructure(rootValue)',
    'findNestedCandidateStructure(rootValue)',
    'addChildren(rootValue, stack)',
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
    '"BKE DNA normalization: candidate_root_object"',
    '"BKE DNA normalization: candidate_root_array"',
    '"BKE DNA normalization: candidate_root_other"',
    '"BKE DNA normalization: candidate_root_mapping"',
    '"BKE DNA normalization: candidate_nested_mapping"',
    '"BKE DNA normalization: candidate_root_conversation_id"',
    '"BKE DNA normalization: candidate_nested_conversation_id"',
    '"BKE DNA normalization: candidate_root_current_node"',
    '"BKE DNA normalization: candidate_nested_current_node"',
    '"BKE DNA normalization: normalization_skip_existing_result_unusable"',
    '"BKE DNA normalization: normalization_skip_body_missing"',
    '"BKE DNA normalization: normalization_skip_body_oversize"',
    '"BKE DNA normalization: normalization_skip_invalid_json"',
    '"BKE DNA normalization: normalization_skip_not_object"',
    '"BKE DNA normalization: normalization_skip_no_root_mapping"',
    '"BKE DNA normalization: normalization_skip_missing_conversation_id"',
    'output.fd.sync()',
    'StandardCopyOption.ATOMIC_MOVE',
):
    assert token in normalizer, token

# Candidate normalization diagnostics are observation-only. The normalizer still
# admits only classifier candidates with a root object and root mapping.
assert normalizer.index('!= CANDIDATE_KIND') < normalizer.index('val rootValue = try')
assert normalizer.index('logCandidateStructure(rootValue)') < normalizer.index('val root = rootValue as? JSONObject')
assert normalizer.index('val root = rootValue as? JSONObject') < normalizer.index('val mapping = root.optJSONObject("mapping")')
assert 'val mapping = root.optJSONObject("mapping") ?: run {' in normalizer
assert 'Log.d(TAG, "BKE DNA normalization: normalization_skip_no_root_mapping")' in normalizer
assert normalizer.index('addChildren(rootValue, stack)') < normalizer.index('while (stack.isNotEmpty()')

# Candidate envelope and skip diagnostics must remain fixed, non-sensitive
# markers. Never expose evidence identity, file paths, payload values, IDs, or
# dynamically formatted structure names.
for forbidden in (
    'Log.d(TAG, sourceSha256',
    'Log.d(TAG, bodyPath',
    'Log.d(TAG, rootValue',
    'Log.d(TAG, conversationNativeId',
    'Log.d(TAG, currentNodeNativeId',
    'BKE DNA normalization: $',
    'candidate_root_$',
    'candidate_nested_$',
    'normalization_skip_$',
):
    assert forbidden not in normalizer, forbidden

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
    'classification.getInt("score")',
    'classification.getJSONArray("signals")',
    'logLowScoreClassifierShape(outcome)',
    'logCandidateClassifierShape(outcome)',
    'logClassifierSignals(outcome)',
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
    '"BKE DNA derivation: classifier_score_0_9"',
    '"BKE DNA derivation: classifier_score_10_19"',
    '"BKE DNA derivation: classifier_score_20_27"',
    '"BKE DNA derivation: classifier_score_unexpected"',
    '"BKE DNA derivation: classifier_signal_mapping"',
    '"BKE DNA derivation: classifier_signal_messages"',
    '"BKE DNA derivation: classifier_signal_message"',
    '"BKE DNA derivation: classifier_signal_author"',
    '"BKE DNA derivation: classifier_signal_role"',
    '"BKE DNA derivation: classifier_signal_content"',
    '"BKE DNA derivation: classifier_signal_parts"',
    '"BKE DNA derivation: classifier_signal_parent"',
    '"BKE DNA derivation: classifier_signal_children"',
    '"BKE DNA derivation: classifier_signal_conversation_id"',
    '"BKE DNA derivation: classifier_signal_current_node"',
    '"BKE DNA derivation: classifier_signal_recognized_role"',
    '"BKE DNA derivation: classifier_signal_graph_shape"',
    '"BKE DNA derivation: classifier_signal_authored_shape"',
    'Log.d(TAG, "BKE DNA derivation: normalization_skipped")',
    'Log.d(TAG, "BKE DNA derivation: normalization_complete")',
    'Log.d(TAG, "BKE DNA derivation: reconciliation_complete")',
    'Log.d(TAG, "BKE DNA derivation: derivative_failed")',
):
    assert token in pipeline, token

# Classification diagnostics must remain fixed categories derived only from
# persisted classifier metadata. They must not expose evidence identity,
# content, raw signal collections, or dynamically formatted score/signal names.
for forbidden in (
    'Log.d(TAG, sourceSha256',
    'Log.d(TAG, bodyFile',
    'Log.d(TAG, contentType',
    'Log.d(TAG, normalized.conversationNativeId',
    'Log.d(TAG, outcome.signals',
    'Log.d(TAG, outcome.kind',
    'Log.d(TAG, outcome.confidence',
    'Log.d(TAG, outcome.score',
    'classifier_signal_$',
    'classifier_score_$',
    '"classification_other")',
    '"classification_candidate")',
):
    assert forbidden not in pipeline, forbidden

# Shape diagnostics are observation only; classifier admission stays locked to
# the existing native parity thresholds. Raw score buckets remain low-score-only,
# while admitted candidates expose only the same fixed structural signal set.
assert 'score >= 28' in classifier
assert 'score >= 55' in classifier
assert 'event == "classification_other_low_score" -> logLowScoreClassifierShape(outcome)' in pipeline
assert 'outcome.kind == CANDIDATE_KIND -> logCandidateClassifierShape(outcome)' in pipeline
assert pipeline.index('classification.getInt("score")') < pipeline.index('logLowScoreClassifierShape(outcome)')
assert pipeline.index('classification.getJSONArray("signals")') < pipeline.index('logCandidateClassifierShape(outcome)')
assert 'private fun logCandidateClassifierShape(outcome: ClassificationOutcome) {\n        logClassifierSignals(outcome)\n    }' in pipeline
assert 'private fun logLowScoreClassifierShape(outcome: ClassificationOutcome)' in pipeline
assert pipeline.count('classifier_score_0_9') == 1
assert pipeline.count('classifier_score_10_19') == 1
assert pipeline.count('classifier_score_20_27') == 1

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
