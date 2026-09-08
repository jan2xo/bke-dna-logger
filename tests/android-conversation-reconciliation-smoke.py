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
    'GRAPH_COVERAGE_BASIS = "structural_graph_closure_only"',
    'LOGICAL_GRAPH_COVERAGE_BASIS = "multi_snapshot_structural_union"',
    'MESSAGES_COVERAGE_BASIS = "messages_array_no_graph_edges"',
    "val graphSnapshots = snapshots.filter { it.coverageBasis == GRAPH_COVERAGE_BASIS }",
    "computeGraphlessCoverage",
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

# Messages-only evidence has no graph topology and must never be upgraded to a
# complete structural graph merely because its normalized nodes have empty edge lists.
for token in (
    'status = "indeterminate"',
    "rootFound = false",
    "currentLeafFound = false",
    "parentChainComplete = false",
):
    assert token in aggregation, token

schema_tokens = [
    "DATABASE_VERSION = 3",
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

# Contract fixture: two independent graph-backed device snapshots for one native
# conversation must preserve both branches, one prompt identity, and both sources.
source_a = "a" * 64
source_b = "b" * 64
snapshots = [
    {
        "source": source_a,
        "coverage_basis": "structural_graph_closure_only",
        "nodes": {
            "user-root": {"children": ["assistant-a"], "message": "message-user"},
            "assistant-a": {"children": [], "message": "message-a"},
        },
    },
    {
        "source": source_b,
        "coverage_basis": "structural_graph_closure_only",
        "nodes": {
            "user-root": {"children": ["assistant-b"], "message": "message-user"},
            "assistant-b": {"children": [], "message": "message-b"},
        },
    },
]
union = {}
for snapshot in snapshots:
    assert snapshot["coverage_basis"] == "structural_graph_closure_only"
    for node_id, node in snapshot["nodes"].items():
        current = union.setdefault(node_id, {"children": set(), "messages": set(), "sources": set()})
        current["children"].update(node["children"])
        current["messages"].add(node["message"])
        current["sources"].add(snapshot["source"])

assert union["user-root"]["children"] == {"assistant-a", "assistant-b"}
assert union["user-root"]["messages"] == {"message-user"}
assert set().union(*(node["sources"] for node in union.values())) == {source_a, source_b}

# A messages-array snapshot can contribute message evidence, but without parent
# or child fields it cannot independently establish graph completeness.
messages_snapshot = {
    "coverage_basis": "messages_array_no_graph_edges",
    "nodes": [
        {"id": "message-user", "parent": None, "children": []},
        {"id": "message-assistant", "parent": None, "children": []},
    ],
}
assert messages_snapshot["coverage_basis"] == "messages_array_no_graph_edges"
assert all(not node["children"] for node in messages_snapshot["nodes"])

print("android logical conversation reconciliation smoke PASS")
