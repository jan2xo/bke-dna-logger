#!/usr/bin/env python3
from pathlib import Path

root = Path(__file__).resolve().parents[1]
base = root / "android" / "app" / "src" / "main" / "kotlin" / "com" / "bke" / "dna" / "logger"
kt = (base / "AndroidConversationDnaImportService.kt").read_text()
derivative_store = (base / "AndroidDerivativeStore.kt").read_text()
contract = (base / "DnaReconciliationContract.kt").read_text()

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
    'derivativeSourceSha(item.relativeTarget, "normalized")',
    'derivativeSourceSha(item.relativeTarget, "classifications")',
    "store.putNormalizedJson(sourceSha256, payload)",
    "store.putClassificationJson(sourceSha256, payload)",
]
for token in required:
    assert token in kt, token

# PR5 may write imported derivative payloads into this device's local Working
# Data SQLite through AndroidDerivativeStore. It still must never attach/merge a
# foreign SQLite database or use SQL merge primitives.
for forbidden in [
    "android.database.sqlite",
    "SQLiteDatabase",
    "SQLiteOpenHelper",
    "ATTACH DATABASE",
    "attachDatabase",
]:
    assert forbidden not in kt, f"native DNA import must not use SQLite merge primitive: {forbidden}"

# Derivative staging is consumed before generic file promotion, so new imports
# do not recreate permanent normalized/classification files.
commit = kt[kt.index("private fun commitStaged"):kt.index("private fun derivativeSourceSha")]
assert commit.index('derivativeSourceSha(item.relativeTarget, "normalized")') < commit.index("val target = File(captureRoot")
assert commit.index('derivativeSourceSha(item.relativeTarget, "classifications")') < commit.index("val target = File(captureRoot")
for token in [
    "CREATE TABLE IF NOT EXISTS derivative_classification",
    "CREATE TABLE IF NOT EXISTS derivative_normalized",
    "Conflicting immutable derivative",
]:
    assert token in derivative_store, token

assert "AUTOMATIC_DNA_EXPORT = false" in contract
assert "MERGE_SQLITE_ACROSS_DEVICES = false" in contract
assert "clearable" not in kt, "import must not make evidence cleanup-eligible"

print("android DNA reconciliation import into local derivative SQLite smoke PASS")
