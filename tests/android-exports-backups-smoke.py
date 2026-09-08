#!/usr/bin/env python3
from pathlib import Path
import hashlib

root = Path(__file__).resolve().parents[1]
base = root / "android" / "app" / "src" / "main" / "kotlin" / "com" / "bke" / "dna" / "logger"
archive = (base / "AndroidConversationDnaArchiveService.kt").read_text()
index = (base / "AndroidCaptureIndex.kt").read_text()
ui = (base / "AndroidExportsBackupsActivity.kt").read_text()
reader = (base / "AndroidConversationReaderActivity.kt").read_text()
main = (base / "MainActivity.kt").read_text()
human = (base / "AndroidHumanExportService.kt").read_text()
working_data = (base / "AndroidWorkingDataManager.kt").read_text()
paths = (base / "AndroidDnaPaths.kt").read_text()
backup = (base / "AndroidWorkingBackupService.kt").read_text()
contract = (base / "DnaReconciliationContract.kt").read_text()
manifest = (root / "android" / "app" / "src" / "main" / "AndroidManifest.xml").read_text()
readme = (root / "README.md").read_text()

archive_tokens = [
    '"formatVersion", 2',
    '"scope", DnaReconciliationContract.ARCHIVE_SCOPE',
    '"conversation/state.json"',
    '"SHA256SUMS"',
    '"sources/$sourceSha/raw.body"',
    '"sources/$sourceSha/normalized.json"',
    '"capture_observation"',
    '"dom_witness"',
    '"reconciliation"',
    'ZipEntry.STORED',
    'AndroidConversationDnaV2Verifier.verify',
    'resolver.openOutputStream(destinationUri, "w")',
    'resolver.openInputStream(destinationUri)',
    'destinationSha256 == verified.archiveSha256',
    'index.recordVerifiedConversationArchive',
    'AUTOMATIC_DNA_EXPORT',
]
for token in archive_tokens:
    assert token in archive, token

assert archive.index('resolver.openInputStream(destinationUri)') < archive.index('index.recordVerifiedConversationArchive')
assert archive.index('destinationSha256 == verified.archiveSha256') < archive.index('index.recordVerifiedConversationArchive')

for token in [
    'DATABASE_VERSION = 3',
    'dna_archive_id',
    'dna_archive_sha256',
    'archived_at',
    'recordVerifiedConversationArchive',
    'conversation_source',
    'dna_archived',
]:
    assert token in index, token

working_data_tokens = [
    'Latest — Active',
    'read_only_recovery',
    'working.sqlite',
    'PRAGMA wal_checkpoint(TRUNCATE)',
    'SQLiteDatabase.OPEN_READONLY',
    'rawEvidenceIncluded',
    'rawEvidenceSharedBySha',
    'conversationStateIncluded',
    'appContext.deleteDatabase(AndroidCaptureIndex.DATABASE_NAME)',
    'ensureActiveDatabaseCreated()',
    'savedWorkingDataBytes',
    'GENERATION_ID_REGEX',
    'snapshotDatabase.setReadOnly()',
    'verifiedGeneration',
    'if (!latestRetired) generationDirectory.deleteRecursively()',
]
for token in working_data_tokens:
    assert token in working_data, token

verify_pos = working_data.index('val verifiedGeneration = readGeneration(generationDirectory)')
retire_pos = working_data.index('appContext.deleteDatabase(AndroidCaptureIndex.DATABASE_NAME)')
assert verify_pos < retire_pos
assert 'if (!latestRetired) generationDirectory.deleteRecursively()' in working_data

assert 'dna/working-data' in paths
assert '.put("rawEvidenceIncluded", false)' in working_data
assert '.put("rawEvidenceSharedBySha", true)' in working_data
assert 'bodies/' not in working_data

ui_tokens = [
    'Working Data & Exports',
    'Spinner',
    'READ-ONLY RECOVERY',
    'Save & Start New Working Data',
    'Back to Latest Working Data',
    'Read conversation',
    'Export .dna',
    'Export CLEAN.md',
    'Export RAW.md',
    'Backup Working Data',
    'Import .dna / backup',
    'Notify threshold: 1 GiB — no hard limit; capture continues.',
    'EXTRA_WORKING_DATA_ID',
    'AndroidWorkingDataManager',
    'AndroidConversationReaderActivity::class.java',
    'AndroidConversationDnaArchiveService',
    'exportCleanMarkdownToUri',
    'exportRawMarkdownToUri',
    'operationInProgress',
    'Working Data operation in progress',
]
for token in ui_tokens:
    assert token in ui, token

