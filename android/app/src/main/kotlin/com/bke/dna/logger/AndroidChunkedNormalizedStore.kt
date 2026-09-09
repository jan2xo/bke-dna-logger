package com.bke.dna.logger

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.JsonReader
import android.util.JsonWriter
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.security.MessageDigest
import java.time.Instant

/**
 * Bounded-memory storage for large normalized conversation derivatives.
 *
 * PR5's original inline TEXT rows remain readable for compatibility. New large
 * messages-array derivatives are serialized directly through JsonWriter into
 * bounded UTF-8 SQLite BLOB chunks. Chunks are committed independently so a
 * large derivative never monopolizes the live Working Data writer. The metadata
 * row is the publish marker and is written only after exact SHA/length/chunk
 * verification succeeds. Exact RAW evidence is unaffected.
 */
class AndroidChunkedNormalizedStore private constructor(
    private val handle: DatabaseHandle,
    private val writable: Boolean,
) : AutoCloseable {
    private val database: SQLiteDatabase
        get() = handle.database

    constructor(context: Context) : this(DatabaseHandle.active(context.applicationContext), true)

    constructor(generation: AndroidWorkingDataGeneration) : this(
        DatabaseHandle.readOnly(generation.databaseFile),
        false,
    )

    init {
        if (writable) ensureSchema()
    }

    fun ensureSchema() {
        check(writable) { "Chunked normalized schema mutation requires writable Working Data" }
        createSchema(database)
    }

    fun hasPayload(sourceSha256: String): Boolean {
        requireSha(sourceSha256)
        if (!tableExists(TABLE_METADATA)) return false
        return database.rawQuery(
            "SELECT 1 FROM $TABLE_METADATA WHERE source_sha256 = ? LIMIT 1",
            arrayOf(sourceSha256),
        ).use { it.moveToFirst() }
    }

    fun metadata(sourceSha256: String): AndroidChunkedNormalizedMetadata? {
        requireSha(sourceSha256)
        if (!tableExists(TABLE_METADATA)) return null
        return database.query(
            TABLE_METADATA,
            arrayOf(
                "conversation_native_id",
                "normalized_at",
                "payload_sha256",
                "byte_length",
                "chunk_count",
            ),
            "source_sha256 = ?",
            arrayOf(sourceSha256),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            AndroidChunkedNormalizedMetadata(
                sourceSha256 = sourceSha256,
                conversationNativeId = cursor.getString(0),
                normalizedAt = cursor.getString(1),
                payloadSha256 = cursor.getString(2),
                byteLength = cursor.getLong(3),
                chunkCount = cursor.getInt(4),
            )
        }
    }

    fun listSourceSha256s(): List<String> {
        if (!tableExists(TABLE_METADATA)) return emptyList()
        return database.query(
            TABLE_METADATA,
            arrayOf("source_sha256"),
            null,
            null,
            null,
            null,
            "source_sha256 ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
    }

    fun putNormalizedJsonStream(
        sourceSha256: String,
        conversationNativeId: String,
        normalizedAt: String,
        writePayload: (JsonWriter) -> Unit,
    ) {
        check(writable) { "Chunked normalized derivative write requires writable Working Data" }
        requireSha(sourceSha256)
        require(conversationNativeId.isNotBlank()) { "Normalized derivative lacks conversation identity" }
        require(normalizedAt.isNotBlank()) { "Normalized derivative lacks normalized timestamp" }
        check(!hasPayload(sourceSha256)) {
            "Conflicting immutable derivative for source '$sourceSha256'"
        }

        // A process death before metadata publication may leave committed chunks.
        // They are not a visible derivative because readers require metadata.
        // Remove only those unpublished chunks before rebuilding this exact SHA.
        deleteUnpublishedChunks(sourceSha256)

        val sink = ChunkOutputStream(database, sourceSha256)
        JsonWriter(OutputStreamWriter(sink, Charsets.UTF_8)).use { writer ->
            writer.setIndent("  ")
            writePayload(writer)
        }
        val identity = sink.identity()
        require(identity.chunkCount > 0) { "Normalized derivative produced no payload chunks" }

        // Every chunk is already durable in its own short SQLite write. Verify
        // the complete representation before making it visible to readers.
        verifyStoredPayload(sourceSha256, identity)

        val values = ContentValues().apply {
            put("source_sha256", sourceSha256)
            put("conversation_native_id", conversationNativeId)
            put("normalized_at", normalizedAt)
            put("payload_sha256", identity.sha256)
            put("byte_length", identity.byteLength)
            put("chunk_count", identity.chunkCount)
            put("stored_at", Instant.now().toString())
        }
        // Single-row autocommit is the publication boundary. A crash before this
        // point leaves only invisible orphan chunks; a crash after it leaves a
        // fully verified immutable derivative.
        database.insertOrThrow(TABLE_METADATA, null, values)
    }

    fun normalizedJson(sourceSha256: String): String? {
        val expected = metadata(sourceSha256) ?: return null
        val builder = StringBuilder()
        withJsonReaderSource(sourceSha256) { input ->
            InputStreamReader(input, Charsets.UTF_8).use { reader ->
                val buffer = CharArray(TEXT_READ_BUFFER_CHARS)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    builder.append(buffer, 0, count)
                }
            }
        }
        val payload = builder.toString()
        val actual = identityOf(payload)
        require(
            actual.sha256 == expected.payloadSha256 &&
                actual.byteLength == expected.byteLength
        ) {
            "Chunked normalized derivative integrity mismatch"
        }
        return payload
    }

    fun <T> withJsonReader(sourceSha256: String, block: (JsonReader) -> T): T? {
        val expected = metadata(sourceSha256) ?: return null
        verifyStoredPayload(
            sourceSha256,
            PayloadIdentity(
                sha256 = expected.payloadSha256,
                byteLength = expected.byteLength,
                chunkCount = expected.chunkCount,
            ),
        )
        return withJsonReaderSource(sourceSha256) { input ->
            JsonReader(InputStreamReader(input, Charsets.UTF_8)).use(block)
        }
    }

    private fun <T> withJsonReaderSource(sourceSha256: String, block: (InputStream) -> T): T {
        requireSha(sourceSha256)
        val input = SQLiteChunkInputStream(database, sourceSha256)
        input.use { return block(it) }
    }

    private fun deleteUnpublishedChunks(sourceSha256: String) {
        check(!hasPayload(sourceSha256)) {
            "Published normalized derivative chunks cannot be discarded"
        }
        database.delete(
            TABLE_CHUNK,
            "source_sha256 = ?",
            arrayOf(sourceSha256),
        )
    }

    private fun verifyStoredPayload(sourceSha256: String, expected: PayloadIdentity) {
        val digest = MessageDigest.getInstance("SHA-256")
        var byteLength = 0L
        var chunkCount = 0
        database.query(
            TABLE_CHUNK,
            arrayOf("chunk_index", "payload_utf8"),
            "source_sha256 = ?",
            arrayOf(sourceSha256),
            null,
            null,
            "chunk_index ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                require(cursor.getInt(0) == chunkCount) {
                    "Chunked normalized derivative chunk sequence mismatch"
                }
                val bytes = cursor.getBlob(1)
                digest.update(bytes)
                byteLength += bytes.size.toLong()
                chunkCount += 1
            }
        }
        require(
            chunkCount == expected.chunkCount &&
                byteLength == expected.byteLength &&
                hex(digest.digest()) == expected.sha256
        ) {
            "Derivative SQLite round-trip mismatch"
        }
    }

    private fun tableExists(table: String): Boolean = database.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
        arrayOf(table),
    ).use { it.moveToFirst() }

    override fun close() = handle.close()

    private data class PayloadIdentity(
        val sha256: String,
        val byteLength: Long,
        val chunkCount: Int,
    )

    private class ChunkOutputStream(
        private val database: SQLiteDatabase,
        private val sourceSha256: String,
    ) : OutputStream() {
        private val buffer = ByteArray(PAYLOAD_CHUNK_BYTES)
        private val digest = MessageDigest.getInstance("SHA-256")
        private var bufferLength = 0
        private var byteLength = 0L
        private var chunkIndex = 0
        private var closed = false

        override fun write(value: Int) {
            val one = byteArrayOf(value.toByte())
            write(one, 0, 1)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            check(!closed) { "Chunked normalized sink is closed" }
            require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
            var cursor = offset
            var remaining = length
            while (remaining > 0) {
                val copyCount = minOf(remaining, buffer.size - bufferLength)
                bytes.copyInto(
                    destination = buffer,
                    destinationOffset = bufferLength,
                    startIndex = cursor,
                    endIndex = cursor + copyCount,
                )
                digest.update(bytes, cursor, copyCount)
                byteLength += copyCount.toLong()
                bufferLength += copyCount
                cursor += copyCount
                remaining -= copyCount
                if (bufferLength == buffer.size) flushChunk()
            }
        }

        override fun flush() {
            // Keep partial data bounded in memory. Final persistence occurs on
            // close so chunk boundaries are deterministic by byte count.
        }

        override fun close() {
            if (closed) return
            flushChunk()
            closed = true
        }

        fun identity(): PayloadIdentity {
            check(closed) { "Chunked normalized sink must be closed before identity is read" }
            return PayloadIdentity(
                sha256 = hex(digest.digest()),
                byteLength = byteLength,
                chunkCount = chunkIndex,
            )
        }

        private fun flushChunk() {
            if (bufferLength == 0) return
            val values = ContentValues().apply {
                put("source_sha256", sourceSha256)
                put("chunk_index", chunkIndex)
                put("payload_utf8", buffer.copyOf(bufferLength))
            }
            // Intentionally autocommit each bounded chunk. Capture ingress shares
            // this live pool and must never wait behind a multi-megabyte write
            // transaction just to persist capture_end.
            database.insertOrThrow(TABLE_CHUNK, null, values)
            chunkIndex += 1
            bufferLength = 0
        }
    }

    private class SQLiteChunkInputStream(
        database: SQLiteDatabase,
        sourceSha256: String,
    ) : InputStream() {
        private val cursor = database.query(
            TABLE_CHUNK,
            arrayOf("chunk_index", "payload_utf8"),
            "source_sha256 = ?",
            arrayOf(sourceSha256),
            null,
            null,
            "chunk_index ASC",
        )
        private var expectedChunkIndex = 0
        private var current = ByteArray(0)
        private var currentOffset = 0
        private var exhausted = false

        override fun read(): Int {
            if (!ensureAvailable()) return -1
            return current[currentOffset++].toInt() and 0xff
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
            if (length == 0) return 0
            if (!ensureAvailable()) return -1
            val count = minOf(length, current.size - currentOffset)
            current.copyInto(bytes, offset, currentOffset, currentOffset + count)
            currentOffset += count
            return count
        }

        private fun ensureAvailable(): Boolean {
            while (currentOffset >= current.size) {
                if (exhausted || !cursor.moveToNext()) {
                    exhausted = true
                    return false
                }
                require(cursor.getInt(0) == expectedChunkIndex) {
                    "Chunked normalized derivative chunk sequence mismatch"
                }
                expectedChunkIndex += 1
                current = cursor.getBlob(1)
                currentOffset = 0
            }
            return true
        }

        override fun close() = cursor.close()
    }

    companion object {
        private const val TABLE_METADATA = "derivative_normalized_chunked"
        private const val TABLE_CHUNK = "derivative_normalized_chunk"
        private const val PAYLOAD_CHUNK_BYTES = 64 * 1024
        private const val PAYLOAD_HASH_CHUNK_CHARS = 32 * 1024
        private const val TEXT_READ_BUFFER_CHARS = 16 * 1024
        private val SHA256 = Regex("[0-9a-f]{64}")

        fun createSchema(database: SQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS derivative_normalized_chunked (
                    source_sha256 TEXT PRIMARY KEY,
                    conversation_native_id TEXT NOT NULL,
                    normalized_at TEXT NOT NULL,
                    payload_sha256 TEXT NOT NULL,
                    byte_length INTEGER NOT NULL,
                    chunk_count INTEGER NOT NULL,
                    stored_at TEXT NOT NULL
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS derivative_normalized_chunk (
                    source_sha256 TEXT NOT NULL,
                    chunk_index INTEGER NOT NULL,
                    payload_utf8 BLOB NOT NULL,
                    PRIMARY KEY (source_sha256, chunk_index)
                )
                """.trimIndent(),
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS derivative_normalized_chunk_source_idx " +
                    "ON derivative_normalized_chunk(source_sha256, chunk_index)",
            )
        }

        private fun requireSha(sourceSha256: String) {
            require(SHA256.matches(sourceSha256)) { "Expected lowercase SHA-256 source identity" }
        }

        private fun identityOf(payload: String): PayloadIdentity {
            val digest = MessageDigest.getInstance("SHA-256")
            var byteLength = 0L
            var offset = 0
            while (offset < payload.length) {
                var end = minOf(payload.length, offset + PAYLOAD_HASH_CHUNK_CHARS)
                if (
                    end < payload.length &&
                    end > offset &&
                    Character.isHighSurrogate(payload[end - 1]) &&
                    Character.isLowSurrogate(payload[end])
                ) {
                    end -= 1
                }
                val bytes = payload.substring(offset, end).toByteArray(Charsets.UTF_8)
                digest.update(bytes)
                byteLength += bytes.size.toLong()
                offset = end
            }
            return PayloadIdentity(
                sha256 = hex(digest.digest()),
                byteLength = byteLength,
                chunkCount = -1,
            )
        }

        private fun hex(bytes: ByteArray): String =
            bytes.joinToString("") { "%02x".format(it) }
    }

    private class DatabaseHandle(
        val database: SQLiteDatabase,
        private val closer: () -> Unit,
    ) : AutoCloseable {
        override fun close() = closer()

        companion object {
            fun active(context: Context): DatabaseHandle {
                val index = AndroidCaptureIndex(context)
                return DatabaseHandle(index.writableDatabase) { index.close() }
            }

            fun readOnly(databaseFile: File): DatabaseHandle {
                val database = SQLiteDatabase.openDatabase(
                    databaseFile.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY,
                )
                return DatabaseHandle(database) { database.close() }
            }
        }
    }
}

data class AndroidChunkedNormalizedMetadata(
    val sourceSha256: String,
    val conversationNativeId: String,
    val normalizedAt: String,
    val payloadSha256: String,
    val byteLength: Long,
    val chunkCount: Int,
)
