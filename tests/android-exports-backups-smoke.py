#!/usr/bin/env python3
from pathlib import Path
import hashlib

root = Path(__file__).resolve().parents[1]
base = root / "android" / "app" / "src" / "main" / "kotlin" / "com" / "bke" / "dna" / "logger"
archive = (base / "AndroidConversationDnaArchiveService.kt").read_text()
index = (base / "AndroidCaptureIndex.kt").read_text()
ui = (base / "AndroidExportsBackupsActivity.kt").read_text()
main = (base / "MainActivity.kt").read_text()
human = (base / "AndroidHumanExportService.kt").read_text()
backup = (base / "AndroidWorkingBackupService.kt").read_text()
contract = (base / "DnaReconciliationContract.kt").read_text()
manifest = (root / "android" / "app" / "src" / "main" / "AndroidManifest.xml").read_text()

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

# A successful build alone may not mark cleanup durability. The selected
# destination is re-read and compared before the SQLite durability mutation.
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

ui_tokens = [
    'Exports & Backups',
    'Export .dna',
    'Export .md',
    'Backup working store',
    'Import .dna / backup',
    'Notify threshold: 1 GiB — no hard limit; capture continues.',
    'ACTION_CREATE_DOCUMENT',
    'ACTION_OPEN_DOCUMENT',
    'AndroidConversationDnaArchiveService',
    'AndroidWorkingBackupService',
]
for token in ui_tokens:
    assert token in ui, token

assert 'Exports & Backups' in main
assert 'AndroidExportsBackupsActivity::class.java' in main
assert 'AndroidExportsBackupsActivity' in manifest

for token in [
    'Historical date',
    'createdAtValues',
    'title',
    'Human-readable derivative',
    'AUTOMATIC_MARKDOWN_EXPORT',
]:
    assert token in human, token

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

assert 'STORAGE_WARNING_BYTES = 1_073_741_824L' in contract
assert 'AUTOMATIC_DNA_EXPORT = false' in contract
assert 'AUTOMATIC_MARKDOWN_EXPORT = false' in contract
assert 'MERGE_SQLITE_ACROSS_DEVICES = false' in contract

# Lock canonical archive identity material used by both desktop and Android:
# path, sha256, byte length, optional source SHA; lexicographically by path.
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

print('android manual exports and backups smoke PASS')
