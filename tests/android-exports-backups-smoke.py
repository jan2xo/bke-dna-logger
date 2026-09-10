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
raw_access = (base / "AndroidRawSourceAccess.kt").read_text()
raw_store = (base / "AndroidRawEvidenceStore.kt").read_text()
derivative_access = (base / "AndroidDerivativeSourceAccess.kt").read_text()
derivative_store = (base / "AndroidDerivativeStore.kt").read_text()
unified = (base / "AndroidUnifiedConversationLibrary.kt").read_text()
titles = (base / "AndroidConversationTitleCatalog.kt").read_text()
main = (base / "MainActivity.kt").read_text()
runtime = (base / "AndroidCaptureRuntime.kt").read_text()
queue = (base / "AndroidDerivationQueue.kt").read_text()
human = (base / "AndroidHumanExportService.kt").read_text()
working_data = (base / "AndroidWorkingDataManager.kt").read_text()
paths = (base / "AndroidDnaPaths.kt").read_text()
backup = (base / "AndroidWorkingBackupService.kt").read_text()
contract = (base / "DnaReconciliationContract.kt").read_text()
manifest = (root / "android" / "app" / "src" / "main" / "AndroidManifest.xml").read_text()

# .dna remains an explicit portable owner export. SQLite-backed RAW and
# derivatives are materialized only into export-temporary files; permanent loose
# body/classification/normalized evidence is not recreated.
for token in [
    '"formatVersion", 2', '"conversation/state.json"', '"SHA256SUMS"',
    '"sources/$sourceSha/raw.body"', 'AndroidConversationDnaV2Verifier.verify',
    'index.recordVerifiedConversationArchive',
    'materializeRawSource(sourceSha)', 'AndroidRawSourceAccess.writeExactSource(',
    'materializeDerivativeSource(',
    'AndroidDerivativeSourceAccess.readNormalized(',
    'AndroidDerivativeSourceAccess.readClassification(',
    'File(stagingDirectory, ".raw-$sourceSha256-${UUID.randomUUID()}.tmp")',
    '".derivative-$kind-$sourceSha256-${UUID.randomUUID()}.tmp"',
    'temporaryEvidence.forEach(File::delete)',
]:
    assert token in archive, token
assert archive.index('resolver.openInputStream(destinationUri)') < archive.index('index.recordVerifiedConversationArchive')
for forbidden in [
    'File(captureRoot, "bodies/$sourceSha.body")',
    'File(captureRoot, "normalized/$sourceSha.json")',
    'File(captureRoot, "classifications/$sourceSha.json")',
]:
    assert forbidden not in archive, forbidden

# Working Data: Latest is the only writable generation. New/saved/restored
# generations are verified read-only SQLite recovery sources with self-contained
# RAW. Restore is staged before promotion and never merges foreign SQLite into Latest.
for token in [
    'Latest — Active', 'read_only_recovery', 'working.sqlite',
    'PRAGMA wal_checkpoint(TRUNCATE)', 'SQLiteDatabase.OPEN_READONLY',
    '.put("rawEvidenceIncluded", true)', '.put("rawEvidenceSharedBySha", false)',
    'manifest.optBoolean("rawEvidenceIncluded", false)',
    'manifest.optBoolean("rawEvidenceSharedBySha", !rawEvidenceIncluded)',
    'conversationStateIncluded', 'makeGenerationReadOnly(',
    'fun generationForBackup(', 'fun importReadOnlyGeneration(',
    'fun deleteSavedGeneration(', 'DELETE_CONFIRMATION_TEXT = "jan2x"',
    'databaseHasSelfContainedRaw(', 'File(workingDataRoot, ".import-${UUID.randomUUID()}")',
    'stagingDirectory.renameTo(finalDirectory)',
    'Latest Working Data cannot be deleted', 'Only saved Working Data can be deleted',
]:
    assert token in working_data, token
for forbidden in ['ATTACH DATABASE', 'MERGE INTO LATEST']:
    assert forbidden not in working_data.upper(), forbidden
assert 'dna/working-data' in paths

