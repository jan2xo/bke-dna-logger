package com.bke.dna.logger

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * Exact RAW evidence storage inside one Working Data SQLite generation.
 *
 * Each source body is split into independently compressed chunks. Compression is
 * purely a storage representation: reading the chunks in sequence reconstructs
 * the exact original bytes. A new source is not committed until that SQLite
 * reconstruction passes exact byte-length and SHA-256 verification.
 */
class AndroidRawEvidenceStore private constructor(
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
        check(writable) { "RAW evidence schema mutation requires writable Working Data" }
        createSchema(database)
    }

    fun hasSchema(): Boolean = database.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'raw_source' LIMIT 1",
        null,
    ).use { it.moveToFirst() }

    fun contains(sourceSha256: String): Boolean {
        requireSha(sourceSha256)
        if (!hasSchema()) return false
        database.rawQuery(
            "SELECT 1 FROM raw_source WHERE source_sha256 = ? LIMIT 1",
            arrayOf(sourceSha256),
        ).use { return it.moveToFirst() }
    }

    fun descriptor(sourceSha256: String): AndroidRawSourceDescriptor? {
        requireSha(sourceSha256)
        if (!hasSchema()) return null
        database.query(
            "raw_source",
            arrayOf("byte_length", "chunk_count", "codec", "stored_at", "compressed_bytes"),
            "source_sha256 = ?",
            arrayOf(sourceSha256),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return AndroidRawSourceDescriptor(
                sourceSha256 = sourceSha256,
                byteLength = cursor.getLong(0),
                chunkCount = cursor.getInt(1),
                codec = cursor.getString(2),
                storedAt = cursor.getString(3),
                compressedBytes = cursor.getLong(4),
            )
        }
    }

    fun importVerified(
        sourceSha256: String,
        stagingFile: File,
        expectedByteLength: Long,
    ): AndroidRawImportResult {
        check(writable) { "RAW import requires writable Working Data" }
        requireSha(sourceSha256)
        require(expectedByteLength >= 0)
        require(stagingFile.isFile) { "RAW staging file does not exist" }

        descriptor(sourceSha256)?.let { existing ->
            require(existing.byteLength == expectedByteLength) {
                "Existing RAW source length does not match capture"
            }
            verifySource(sourceSha256, expectedByteLength)
            return AndroidRawImportResult(existing, reused = true)
        }

        val digest = MessageDigest.getInstance("SHA-256")
        var byteLength = 0L
        var chunkCount = 0
        var compressedBytes = 0L
        var offset = 0L
        val storedAt = Instant.now().toString()

        database.beginTransaction()
        try {
            stagingFile.inputStream().buffered().use { input ->
                val buffer = ByteArray(RAW_CHUNK_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    digest.update(buffer, 0, read)
                    val compressed = compress(buffer, read)
                    val values = ContentValues().apply {
                        put("source_sha256", sourceSha256)
                        put("sequence", chunkCount)
                        put("uncompressed_offset", offset)
                        put("uncompressed_length", read)
                        put("compressed_bytes", compressed)
                    }
                    database.insertOrThrow("raw_source_chunk", null, values)
                    byteLength += read
                    offset += read
                    compressedBytes += compressed.size
                    chunkCount += 1
                }
            }

            require(byteLength == expectedByteLength) {
                "RAW staging length changed during SQLite import"
            }
            require(digest.digest().toLowerHex() == sourceSha256) {
                "RAW staging SHA-256 does not match source identity"
            }

            val sourceValues = ContentValues().apply {
                put("source_sha256", sourceSha256)
                put("byte_length", byteLength)
                put("chunk_count", chunkCount)
                put("codec", CODEC)
                put("stored_at", storedAt)
                put("compressed_bytes", compressedBytes)
            }
            database.insertOrThrow("raw_source", null, sourceValues)

            // Re-read and decompress the exact SQLite representation before the
            // transaction is allowed to commit. If verification fails or the
            // process dies first, the whole source/chunk insert rolls back and
            // the durable staging file remains the recovery authority.
            verifySource(sourceSha256, expectedByteLength)
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }

        return AndroidRawImportResult(requireNotNull(descriptor(sourceSha256)), reused = false)
    }

    fun readPage(sourceSha256: String, byteOffset: Long, maxBytes: Int): ByteArray {
        requireSha(sourceSha256)
        require(byteOffset >= 0)
        require(maxBytes in 1..MAX_READ_PAGE_BYTES)
        val source = descriptor(sourceSha256) ?: error("RAW source is not stored in SQLite")
        if (byteOffset >= source.byteLength) return ByteArray(0)
        val endExclusive = minOf(source.byteLength, byteOffset + maxBytes)
        val output = ByteArrayOutputStream((endExclusive - byteOffset).toInt())

        database.rawQuery(
            """
            SELECT uncompressed_offset, uncompressed_length, compressed_bytes
            FROM raw_source_chunk
            WHERE source_sha256 = ?
              AND uncompressed_offset < ?
              AND (uncompressed_offset + uncompressed_length) > ?
            ORDER BY sequence ASC
            """.trimIndent(),
            arrayOf(sourceSha256, endExclusive.toString(), byteOffset.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val chunkOffset = cursor.getLong(0)
                val chunk = decompress(cursor.getBlob(2), cursor.getInt(1))
                val localStart = maxOf(0L, byteOffset - chunkOffset).toInt()
                val localEnd = minOf(chunk.size.toLong(), endExclusive - chunkOffset).toInt()
                if (localEnd > localStart) output.write(chunk, localStart, localEnd - localStart)
            }
        }
        return output.toByteArray()
    }

    fun readAllBytes(sourceSha256: String, maxBytes: Long): ByteArray {
        require(maxBytes in 0..Int.MAX_VALUE.toLong())
        val source = descriptor(sourceSha256) ?: error("RAW source is not stored in SQLite")
        require(source.byteLength <= maxBytes) { "RAW source exceeds bounded read limit" }
        val output = ByteArrayOutputStream(source.byteLength.toInt())
        writeExactSource(sourceSha256, output)
        return output.toByteArray()
    }

    fun writeExactSource(sourceSha256: String, output: OutputStream) {
        requireSha(sourceSha256)
        val source = descriptor(sourceSha256) ?: error("RAW source is not stored in SQLite")
        var written = 0L
        database.query(
            "raw_source_chunk",
            arrayOf("uncompressed_length", "compressed_bytes"),
            "source_sha256 = ?",
            arrayOf(sourceSha256),
            null,
            null,
            "sequence ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val chunk = decompress(cursor.getBlob(1), cursor.getInt(0))
                output.write(chunk)
                written += chunk.size
            }
        }
        require(written == source.byteLength) { "RAW SQLite source length mismatch" }
    }

    fun openExactInputStream(sourceSha256: String): InputStream {
        requireSha(sourceSha256)
        val source = descriptor(sourceSha256) ?: error("RAW source is not stored in SQLite")
        return RawChunkInputStream(database, source)
    }

    fun verifySource(sourceSha256: String, expectedByteLength: Long? = null) {
        val source = descriptor(sourceSha256) ?: error("RAW source is not stored in SQLite")
        expectedByteLength?.let {
            require(source.byteLength == it) { "RAW SQLite source byte length mismatch" }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        openExactInputStream(sourceSha256).use { input ->
            val buffer = ByteArray(VERIFY_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                digest.update(buffer, 0, read)
                bytes += read
            }
        }
        require(bytes == source.byteLength) { "RAW SQLite source verification length mismatch" }
        require(digest.digest().toLowerHex() == sourceSha256) {
            "RAW SQLite source verification SHA-256 mismatch"
        }
    }

    fun deleteSource(sourceSha256: String) {
        check(writable) { "RAW deletion requires writable Working Data" }
        requireSha(sourceSha256)
        database.beginTransaction()
        try {
            database.delete("raw_source_chunk", "source_sha256 = ?", arrayOf(sourceSha256))
            database.delete("raw_source", "source_sha256 = ?", arrayOf(sourceSha256))
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    override fun close() = handle.close()

    private class RawChunkInputStream(
        private val database: SQLiteDatabase,
        private val source: AndroidRawSourceDescriptor,
    ) : InputStream() {
        private val cursor = database.query(
            "raw_source_chunk",
            arrayOf("uncompressed_length", "compressed_bytes"),
            "source_sha256 = ?",
            arrayOf(source.sourceSha256),
            null,
            null,
            "sequence ASC",
        )
        private var chunk = ByteArray(0)
        private var chunkOffset = 0
        private var closed = false

        override fun read(): Int {
            val one = ByteArray(1)
            val count = read(one, 0, 1)
            return if (count < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            require(offset >= 0 && length >= 0 && offset + length <= buffer.size)
            if (length == 0) return 0
            if (closed) return -1
            var written = 0
            while (written < length) {
                if (chunkOffset >= chunk.size && !loadNextChunk()) break
                val copy = minOf(length - written, chunk.size - chunkOffset)
                System.arraycopy(chunk, chunkOffset, buffer, offset + written, copy)
                chunkOffset += copy
                written += copy
            }
            return if (written == 0) -1 else written
        }

        private fun loadNextChunk(): Boolean {
            if (!cursor.moveToNext()) return false
            chunk = decompress(cursor.getBlob(1), cursor.getInt(0))
            chunkOffset = 0
            return true
        }

        override fun close() {
            if (closed) return
            closed = true
            cursor.close()
        }
    }

    companion object {
        const val RAW_CHUNK_BYTES = 256 * 1024
        const val MAX_READ_PAGE_BYTES = 1024 * 1024
        const val CODEC = "deflate-raw-chunk-v1"
        private const val VERIFY_BUFFER_BYTES = 64 * 1024
        private val SHA256 = Regex("[0-9a-f]{64}")

        fun createSchema(database: SQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS raw_source (
                    source_sha256 TEXT PRIMARY KEY,
                    byte_length INTEGER NOT NULL,
                    chunk_count INTEGER NOT NULL,
                    codec TEXT NOT NULL,
                    stored_at TEXT NOT NULL,
                    compressed_bytes INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS raw_source_chunk (
                    source_sha256 TEXT NOT NULL,
                    sequence INTEGER NOT NULL,
                    uncompressed_offset INTEGER NOT NULL,
                    uncompressed_length INTEGER NOT NULL,
                    compressed_bytes BLOB NOT NULL,
                    PRIMARY KEY (source_sha256, sequence)
                )
                """.trimIndent(),
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS raw_source_chunk_offset_idx " +
                    "ON raw_source_chunk(source_sha256, uncompressed_offset)",
            )
        }

        private fun requireSha(sourceSha256: String) {
            require(SHA256.matches(sourceSha256)) { "Expected lowercase SHA-256 source identity" }
        }

        private fun compress(buffer: ByteArray, length: Int): ByteArray {
            val output = ByteArrayOutputStream(length.coerceAtLeast(256))
            DeflaterOutputStream(output, Deflater(Deflater.BEST_SPEED, false)).use { deflater ->
                deflater.write(buffer, 0, length)
                deflater.finish()
            }
            return output.toByteArray()
        }

        private fun decompress(compressed: ByteArray, expectedLength: Int): ByteArray {
            val output = ByteArrayOutputStream(expectedLength)
            InflaterInputStream(ByteArrayInputStream(compressed)).use { inflater ->
                val buffer = ByteArray(minOf(64 * 1024, expectedLength.coerceAtLeast(1)))
                while (true) {
                    val read = inflater.read(buffer)
                    if (read < 0) break
                    if (read > 0) output.write(buffer, 0, read)
                }
            }
            val result = output.toByteArray()
            require(result.size == expectedLength) { "RAW SQLite chunk decompression length mismatch" }
            return result
        }

        private fun ByteArray.toLowerHex(): String = joinToString("") { "%02x".format(it) }
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

data class AndroidRawSourceDescriptor(
    val sourceSha256: String,
    val byteLength: Long,
    val chunkCount: Int,
    val codec: String,
    val storedAt: String,
    val compressedBytes: Long,
)

data class AndroidRawImportResult(
    val descriptor: AndroidRawSourceDescriptor,
    val reused: Boolean,
)
