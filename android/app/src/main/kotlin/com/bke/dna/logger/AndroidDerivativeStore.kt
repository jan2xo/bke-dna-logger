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

    fun putNormalizedJson(sourceSha256: String, payloadJson: String) {
        check(writable) { "Normalized derivative write requires writable Working Data" }
        requireSha(sourceSha256)
        val root = JSONObject(payloadJson)
        require(root.getString("sourceSha256") == sourceSha256) {
            "Normalized derivative source identity mismatch"
        }
        val conversationNativeId = root.optString("conversationNativeId").trim()
        require(conversationNativeId.isNotBlank()) {
            "Normalized derivative lacks conversation identity"
        }
        val normalizedAt = root.getString("normalizedAt")
        putImmutablePayload(
            table = TABLE_NORMALIZED,
            sourceSha256 = sourceSha256,
            payloadJson = payloadJson,
            extraValues = ContentValues().apply {
                put("conversation_native_id", conversationNativeId)
                put("normalized_at", normalizedAt)
            },
        )
    }

    private fun putImmutablePayload(
        table: String,
        sourceSha256: String,
        payloadJson: String,
        extraValues: ContentValues,
    ) {
        val bytes = payloadJson.toByteArray(Charsets.UTF_8)
        val payloadSha256 = sha256(bytes)
        val existing = readPayload(table, sourceSha256)
        if (existing != null) {
            require(existing == payloadJson) {
                "Conflicting immutable derivative for source '$sourceSha256'"
            }
            return
        }

        val values = ContentValues(extraValues).apply {
            put("source_sha256", sourceSha256)
            put("payload_json", payloadJson)
            put("payload_sha256", payloadSha256)
            put("byte_length", bytes.size.toLong())
            put("stored_at", Instant.now().toString())
        }

        database.beginTransaction()
        try {
            database.insertOrThrow(table, null, values)
            val committed = readPayload(table, sourceSha256)
                ?: error("Derivative row was not readable inside transaction")
            require(committed == payloadJson) { "Derivative SQLite round-trip mismatch" }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    private fun readPayload(table: String, sourceSha256: String): String? {
        requireSha(sourceSha256)
        if (!tableExists(table)) return null

        val metadata = database.query(
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
            cursor.getString(0) to cursor.getLong(1)
        }

        // Android CursorWindow cannot materialize multi-megabyte TEXT rows on
        // many devices. Read only bounded substrings through the window, then
        // preserve the existing exact byte-length + SHA-256 verification over
        // the reconstructed serialized payload.
        val payload = buildString {
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
                if (chunk.isEmpty()) break
                append(chunk)
                payloadOffset += PAYLOAD_READ_CHUNK_CHARS
            }
        }

        val bytes = payload.toByteArray(Charsets.UTF_8)
        require(bytes.size.toLong() == metadata.second) {
            "Derivative SQLite byte-length mismatch"
        }
        require(sha256(bytes) == metadata.first) {
            "Derivative SQLite SHA-256 mismatch"
        }
        return payload
    }

    private fun tableExists(table: String): Boolean = database.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
        arrayOf(table),
    ).use { it.moveToFirst() }

    override fun close() = handle.close()

    companion object {
        private const val TABLE_CLASSIFICATION = "derivative_classification"
        private const val TABLE_NORMALIZED = "derivative_normalized"
        private const val PAYLOAD_READ_CHUNK_CHARS = 128 * 1024
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

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
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
