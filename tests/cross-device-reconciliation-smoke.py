#!/usr/bin/env python3
from pathlib import Path

root = Path(__file__).resolve().parents[1]

doc = (root / "docs" / "cross-device-reconciliation.md").read_text()
cs = (root / "src" / "BKE.Dna.Logger.Host" / "Reconciliation" / "DnaReconciliationContract.cs").read_text()
kt = (root / "android" / "app" / "src" / "main" / "kotlin" / "com" / "bke" / "dna" / "logger" / "DnaReconciliationContract.kt").read_text()

required = [
    "bke-dna-reconciliation-v1",
    "1073741824",
    "conversation",
]
for token in required:
    assert token in doc.replace(",", ""), token
    assert token in cs.replace("_", ""), token
    assert token in kt.replace("_", ""), token

assert "AutomaticDnaExport = false" in cs
assert "AutomaticMarkdownExport = false" in cs
assert "MergeSqliteAcrossDevices = false" in cs
assert "AUTOMATIC_DNA_EXPORT = false" in kt
assert "AUTOMATIC_MARKDOWN_EXPORT = false" in kt
assert "MERGE_SQLITE_ACROSS_DEVICES = false" in kt
assert "never merges live SQLite databases" in doc
assert "`.dna` export is manual" in doc
assert "`.md` export is manual" in doc
assert "soft notification threshold" in doc
assert "MUST NOT stop capture" in doc
assert "conversationNativeId" in doc
assert "nodeNativeId" in doc
assert "messageNativeId" in doc
assert "SHA-256" in doc
assert "preserve every unique observation" in doc

print("cross-device DNA reconciliation contract smoke PASS")
