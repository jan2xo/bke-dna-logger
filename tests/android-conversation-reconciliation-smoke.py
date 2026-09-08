#!/usr/bin/env python3
from pathlib import Path

root = Path(__file__).resolve().parents[1]
base = root / "android" / "app" / "src" / "main" / "kotlin" / "com" / "bke" / "dna" / "logger"
aggregation = (base / "AndroidConversationAggregationEngine.kt").read_text()
index = (base / "AndroidCaptureIndex.kt").read_text()
importer = (base / "AndroidConversationDnaImportService.kt").read_text()
contract = (base / "DnaReconciliationContract.kt").read_text()

aggregation_tokens = [
    "groupBy { it.conversationNativeId }",
    "getOrPut(node.nodeNativeId)",
    "messageNativeIds",
    "parentNativeIds",
    "childNativeIds",
    "createdAtValues",
    "revisionSha256",
    "sourceSha256s",
    "firstObservedAt",
    "lastObservedAt",
    'coverageBasis = "multi_snapshot_structural_union"',
    "computeCoverage",
    "unresolvedParentNativeIds",
    "unresolvedChildNativeIds",
    'val relativePath = "conversations/$conversationKey.json"',
    "output.fd.sync()",
    "StandardCopyOption.ATOMIC_MOVE",
    "index.replaceLogicalConversation",
]
for token in aggregation_tokens:
    assert token in aggregation, token

schema_tokens = [
    "DATABASE_VERSION = 2",
    "logical_conversation",
    "conversation_source",
    "logical_message_node",
    "logical_message_edge",
    "logical_message_revision",
    "logical_message_revision_source",
    "created_at_values_json",
    "source_count",
    "node_count",
    "dna_archived INTEGER NOT NULL DEFAULT 0",
    'put("dna_archived", 0)',
]
for token in schema_tokens:
    assert token in index, token

assert "AndroidConversationAggregationEngine(appContext)" in importer
assert "engine.aggregateAll()" in importer
assert "conversationKey = logicalConversation.conversationKey" in importer
assert "coverageStatus = logicalConversation.coverageStatus" in importer
assert "nodeCount = logicalConversation.nodes.size" in importer
assert "clearable" not in aggregation
assert "clearable" not in index
assert "ATTACH DATABASE" not in aggregation.upper()
assert "ATTACH DATABASE" not in index.upper()
assert "AUTOMATIC_DNA_EXPORT = false" in contract
assert "AUTOMATIC_MARKDOWN_EXPORT = false" in contract
assert "MERGE_SQLITE_ACROSS_DEVICES = false" in contract

# Contract fixture: two independent device snapshots for one native conversation
# must preserve both branches, one prompt revision identity, and both sources.
source_a = "a" * 64
source_b = "b" * 64
snapshots = [
    {
        "source": source_a,
        "nodes": {
            "user-root": {"children": ["assistant-a"], "message": "message-user"},
            "assistant-a": {"children": [], "message": "message-a"},
        },
    },
    {
        "source": source_b,
        "nodes": {
            "user-root": {"children": ["assistant-b"], "message": "message-user"},
            "assistant-b": {"children": [], "message": "message-b"},
        },
    },
]
union = {}
for snapshot in snapshots:
    for node_id, node in snapshot["nodes"].items():
        current = union.setdefault(node_id, {"children": set(), "messages": set(), "sources": set()})
        current["children"].update(node["children"])
        current["messages"].add(node["message"])
        current["sources"].add(snapshot["source"])

assert union["user-root"]["children"] == {"assistant-a", "assistant-b"}
assert union["user-root"]["messages"] == {"message-user"}
assert set().union(*(node["sources"] for node in union.values())) == {source_a, source_b}

print("android logical conversation reconciliation smoke PASS")
