#!/usr/bin/env python3
from pathlib import Path

root = Path(__file__).resolve().parents[1]
base = root / "android/app/src/main/kotlin/com/bke/dna/logger"

scheduler = (base / "AndroidDerivationQueue.kt").read_text(encoding="utf-8")
service = (base / "AndroidDnaProcessingService.kt").read_text(encoding="utf-8")
main = (base / "MainActivity.kt").read_text(encoding="utf-8")
raw_access = (base / "AndroidRawSourceAccess.kt").read_text(encoding="utf-8")
raw_store = (base / "AndroidRawEvidenceStore.kt").read_text(encoding="utf-8")
normalized_store = (base / "AndroidChunkedNormalizedStore.kt").read_text(encoding="utf-8")
unified = (base / "AndroidUnifiedConversationLibrary.kt").read_text(encoding="utf-8")
titles = (base / "AndroidConversationTitleCatalog.kt").read_text(encoding="utf-8")
manifest = (root / "android/app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")

# DNA derivation is explicitly background-priority and waits for a meaningful
# quiet window after browser/capture activity before taking heavy work.
for token in (
    'import android.os.Process',
    'import android.os.SystemClock',
    'Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)',
    'BROWSER_QUIET_MS = 3_000L',
    'BROWSER_QUIET_POLL_MS = 100L',
    'lastBrowserActivityAt = AtomicLong(SystemClock.elapsedRealtime())',
    'fun noteBrowserActivity()',
    'fun yieldForBrowserActivity()',
    'private fun waitForBrowserQuiet()',
):
    assert token in scheduler, token

capture_started = scheduler[scheduler.index('fun captureStarted()'):scheduler.index('fun captureFinished()')]
capture_finished = scheduler[scheduler.index('fun captureFinished()'):scheduler.index('fun getProfile(')]
assert 'noteBrowserActivity()' in capture_started
assert 'noteBrowserActivity()' in capture_finished

breathe = scheduler[scheduler.index('private fun breathe('):scheduler.index('const val STAGE_RAW_INGEST')]
assert breathe.count('waitForBrowserQuiet()') >= 2
assert breathe.index('Thread.sleep(restMillis)') < breathe.rindex('waitForBrowserQuiet()')

# Normal browser interaction refreshes the quiet deadline without rebuilding or
# destroying GeckoView. Focus regain covers return from the sibling management Activity.
for token in (
    'override fun onUserInteraction()',
    'override fun onWindowFocusChanged(hasFocus: Boolean)',
    'AndroidDerivationScheduler.noteBrowserActivity()',
):
    assert token in main, token
assert 'override fun onResume()' not in main
assert 'geckoHost.stop()' in main

# Unfinished queue work owns a single bounded foreground-service lifetime so
# HyperOS/Android may keep processing after screen-off. The service never creates
# another queue executor; it starts the existing scheduler through a recursion-safe entry.
for token in (
    'AndroidDnaProcessingService.ensureRunning(appContext)',
    'internal fun startFromProcessingService(context: Context)',
    'AndroidDnaProcessingService.beginActiveWork(context)',
    'AndroidDnaProcessingService.endActiveWork()',
    'AndroidDnaProcessingService.stopWhenIdle(appContext)',
):
    assert token in scheduler, token
assert scheduler.count('Executors.newSingleThreadExecutor') == 1

for token in (
    'class AndroidDnaProcessingService : Service()',
    'startForeground(NOTIFICATION_ID, buildNotification())',
    'AndroidDerivationScheduler.startFromProcessingService(applicationContext)',
    'return START_STICKY',
    'NotificationManager.IMPORTANCE_LOW',
    'PowerManager.PARTIAL_WAKE_LOCK',
    'WAKE_LOCK_TIMEOUT_MS = 30 * 60 * 1_000L',
    'acquire(WAKE_LOCK_TIMEOUT_MS)',
    'wakeLock.release()',
    'fun stopWhenIdle(context: Context)',
):
    assert token in service, token
assert 'Executors.new' not in service

# Wake lock is acquired only after a queue job is claimed, then released in finally.
drain = scheduler[scheduler.index('private fun drain('):scheduler.index('private fun waitForBrowserQuiet()')]
claim_at = drain.index('claimNext()')
wake_at = drain.index('AndroidDnaProcessingService.beginActiveWork(context)')
finally_at = drain.index('finally {')
release_at = drain.index('AndroidDnaProcessingService.endActiveWork()')
assert claim_at < wake_at < finally_at < release_at