# PR5 derivative tables remain inside SQLite Working Data.
for token in [
    'CREATE TABLE IF NOT EXISTS derivative_classification (',
    'CREATE TABLE IF NOT EXISTS derivative_normalized (',
    'payload_sha256 TEXT NOT NULL', 'byte_length INTEGER NOT NULL',
]:
    assert token in derivative_store, token

# RAW source abstraction prefers selected-generation SQLite and retains legacy
# .body only as a pre-PR4 fallback.
for token in [
    'object AndroidRawSourceAccess', 'AndroidRawEvidenceStore(generation)',
    'File(captureRoot, "bodies/$sourceSha256.body")',
    'AndroidRawBackend.SQLITE', 'AndroidRawBackend.LEGACY_BODY',
    'fun readPage(', 'fun writeExactSource(', 'fun <T> withExactInputStream(',
]:
    assert token in raw_access, token
for token in [
    'CREATE TABLE IF NOT EXISTS raw_source (',
    'CREATE TABLE IF NOT EXISTS raw_source_chunk (',
    'RAW_CHUNK_BYTES = 256 * 1024', 'CODEC = "deflate-raw-chunk-v1"',
]:
    assert token in raw_store, token

# Derivative source access federates all Working Data generations and keeps only
# explicit legacy loose-file fallback for pre-PR5 snapshots.
for token in [
    'object AndroidDerivativeSourceAccess',
    'AndroidWorkingDataManager(appContext).listWorkingData()',
    'AndroidDerivativeStore(generation)',
    'File(captureRoot, "$directoryName/$sourceSha256.json")',
]:
    assert token in derivative_access, token

# Management never destroys/reloads ChatGPT. Storage mutations pause capture and
# derivation without killing GeckoSession.
assert 'startActivity(Intent(this@MainActivity, AndroidExportsBackupsActivity::class.java))' in main
assert 'capturePausedForWorkingData' not in main
assert 'override fun onResume()' not in main
button_start = main.index('text = "Working Data & Exports"')
button = main[button_start:main.index('geckoView = GeckoView(this)', button_start)]
for token in ['textSize = 12f', 'minHeight = 0', 'ViewGroup.LayoutParams.WRAP_CONTENT']:
    assert token in button, token
assert 'ViewGroup.LayoutParams.MATCH_PARENT' not in button
assert 'geckoHost.stop()' not in button
assert 'geckoHost.stop()' in main
for token in ['withStorageMutationPause', 'pauseForStorageMutation', 'resumeAfterStorageMutation', 'awaitBackgroundDerivationIdle']:
    assert token in runtime, token
for token in ['storageMutation = true', 'AndroidCaptureRuntime.withStorageMutationPause(this)']:
    assert token in ui, token

# Shared title catalog derives only actual captured title evidence. Catalog v3
# adds bounded conversation-list metadata recovery so ordinary cards can hydrate
# titles without scanning giant conversation RAW, while the full explicit search
# path still supports normalized/root RAW title evidence and historical recovery.
for token in [
    'class AndroidConversationTitleCatalog', 'conversation-title-catalog.json',
    'candidate_root_title', 'candidate_title_string', 'candidate_bound_title',
    'conversations_list_title_found', 'conversations_list_title_conflict',
    'matchingConversationIds', 'indexedSources', 'indexedMetadataSources',
    'FORMAT_VERSION = 3',
    'MAX_LEGACY_BODY_BYTES = 16L * 1024 * 1024',
    'MAX_CONVERSATION_LIST_BYTES = 8L * 1024 * 1024',
    'fun refreshFromConversationListEvidence()',
    'queryConversationListCaptures(', 'isConversationListRequest(requestUrl)',
    'collectConversationListTitles(root, candidates, depth = 0)',
    'CONVERSATION_ID_KEYS = setOf("id", "conversation_id", "conversationId")',
    'AndroidWorkingDataManager(appContext).listWorkingData()',
    'AndroidDerivativeSourceAccess.listNormalizedSourceSha256s(generation, captureRoot)',
    'AndroidDerivativeSourceAccess.readNormalized(generation, captureRoot, sourceSha256)',
    'AndroidRawSourceAccess.withExactInputStream(appContext, sourceSha256)',
    'readCapturedRootTitle(reader, conversationNativeId)',
    'AndroidRawSourceAccess.readAllBytes(',
]:
    assert token in titles, token
