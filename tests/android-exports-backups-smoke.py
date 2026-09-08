#!/usr/bin/env python3
from pathlib import Path
import hashlib

root = Path(__file__).resolve().parents[1]
base = root / "android" / "app" / "src" / "main" / "kotlin" / "com" / "bke" / "dna" / "logger"
archive = (base / "AndroidConversationDnaArchiveService.kt").read_text()
index = (base / "AndroidCaptureIndex.kt").read_text()
ui = (base / "AndroidExportsBackupsActivity.kt").read_text()
reader = (base / "AndroidConversationReaderActivity.kt").read_text()
pager = (base / "AndroidConversationReadPager.kt").read_text()
unified = (base / "AndroidUnifiedConversationLibrary.kt").read_text()
titles = (base / "AndroidConversationTitleCatalog.kt").read_text()
main = (base / "MainActivity.kt").read_text()
runtime = (base / "AndroidCaptureRuntime.kt").read_text()
purge = (base / "AndroidPurgeService.kt").read_text()
human = (base / "AndroidHumanExportService.kt").read_text()
working_data = (base / "AndroidWorkingDataManager.kt").read_text()
paths = (base / "AndroidDnaPaths.kt").read_text()
backup = (base / "AndroidWorkingBackupService.kt").read_text()
contract = (base / "DnaReconciliationContract.kt").read_text()
manifest = (root / "android" / "app" / "src" / "main" / "AndroidManifest.xml").read_text()

# Durable archive remains the only destructive-cleanup durability gate in the
# transitional alpha implementation. The README target architecture supersedes
# this later, but PR3 does not change archive/purge semantics.
for token in [
    '"formatVersion", 2', '"conversation/state.json"', '"SHA256SUMS"',
    '"sources/$sourceSha/raw.body"', 'AndroidConversationDnaV2Verifier.verify',
    'index.recordVerifiedConversationArchive',
]:
    assert token in archive, token
assert archive.index('resolver.openInputStream(destinationUri)') < archive.index('index.recordVerifiedConversationArchive')

# Working Data remains timestamped/read-only outside Latest and shares raw evidence.
for token in [
    'Latest — Active', 'read_only_recovery', 'working.sqlite',
    'PRAGMA wal_checkpoint(TRUNCATE)', 'SQLiteDatabase.OPEN_READONLY',
    'rawEvidenceSharedBySha', 'conversationStateIncluded', 'snapshotDatabase.setReadOnly()',
]:
    assert token in working_data, token
assert 'bodies/' not in working_data
assert 'dna/working-data' in paths

# Management no longer destroys/reloads ChatGPT. Storage mutation pauses capture only.
assert 'startActivity(Intent(this@MainActivity, AndroidExportsBackupsActivity::class.java))' in main
assert 'capturePausedForWorkingData' not in main
assert 'override fun onResume()' not in main
button = main[main.index('text = "Working Data & Exports"'):main.index('root.addView', main.index('text = "Working Data & Exports"'))]
assert 'geckoHost.stop()' not in button
assert 'geckoHost.stop()' in main
for token in ['withStorageMutationPause', 'pauseForStorageMutation', 'resumeAfterStorageMutation', 'awaitBackgroundDerivationIdle']:
    assert token in runtime, token
for token in ['storageMutation = true', 'AndroidCaptureRuntime.withStorageMutationPause(this)']:
    assert token in ui, token

# Shared title catalog derives actual captured root titles; never JAN text / UUID fallback.
for token in [
    'class AndroidConversationTitleCatalog', 'conversation-title-catalog.json',
    'root.has("title")', 'candidate_root_title', 'candidate_title_string',
    'matchingConversationIds', 'indexedSources',
]:
    assert token in titles, token
for forbidden in ['setOf("user")', 'textParts', 'first user', 'first JAN']:
    assert forbidden not in titles, forbidden

# Unified library remains metadata-only, federated and deduplicated by native identity.
for token in [
    'class AndroidUnifiedConversationLibrary', 'workingData.listWorkingData()',
    'AndroidConversationTitleCatalog', 'SQLiteDatabase.OPEN_READONLY', 'logical_conversation',
    '.groupBy { it.conversationNativeId }', 'generationCount', 'hasLatest',
    'DEFAULT_PAGE_SIZE = 40', 'MAX_PAGE_SIZE = 10_000',
    'UNTITLED_TITLE = "Untitled conversation"', 'fun resolve(conversationNativeId: String)',
]:
    assert token in unified, token
for forbidden in ['AndroidHumanExportService', '.readText(']:
    assert forbidden not in unified, forbidden

# Owner library remains lazy and searchable.
for token in [
    'Working Data & Conversations', 'All Conversations', 'Search conversation titles',
    'SEARCH', 'CLEAR', 'LOAD MORE', 'library.search(searchQuery, libraryLimit)',
    'Read conversation', 'Export .dna', 'Export CLEAN.md', 'Export RAW.md',
]:
    assert token in ui, token
conversation_loop = ui[ui.index('conversations.forEach'):ui.index('private fun preparePurge')]
assert 'human.describe' not in conversation_loop
assert 'conversationWorkingBytes' not in conversation_loop
assert 'summary.displayTitle' in conversation_loop
assert 'summary.generationCount' in conversation_loop

