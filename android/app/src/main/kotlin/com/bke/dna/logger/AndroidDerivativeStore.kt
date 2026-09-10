package com.bke.dna.logger

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.Instant

/**
 * Durable classification and normalized derivative storage inside one Working
 * Data SQLite generation.
 *
 * Derivatives are not primary evidence, but their exact serialized JSON is kept
 * so existing reconciliation/export contracts can migrate away from permanent
 * loose files without changing semantics. Rows are immutable per source SHA:
 * retries reuse an identical row and conflicting rewrites fail closed.
 */
class AndroidDerivativeStore private constructor(
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
        check(writable) { "Derivative schema mutation requires writable Working Data" }
        createSchema(database)
    }

    fun hasSchema(): Boolean = tableExists(TABLE_CLASSIFICATION) && tableExists(TABLE_NORMALIZED)

    fun classificationJson(sourceSha256: String): String? =
        readPayload(TABLE_CLASSIFICATION, sourceSha256)

    fun normalizedJson(sourceSha256: String): String? =
        readPayload(TABLE_NORMALIZED, sourceSha256)

    fun listNormalizedSourceSha256s(): List<String> {
        if (!tableExists(TABLE_NORMALIZED)) return emptyList()
        database.query(
            TABLE_NORMALIZED,
            arrayOf("source_sha256"),
            null,
            null,
            null,
            null,
            "source_sha256 ASC",
        ).use { cursor ->
            return buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
    }

    fun putClassificationJson(sourceSha256: String, payloadJson: String) {
        check(writable) { "Classification derivative write requires writable Working Data" }
        requireSha(sourceSha256)
        val root = JSONObject(payloadJson)
        require(root.getString("sha256") == sourceSha256) {
            "Classification derivative source identity mismatch"
        }
        putImmutablePayload(
            table = TABLE_CLASSIFICATION,
            sourceSha256 = sourceSha256,
            payloadJson = payloadJson,
            extraValues = ContentValues().apply {
                put("classified_at", root.getString("classifiedAt"))
            },
        )
    }

    /**
     * Narrow upgrade migration for the pre-streaming oversized-RAW rejection.
     *
     * Classification rows remain immutable except for this one explicitly
     * obsolete derivative state. RAW and normalized derivatives are untouched.
     * The caller is responsible for limiting this migration to oversized RAW.
     */
    fun invalidateLegacyClassificationSizeLimit(sourceSha256: String): Boolean {
        check(writable) { "Classification derivative invalidation requires writable Working Data" }
        requireSha(sourceSha256)
        val payload = classificationJson(sourceSha256) ?: return false
        val classification = runCatching {
            JSONObject(payload).getJSONObject("classification")
        }.getOrNull() ?: return false
        val signals = classification.optJSONArray("signals") ?: return false
        val isLegacySizeLimit = (0 until signals.length()).any { index ->
            signals.optString(index) == LEGACY_CLASSIFICATION_SIZE_LIMIT_SIGNAL
        }
        if (!isLegacySizeLimit) return false

        database.beginTransaction()
        try {
            val deleted = database.delete(
                TABLE_CLASSIFICATION,
                "source_sha256 = ?",
                arrayOf(sourceSha256),
            )
            check(deleted == 1) { "Legacy classification derivative disappeared during migration" }
            database.setTransactionSuccessful()
            return true
        } finally {
            database.endTransaction()
        }
    }

    fun putNormalizedJson(sourceSha256: String, payloadJson: String) {
        check(writable) { "Normalized derivative write requires writable Working Data" }
        requireSha(sourceSha256)

        // Normalized payloads can be tens of megabytes. Do not parse the whole
        // serialized derivative into a second JSONObject merely to recover the
        // three top-level identity fields needed for SQLite columns.
        val metadata = readNormalizedMetadata(payloadJson)
        require(metadata.sourceSha256 == sourceSha256) {
            "Normalized derivative source identity mismatch"
        }
        require(metadata.conversationNativeId.isNotBlank()) {
            "Normalized derivative lacks conversation identity"
        }

        putImmutablePayload(
            table = TABLE_NORMALIZED,
            sourceSha256 = sourceSha256,
            payloadJson = payloadJson,
            extraValues = ContentValues().apply {
                put("conversation_native_id", metadata.conversationNativeId)
                put("normalized_at", metadata.normalizedAt)
            },
        )
    }

    private fun putImmutablePayload(
        table: String,
        sourceSha256: String,
        payloadJson: String,
        extraValues: ContentValues,
    ) {
        // Hash UTF-8 incrementally so a large derivative never needs a second
        // full-size ByteArray beside the already serialized String.
        val expected = identityOf(payloadJson)
        val existing = readPayloadIdentity(table, sourceSha256)
        if (existing != null) {
            require(existing == expected) {
                "Conflicting immutable derivative for source '$sourceSha256'"
            }
            verifyStoredPayload(table, sourceSha256, expected)
            return
        }

        val values = ContentValues(extraValues).apply {
            put("source_sha256", sourceSha256)
            put("payload_json", payloadJson)
            put("payload_sha256", expected.sha256)
            put("byte_length", expected.byteLength)
            put("stored_at", Instant.now().toString())
        }

        database.beginTransaction()
        try {
            database.insertOrThrow(table, null, values)
            // Verify the committed TEXT in bounded substrings rather than
            // rebuilding another full payload String inside the transaction.
            verifyStoredPayload(table, sourceSha256, expected)
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    private fun readPayload(table: String, sourceSha256: String): String? {
        requireSha(sourceSha256)
        if (!tableExists(table)) return null
        val expected = readPayloadIdentity(table, sourceSha256) ?: return null

        val digest = MessageDigest.getInstance("SHA-256")
        var byteLength = 0L
        val payload = StringBuilder()
        forEachStoredPayloadChunk(table, sourceSha256) { chunk ->
            payload.append(chunk)
            val bytes = chunk.toByteArray(Charsets.UTF_8)
            digest.update(bytes)
            byteLength += bytes.size.toLong()
        }

        require(byteLength == expected.byteLength) {
            "Derivative SQLite byte-length mismatch"
        }
        require(hex(digest.digest()) == expected.sha256) {
            "Derivative SQLite SHA-256 mismatch"
        }
        return payload.toString()
    }

    private fun readPayloadIdentity(table: String, sourceSha256: String): PayloadIdentity? {
        if (!tableExists(table)) return null
        return database.query(
            table,
            arrayOf("payload_sha256", "byte_length"),
            "source_sha256 = ?",
            arrayOf(sourceSha256),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            PayloadIdentity(
                sha256 = cursor.getString(0),
                byteLength = cursor.getLong(1),
            )
        }
    }

    private fun verifyStoredPayload(
        table: String,
        sourceSha256: String,
        expected: PayloadIdentity,
    ) {
        val committed = readPayloadIdentity(table, sourceSha256)
            ?: error("Derivative row was not readable inside transaction")
        require(committed == expected) { "Derivative SQLite round-trip mismatch" }

        val digest = MessageDigest.getInstance("SHA-256")
        var byteLength = 0L
        forEachStoredPayloadChunk(table, sourceSha256) { chunk ->
            val bytes = chunk.toByteArray(Charsets.UTF_8)
            digest.update(bytes)
            byteLength += bytes.size.toLong()
        }
        require(
            byteLength == expected.byteLength &&
                hex(digest.digest()) == expected.sha256
        ) {
            "Derivative SQLite round-trip mismatch"
        }
    }

    private inline fun forEachStoredPayloadChunk(
        table: String,
        sourceSha256: String,
        block: (String) -> Unit,
    ) {
        var payloadOffset = 1
        while (true) {
            val chunk = database.rawQuery(
                "SELECT substr(payload_json, ?, ?) FROM $table " +
                    "WHERE source_sha256 = ? LIMIT 1",
                arrayOf(
                    payloadOffset.toString(),
                    PAYLOAD_READ_CHUNK_CHARS.toString(),
                    sourceSha256,
                ),
            ).use { cursor ->
                check(cursor.moveToFirst()) {
                    "Derivative row disappeared during payload read"
                }
                cursor.getString(0)
            }
            if (chunk.isEmpty()) return
            block(chunk)
            payloadOffset += PAYLOAD_READ_CHUNK_CHARS
        }
    }

    private fun identityOf(payload: String): PayloadIdentity {
        val digest = MessageDigest.getInstance("SHA-256")
        var byteLength = 0L
        var offset = 0
        while (offset < payload.length) {
            var end = minOf(payload.length, offset + PAYLOAD_HASH_CHUNK_CHARS)
            // Keep a UTF-16 surrogate pair in the same bounded encoding chunk.
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
        return PayloadIdentity(hex(digest.digest()), byteLength)
    }

    private fun readNormalizedMetadata(payload: String): NormalizedMetadata {
        val wanted = setOf("sourceSha256", "conversationNativeId", "normalizedAt")
        val values = linkedMapOf<String, String>()
        var objectDepth = 0
        var index = 0

        while (index < payload.length && values.size < wanted.size) {
            when (payload[index]) {
                '{' -> {
                    objectDepth += 1
                    index += 1
                }
                '}' -> {
                    objectDepth -= 1
                    index += 1
                }
                '"' -> {
                    val key = readJsonString(payload, index)
                    if (objectDepth != 1) {
                        index = key.nextIndex
                        continue
                    }

                    val colon = skipWhitespace(payload, key.nextIndex)
                    if (colon >= payload.length || payload[colon] != ':') {
                        index = key.nextIndex
                        continue
                    }

                    val valueStart = skipWhitespace(payload, colon + 1)
                    if (key.value in wanted && valueStart < payload.length && payload[valueStart] == '"') {
                        val value = readJsonString(payload, valueStart)
                        values[key.value] = value.value
                        index = value.nextIndex
                    } else {
                        index = key.nextIndex
                    }
                }
                else -> index += 1
            }
        }

        return NormalizedMetadata(
            sourceSha256 = values["sourceSha256"]
                ?: error("Normalized derivative lacks source identity"),
            conversationNativeId = values["conversationNativeId"]
                ?: error("Normalized derivative lacks conversation identity"),
            normalizedAt = values["normalizedAt"]
                ?: error("Normalized derivative lacks normalized timestamp"),
        )
    }

    private fun readJsonString(payload: String, startIndex: Int): JsonStringToken {
        require(payload[startIndex] == '"') { "Expected JSON string" }
        val value = StringBuilder()
        var index = startIndex + 1
        while (index < payload.length) {
            when (val char = payload[index++]) {
                '"' -> return JsonStringToken(value.toString(), index)
                '\\' -> {
                    require(index < payload.length) { "Invalid JSON escape" }
                    when (val escaped = payload[index++]) {
                        '"', '\\', '/' -> value.append(escaped)
                        'b' -> value.append('\b')
                        'f' -> value.append('\u000c')
                        'n' -> value.append('\n')
                        'r' -> value.append('\r')
                        't' -> value.append('\t')
                        'u' -> {
                            require(index + 4 <= payload.length) { "Invalid JSON unicode escape" }
                            val codeUnit = payload.substring(index, index + 4).toInt(16)
                            value.append(codeUnit.toChar())
                            index += 4
                        }
                        else -> error("Invalid JSON escape '$escaped'")
                    }
                }
                else -> value.append(char)
            }
        }
        error("Unterminated JSON string")
    }

    private fun skipWhitespace(payload: String, startIndex: Int): Int {
        var index = startIndex
        while (index < payload.length && payload[index].isWhitespace()) index += 1
        return index
    }

    private fun tableExists(table: String): Boolean = database.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
        arrayOf(table),
    ).use { it.moveToFirst() }

    override fun close() = handle.close()

    private data class PayloadIdentity(
        val sha256: String,
        val byteLength: Long,
    )

    private data class NormalizedMetadata(
        val sourceSha256: String,
        val conversationNativeId: String,
        val normalizedAt: String,
    )

    private data class JsonStringToken(
        val value: String,
        val nextIndex: Int,
    )

    companion object {
        private const val TABLE_CLASSIFICATION = "derivative_classification"
        private const val TABLE_NORMALIZED = "derivative_normalized"
        private const val LEGACY_CLASSIFICATION_SIZE_LIMIT_SIGNAL = "classification_size_limit"
        private const val PAYLOAD_READ_CHUNK_CHARS = 128 * 1024
        private const val PAYLOAD_HASH_CHUNK_CHARS = 64 * 1024
        private val SHA256 = Regex("[0-9a-f]{64}")

        fun createSchema(database: SQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS derivative_classification (
                    source_sha256 TEXT PRIMARY KEY,
                    classified_at TEXT NOT NULL,
                    payload_json TEXT NOT NULL,
                    payload_sha256 TEXT NOT NULL,
                    byte_length INTEGER NOT NULL,
                    stored_at TEXT NOT NULL
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS derivative_normalized (
                    source_sha256 TEXT PRIMARY KEY,
                    conversation_native_id TEXT NOT NULL,
                    normalized_at TEXT NOT NULL,
                    payload_json TEXT NOT NULL,
                    payload_sha256 TEXT NOT NULL,
                    byte_length INTEGER NOT NULL,
                    stored_at TEXT NOT NULL
                )
                """.trimIndent(),
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS derivative_normalized_conversation_idx " +
                    "ON derivative_normalized(conversation_native_id, normalized_at)",
            )
        }

        private fun requireSha(sourceSha256: String) {
            require(SHA256.matches(sourceSha256)) { "Expected lowercase SHA-256 source identity" }
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
