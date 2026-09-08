#!/usr/bin/env python3
from pathlib import Path
import hashlib

root = Path(__file__).resolve().parents[1]
base = root / "android" / "app" / "src" / "main" / "kotlin" / "com" / "bke" / "dna" / "logger"
archive = (base / "AndroidConversationDnaArchiveService.kt").read_text()
index = (base / "AndroidCaptureIndex.kt").read_text()
ui = (base / "AndroidExportsBackupsActivity.kt").read_text()
reader = (base / "AndroidConversationReaderActivity.kt").read_text()
unified = (base / "AndroidUnifiedConversationLibrary.kt").read_text()
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
    'AndroidConversationDnaV2Verifier.verify',
    'index.recordVerifiedConversationArchive',
]
for token in archive_tokens:
    assert token in archive, token
assert archive.index('resolver.openInputStream(destinationUri)') < archive.index('index.recordVerifiedConversationArchive')

for token in [
    'DATABASE_VERSION = 4',
    'display_title',
    'upgradeLibrarySchemaV4',
    'conversation.displayTitle',
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
    'savedWorkingDataBytes',
    'readGeneration(it, verify = false)',
    'readGeneration(generationDirectory, verify = true)',
    'snapshotDatabase.setReadOnly()',
    'if (!latestRetired) generationDirectory.deleteRecursively()',
]
for token in working_data_tokens:
    assert token in working_data, token
assert 'bodies/' not in working_data
assert 'dna/working-data' in paths

# Unified library federates read-only summary queries; it must never open full
# conversation state or raw bodies while rendering/searching the list.
for token in [
    'class AndroidUnifiedConversationLibrary',
    'workingData.listWorkingData()',
    'SQLiteDatabase.OPEN_READONLY',
    'logical_conversation',
    'display_title',
    '.groupBy { it.conversationNativeId }',
    'generationCount',
    'hasLatest',
    'DEFAULT_PAGE_SIZE = 40',
    'MAX_PAGE_SIZE = 400',
    'fun resolve(conversationNativeId: String)',
]:
    assert token in unified, token
for forbidden in ['bodies/', 'normalized/', 'conversations/', '.readText(', 'AndroidHumanExportService']:
    assert forbidden not in unified, forbidden

ui_tokens = [
    'Working Data & Conversations',
    'Spinner',
    'This selector only inspects Working Data; it does NOT filter the conversation library below.',
    'Save & Start New Working Data',
    'All Conversations',
    'Search conversation titles',
    'SEARCH',
    'CLEAR',
    'LOAD MORE',
    'AndroidUnifiedConversationLibrary',
    'library.search(searchQuery, libraryLimit)',
    'Read conversation',
    'Export .dna',
    'Export CLEAN.md',
    'Export RAW.md',
    'Backup Working Data',
    'Import .dna / backup',
    'operationInProgress',
]
for token in ui_tokens:
    assert token in ui, token

# Critical lazy-loading contract: cards use SQLite summary metadata only.
conversation_loop = ui[ui.index('conversations.forEach'):ui.index('private fun prepareHumanExport')]
assert 'human.describe' not in conversation_loop
assert 'conversationWorkingBytes' not in conversation_loop
assert 'AndroidHumanExportService' not in conversation_loop
assert 'manager.listConversations(' not in ui
assert 'summary.displayTitle' in conversation_loop
assert 'summary.generationCount' in conversation_loop
assert 'EXTRA_CONVERSATION_NATIVE_ID' in conversation_loop

# Historical generations remain immutable. .dna durability mutation stays tied
# to a conversation that exists in Latest; CLEAN/RAW can resolve historical data.
assert 'if (summary.hasLatest)' in ui
assert 'Historical Working Data cannot mutate Latest .dna durability state' in ui
assert 'pendingWorkingDataId == AndroidWorkingDataManager.LATEST_ID' in ui
assert 'AndroidUnifiedConversationLibrary(this).resolve' in ui

reader_tokens = [
    'AndroidUnifiedConversationLibrary(this).resolve(conversationNativeId)',
    'AndroidWorkingDataManager(this).generation(location.generation.id)',
    'CLEAN · JAN / RIGHT-HAND only · no tools',
    'RAW · unfiltered captured conversation payloads',
    'Seen in ${location.allGenerationIds.size} Working Data generation(s)',
    'conversationWorkingBytes',
    'renderCleanMarkdown',
    'renderRawMarkdown',
    'setTextIsSelectable(true)',
    'EXTRA_CONVERSATION_NATIVE_ID',
]
for token in reader_tokens:
    assert token in reader, token

# Heavy human evidence resolution belongs behind the click/export trigger.
assert 'human.describe(location.conversationKey)' in reader
assert 'conversationWorkingBytes(location.conversationKey)' in reader
assert 'AndroidHumanExportService' in ui[ui.index('private fun prepareHumanExport'):]

assert 'Working Data & Exports' in main or 'Working Data & Conversations' in main
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
]
for token in human_tokens:
    assert token in human, token

clean_start = human.index('private fun renderCleanMarkdown(state: JSONObject)')
clean_end = human.index('/**\n     * RAW contract', clean_start)
clean = human[clean_start:clean_end]
for forbidden in [
    'conversationNativeId', 'sourceSha256', 'nodeNativeId', 'createdAtValues',
    'contentJson', 'tool', 'truncate', 'summary',
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

assert 'recordVerifiedConversationArchive' not in human
assert 'AndroidConversationDnaArchiveService' not in human

for token in [
    'bke-dna-working-backup',
    '"sqliteIncluded", false',
    'MERGE_SQLITE_ACROSS_DEVICES',
    'Cross-device working backups must not import SQLite',
]:
    assert token in backup, token
assert 'ATTACH DATABASE' not in backup.upper()

for token in [
    'SQLite is local **Working Data**',
    '`Latest — Active`',
    '`JAN` user turns',
    '`RIGHT-HAND` assistant turns',
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

print('android unified lazy conversation library and Working Data smoke PASS')