# Guardrailed purge remains unchanged in this PR.
for token in [
    'CONFIRMATION_TEXT = "jan2x"',
    'conversationArchived', 'sources.any { !it.archived }', 'archiveId', 'archiveSha256',
    'conversation_source cs', 'lc.conversation_native_id <> ?',
    'Unable to prove source exclusivity across every Working Data generation',
    'bodies/$sourceSha256.body', 'normalized/$sourceSha256.json',
    'classifications/$sourceSha256.json', 'observations',
    'logicalStateRetained', 'sqliteRetained', 'bytesReclaimed',
]:
    assert token in purge, token
for token in [
    'PURGE ALL VERIFIED RAW', 'PURGE VERIFIED RAW', 'Type exact confirmation: jan2x',
    'setBackgroundColor(Color.rgb(183, 28, 28))', 'setTextColor(Color.WHITE)',
]:
    assert token in ui, token
assert 'deleteDatabase' not in purge
assert 'conversations/' not in purge

# Historical generations cannot mutate Latest .dna durability state.
assert 'Historical Working Data cannot mutate Latest .dna durability state' in ui
assert 'pendingWorkingDataId == AndroidWorkingDataManager.LATEST_ID' in ui

# Interactive reader is now strictly bounded. It resolves/open pagers on its
# dedicated IO executor and never calls the giant full-conversation renderers.
for token in [
    'Executors.newSingleThreadExecutor',
    '"bke-dna-reader-io"',
    'AndroidUnifiedConversationLibrary(this)',
    'AndroidCleanConversationPager(',
    'AndroidRawConversationPager(',
    'compactButton("CLEAN")',
    'compactButton("RAW")',
    'compactButton("PREV")',
    'compactButton("NEXT")',
    'CLEAN · JAN / RIGHT-HAND only · no tools',
    'RAW · full captured evidence in context · bounded stream',
    'setTextIsSelectable(true)',
    'loadCleanPage(',
    'loadRawPage(',
]:
    assert token in reader, token
for forbidden in [
    'human.renderCleanMarkdown',
    'human.renderRawMarkdown',
    'AndroidHumanExportService(this',
    'conversationWorkingBytes(',
]:
    assert forbidden not in reader, forbidden
assert reader.index('ioExecutor.execute {') < reader.index('AndroidUnifiedConversationLibrary(this)')

# CLEAN paging comes from SQLite normalized rows, 40 turns at a time. RAW paging
# reads only a 64 KiB exact byte window at a time from the transitional body backend.
for token in [
    'class AndroidCleanConversationPager',
    'class AndroidRawConversationPager',
    'SQLiteDatabase.OPEN_READONLY',
    'logical_message_node',
    'logical_message_revision',
    'conversation_source',
    'const val DEFAULT_PAGE_SIZE = 40',
    'const val RAW_PAGE_BYTES = 64 * 1024',
    'RandomAccessFile(body, "r")',
    'file.seek(cursor.byteOffset)',
    'safeUtf8PrefixLength',
    'setOf("user") -> AndroidHumanExportService.CLEAN_JAN',
    'setOf("assistant") -> AndroidHumanExportService.CLEAN_RIGHT_HAND',
]:
    assert token in pager, token
for forbidden in [
    '.readText(Charsets.UTF_8)',
    'buildString {\n        appendLine("#',
]:
    assert forbidden not in pager, forbidden

# Export helpers preserve existing CLEAN/RAW semantics; they are no longer used
# by the interactive reader but remain explicit owner export routes.
for token in [
    'CLEAN_JAN = "JAN"', 'CLEAN_RIGHT_HAND = "RIGHT-HAND"',
    'setOf("user") -> CLEAN_JAN', 'setOf("assistant") -> CLEAN_RIGHT_HAND',
    'exportCleanMarkdownToUri', 'exportRawMarkdownToUri', 'bodies/$sha.body',
]:
    assert token in human, token
clean_start = human.index('private fun renderCleanMarkdown(state: JSONObject)')
clean_end = human.index('/**\n     * RAW contract', clean_start)
clean = human[clean_start:clean_end]
for forbidden in ['conversationNativeId', 'sourceSha256', 'nodeNativeId', 'contentJson', 'tool', 'truncate']:
    assert forbidden not in clean, forbidden

# Dark UI and backup/cross-device SQLite guardrails remain.
assert '@android:style/Theme.Material.NoActionBar' in manifest
assert 'Theme.Material.Light.NoActionBar' not in manifest
for token in ['bke-dna-working-backup', '"sqliteIncluded", false', 'MERGE_SQLITE_ACROSS_DEVICES']:
    assert token in backup, token
assert 'ATTACH DATABASE' not in backup.upper()
assert 'STORAGE_WARNING_BYTES = 1_073_741_824L' in contract
assert 'AUTOMATIC_DNA_EXPORT = false' in contract
assert 'AUTOMATIC_MARKDOWN_EXPORT = false' in contract
assert 'MERGE_SQLITE_ACROSS_DEVICES = false' in contract
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

print('android bounded CLEAN/RAW reader, Working Data, purge and export smoke PASS')
