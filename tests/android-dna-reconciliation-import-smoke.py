#!/usr/bin/env python3
from pathlib import Path

root = Path(__file__).resolve().parents[1]
kt = (root / "android" / "app" / "src" / "main" / "kotlin" / "com" / "bke" / "dna" / "logger" / "AndroidConversationDnaImportService.kt").read_text()
contract = (root / "android" / "app" / "src" / "main" / "kotlin" / "com" / "bke" / "dna" / "logger" / "DnaReconciliationContract.kt").read_text()

required = [
    "AndroidConversationDnaVerifier::verify",
    "conversationNativeId",
    "Cross-device reconciliation cannot mix different conversationNativeId values",
    "SHA256SUMS",
    "formatVersion",
    "ARCHIVE_SCOPE",
    "sources/$source/raw.body",
    "sources/$source/normalized.json",
    "observations/",
    "witnesses/",
    "reconciliations/",
    "import-staging",
    "output.fd.sync()",
    "Existing local evidence conflicts",
    "DnaReconciliationContract.CONTRACT_ID",
]
for token in required:
    assert token in kt, token

# The implementation may document the SQLite guardrail in comments. Prove the
# importer itself has no Android SQLite API or SQL attach/merge behavior instead
# of rejecting the harmless word "SQLite" anywhere in the source file.
for forbidden in [
    "android.database.sqlite",
    "SQLiteDatabase",
    "SQLiteOpenHelper",
    "ATTACH DATABASE",
    "attachDatabase",
]:
    assert forbidden not in kt, f"native DNA import must not use SQLite merge primitive: {forbidden}"

assert "AUTOMATIC_DNA_EXPORT = false" in contract
assert "MERGE_SQLITE_ACROSS_DEVICES = false" in contract
assert "clearable" not in kt, "import must not make evidence cleanup-eligible"

print("android DNA reconciliation import smoke PASS")