assert 'source.exceptionOrNull() is IllegalArgumentException' not in titles
for forbidden in ['setOf("user")', 'textParts', 'first user', 'first JAN', 'bodiesDirectory', 'normalizedDirectory']:
    assert forbidden not in titles, forbidden

# Unified library remains metadata-only, federated and deduplicated by native identity.
# Blank loads may hydrate bounded conversation-list titles; explicit title search
# performs the full evidence refresh.
for token in [
    'class AndroidUnifiedConversationLibrary', 'workingData.listWorkingData()',
    'AndroidConversationTitleCatalog', 'SQLiteDatabase.OPEN_READONLY', 'logical_conversation',
    '.groupBy { it.conversationNativeId }', 'generationCount', 'hasLatest',
    'DEFAULT_PAGE_SIZE = 40', 'MAX_PAGE_SIZE = 10_000',
    'UNTITLED_TITLE = "Untitled conversation"', 'fun resolve(conversationNativeId: String)',
    'titleCatalog.refreshFromConversationListEvidence()',
    'titleCatalog.refreshFromEvidence()',
]:
    assert token in unified, token
for forbidden in ['AndroidHumanExportService', '.readText(']:
    assert forbidden not in unified, forbidden

# Working Data UI retains the same owner controls, but all storage/library/queue
# reads and export preparation run on a dedicated IO executor. The visible
# conversation section live-refreshes independently so newly reconciled rows and
# titles appear without leaving/re-entering the Activity or rebuilding the full UI.
for token in [
    'Working Data & Conversations', 'Processing', 'All Conversations', 'Search conversation titles',
    'SEARCH', 'CLEAR', 'LOAD MORE',
    'Executors.newSingleThreadExecutor', '"bke-dna-working-data-io"', 'ioExecutor.execute {',
    'private fun loadUiSnapshot(', 'private fun renderUi(snapshot: UiSnapshot)',
    'AndroidUnifiedConversationLibrary(appContext).search(requestedSearchQuery, requestedLibraryLimit)',
    'Handler(Looper.getMainLooper())', 'QUEUE_REFRESH_MS = 1_500L', 'LIBRARY_REFRESH_MS = 3_000L',
    'private val libraryMonitorTick', 'refreshConversationLibrary()',
    'private fun renderConversationList(', 'conversationListContainer', 'renderedConversations',
    'AndroidDerivationScheduler.start(this)', 'AndroidDerivationScheduler.snapshot(appContext)',
    'AndroidDerivationScheduler.getProfile(appContext)', 'AndroidDerivationScheduler.setProfile(this, profile)',
    'AndroidProcessingProfile.entries', 'profileLabel(profile)',
    'compactButton("CLEAN")', 'compactButton("RAW")', 'compactButton("MORE")',
    'PopupMenu(this, anchor)', 'setOnClickListener { openConversation(summary) }',
    'contentDescription = "Read ${summary.displayTitle}"',
    'Tap a conversation row to read it', 'portable .dna export lives under MORE',
]:
    assert token in ui, token
for token in ['SLOW(500L)', 'BALANCED(150L)', 'FAST(25L)', 'fun snapshot(context: Context)']:
    assert token in queue, token
for forbidden in ['actionButton("Read conversation")', 'actionButton("Export CLEAN.md")', 'actionButton("Export RAW.md")']:
    assert forbidden not in ui, forbidden

resume_start = ui.index('override fun onResume()')
resume_end = ui.index('override fun onPause()', resume_start)
resume = ui[resume_start:resume_end]
for token in [
    'queueMonitorHandler.removeCallbacks(libraryMonitorTick)',
    'queueMonitorHandler.post(libraryMonitorTick)',
]:
    assert token in resume, token
