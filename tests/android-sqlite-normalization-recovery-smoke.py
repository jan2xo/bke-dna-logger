#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "android/app/src/main/kotlin/com/bke/dna/logger"

dispatcher = (BASE / "AndroidConversationNormalizationDispatcher.kt").read_text(encoding="utf-8")
recovery = (BASE / "AndroidNormalizationRecovery.kt").read_text(encoding="utf-8")
runtime = (BASE / "AndroidCaptureRuntime.kt").read_text(encoding="utf-8")
telemetry = (BASE / "AndroidWorkingDataTelemetry.kt").read_text(encoding="utf-8")
ui = (BASE / "AndroidExportsBackupsActivity.kt").read_text(encoding="utf-8")

# New classifications are SQLite-first. The dispatcher must never regress to
# the retired permanent captures/classifications/<sha>.json path.
for token in (
    'AndroidDerivativeSourceAccess.readClassification(appContext, sourceSha256)',
    'classificationRoot.optJSONObject("classification")',
    'AndroidRawSourceAccess.readAllBytes(appContext, sourceSha256, MAX_BODY_BYTES)',
    'root.optJSONObject("mapping") != null',
    'root.optJSONArray("messages") != null',
):
    assert token in dispatcher, token
for forbidden in (
    'classificationsDirectory',
    'classificationPath',
    'File(captureRoot, "classifications")',
):
    assert forbidden not in dispatcher, forbidden

# Semantic repairs are deliberately one-shot and re-arm only DONE candidates
# that have SQLite classification but no normalized derivative. Alpha.5 gets a
# new marker so candidates skipped by alpha.4 are eligible exactly once.
for token in (
    'sqlite-classification-dispatcher-v1',
    'modern-messages-normalizer-v2',
    'INNER JOIN derivative_classification c',
    'LEFT JOIN derivative_normalized n',
    'WHERE q.status = ?',
    'n.source_sha256 IS NULL',
    'conversation_payload_candidate',
    'put("status", STATUS_WAITING)',
    'RECOVERED_SQLITE_CLASSIFICATION',
    'RECOVERED_MODERN_MESSAGES',
    'persistMarker(preferences, PREF_SQLITE_CLASSIFICATION_RECOVERY)',
    'persistMarker(preferences, PREF_MODERN_MESSAGES_RECOVERY)',
    '.putBoolean(marker, true)',
):
    assert token in recovery, token
for forbidden in ('deleteSource(', 'deleteDatabase(', 'captures/bodies', 'stagingFile.delete'):
    assert forbidden not in recovery, forbidden

# Recovery must run before the queue drains so re-armed rows are visible to the
# normal RAW-verification + semantic pipeline immediately on app start.
assert 'AndroidNormalizationRecovery.rearmOnce(appContext)' in runtime
assert runtime.index('AndroidNormalizationRecovery.rearmOnce(appContext)') < runtime.index(
    'AndroidDerivationScheduler.start(appContext)'
)

# The owner-facing size display still distinguishes logical exact evidence from
# compressed physical representation, but the redesigned UI presents those
# values as compact card metadata instead of a debug-style multiline dump.
for token in (
    'SUM(byte_length)',
    'SUM(compressed_bytes)',
    'verifiedSourceCount',
    'exactRawBytes',
    'compressedRawPayloadBytes',
):
    assert token in telemetry, token
for token in (
    'formatBytes(totalWorkingBytes)',
    'formatBytes(inspected.snapshotBytes)',
    'formatBytes(telemetry.exactRawBytes)',
    'formatBytes(telemetry.compressedRawPayloadBytes)',
    'telemetry.verifiedSourceCount',
    'storage warning at 1 GiB',
):
    assert token in ui, token

print('android SQLite normalization recovery + RAW telemetry smoke PASS')
