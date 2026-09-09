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
    'JSONObject(classificationPayload).getJSONObject("classification")',
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

# Alpha.3 repair is deliberately one-shot and re-arms only DONE candidates that
# have SQLite classification but no normalized derivative. It never recaptures
# or deletes RAW.
for token in (
    'sqlite-classification-dispatcher-v1',
    'INNER JOIN derivative_classification c',
    'LEFT JOIN derivative_normalized n',
    'WHERE q.status = ?',
    'n.source_sha256 IS NULL',
    'conversation_payload_candidate',
    'put("status", STATUS_WAITING)',
    'RECOVERED_SQLITE_CLASSIFICATION',
    'putBoolean(PREF_SQLITE_CLASSIFICATION_RECOVERY, true)',
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

# The owner-facing size display distinguishes logical exact evidence from the
# compressed physical representation instead of making compression look like loss.
for token in (
    'SUM(byte_length)',
    'SUM(compressed_bytes)',
    'verifiedSourceCount',
    'exactRawBytes',
    'compressedRawPayloadBytes',
):
    assert token in telemetry, token
for token in (
    'Physical local Working Data:',
    'Inspected exact RAW represented:',
    'Inspected compressed RAW payload:',
    'Inspected verified RAW sources:',
    'exact bytes are length + SHA-256 verified during ingest',
):
    assert token in ui, token

print('android SQLite normalization recovery + RAW telemetry smoke PASS')