pause_start = ui.index('override fun onPause()')
pause_end = ui.index('override fun onDestroy()', pause_start)
pause = ui[pause_start:pause_end]
assert 'queueMonitorHandler.removeCallbacks(libraryMonitorTick)' in pause

load_start = ui.index('private fun loadUiSnapshot(')
load_end = ui.index('private fun renderLoadFailure(', load_start)
load_section = ui[load_start:load_end]
for token in [
    'manager.listWorkingData()', 'AndroidWorkingStorage.workingBytes(appContext)',
    'manager.savedWorkingDataBytes()', 'AndroidWorkingDataTelemetry.inspect(inspected)',
    'AndroidUnifiedConversationLibrary(appContext).search(',
]:
    assert token in load_section, token

live_start = ui.index('private fun refreshConversationLibrary()')
live_end = ui.index('private fun renderConversationList(', live_start)
live_refresh = ui[live_start:live_end]
for token in [
    'if (operationInProgress || libraryRefreshInFlight || ioExecutor.isShutdown) return',
    'ioExecutor.execute {',
    'AndroidUnifiedConversationLibrary(appContext).search(',
    'conversationListContainer !== targetContainer',
    'searchQuery != requestedSearchQuery || libraryLimit != requestedLibraryLimit',
    'conversations == renderedConversations',
    'targetCount.text = conversationSummaryText(conversations.size)',
    'renderConversationList(',
]:
    assert token in live_refresh, token
assert live_refresh.index('ioExecutor.execute {') < live_refresh.index('AndroidUnifiedConversationLibrary(appContext).search(')
assert 'setContentView(' not in live_refresh

render_start = ui.index('private fun renderUi(snapshot: UiSnapshot)')
render_end = ui.index('private fun prepareWorkingDataBackup', render_start)
render_section = ui[render_start:render_end]
for forbidden in [
    'manager.listWorkingData()', 'AndroidWorkingStorage.workingBytes(',
    'savedWorkingDataBytes()', 'AndroidWorkingDataTelemetry.inspect(',
    'AndroidUnifiedConversationLibrary(this).search(',
]:
    assert forbidden not in render_section, forbidden
assert ui.index('ioExecutor.execute {', ui.index('private fun refreshQueueStatus()')) < ui.index('AndroidDerivationScheduler.snapshot(appContext)')
assert ui.index('ioExecutor.execute {', ui.index('private fun prepareHumanExport')) < ui.index('AndroidUnifiedConversationLibrary(applicationContext)')

conversation_loop = ui[ui.index('conversations.forEach'):ui.index('private fun prepareWorkingDataBackup')]
assert 'human.describe' not in conversation_loop
assert 'conversationWorkingBytes' not in conversation_loop
assert 'summary.displayTitle' in conversation_loop
assert 'summary.generationCount' in conversation_loop

# PR8 backup is the actual self-contained SQLite generation, not a loose-evidence
# archive. Written output is re-opened and verified. Restore becomes read-only.
for token in [
    'BACKUP_FORMAT = "bke-dna-working-sqlite-backup"', 'BACKUP_VERSION = 2',
    'generationForBackup(generationId)', '"sqliteIncluded", true',
    '"sqliteMergeAllowed", false', '"restoreMode", "read_only_generation"',
    '"rawEvidenceIncluded", true', '"conversationStateIncluded", true',
    'SQLITE_PATH = "working.sqlite"', 'CHECKSUMS_PATH = "SHA256SUMS"',
    'resolver.openInputStream(destinationUri)', 'verifyBackup(verificationCopy)',
    'manager.importReadOnlyGeneration(', 'extractVerified(',
]:
    assert token in backup, token
for forbidden in [
    '"sqliteIncluded", false', 'EVIDENCE_DIRECTORIES',
    'AndroidConversationAggregationEngine', 'bodies', 'normalized', 'classifications',
    'ATTACH DATABASE',
]:
    assert forbidden not in backup.upper() if forbidden == 'ATTACH DATABASE' else forbidden not in backup, forbidden

