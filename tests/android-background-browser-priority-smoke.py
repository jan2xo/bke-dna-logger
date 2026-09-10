#!/usr/bin/env python3
from pathlib import Path

root = Path(__file__).resolve().parents[1]
base = root / "android/app/src/main/kotlin/com/bke/dna/logger"

scheduler = (base / "AndroidDerivationQueue.kt").read_text(encoding="utf-8")
main = (base / "MainActivity.kt").read_text(encoding="utf-8")
raw_access = (base / "AndroidRawSourceAccess.kt").read_text(encoding="utf-8")
raw_store = (base / "AndroidRawEvidenceStore.kt").read_text(encoding="utf-8")
normalized_store = (base / "AndroidChunkedNormalizedStore.kt").read_text(encoding="utf-8")
unified = (base / "AndroidUnifiedConversationLibrary.kt").read_text(encoding="utf-8")

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

# Opening the ordinary blank Working Data library must not launch title archaeology
# before metadata rows can be rendered. Explicit title search may still request it.
search_start = unified.index('fun search(')
search_end = unified.index('fun resolve(', search_start)
search = unified[search_start:search_end]
assert 'val normalizedQuery = query.trim()' in search
assert 'if (normalizedQuery.isNotBlank())' in search
assert 'titleCatalog.refreshFromEvidence()' in search
assert search.index('if (normalizedQuery.isNotBlank())') < search.index('titleCatalog.refreshFromEvidence()')

resolve_start = unified.index('fun resolve(')
resolve_end = unified.index('private fun mergeCopies(', resolve_start)
resolve = unified[resolve_start:resolve_end]
assert 'titleCatalog.refreshFromEvidence()' not in resolve

print('android browser-first background processing smoke PASS')
