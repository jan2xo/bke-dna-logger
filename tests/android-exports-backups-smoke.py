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
titles = (base / "AndroidConversationTitleCatalog.kt").read_text()
main = (base / "MainActivity.kt").read_text()
human = (base / "AndroidHumanExportService.kt").read_text()
working_data = (base / "AndroidWorkingDataManager.kt").read_text()
paths = (base / "AndroidDnaPaths.kt").read_text()
backup = (base / "AndroidWorkingBackupService.kt").read_text()
contract = (base / "DnaReconciliationContract.kt").read_text()
manifest = (root / "android" / "app" / "src" / "main" / "AndroidManifest.xml").read_text()
readme = (root / "README.md").read_text()

# Durable archive remains the only durability gate.
for token in [
    '"formatVersion", 2',
    '"conversation/state.json"',
    '"SHA256SUMS"',
    '"sources/$sourceSha/raw.body"',
    'AndroidConversationDnaV2Verifier.verify',
    'index.recordVerifiedConversationArchive',
]:
    assert token in archive, token
assert archive.index('resolver.openInputStream(destinationUri)') < archive.index('index.recordVerifiedConversationArchive')

# Working Data remains timestamped/read-only outside Latest and shares raw evidence.
for token in [
    'Latest — Active',
    'read_only_recovery',
    'working.sqlite',
    'PRAGMA wal_checkpoint(TRUNCATE)',
    'SQLiteDatabase.OPEN_READONLY',
    'rawEvidenceSharedBySha',
    'conversationStateIncluded',
    'snapshotDatabase.setReadOnly()',
]:
    assert token in working_data, token
assert 'bodies/' not in working_data
assert 'dna/working-data' in paths

# Shared title catalog derives real owner-facing titles from immutable captured payloads.
for token in [
    'class AndroidConversationTitleCatalog',
    'conversation-title-catalog.json',
    'conversationNativeId',
    'sourceSha256',
    'root.has("title")',
    'candidate_root_title',
    'candidate_title_string',
    'readCapturedTitle',
    'matchingConversationIds',
    'indexedSources',
    'StandardCopyOption.ATOMIC_MOVE',
]:
    assert token in titles, token
# It may consume normalized source identity + raw body, but it must never synthesize
# a title from JAN/user message content.
for forbidden in ['setOf("user")', 'textParts', 'first user', 'first JAN']:
    assert forbidden not in titles, forbidden

# Unified library federates summary rows only, dedups by native conversation identity,
# resolves titles through the shared captured-title catalog, and never exposes UUIDs
# as an owner-facing fallback.
for token in [
    'class AndroidUnifiedConversationLibrary',
    'workingData.listWorkingData()',
    'AndroidConversationTitleCatalog',
    'titleCatalog.refreshFromEvidence()',
    'titleCatalog.matchingConversationIds',
    'SQLiteDatabase.OPEN_READONLY',
    'logical_conversation',
    '.groupBy { it.conversationNativeId }',
    'generationCount',
    'hasLatest',
    'DEFAULT_PAGE_SIZE = 40',
    'MAX_PAGE_SIZE = 10_000',
    'MAX_METADATA_ROWS_PER_GENERATION = 10_000',
    'while (true)',
    'perGenerationLimit * 2',
    'UNTITLED_TITLE = "Untitled conversation"',
    'fun resolve(conversationNativeId: String)',
]:
    assert token in unified, token
for forbidden in [
    'displayTitle ?: conversationNativeId',
    'bestTitle = primary.displayTitle',
    'deriveDisplayTitleFromState',
    'AndroidHumanExportService',
    '.readText(',
]:
    assert forbidden not in unified, forbidden

# Owner library remains lazy; heavy evidence resolution happens only on open/export.
for token in [
    'Working Data & Conversations',
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
]:
    assert token in ui, token
