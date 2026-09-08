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
aggregation = (kotlin / "AndroidConversationAggregationEngine.kt").read_text(encoding="utf-8")
contract = (kotlin / "DnaReconciliationContract.kt").read_text(encoding="utf-8")

# Classifier admission remains locked.
for token in (
    '"conversation_payload_candidate"',
    '"mapping"',
    '"messages"',
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

# generic-mapping-graph-v0 remains strict and independently implemented.
for token in (
    'MAX_BODY_BYTES = 16L * 1024 * 1024',
    'PARSER = "generic-mapping-graph-v0"',
    'COVERAGE_BASIS = "structural_graph_closure_only"',
    'JSONTokener(bodyPath.readText(Charsets.UTF_8))',
    'root.optJSONObject("mapping")',
    'root.opt("conversation_id")',
    'root.opt("current_node")',
    'message.opt("create_time")',
    'message.opt("id")',
    'message.optJSONObject("author")',
    'content.optJSONArray("parts")',
    '"parentNativeId"',
    '"childNativeIds"',
    '"BKE DNA normalization: normalization_skip_no_root_mapping"',
):
    assert token in graph_normalizer, token

assert 'val mapping = root.optJSONObject("mapping") ?: run {' in graph_normalizer
assert graph_normalizer.index('val root = rootValue as? JSONObject') < graph_normalizer.index(
    'val mapping = root.optJSONObject("mapping")'
)

# The runtime-observed messages array remains represented by fixed diagnostics.
for token in (
    '"BKE DNA normalization: candidate_root_messages"',
    '"BKE DNA normalization: candidate_nested_messages"',
    '"BKE DNA normalization: candidate_messages_array"',
    '"BKE DNA normalization: candidate_messages_object"',
):
    assert token in graph_normalizer, token

# Modern messages representation is a separate parser. It preserves only
# evidenced message fields and never manufactures mapping or graph edges.
for token in (
    'PARSER = "messages-array-v0"',
    'COVERAGE_BASIS = "messages_array_no_graph_edges"',
    'root.optJSONArray("messages")',
    'root.opt("conversation_id")',
    'root.opt("current_node")',
    'message.opt("id")',
    'message.optJSONObject("author")',
    'message.opt("create_time")',
    'message.opt("content")',
    'content.optJSONArray("parts")',
    '.put("coverageStatus", "indeterminate")',
    '.put("rootFound", false)',
    '.put("currentLeafFound", false)',
    '.put("parentChainComplete", false)',
    '.put("parentNativeId", JSONObject.NULL)',
    '.put("childNativeIds", JSONArray())',
    'sourceCurrentNodeId?.takeIf { it in knownMessageIds }',
    '"BKE DNA normalization: messages_array_normalization_complete"',
):
    assert token in messages_normalizer, token

for forbidden in (
    'optJSONObject("mapping")',
    'message.opt("parent")',
    'message.optJSONArray("children")',
    'parent =',
    'children =',
):
    assert forbidden not in messages_normalizer, forbidden

# The dispatcher chooses a representation explicitly. Mapping remains the
# richer/legacy graph representation and is checked before messages-array-v0.
for token in (
    'AndroidGraphNormalizationEngine(context.applicationContext)',
    'AndroidMessagesNormalizationEngine(context.applicationContext)',
    'root.optJSONObject("mapping") != null',
    'root.optJSONArray("messages") != null',
    '"BKE DNA normalization: normalization_representation_mapping_graph"',
    '"BKE DNA normalization: normalization_representation_messages_array"',
    '"BKE DNA normalization: normalization_skip_unsupported_representation"',
):
    assert token in dispatcher, token
assert dispatcher.index('root.optJSONObject("mapping") != null') < dispatcher.index(
    'root.optJSONArray("messages") != null'
)

# Candidate diagnostics remain fixed and non-sensitive.
for source in (graph_normalizer, messages_normalizer, dispatcher):
    for forbidden in (
        'Log.d(TAG, sourceSha256',
        'Log.d(TAG, bodyPath',
        'Log.d(TAG, conversationNativeId',
        'Log.d(TAG, currentNodeNativeId',
        'BKE DNA normalization: $',
        'normalization_skip_$',
    ):
        assert forbidden not in source, forbidden

# Live pipeline now routes through the representation dispatcher and retains
# the same derivative failure containment + classifier diagnostics.
for token in (
    'MAX_CLASSIFICATION_BYTES = 16L * 1024 * 1024',
    'CANDIDATE_KIND = "conversation_payload_candidate"',
    'AndroidConversationPayloadClassifier.classify',
    'AndroidConversationNormalizationDispatcher(appContext)',
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
    '"BKE DNA derivation: classifier_signal_mapping"',
    '"BKE DNA derivation: classifier_signal_messages"',
    '"BKE DNA derivation: classifier_signal_author"',
    '"BKE DNA derivation: classifier_signal_role"',
    '"BKE DNA derivation: classifier_signal_content"',
    '"BKE DNA derivation: classifier_signal_parts"',
    '"BKE DNA derivation: classifier_signal_conversation_id"',
    '"BKE DNA derivation: classifier_signal_current_node"',
    '"BKE DNA derivation: classifier_signal_recognized_role"',
    '"BKE DNA derivation: classifier_signal_authored_shape"',
    'Log.d(TAG, "BKE DNA derivation: normalization_skipped")',
    'Log.d(TAG, "BKE DNA derivation: normalization_complete")',
    'Log.d(TAG, "BKE DNA derivation: reconciliation_complete")',
    'Log.d(TAG, "BKE DNA derivation: derivative_failed")',
):
    assert token in pipeline, token

for forbidden in (
    'Log.d(TAG, sourceSha256',
    'Log.d(TAG, bodyFile',
    'Log.d(TAG, contentType',
    'Log.d(TAG, normalized.conversationNativeId',
    'Log.d(TAG, outcome.signals',
    'Log.d(TAG, outcome.score',
    'classifier_signal_$',
    'classifier_score_$',
):
    assert forbidden not in pipeline, forbidden

assert 'score >= 28' in classifier
assert 'score >= 55' in classifier
assert 'event == "classification_other_low_score" -> logLowScoreClassifierShape(outcome)' in pipeline
assert 'outcome.kind == CANDIDATE_KIND -> logCandidateClassifierShape(outcome)' in pipeline
assert pipeline.index('ensureClassification(') < pipeline.index('readClassificationOutcome(sourceSha256)')
assert pipeline.index('readClassificationOutcome(sourceSha256)') < pipeline.index(
    'normalizer.normalizeCandidate(sourceSha256)'
)
assert pipeline.index('normalizer.normalizeCandidate(sourceSha256)') < pipeline.index(
    'engine.aggregateConversation(normalized.conversationNativeId)'
)

# Reconciliation must not reinterpret absent parent/child edges as a complete
# graph. Graph coverage is computed only from graph-backed normalized sources.
for token in (
    'GRAPH_COVERAGE_BASIS = "structural_graph_closure_only"',
    'LOGICAL_GRAPH_COVERAGE_BASIS = "multi_snapshot_structural_union"',
    'MESSAGES_COVERAGE_BASIS = "messages_array_no_graph_edges"',
    'val graphSnapshots = snapshots.filter { it.coverageBasis == GRAPH_COVERAGE_BASIS }',
    'computeGraphlessCoverage(latest.currentNodeNativeId, nodes)',
    'status = "indeterminate"',
    'rootFound = false',
    'currentLeafFound = false',
    'parentChainComplete = false',
):
    assert token in aggregation, token

assert 'fun aggregateConversation(conversationNativeId: String)' in aggregation
assert 'aggregateConversationState(conversationNativeId, snapshots)' in aggregation
assert 'AndroidLiveDerivationPipeline(context.applicationContext)' in store
assert 'derivation.processCompletedCapture(' in store
assert store.index('index.record(') < store.index('derivation.processCompletedCapture(')

# Owner-controlled export/storage semantics remain untouched.
assert 'AUTOMATIC_DNA_EXPORT = false' in contract
assert 'AUTOMATIC_MARKDOWN_EXPORT = false' in contract
assert 'MERGE_SQLITE_ACROSS_DEVICES = false' in contract
assert 'STORAGE_WARNING_BYTES = 1_073_741_824L' in contract

# Mapping fixture: node identity and graph topology stay separate from message ID.
mapping_fixture = {
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
assert mapping_fixture["mapping"]["node-user"]["message"]["id"] == "message-user"
assert mapping_fixture["mapping"]["node-user"]["children"] == ["node-assistant"]

# Runtime-proven messages representation: root array, authored messages, but no
# graph edges. The implementation may preserve a current node only on an exact
# match to an exposed message ID; it must not invent edge topology.
messages_fixture = {
    "conversation_id": "conv-modern-1",
    "current_node": "message-assistant",
    "messages": [
        {
            "id": "message-user",
            "author": {"role": "user"},
            "create_time": 1788839000.125,
            "content": {"content_type": "text", "parts": ["hello logger"]},
        },
        {
            "id": "message-assistant",
            "author": {"role": "assistant"},
            "create_time": 1788839001.5,
            "content": {"content_type": "text", "parts": ["captured"]},
        },
    ],
}
assert isinstance(messages_fixture["messages"], list)
assert messages_fixture["messages"][1]["id"] == messages_fixture["current_node"]
assert "parent" not in messages_fixture["messages"][0]
assert "children" not in messages_fixture["messages"][0]

print("android live classification and normalization smoke PASS")