# PR8 deletion is whole-generation, owner-directed, and independent from .dna.
assert not (base / "AndroidPurgeService.kt").exists()
for token in [
    'dangerButton("DELETE")', 'confirmDeleteGeneration(',
    'deleteSavedGeneration(', 'AndroidWorkingDataManager.DELETE_CONFIRMATION_TEXT',
    'Type exact confirmation: jan2x',
    'This does not touch Latest and does not require a .dna export.',
    'Working Data restored as read-only generation',
    'Working Data SQLite backup exported and verified',
]:
    assert token in ui, token
for forbidden in [
    'PURGE ALL VERIFIED RAW', 'PURGE VERIFIED RAW', 'AndroidPurgeService',
    'LOCAL RAW PURGED', 'Verified .dna is the durability gate',
]:
    assert forbidden not in ui, forbidden
assert '.dna' not in working_data[working_data.index('fun deleteSavedGeneration'):working_data.index('fun listConversations')]

# .dna export remains Latest-only because it mutates Latest durability metadata.
assert 'Historical Working Data cannot mutate Latest .dna durability state' in ui
assert 'pendingWorkingDataId == AndroidWorkingDataManager.LATEST_ID' in ui

# Reader stays lazy/paged.
for token in [
    'Executors.newSingleThreadExecutor', '"bke-dna-reader-io"',
    'AndroidUnifiedConversationLibrary(this)', 'AndroidCleanConversationPager(',
    'AndroidRawConversationPager(', 'compactButton("CLEAN")', 'compactButton("RAW")',
    'compactButton("PREV")', 'compactButton("NEXT")',
    'CLEAN · JAN / RIGHT-HAND only · no tools',
    'RAW · full captured evidence in context · bounded stream',
    'setTextIsSelectable(true)', 'loadCleanPage(', 'loadRawPage(',
]:
    assert token in reader, token
for forbidden in [
    'human.renderCleanMarkdown', 'human.renderRawMarkdown',
    'AndroidHumanExportService(this', 'conversationWorkingBytes(',
]:
    assert forbidden not in reader, forbidden
assert reader.index('ioExecutor.execute {') < reader.index('AndroidUnifiedConversationLibrary(this)')

for token in [
    'class AndroidCleanConversationPager', 'class AndroidRawConversationPager',
    'SQLiteDatabase.OPEN_READONLY', 'logical_message_node', 'logical_message_revision',
    'conversation_source', 'const val DEFAULT_PAGE_SIZE = 40',
    'const val RAW_PAGE_BYTES = 64 * 1024', 'AndroidRawSourceAccess.readPage(',
    'safeUtf8PrefixLength',
    'setOf("user") -> AndroidHumanExportService.CLEAN_JAN',
    'setOf("assistant") -> AndroidHumanExportService.CLEAN_RIGHT_HAND',
]:
    assert token in pager, token
for forbidden in ['RandomAccessFile(', '.readText(Charsets.UTF_8)', 'buildString {\n        appendLine("#']:
    assert forbidden not in pager, forbidden

for token in [
    'CLEAN_JAN = "JAN"', 'CLEAN_RIGHT_HAND = "RIGHT-HAND"',
    'setOf("user") -> CLEAN_JAN', 'setOf("assistant") -> CLEAN_RIGHT_HAND',
    'exportCleanMarkdownToUri', 'exportRawMarkdownToUri',
    'AndroidRawSourceAccess.writeExactSource(', 'renderRawToStream(',
    'AndroidWorkingDataManager(appContext).listWorkingData()',
]:
    assert token in human, token
clean_start = human.index('private fun renderCleanMarkdown(state: JSONObject)')
clean_end = human.index('private fun renderRawToStream(', clean_start)
clean = human[clean_start:clean_end]
for forbidden in ['conversationNativeId', 'sourceSha256', 'nodeNativeId', 'contentJson', 'tool', 'truncate']:
    assert forbidden not in clean, forbidden

assert '@android:style/Theme.Material.NoActionBar' in manifest
assert 'Theme.Material.Light.NoActionBar' not in manifest
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

print('android SQLite Working Data backup + background UI + title recovery + visible live refresh smoke PASS')
