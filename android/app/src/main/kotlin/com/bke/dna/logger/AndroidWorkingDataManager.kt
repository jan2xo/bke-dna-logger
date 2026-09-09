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
import java.util.UUID

/**
 * App-private Working Data generations.
 *
 * Latest is the only writable/live SQLite database. Saved generations are
 * verified read-only recovery sources. New generations are self-contained:
 * exact RAW plus derivatives/logical rows live in their SQLite snapshot, while
 * the small logical conversation-state JSON remains transitional companion data.
 */
class AndroidWorkingDataManager(context: Context) {
    private val appContext = context.applicationContext
    private val captureRoot = AndroidDnaPaths.capturesRoot(appContext)
    private val workingDataRoot = AndroidDnaPaths.workingDataRoot(appContext)

    /** Lightweight enumeration for the unified library: no full-file SHA scan. */
    fun listWorkingData(): List<AndroidWorkingDataGeneration> = buildList {
        add(latestGeneration())
        workingDataRoot.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.matches(GENERATION_ID_REGEX) }
            .mapNotNull { readGeneration(it, verify = false) }
            .sortedByDescending { it.createdAt }
            .forEach(::add)
    }

    /** Explicit generation access verifies the saved SQLite before recovery use. */
    fun generation(id: String): AndroidWorkingDataGeneration {
        if (id == LATEST_ID) return latestGeneration()
        require(id.matches(GENERATION_ID_REGEX)) { "Invalid Working Data generation id" }
        return readGeneration(File(workingDataRoot, id), verify = true)
            ?: error("Working Data generation does not exist or failed verification")
    }

    /**
     * Return a generation safe to copy into a backup.
     *
     * The caller must hold the storage-mutation pause when backing up Latest so
     * no capture/derivation write can race the WAL checkpoint and subsequent copy.
     */
    fun generationForBackup(id: String): AndroidWorkingDataGeneration {
        if (id != LATEST_ID) return generation(id)
        ensureActiveDatabaseCreated()
        checkpointLatest()
        return latestGeneration()
    }

    fun savedWorkingDataBytes(): Long = workingDataRoot.walkTopDown()
        .filter { it.isFile }
        .sumOf { it.length() }

    /**
     * Save the current Latest generation and start a fresh writable Latest.
     * The caller must pause live capture/background derivation before invoking.
     */
    fun rotateLatest(): AndroidWorkingDataGeneration {
        val activeDatabase = appContext.getDatabasePath(AndroidCaptureIndex.DATABASE_NAME)
        ensureActiveDatabaseCreated()
        checkpointLatest()
        require(activeDatabase.isFile) { "Latest Working Data SQLite does not exist" }

        val createdAt = Instant.now()
        val id = nextGenerationId(createdAt)
        val generationDirectory = File(workingDataRoot, id)
        check(generationDirectory.mkdirs()) { "Unable to create Working Data generation" }

        var latestRetired = false
        try {
            val snapshotDatabase = File(generationDirectory, SNAPSHOT_DATABASE_NAME)
            copyDurably(activeDatabase, snapshotDatabase)
            val databaseSha256 = sha256File(snapshotDatabase)
            require(databaseSha256 == sha256File(activeDatabase)) {
                "Working Data SQLite snapshot verification failed"
            }

            val summaries = listConversationsFromDatabase(snapshotDatabase)
            val snapshotConversations = File(generationDirectory, SNAPSHOT_CONVERSATIONS_DIRECTORY)
            check(snapshotConversations.mkdirs()) { "Unable to create Working Data conversation-state snapshot" }
            val liveConversations = File(captureRoot, "conversations")
            summaries.forEach { summary ->
                val source = File(liveConversations, "${summary.conversationKey}.json")
                require(source.isFile) {
                    "Latest Working Data indexes conversation '${summary.conversationKey}' without a state file"
                }
                copyDurably(source, File(snapshotConversations, source.name))
            }

            writeGenerationManifest(
                directory = generationDirectory,
                id = id,
                createdAt = createdAt,
                database = snapshotDatabase,
                databaseSha256 = databaseSha256,
                conversationCount = summaries.size,
                restoredFromBackup = false,
                sourceCreatedAt = null,
            )

            val verifiedGeneration = readGeneration(generationDirectory, verify = true)
                ?: error("Unable to verify saved Working Data generation")
            makeGenerationReadOnly(generationDirectory)

            require(appContext.deleteDatabase(AndroidCaptureIndex.DATABASE_NAME)) {
                "Unable to retire Latest Working Data SQLite after verified snapshot"
            }
            latestRetired = true
            ensureActiveDatabaseCreated()

            return verifiedGeneration.copy(
                snapshotBytes = generationDirectory.walkTopDown().filter { it.isFile }.sumOf { it.length() },
            )
        } catch (error: Throwable) {
            if (!latestRetired) deleteDirectoryBestEffort(generationDirectory)
            throw error
        }
    }

    /**
     * Restore a verified SQLite backup as a separate read-only generation.
     * Foreign/backup SQLite is never ATTACHed to or merged into Latest.
     */
    fun importReadOnlyGeneration(
        databaseSource: File,
        conversationStateSource: File,
        sourceCreatedAt: String?,
    ): AndroidWorkingDataGeneration {
        require(databaseSource.isFile) { "Working Data backup lacks SQLite" }
        require(conversationStateSource.isDirectory) { "Working Data backup lacks conversation state" }
        require(databaseHasSelfContainedRaw(databaseSource)) {
            "Working Data backup SQLite is not self-contained RAW evidence"
        }

        val sourceSummaries = listConversationsFromDatabase(databaseSource)
        sourceSummaries.forEach { summary ->
            require(File(conversationStateSource, "${summary.conversationKey}.json").isFile) {
                "Working Data backup lacks state for '${summary.conversationKey}'"
            }
        }

        val createdAt = Instant.now()
        val id = nextGenerationId(createdAt)
        val finalDirectory = File(workingDataRoot, id)
        val stagingDirectory = File(workingDataRoot, ".import-${UUID.randomUUID()}")
        check(stagingDirectory.mkdirs()) { "Unable to create Working Data restore staging" }

        var promoted = false
        try {
            val database = File(stagingDirectory, SNAPSHOT_DATABASE_NAME)
            copyDurably(databaseSource, database)
            val databaseSha256 = sha256File(database)
            require(databaseSha256 == sha256File(databaseSource)) {
                "Restored Working Data SQLite copy verification failed"
            }

            val states = File(stagingDirectory, SNAPSHOT_CONVERSATIONS_DIRECTORY)
            check(states.mkdirs()) { "Unable to create restored conversation-state directory" }
            sourceSummaries.forEach { summary ->
                val source = File(conversationStateSource, "${summary.conversationKey}.json")
                copyDurably(source, File(states, source.name))
            }

            writeGenerationManifest(
                directory = stagingDirectory,
                id = id,
                createdAt = createdAt,
                database = database,
                databaseSha256 = databaseSha256,
                conversationCount = sourceSummaries.size,
                restoredFromBackup = true,
                sourceCreatedAt = sourceCreatedAt,
            )

            require(databaseHasSelfContainedRaw(database)) {
                "Restored Working Data lost self-contained RAW evidence"
            }
            require(listConversationsFromDatabase(database).size == sourceSummaries.size) {
                "Restored Working Data conversation verification failed"
            }
            makeGenerationReadOnly(stagingDirectory)

            check(!finalDirectory.exists()) { "Restored Working Data generation already exists" }
            check(stagingDirectory.renameTo(finalDirectory)) {
                "Unable to promote verified Working Data restore"
            }
            promoted = true

            return readGeneration(finalDirectory, verify = true)
                ?: error("Promoted Working Data restore failed final verification")
        } catch (error: Throwable) {
            if (promoted) deleteDirectoryBestEffort(finalDirectory) else deleteDirectoryBestEffort(stagingDirectory)
            throw error
        }
    }

    /** Delete one saved read-only generation. Latest can never be deleted here. */
    fun deleteSavedGeneration(id: String, confirmation: String): AndroidWorkingDataDeleteResult {
        require(confirmation == DELETE_CONFIRMATION_TEXT) {
            "Type exact confirmation '$DELETE_CONFIRMATION_TEXT'"
        }
        require(id != LATEST_ID) { "Latest Working Data cannot be deleted" }
        val verified = generation(id)
        require(verified.isReadOnly && !verified.isLatest) { "Only saved Working Data can be deleted" }

        val directory = File(workingDataRoot, id)
        val bytes = directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        makeWritableRecursively(directory)
        check(directory.deleteRecursively() && !directory.exists()) {
            "Unable to delete saved Working Data '$id'"
        }
        return AndroidWorkingDataDeleteResult(
            generationId = id,
            bytesReclaimed = bytes,
            deletedAt = Instant.now().toString(),
        )
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
            rawEvidenceIncluded = true,
        )
    }

    private fun readGeneration(
        directory: File,
        verify: Boolean,
    ): AndroidWorkingDataGeneration? = runCatching {
        val manifestFile = File(directory, MANIFEST_NAME)
        val database = File(directory, SNAPSHOT_DATABASE_NAME)
        val states = File(directory, SNAPSHOT_CONVERSATIONS_DIRECTORY)
        require(manifestFile.isFile && database.isFile && states.isDirectory)
        val manifest = JSONObject(manifestFile.readText())
        require(manifest.getString("format") == WORKING_DATA_FORMAT)
        require(manifest.getInt("formatVersion") == 1)
        require(manifest.getString("generationId") == directory.name)
        require(manifest.getString("mode") == "read_only_recovery")
        require(manifest.getBoolean("conversationStateIncluded"))

        val rawEvidenceIncluded = manifest.optBoolean("rawEvidenceIncluded", false)
        val rawEvidenceSharedBySha = manifest.optBoolean("rawEvidenceSharedBySha", !rawEvidenceIncluded)
        require(rawEvidenceIncluded || rawEvidenceSharedBySha) {
            "Working Data generation exposes no RAW evidence recovery route"
        }

        if (verify) {
            require(sha256File(database) == manifest.getString("sqliteSha256"))
            val summaries = listConversationsFromDatabase(database)
            require(summaries.size == manifest.getInt("conversationCount"))
            summaries.forEach { summary ->
                require(File(states, "${summary.conversationKey}.json").isFile)
            }
            if (rawEvidenceIncluded) {
                require(databaseHasSelfContainedRaw(database)) {
                    "Working Data declares SQLite RAW but is not self-contained"
                }
            }
        }

        val createdAt = Instant.parse(manifest.getString("createdAt"))
        AndroidWorkingDataGeneration(
            id = directory.name,
            label = "${DISPLAY_FORMATTER.format(createdAt.atZone(ZoneId.systemDefault()))} — Read-only",
            createdAt = createdAt.toString(),
            isLatest = false,
            isReadOnly = true,
            databaseFile = database,
            conversationStateDirectory = states,
            snapshotBytes = manifest.optLong("sqliteByteLength", database.length()) +
                states.listFiles().orEmpty().filter { it.isFile }.sumOf { it.length() } + manifestFile.length(),
            rawEvidenceIncluded = rawEvidenceIncluded,
        )
    }.getOrNull()

    private fun writeGenerationManifest(
        directory: File,
        id: String,
        createdAt: Instant,
        database: File,
        databaseSha256: String,
        conversationCount: Int,
        restoredFromBackup: Boolean,
        sourceCreatedAt: String?,
    ) {
        val manifest = JSONObject()
            .put("format", WORKING_DATA_FORMAT)
            .put("formatVersion", 1)
            .put("generationId", id)
            .put("createdAt", createdAt.toString())
            .put("mode", "read_only_recovery")
            .put("sqliteFile", SNAPSHOT_DATABASE_NAME)
            .put("sqliteSha256", databaseSha256)
            .put("sqliteByteLength", database.length())
            .put("conversationCount", conversationCount)
            .put("conversationStateIncluded", true)
            .put("rawEvidenceIncluded", true)
            .put("rawEvidenceSharedBySha", false)
            .put("restoredFromBackup", restoredFromBackup)
        if (!sourceCreatedAt.isNullOrBlank()) {
            manifest.put("sourceCreatedAt", sourceCreatedAt)
        }
        writeDurably(File(directory, MANIFEST_NAME), manifest.toString(2))
    }

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

    private fun databaseHasSelfContainedRaw(databaseFile: File): Boolean {
        if (!databaseFile.isFile) return false
        return runCatching {
            SQLiteDatabase.openDatabase(
                databaseFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { database ->
                if (!hasTable(database, "raw_source") || !hasTable(database, "raw_source_chunk")) {
                    return@use false
                }
                database.rawQuery(
                    """
                    SELECT COUNT(*)
                    FROM conversation_source cs
                    LEFT JOIN raw_source rs ON rs.source_sha256 = cs.source_sha256
                    WHERE rs.source_sha256 IS NULL
                    """.trimIndent(),
                    null,
                ).use { cursor ->
                    cursor.moveToFirst() && cursor.getLong(0) == 0L
                }
            }
        }.getOrDefault(false)
    }

    private fun listConversationsFromDatabase(databaseFile: File): List<AndroidConversationSummary> {
        require(databaseFile.isFile) { "Working Data SQLite does not exist" }
        val database = SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
        try {
            val hasDisplayTitle = hasColumn(database, "logical_conversation", "display_title")
            val cursor = database.query(
                "logical_conversation",
                arrayOf(
                    "conversation_key",
                    "conversation_native_id",
                    if (hasDisplayTitle) "display_title" else "NULL AS display_title",
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
                        val nativeId = it.getString(1)
                        add(
                            AndroidConversationSummary(
                                conversationKey = it.getString(0),
                                conversationNativeId = nativeId,
                                displayTitle = if (it.isNull(2)) nativeId else it.getString(2),
                                stateObservedThrough = it.getString(3),
                                coverageStatus = it.getString(4),
                                sourceCount = it.getInt(5),
                                nodeCount = it.getInt(6),
                                dnaArchived = it.getInt(7) != 0,
                                archivedAt = if (it.isNull(8)) null else it.getString(8),
                            ),
                        )
                    }
                }
            }
        } finally {
            database.close()
        }
    }

    private fun hasColumn(database: SQLiteDatabase, table: String, column: String): Boolean {
        database.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == column) return true
            }
        }
        return false
    }

    private fun hasTable(database: SQLiteDatabase, table: String): Boolean = database.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
        arrayOf(table),
    ).use { it.moveToFirst() }

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

    private fun makeGenerationReadOnly(directory: File) {
        directory.walkBottomUp().filter { it.isFile }.forEach(File::setReadOnly)
    }

    private fun makeWritableRecursively(directory: File) {
        directory.walkTopDown().forEach { path ->
            if (path.isDirectory) path.setWritable(true, true) else path.setWritable(true, true)
        }
    }

    private fun deleteDirectoryBestEffort(directory: File) {
        if (!directory.exists()) return
        makeWritableRecursively(directory)
        directory.deleteRecursively()
    }

    private fun nextGenerationId(seed: Instant): String {
        var candidate = seed
        while (true) {
            val id = GENERATION_FILE_FORMATTER.format(candidate)
            if (!File(workingDataRoot, id).exists()) return id
            candidate = candidate.plusMillis(1)
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
        const val DELETE_CONFIRMATION_TEXT = "jan2x"
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
    val rawEvidenceIncluded: Boolean = true,
)

data class AndroidWorkingDataDeleteResult(
    val generationId: String,
    val bytesReclaimed: Long,
    val deletedAt: String,
)
