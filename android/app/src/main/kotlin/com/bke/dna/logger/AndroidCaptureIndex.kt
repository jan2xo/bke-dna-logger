package com.bke.dna.logger

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray

/**
 * Android live SQLite projection.
 *
 * Raw files and logical conversation JSON remain evidence/derivative files on
 * disk; SQLite exists for fast library/index queries and never replaces `.dna`
 * durability verification.
 */
class AndroidCaptureIndex(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        createCaptureSchema(db)
        createConversationSchema(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createConversationSchema(db)
        if (oldVersion == 2 && newVersion >= 3) upgradeDurabilitySchemaV3(db)
    }

    fun record(observation: JSONObjectObservation) {
        val values = ContentValues().apply {
            put("capture_id", observation.captureId)
            put("sha256", observation.sha256)
            put("byte_length", observation.byteLength)
            put("body_path", observation.bodyPath)
            put("page_url", observation.pageUrl)
            put("request_url", observation.requestUrl)
            put("method", observation.method)
            if (observation.status != null) put("status", observation.status)
            put("content_type", observation.contentType)
            put("initiator", observation.initiator)
            put("captured_at", observation.capturedAt)
            put("fidelity", observation.fidelity)
            put("stored_at", observation.storedAt)
            put("dna_archived", 0)
            putNull("dna_archive_id")
            putNull("dna_archive_sha256")
            putNull("archived_at")
        }
        writableDatabase.insertOrThrow("captures", null, values)
    }

    fun replaceLogicalConversation(
        conversation: AndroidLogicalConversation,
        statePath: String,
    ) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            deleteConversationChildren(db, conversation.conversationKey)

            val conversationValues = ContentValues().apply {
                put("conversation_key", conversation.conversationKey)
                put("conversation_native_id", conversation.conversationNativeId)
                put("current_node_native_id", conversation.currentNodeNativeId)
                put("state_observed_through", conversation.stateObservedThrough)
                put("coverage_status", conversation.coverageStatus)
                put("coverage_basis", conversation.coverageBasis)
                put("state_path", statePath)
                put("source_count", conversation.sources.size)
                put("node_count", conversation.nodes.size)
                // Any new/reconciled working state requires a fresh manual archive.
                put("dna_archived", 0)
                putNull("dna_archive_id")
                putNull("dna_archive_sha256")
                putNull("archived_at")
            }
            check(
                db.insertWithOnConflict(
                    "logical_conversation",
                    null,
                    conversationValues,
                    SQLiteDatabase.CONFLICT_REPLACE,
                ) != -1L,
            ) { "Unable to project Android logical conversation" }

            conversation.sources.forEach { source ->
                val values = ContentValues().apply {
                    put("conversation_key", conversation.conversationKey)
                    put("source_sha256", source.sourceSha256)
                    put("observed_at", source.observedAt)
                    put("current_node_native_id", source.currentNodeNativeId)
                    put("coverage_status", source.coverageStatus)
                    put("coverage_basis", source.coverageBasis)
                    put("dna_archived", 0)
                    putNull("dna_archive_id")
                    putNull("dna_archive_sha256")
                    putNull("archived_at")
                }
                db.insertOrThrow("conversation_source", null, values)
            }

            conversation.nodes.forEach { node ->
                val values = ContentValues().apply {
                    put("conversation_key", conversation.conversationKey)
                    put("node_native_id", node.nodeNativeId)
                    put("message_native_ids_json", JSONArray(node.messageNativeIds).toString())
                    put("parent_native_ids_json", JSONArray(node.parentNativeIds).toString())
                    put("child_native_ids_json", JSONArray(node.childNativeIds).toString())
                    put("roles_json", JSONArray(node.roles).toString())
                    put("created_at_values_json", JSONArray(node.createdAtValues).toString())
                }
                db.insertOrThrow("logical_message_node", null, values)
            }

            conversation.nodes.forEach { node ->
                node.childNativeIds.forEach { childNativeId ->
                    val values = ContentValues().apply {
                        put("conversation_key", conversation.conversationKey)
                        put("parent_node_native_id", node.nodeNativeId)
                        put("child_node_native_id", childNativeId)
                    }
                    db.insertWithOnConflict(
                        "logical_message_edge",
                        null,
                        values,
                        SQLiteDatabase.CONFLICT_IGNORE,
                    )
                }

                node.revisions.forEach { revision ->
                    val revisionValues = ContentValues().apply {
                        put("conversation_key", conversation.conversationKey)
                        put("node_native_id", node.nodeNativeId)
                        put("revision_sha256", revision.revisionSha256)
                        put("content_json", revision.contentJson)
                        put("text_parts_json", JSONArray(revision.textParts).toString())
                        put("first_observed_at", revision.firstObservedAt)
                        put("last_observed_at", revision.lastObservedAt)
                    }
                    db.insertOrThrow("logical_message_revision", null, revisionValues)

                    revision.sourceSha256s.forEach { sourceSha256 ->
                        val sourceValues = ContentValues().apply {
                            put("conversation_key", conversation.conversationKey)
                            put("node_native_id", node.nodeNativeId)
                            put("revision_sha256", revision.revisionSha256)
                            put("source_sha256", sourceSha256)
                        }
                        db.insertOrThrow("logical_message_revision_source", null, sourceValues)
                    }
                }
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Durability gate: called only after a manual .dna destination was written,
     * re-read, and verified. Imported evidence can have no local capture row,
     * so conversation_source is the authoritative per-source durability set.
     */
    fun recordVerifiedConversationArchive(
        conversationKey: String,
        sourceSha256s: List<String>,
        archiveId: String,
        archiveSha256: String,
        archivedAt: String,
    ) {
        require(sourceSha256s.isNotEmpty())
        val db = writableDatabase
        db.beginTransaction()
        try {
            var sourceRows = 0
            sourceSha256s.distinct().forEach { sourceSha256 ->
                val values = ContentValues().apply {
                    put("dna_archived", 1)
                    put("dna_archive_id", archiveId)
                    put("dna_archive_sha256", archiveSha256)
                    put("archived_at", archivedAt)
                }
                sourceRows += db.update(
                    "conversation_source",
                    values,
                    "conversation_key = ? AND source_sha256 = ?",
                    arrayOf(conversationKey, sourceSha256),
                )
                // Local captures may not exist for evidence imported from another device.
                db.update(
                    "captures",
                    values,
                    "sha256 = ?",
                    arrayOf(sourceSha256),
                )
            }
            require(sourceRows == sourceSha256s.distinct().size) {
                "Verified conversation .dna did not cover every logical source row"
            }

            val conversationValues = ContentValues().apply {
                put("dna_archived", 1)
                put("dna_archive_id", archiveId)
                put("dna_archive_sha256", archiveSha256)
                put("archived_at", archivedAt)
            }
            require(
                db.update(
                    "logical_conversation",
                    conversationValues,
                    "conversation_key = ?",
                    arrayOf(conversationKey),
                ) == 1,
            ) { "Verified conversation .dna could not update logical durability state" }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun listLogicalConversations(): List<AndroidConversationSummary> {
        val cursor = readableDatabase.query(
            "logical_conversation",
            arrayOf(
                "conversation_key",
                "conversation_native_id",
                "state_observed_through",
                "coverage_status",
                "source_count",
                "node_count",
                "dna_archived",
                "archived_at",
            ),
            null,
            null,
            null,
            null,
            "state_observed_through DESC, conversation_key ASC",
        )
        cursor.use {
            val result = mutableListOf<AndroidConversationSummary>()
            while (it.moveToNext()) {
                result += AndroidConversationSummary(
                    conversationKey = it.getString(0),
                    conversationNativeId = it.getString(1),
                    stateObservedThrough = it.getString(2),
                    coverageStatus = it.getString(3),
                    sourceCount = it.getInt(4),
                    nodeCount = it.getInt(5),
                    dnaArchived = it.getInt(6) != 0,
                    archivedAt = if (it.isNull(7)) null else it.getString(7),
                )
            }
            return result
        }
    }

    private fun deleteConversationChildren(db: SQLiteDatabase, conversationKey: String) {
        listOf(
            "logical_message_revision_source",
            "logical_message_revision",
            "logical_message_edge",
            "logical_message_node",
            "conversation_source",
        ).forEach { table ->
            db.delete(table, "conversation_key = ?", arrayOf(conversationKey))
        }
    }

    private fun createCaptureSchema(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS captures (
                capture_id TEXT PRIMARY KEY,
                sha256 TEXT NOT NULL,
                byte_length INTEGER NOT NULL,
                body_path TEXT NOT NULL,
                page_url TEXT,
                request_url TEXT,
                method TEXT,
                status INTEGER,
                content_type TEXT,
                initiator TEXT,
                captured_at TEXT,
                fidelity TEXT,
                stored_at TEXT NOT NULL,
                dna_archived INTEGER NOT NULL DEFAULT 0,
                dna_archive_id TEXT,
                dna_archive_sha256 TEXT,
                archived_at TEXT
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS captures_sha256_idx ON captures(sha256)")
    }

    private fun createConversationSchema(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS logical_conversation (
                conversation_key TEXT PRIMARY KEY,
                conversation_native_id TEXT NOT NULL UNIQUE,
                current_node_native_id TEXT,
                state_observed_through TEXT NOT NULL,
                coverage_status TEXT NOT NULL,
                coverage_basis TEXT NOT NULL,
                state_path TEXT NOT NULL,
                source_count INTEGER NOT NULL,
                node_count INTEGER NOT NULL,
                dna_archived INTEGER NOT NULL DEFAULT 0,
                dna_archive_id TEXT,
                dna_archive_sha256 TEXT,
                archived_at TEXT
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS conversation_source (
                conversation_key TEXT NOT NULL,
                source_sha256 TEXT NOT NULL,
                observed_at TEXT NOT NULL,
                current_node_native_id TEXT,
                coverage_status TEXT NOT NULL,
                coverage_basis TEXT NOT NULL,
                dna_archived INTEGER NOT NULL DEFAULT 0,
                dna_archive_id TEXT,
                dna_archive_sha256 TEXT,
                archived_at TEXT,
                PRIMARY KEY (conversation_key, source_sha256)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS conversation_source_sha_idx ON conversation_source(source_sha256)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS logical_message_node (
                conversation_key TEXT NOT NULL,
                node_native_id TEXT NOT NULL,
                message_native_ids_json TEXT NOT NULL,
                parent_native_ids_json TEXT NOT NULL,
                child_native_ids_json TEXT NOT NULL,
                roles_json TEXT NOT NULL,
                created_at_values_json TEXT NOT NULL,
                PRIMARY KEY (conversation_key, node_native_id)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS logical_message_edge (
                conversation_key TEXT NOT NULL,
                parent_node_native_id TEXT NOT NULL,
                child_node_native_id TEXT NOT NULL,
                PRIMARY KEY (conversation_key, parent_node_native_id, child_node_native_id)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS logical_message_revision (
                conversation_key TEXT NOT NULL,
                node_native_id TEXT NOT NULL,
                revision_sha256 TEXT NOT NULL,
                content_json TEXT NOT NULL,
                text_parts_json TEXT NOT NULL,
                first_observed_at TEXT NOT NULL,
                last_observed_at TEXT NOT NULL,
                PRIMARY KEY (conversation_key, node_native_id, revision_sha256)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS logical_message_revision_source (
                conversation_key TEXT NOT NULL,
                node_native_id TEXT NOT NULL,
                revision_sha256 TEXT NOT NULL,
                source_sha256 TEXT NOT NULL,
                PRIMARY KEY (conversation_key, node_native_id, revision_sha256, source_sha256)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS logical_revision_source_sha_idx ON logical_message_revision_source(source_sha256)",
        )
    }

    private fun upgradeDurabilitySchemaV3(db: SQLiteDatabase) {
        listOf(
            "ALTER TABLE captures ADD COLUMN dna_archive_id TEXT",
            "ALTER TABLE captures ADD COLUMN dna_archive_sha256 TEXT",
            "ALTER TABLE captures ADD COLUMN archived_at TEXT",
            "ALTER TABLE logical_conversation ADD COLUMN dna_archive_id TEXT",
            "ALTER TABLE logical_conversation ADD COLUMN dna_archive_sha256 TEXT",
            "ALTER TABLE logical_conversation ADD COLUMN archived_at TEXT",
            "ALTER TABLE conversation_source ADD COLUMN dna_archived INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE conversation_source ADD COLUMN dna_archive_id TEXT",
            "ALTER TABLE conversation_source ADD COLUMN dna_archive_sha256 TEXT",
            "ALTER TABLE conversation_source ADD COLUMN archived_at TEXT",
        ).forEach(db::execSQL)
    }

    companion object {
        const val DATABASE_NAME = "bke-dna-live.db"
        private const val DATABASE_VERSION = 3
    }
}

data class AndroidConversationSummary(
    val conversationKey: String,
    val conversationNativeId: String,
    val stateObservedThrough: String,
    val coverageStatus: String,
    val sourceCount: Int,
    val nodeCount: Int,
    val dnaArchived: Boolean,
    val archivedAt: String?,
)

data class JSONObjectObservation(
    val captureId: String,
    val sha256: String,
    val byteLength: Long,
    val bodyPath: String,
    val pageUrl: String?,
    val requestUrl: String?,
    val method: String?,
    val status: Int?,
    val contentType: String?,
    val initiator: String?,
    val capturedAt: String?,
    val fidelity: String?,
    val storedAt: String,
)
