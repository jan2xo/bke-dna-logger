package com.bke.dna.logger

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * App-private Working Data generations.
 *
 * Latest is the only writable/live SQLite database. A rotation snapshots the
 * current SQLite projection plus the small logical conversation-state files,
 * verifies the snapshot, then starts a fresh Latest database. Raw bodies and
 * other immutable evidence remain shared by SHA under the capture root and are
 * deliberately not duplicated into every Working Data generation.
 */
class AndroidWorkingDataManager(context: Context) {
    private val appContext = context.applicationContext
    private val captureRoot = AndroidDnaPaths.capturesRoot(appContext)
    private val workingDataRoot = AndroidDnaPaths.workingDataRoot(appContext)

    fun listWorkingData(): List<AndroidWorkingDataGeneration> = buildList {
        add(latestGeneration())
        workingDataRoot.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.matches(GENERATION_ID_REGEX) }
            .mapNotNull(::readGeneration)
            .sortedByDescending { it.createdAt }
            .forEach(::add)
    }

    fun generation(id: String): AndroidWorkingDataGeneration {
        if (id == LATEST_ID) return latestGeneration()
        require(id.matches(GENERATION_ID_REGEX)) { "Invalid Working Data generation id" }
        return readGeneration(File(workingDataRoot, id))
            ?: error("Working Data generation does not exist")
    }

    /**
     * Save the current Latest generation and start a fresh writable Latest.
     * The caller must pause live capture before invoking this operation.
     */
    fun rotateLatest(): AndroidWorkingDataGeneration {
        val activeDatabase = appContext.getDatabasePath(AndroidCaptureIndex.DATABASE_NAME)
        ensureActiveDatabaseCreated()
        checkpointLatest()
        require(activeDatabase.isFile) { "Latest Working Data SQLite does not exist" }

        val createdAt = Instant.now()
        val id = GENERATION_FILE_FORMATTER.format(createdAt)
        val generationDirectory = File(workingDataRoot, id)
        check(!generationDirectory.exists()) { "Working Data generation already exists" }
        check(generationDirectory.mkdirs()) { "Unable to create Working Data generation" }

        try {
            val snapshotDatabase = File(generationDirectory, SNAPSHOT_DATABASE_NAME)
            copyDurably(activeDatabase, snapshotDatabase)
            val databaseSha256 = sha256File(snapshotDatabase)
            require(databaseSha256 == sha256File(activeDatabase)) {
                "Working Data SQLite snapshot verification failed"
            }

            val snapshotConversations = File(generationDirectory, SNAPSHOT_CONVERSATIONS_DIRECTORY)
            snapshotConversations.mkdirs()
            val liveConversations = File(captureRoot, "conversations")
            liveConversations.listFiles().orEmpty()
                .filter { it.isFile && it.extension == "json" }
                .sortedBy { it.name }
                .forEach { source ->
                    copyDurably(source, File(snapshotConversations, source.name))
                }

            val summaries = listConversationsFromDatabase(snapshotDatabase)
            val manifest = JSONObject()
                .put("format", WORKING_DATA_FORMAT)
                .put("formatVersion", 1)
                .put("generationId", id)
                .put("createdAt", createdAt.toString())
                .put("mode", "read_only_recovery")
                .put("sqliteFile", SNAPSHOT_DATABASE_NAME)
                .put("sqliteSha256", databaseSha256)
                .put("sqliteByteLength", snapshotDatabase.length())
                .put("conversationCount", summaries.size)
                .put("conversationStateIncluded", true)
                .put("rawEvidenceIncluded", false)
                .put("rawEvidenceSharedBySha", true)
            writeDurably(File(generationDirectory, MANIFEST_NAME), manifest.toString(2))

            snapshotDatabase.setReadOnly()
            snapshotConversations.listFiles().orEmpty().forEach(File::setReadOnly)
            File(generationDirectory, MANIFEST_NAME).setReadOnly()

            require(appContext.deleteDatabase(AndroidCaptureIndex.DATABASE_NAME)) {
                "Unable to retire Latest Working Data SQLite after verified snapshot"
            }
            ensureActiveDatabaseCreated()

            return readGeneration(generationDirectory)
                ?: error("Unable to reopen saved Working Data generation")
        } catch (error: Throwable) {
            generationDirectory.deleteRecursively()
            throw error
        }
    }

    fun listConversations(generation: AndroidWorkingDataGeneration): List<AndroidConversationSummary> {
        return if (generation.isLatest) {
            val index = AndroidCaptureIndex(appContext)
            try {
                index.listLogicalConversations()
            } finally {
                index.close()
            }
        } else {
            listConversationsFromDatabase(generation.databaseFile)
        }
    }

    private fun latestGeneration(): AndroidWorkingDataGeneration {
        ensureActiveDatabaseCreated()
        val database = appContext.getDatabasePath(AndroidCaptureIndex.DATABASE_NAME)
        return AndroidWorkingDataGeneration(
            id = LATEST_ID,
            label = "Latest — Active",
            createdAt = null,
            isLatest = true,
            isReadOnly = false,
            databaseFile = database,
            conversationStateDirectory = File(captureRoot, "conversations"),
            snapshotBytes = databaseRelatedBytes(database),
        )
    }

    private fun readGeneration(directory: File): AndroidWorkingDataGeneration? = runCatching {
        val manifestFile = File(directory, MANIFEST_NAME)
        val database = File(directory, SNAPSHOT_DATABASE_NAME)
        val states = File(directory, SNAPSHOT_CONVERSATIONS_DIRECTORY)
        require(manifestFile.isFile && database.isFile && states.isDirectory)
        val manifest = JSONObject(manifestFile.readText())
        require(manifest.getString("format") == WORKING_DATA_FORMAT)
        require(manifest.getInt("formatVersion") == 1)
        require(manifest.getString("generationId") == directory.name)
        require(manifest.getString("mode") == "read_only_recovery")
        require(manifest.getBoolean("rawEvidenceSharedBySha"))
        require(sha256File(database) == manifest.getString("sqliteSha256"))
        val createdAt = Instant.parse(manifest.getString("createdAt"))
        AndroidWorkingDataGeneration(
            id = directory.name,
            label = "${DISPLAY_FORMATTER.format(createdAt.atZone(ZoneId.systemDefault()))} — Read-only",
            createdAt = createdAt.toString(),
            isLatest = false,
            isReadOnly = true,
            databaseFile = database,
            conversationStateDirectory = states,
            snapshotBytes = directory.walkTopDown().filter { it.isFile }.sumOf { it.length() },
        )
    }.getOrNull()

    private fun checkpointLatest() {
        val index = AndroidCaptureIndex(appContext)
        try {
            val database = index.writableDatabase
            database.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
                while (cursor.moveToNext()) Unit
            }
        } finally {
            index.close()
        }
    }

    private fun ensureActiveDatabaseCreated() {
        val index = AndroidCaptureIndex(appContext)
        try {
            index.writableDatabase
        } finally {
            index.close()
        }
    }

    private fun listConversationsFromDatabase(databaseFile: File): List<AndroidConversationSummary> {
        require(databaseFile.isFile) { "Working Data SQLite does not exist" }
        val database = SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
        try {
            val cursor = database.query(
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
                return buildList {
                    while (it.moveToNext()) {
                        add(
                            AndroidConversationSummary(
                                conversationKey = it.getString(0),
                                conversationNativeId = it.getString(1),
                                stateObservedThrough = it.getString(2),
                                coverageStatus = it.getString(3),
                                sourceCount = it.getInt(4),
                                nodeCount = it.getInt(5),
                                dnaArchived = it.getInt(6) != 0,
                                archivedAt = if (it.isNull(7)) null else it.getString(7),
                            ),
                        )
                    }
                }
            }
        } finally {
            database.close()
        }
    }

    private fun copyDurably(source: File, destination: File) {
        destination.parentFile?.let { check(it.exists() || it.mkdirs()) }
        source.inputStream().use { input ->
            FileOutputStream(destination, false).use { output ->
                input.copyTo(output, 128 * 1024)
                output.flush()
                output.fd.sync()
            }
        }
    }

    private fun writeDurably(destination: File, text: String) {
        destination.parentFile?.let { check(it.exists() || it.mkdirs()) }
        FileOutputStream(destination, false).use { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
    }

    private fun databaseRelatedBytes(database: File): Long = listOf(
        database,
        File(database.path + "-wal"),
        File(database.path + "-shm"),
    ).filter { it.isFile }.sumOf { it.length() }

    private fun sha256File(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(128 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val LATEST_ID = "latest"
        private const val WORKING_DATA_FORMAT = "bke-dna-working-data"
        private const val SNAPSHOT_DATABASE_NAME = "working.sqlite"
        private const val SNAPSHOT_CONVERSATIONS_DIRECTORY = "conversations"
        private const val MANIFEST_NAME = "manifest.json"
        private val GENERATION_ID_REGEX = Regex("wd-[0-9]{8}T[0-9]{6}\\.[0-9]{3}Z")
        private val GENERATION_FILE_FORMATTER = DateTimeFormatter.ofPattern("'wd-'yyyyMMdd'T'HHmmss.SSS'Z'")
            .withZone(java.time.ZoneOffset.UTC)
        private val DISPLAY_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}

data class AndroidWorkingDataGeneration(
    val id: String,
    val label: String,
    val createdAt: String?,
    val isLatest: Boolean,
    val isReadOnly: Boolean,
    val databaseFile: File,
    val conversationStateDirectory: File,
    val snapshotBytes: Long,
)
