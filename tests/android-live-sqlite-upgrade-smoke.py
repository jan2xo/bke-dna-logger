#!/usr/bin/env python3
from pathlib import Path

repo = Path(__file__).resolve().parents[1]
kotlin = repo / "android" / "app" / "src" / "main" / "kotlin" / "com" / "bke" / "dna" / "logger"

capture_index = (kotlin / "AndroidCaptureIndex.kt").read_text(encoding="utf-8")
capture_store = (kotlin / "AndroidCaptureStore.kt").read_text(encoding="utf-8")
runtime = (kotlin / "AndroidCaptureRuntime.kt").read_text(encoding="utf-8")
derivative_store = (kotlin / "AndroidDerivativeStore.kt").read_text(encoding="utf-8")
derivative_source = (kotlin / "AndroidDerivativeSourceAccess.kt").read_text(encoding="utf-8")
chunked_store = (kotlin / "AndroidChunkedNormalizedStore.kt").read_text(encoding="utf-8")
pipeline = (kotlin / "AndroidLiveDerivationPipeline.kt").read_text(encoding="utf-8")

# Live Working Data owns exactly one process-wide SQLiteOpenHelper/pool. Public
# AndroidCaptureIndex(context) remains a lease facade so existing callers need
# no ownership rewrite, and ordinary close() only releases that lease.
for token in (
    "private object SharedDatabasePool",
    "SharedDatabasePool.acquire(appContext)",
    "SharedDatabasePool.release(checkNotNull(sharedOwner))",
    "private var owner: AndroidCaptureIndex? = null",
    "private var leases = 0",
    "fun closeSharedDatabaseForStorageMutation()",
    "check(leases == 0)",
):
    assert token in capture_index, token

# Do not paper over SQLite ownership bugs with lock sleeps/retries.
for forbidden in (
    "SQLiteDatabaseLockedException",
    "SQLITE_BUSY",
    "Thread.sleep",
    "busy_timeout",
):
    assert forbidden not in capture_index, forbidden

# Storage mutation closes ingress, waits for the single derivation lane to be
# idle, then explicitly asserts/closes the shared live DB before checkpoint,
# snapshot, delete, or rotation code may proceed.
await_idle = runtime.index("AndroidCaptureStore.awaitBackgroundDerivationIdle(appContext)")
close_pool = runtime.index("AndroidCaptureIndex.closeSharedDatabaseForStorageMutation()")
assert await_idle < close_pool

# captureFinished() must be in the end() finally block after every durable
# capture_end persistence step and RAW_INGEST enqueue. This prevents derivation
# from resuming while capture index/queue writes are still in flight.
end_start = capture_store.index("private fun end(")
enqueue = capture_store.index("AndroidDerivationScheduler.enqueue(", end_start)
finally_block = capture_store.index("} finally {", end_start)
finished = capture_store.index("AndroidDerivationScheduler.captureFinished()", end_start)
assert enqueue < finally_block < finished
for token in (
    "session.finish()",
    "result.partial.renameTo(stagedRaw)",
    "index.record(",
    "AndroidDerivationScheduler.enqueue(",
):
    assert capture_store.index(token, end_start) < finished, token

# Large normalized derivatives must never hold one SQLite writer transaction
# across the complete JsonWriter stream. Chunks are short autocommit writes;
# metadata is the publish marker and is inserted only after exact verification.
stream_start = chunked_store.index("fun putNormalizedJsonStream(")
stream_end = chunked_store.index("fun normalizedJson(", stream_start)
stream_method = chunked_store[stream_start:stream_end]
assert "database.beginTransaction()" not in stream_method
for token in (
    "deleteUnpublishedChunks(sourceSha256)",
    "val sink = ChunkOutputStream(database, sourceSha256)",
    "verifyStoredPayload(sourceSha256, identity)",
    "database.insertOrThrow(TABLE_METADATA, null, values)",
):
    assert token in stream_method, token