conversation_loop = ui[ui.index('conversations.forEach'):ui.index('private fun prepareHumanExport')]
assert 'human.describe' not in conversation_loop
assert 'conversationWorkingBytes' not in conversation_loop
assert 'AndroidHumanExportService' not in conversation_loop
assert 'summary.displayTitle' in conversation_loop
assert 'summary.generationCount' in conversation_loop
assert 'EXTRA_CONVERSATION_NATIVE_ID' in conversation_loop

# Historical generations cannot mutate Latest .dna durability state.
assert 'if (summary.hasLatest)' in ui
assert 'Historical Working Data cannot mutate Latest .dna durability state' in ui
assert 'pendingWorkingDataId == AndroidWorkingDataManager.LATEST_ID' in ui

# Reader loads full evidence only after the conversation is clicked.
for token in [
    'AndroidUnifiedConversationLibrary(this).resolve(conversationNativeId)',
    'CLEAN · JAN / RIGHT-HAND only · no tools',
    'RAW · unfiltered captured conversation payloads',
    'conversationWorkingBytes',
    'renderCleanMarkdown',
    'renderRawMarkdown',
    'setTextIsSelectable(true)',
]:
    assert token in reader, token

# CLEAN remains exactly JAN / RIGHT-HAND only; RAW remains captured evidence.
for token in [
    'CLEAN_JAN = "JAN"',
    'CLEAN_RIGHT_HAND = "RIGHT-HAND"',
    'setOf("user") -> CLEAN_JAN',
    'setOf("assistant") -> CLEAN_RIGHT_HAND',
    'exportCleanMarkdownToUri',
    'exportRawMarkdownToUri',
    'Unfiltered captured conversation payloads',
    'bodies/$sha.body',
]:
    assert token in human, token
clean_start = human.index('private fun renderCleanMarkdown(state: JSONObject)')
clean_end = human.index('/**\n     * RAW contract', clean_start)
clean = human[clean_start:clean_end]
for forbidden in [
    'conversationNativeId', 'sourceSha256', 'nodeNativeId', 'createdAtValues',
    'contentJson', 'tool', 'truncate', 'summary',
]:
    assert forbidden not in clean, forbidden

# Dark owner UI is the application default; the old light wall must not return.
assert '@android:style/Theme.Material.NoActionBar' in manifest
assert 'Theme.Material.Light.NoActionBar' not in manifest
assert 'AndroidExportsBackupsActivity' in manifest
assert 'AndroidConversationReaderActivity' in manifest

# Existing lifecycle and backup guardrails remain intact.
assert 'geckoHost.stop()' in main
assert 'capturePausedForWorkingData = true' in main
for token in [
    'bke-dna-working-backup',
    '"sqliteIncluded", false',
    'MERGE_SQLITE_ACROSS_DEVICES',
]:
    assert token in backup, token
assert 'ATTACH DATABASE' not in backup.upper()
assert 'STORAGE_WARNING_BYTES = 1_073_741_824L' in contract
assert 'AUTOMATIC_DNA_EXPORT = false' in contract
assert 'AUTOMATIC_MARKDOWN_EXPORT = false' in contract
assert 'MERGE_SQLITE_ACROSS_DEVICES = false' in contract

# SQLite still contains lightweight display_title for current projections, but the
# unified owner-facing title source is the shared captured-title catalog above it.
assert 'display_title' in index
assert 'DATABASE_VERSION = 4' in index

source = 'a' * 64
evidence = [
    ('conversation/state.json', 'b' * 64, 123, ''),
    (f'sources/{source}/raw.body', source, 456, source),
    (f'sources/{source}/normalized.json', 'c' * 64, 789, source),
]
identity = '\n'.join(f'{p}\t{s}\t{n}\t{src}' for p, s, n, src in sorted(evidence))
archive_id = 'dna-conversation-v2-' + hashlib.sha256(identity.encode()).hexdigest()
assert len(archive_id) == len('dna-conversation-v2-') + 64

print('android captured-title unified library, dark UI, Working Data and export smoke PASS')