assert 'if (selected.isLatest)' in ui
assert 'Historical Working Data cannot mutate Latest .dna durability state' in ui
assert 'pendingWorkingDataId == AndroidWorkingDataManager.LATEST_ID' in ui

reader_tokens = [
    'CLEAN · JAN / RIGHT-HAND only · no tools',
    'RAW · unfiltered captured conversation payloads',
    'READ-ONLY RECOVERY',
    'SQLite shared projection excluded',
    'renderCleanMarkdown',
    'renderRawMarkdown',
    'setTextIsSelectable(true)',
    'EXTRA_CONVERSATION_KEY',
    'EXTRA_WORKING_DATA_ID',
]
for token in reader_tokens:
    assert token in reader, token

assert 'Working Data & Exports' in main
assert 'geckoHost.stop()' in main
assert 'capturePausedForWorkingData = true' in main
resume_start = main.index('override fun onResume()')
resume_end = main.index('override fun onDestroy()', resume_start)
resume = main[resume_start:resume_end]
assert 'geckoHost = GeckoViewHost(this, geckoView)' in resume
assert 'geckoHost.start()' in resume

assert 'AndroidExportsBackupsActivity' in manifest
assert 'AndroidConversationReaderActivity' in manifest

human_tokens = [
    'CLEAN_JAN = "JAN"',
    'CLEAN_RIGHT_HAND = "RIGHT-HAND"',
    'setOf("user") -> CLEAN_JAN',
    'setOf("assistant") -> CLEAN_RIGHT_HAND',
    'conversationWorkingBytes',
    'exportCleanMarkdownToUri',
    'exportRawMarkdownToUri',
    'Unfiltered captured conversation payloads',
    'observationsForSource',
    'bodies/$sha.body',
    'AUTOMATIC_MARKDOWN_EXPORT',
]
for token in human_tokens:
    assert token in human, token

clean_start = human.index('private fun renderCleanMarkdown(state: JSONObject)')
clean_end = human.index('/**\n     * RAW contract', clean_start)
clean = human[clean_start:clean_end]
for forbidden in [
    'conversationNativeId',
    'sourceSha256',
    'nodeNativeId',
    'createdAtValues',
    'contentJson',
    'tool',
    'truncate',
    'summary',
]:
    assert forbidden not in clean, forbidden
assert 'appendLine(speaker)' in clean
assert 'appendLine(text)' in clean

raw_start = human.index('private fun renderRawMarkdown')
raw_end = human.index('private fun observationsForSource', raw_start)
raw = human[raw_start:raw_end]
assert 'sourceSha256' in raw
assert 'bodies/$sha.body' in raw
assert 'cleanSpeaker(' not in raw
assert 'latestRevision(' not in raw

assert 'recordVerifiedConversationArchive' not in human
assert 'AndroidConversationDnaArchiveService' not in human

backup_tokens = [
    'bke-dna-working-backup',
    '"sqliteIncluded", false',
    'MERGE_SQLITE_ACROSS_DEVICES',
    'Cross-device working backups must not import SQLite',
    'AndroidConversationAggregationEngine',
]
for token in backup_tokens:
    assert token in backup, token
assert 'SQLiteDatabase' not in backup
assert 'ATTACH DATABASE' not in backup.upper()

for token in [
    'SQLite is local **Working Data**',
    '`Latest — Active`',
    '`JAN` user turns',
    '`RIGHT-HAND` assistant turns',
    'RAW conversation export preserves the unfiltered captured conversation payload sources',
    '`.dna` is the self-contained durable archive format',
]:
    assert token in readme, token

assert 'STORAGE_WARNING_BYTES = 1_073_741_824L' in contract
assert 'AUTOMATIC_DNA_EXPORT = false' in contract
assert 'AUTOMATIC_MARKDOWN_EXPORT = false' in contract
assert 'MERGE_SQLITE_ACROSS_DEVICES = false' in contract

source = 'a' * 64
evidence = [
    ('conversation/state.json', 'b' * 64, 123, ''),
    (f'sources/{source}/raw.body', source, 456, source),
    (f'sources/{source}/normalized.json', 'c' * 64, 789, source),
]
identity = '\n'.join(f'{p}\t{s}\t{n}\t{src}' for p, s, n, src in sorted(evidence))
archive_id = 'dna-conversation-v2-' + hashlib.sha256(identity.encode()).hexdigest()
assert archive_id.startswith('dna-conversation-v2-')
assert len(archive_id) == len('dna-conversation-v2-') + 64

print('android Working Data, CLEAN/RAW exports and backups smoke PASS')