assert stream_method.index("deleteUnpublishedChunks(sourceSha256)") < stream_method.index(
    "val sink = ChunkOutputStream(database, sourceSha256)"
)
assert stream_method.index("verifyStoredPayload(sourceSha256, identity)") < stream_method.index(
    "database.insertOrThrow(TABLE_METADATA, null, values)"
)

# Crash recovery may discard only unpublished chunk rows for the exact source;
# a published metadata row remains immutable.
cleanup_start = chunked_store.index("private fun deleteUnpublishedChunks(")
cleanup_end = chunked_store.index("private fun verifyStoredPayload(", cleanup_start)
cleanup = chunked_store[cleanup_start:cleanup_end]
for token in (
    "check(!hasPayload(sourceSha256))",
    "TABLE_CHUNK",
    '"source_sha256 = ?"',
):
    assert token in cleanup, token
assert "TABLE_METADATA" not in cleanup

flush_start = chunked_store.index("private fun flushChunk()")
flush_end = chunked_store.index("private class SQLiteChunkInputStream", flush_start)
flush = chunked_store[flush_start:flush_end]
assert "database.insertOrThrow(TABLE_CHUNK, null, values)" in flush
assert "database.beginTransaction()" not in flush

# Context-based derivative federation sees Latest first, but Latest must use the
# shared active AndroidCaptureIndex pool. Standalone generation constructors are
# reserved here for saved read-only generations.
for token in (
    "if (generation.isLatest)",
    "AndroidChunkedNormalizedStore(appContext)",
    "AndroidDerivativeStore(appContext)",
    "AndroidChunkedNormalizedStore(generation)",
    "AndroidDerivativeStore(generation)",
):
    assert token in derivative_source, token
classification_context = derivative_source[
    derivative_source.index("fun readClassification(context: Context"):
    derivative_source.index("fun readNormalized(context: Context")
]
assert "if (generation.isLatest)" in classification_context
assert "AndroidDerivativeStore(appContext)" in classification_context
assert "readClassification(generation, captureRoot, sourceSha256)" in classification_context

# Derivative immutability remains the default. The sole published-row mutation
# escape hatch is the obsolete pre-streaming classification_size_limit row; no
# RAW or normalized derivative table is deleted by this method.
for token in (
    "fun invalidateLegacyClassificationSizeLimit(",
    'LEGACY_CLASSIFICATION_SIZE_LIMIT_SIGNAL = "classification_size_limit"',
    "database.delete(",
    "TABLE_CLASSIFICATION",
):
    assert token in derivative_store, token
migration_start = derivative_store.index("fun invalidateLegacyClassificationSizeLimit(")
migration_end = derivative_store.index("fun putNormalizedJson(", migration_start)
migration = derivative_store[migration_start:migration_end]
assert "TABLE_CLASSIFICATION" in migration
assert "TABLE_NORMALIZED" not in migration
assert "raw_source" not in migration

# Only oversized RAW with the historical size-limit signal is rebuilt. Current
# classifications and all <=16 MiB classifications remain reusable. Rebuild is
# through the streaming classifier and writes a new Latest derivative.
for token in (
    "val existing = AndroidDerivativeSourceAccess.readClassification(appContext, sourceSha256)",
    "byteLength <= MAX_CLASSIFICATION_BYTES",
    "!hasLegacyClassificationSizeLimit(existing)",
    "store.invalidateLegacyClassificationSizeLimit(sourceSha256)",
    '"BKE DNA derivation: classification_legacy_size_limit_invalidated"',
    "if (byteLength > MAX_CLASSIFICATION_BYTES)",
    "AndroidStreamingConversationPayloadClassifier.classify(input, contentType)",
    "store.putClassificationJson(sourceSha256, envelope.toString(2))",
):
    assert token in pipeline, token

# The 16 MiB value remains a legacy materialization threshold, not an enlarged
# semantic limit or lowered classifier admission gate.
assert "MAX_CLASSIFICATION_BYTES = 16L * 1024 * 1024" in pipeline
assert "score >= 28" not in pipeline  # admission threshold belongs to classifier, untouched here

print("android live SQLite ownership + upgrade migration smoke PASS")
