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
purge = (base / "AndroidPurgeService.kt").read_text()
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

# Working Data remains timestamped/read-only outside Latest. New saved SQLite
# generations contain exact RAW and PR5 derivative tables automatically because
# the complete active database is checkpointed and copied.
for token in [
    'Latest — Active', 'read_only_recovery', 'working.sqlite',
    'PRAGMA wal_checkpoint(TRUNCATE)', 'SQLiteDatabase.OPEN_READONLY',
    '.put("rawEvidenceIncluded", true)', '.put("rawEvidenceSharedBySha", false)',
    'manifest.optBoolean("rawEvidenceIncluded", false)',
    'manifest.optBoolean("rawEvidenceSharedBySha", !rawEvidenceIncluded)',
    'conversationStateIncluded', 'snapshotDatabase.setReadOnly()',
]:
    assert token in working_data, token
assert 'dna/working-data' in paths
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
    'fun readPage(', 'fun writeExactSource(',
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

# Management no longer destroys/reloads ChatGPT. Storage mutation pauses capture only.
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

# Shared title catalog derives actual captured root titles through federated
# normalized derivatives + Working Data RAW generations; never JAN text / UUID fallback.
for token in [
    'class AndroidConversationTitleCatalog', 'conversation-title-catalog.json',
    'root.has("title")', 'candidate_root_title', 'candidate_title_string',
    'matchingConversationIds', 'indexedSources',
    'AndroidWorkingDataManager(appContext).listWorkingData()',
    'AndroidDerivativeSourceAccess.listNormalizedSourceSha256s(generation, captureRoot)',
    'AndroidDerivativeSourceAccess.readNormalized(generation, captureRoot, sourceSha256)',
    'AndroidRawSourceAccess.readAllBytes(',
]:
    assert token in titles, token
for forbidden in ['setOf("user")', 'textParts', 'first user', 'first JAN', 'bodiesDirectory', 'normalizedDirectory']:
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

# PR6 keeps Working Data + conversation management compact and exposes the
# existing durable queue/profile controls without creating another scheduler.
for token in [
    'Working Data & Conversations', 'Processing', 'All Conversations', 'Search conversation titles',
    'SEARCH', 'CLEAR', 'LOAD MORE', 'library.search(searchQuery, libraryLimit)',
    'Handler(Looper.getMainLooper())', 'QUEUE_REFRESH_MS = 1_500L',
    'AndroidDerivationScheduler.start(this)', 'AndroidDerivationScheduler.snapshot(this)',
    'AndroidDerivationScheduler.getProfile(this)', 'AndroidDerivationScheduler.setProfile(this, profile)',
    'AndroidProcessingProfile.entries', 'profileLabel(profile)',
    'compactButton("CLEAN")', 'compactButton("RAW")', 'compactButton("MORE")',
    'PopupMenu(this, anchor)', 'setOnClickListener { openConversation(summary) }',
    'contentDescription = "Read ${summary.displayTitle}"',
    'Tap a conversation row to read it', 'archival/purge actions live under MORE',
]:
    assert token in ui, token
for token in ['SLOW(500L)', 'BALANCED(150L)', 'FAST(25L)', 'fun snapshot(context: Context)']:
    assert token in queue, token
for forbidden in ['actionButton("Read conversation")', 'actionButton("Export CLEAN.md")', 'actionButton("Export RAW.md")']:
    assert forbidden not in ui, forbidden
conversation_loop = ui[ui.index('conversations.forEach'):ui.index('private fun refreshQueueStatus')]
assert 'human.describe' not in conversation_loop
assert 'conversationWorkingBytes' not in conversation_loop
assert 'summary.displayTitle' in conversation_loop
assert 'summary.generationCount' in conversation_loop

# Transitional purge may still clean legacy loose derivative files but must not
# delete Working Data SQLite or PR5 derivative rows.
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
assert 'derivative_classification' not in purge
assert 'derivative_normalized' not in purge

assert 'Historical Working Data cannot mutate Latest .dna durability state' in ui
assert 'pendingWorkingDataId == AndroidWorkingDataManager.LATEST_ID' in ui

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

print('android compact queue UI + SQLite RAW/derivative Working Data guardrails smoke PASS')