# Manifest explicitly declares the FGS/wake permissions and a non-exported special-use
# service. This is local processing, not a fake network data-sync classification.
for token in (
    'android.permission.WAKE_LOCK',
    'android.permission.FOREGROUND_SERVICE',
    'android.permission.FOREGROUND_SERVICE_SPECIAL_USE',
    'android:name=".AndroidDnaProcessingService"',
    'android:exported="false"',
    'android:foregroundServiceType="specialUse"',
    'android:stopWithTask="false"',
    'android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE',
    'Local processing of queued, durable conversation DNA while the screen is off',
):
    assert token in manifest, token
assert 'foregroundServiceType="dataSync"' not in manifest

# Large RAW streaming reads cooperate every bounded window so an already-running
# parse/title probe can pause when the owner starts using the browser again.
for token in (
    'BrowserYieldingInputStream',
    'YIELD_READ_BYTES = 256 * 1024',
    'AndroidDerivationScheduler.yieldForBrowserActivity()',
):
    assert token in raw_access, token

# RAW ingest no longer owns one transaction for the full source. Chunk writes
# autocommit and yield; exact unpublished bytes are verified before the tiny
# raw_source metadata publication transaction makes them visible.
import_start = raw_store.index('fun importVerified(')
import_end = raw_store.index('fun readPage(', import_start)
raw_import = raw_store[import_start:import_end]
for token in (
    'deleteUnpublishedChunks(sourceSha256)',
    'AndroidDerivationScheduler.yieldForBrowserActivity()',
    'database.insertOrThrow("raw_source_chunk", null, values)',
    'verifyUnpublishedSource(',
    'database.beginTransaction()',
    'database.insertOrThrow("raw_source", null, sourceValues)',
    'database.setTransactionSuccessful()',
):
    assert token in raw_import, token
verify_at = raw_import.index('verifyUnpublishedSource(')
publish_begin = raw_import.index('database.beginTransaction()')
assert verify_at < publish_begin
assert raw_import[:verify_at].find('database.beginTransaction()') == -1

cleanup_start = raw_store.index('private fun deleteUnpublishedChunks(')
cleanup_end = raw_store.index('private fun verifyUnpublishedSource(', cleanup_start)
cleanup = raw_store[cleanup_start:cleanup_end]
assert 'descriptor(sourceSha256) == null' in cleanup
assert 'database.delete(' in cleanup
assert 'raw_source_chunk' in cleanup
assert 'raw_source"' not in cleanup

# Large normalized output has the same cooperative property between each 64 KiB
# autocommit while retaining the publish-last integrity contract.
flush_start = normalized_store.index('private fun flushChunk()')
flush_end = normalized_store.index('private class SQLiteChunkInputStream', flush_start)
flush = normalized_store[flush_start:flush_end]
assert 'AndroidDerivationScheduler.yieldForBrowserActivity()' in flush
assert flush.index('AndroidDerivationScheduler.yieldForBrowserActivity()') < flush.index('database.insertOrThrow(TABLE_CHUNK, null, values)')
assert 'database.beginTransaction()' not in flush

# Ordinary Working Data loads may hydrate only bounded conversation-list metadata
# titles. They must not invoke the full normalized/RAW title archaeology path.
search_start = unified.index('fun search(')
search_end = unified.index('fun resolve(', search_start)
search = unified[search_start:search_end]
for token in (
    'val normalizedQuery = query.trim()',
    'if (normalizedQuery.isBlank())',
    'titleCatalog.refreshFromConversationListEvidence()',
    'titleCatalog.refreshFromEvidence()',
):
    assert token in search, token
assert search.index('if (normalizedQuery.isBlank())') < search.index('titleCatalog.refreshFromConversationListEvidence()')
assert search.index('titleCatalog.refreshFromConversationListEvidence()') < search.index('titleCatalog.refreshFromEvidence()')

light_start = titles.index('fun refreshFromConversationListEvidence()')
light_end = titles.index('/** Full evidence refresh', light_start)
light = titles[light_start:light_end]
assert 'refreshConversationListEvidence(state, generations)' in light
assert 'AndroidDerivativeSourceAccess.listNormalizedSourceSha256s' not in light
for token in (
    'MAX_CONVERSATION_LIST_BYTES = 8L * 1024 * 1024',
    'isConversationListRequest(requestUrl)',
    'collectConversationListTitles(root, candidates, depth = 0)',
):
    assert token in titles, token

resolve_start = unified.index('fun resolve(')
resolve_end = unified.index('private fun mergeCopies(', resolve_start)
resolve = unified[resolve_start:resolve_end]
assert 'titleCatalog.refreshFromEvidence()' not in resolve

print('android browser-first background processing + lightweight title hydration smoke PASS')
