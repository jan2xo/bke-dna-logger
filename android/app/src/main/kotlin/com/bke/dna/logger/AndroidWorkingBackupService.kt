package com.bke.dna.logger

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Manual Working Data SQLite backup.
 *
 * A backup is one self-contained SQLite generation plus the small transitional
 * conversation-state companions required by the current reader/export layer.
 * Restore never merges/ATTACHes foreign SQLite into Latest; it creates a new
 * verified read-only Working Data generation through AndroidWorkingDataManager.
 */
class AndroidWorkingBackupService(context: Context) {
    private val appContext = context.applicationContext
    private val manager = AndroidWorkingDataManager(appContext)

    fun backupToUri(
        resolver: ContentResolver,
        destinationUri: Uri,
        generationId: String = AndroidWorkingDataManager.LATEST_ID,
    ): AndroidWorkingBackupResult {
        val generation = manager.generationForBackup(generationId)
        require(generation.rawEvidenceIncluded) {
            "This legacy Working Data generation is not self-contained in SQLite"
        }

        val summaries = manager.listConversations(generation)
        val entries = buildList {
            add(
                BackupEntry(
                    archivePath = SQLITE_PATH,
                    file = generation.databaseFile,
                    sha256 = backupSha256File(generation.databaseFile),
                    byteLength = generation.databaseFile.length(),
                ),
            )
            summaries.forEach { summary ->
                val state = File(generation.conversationStateDirectory, "${summary.conversationKey}.json")
                require(state.isFile) {
                    "Working Data lacks conversation state '${summary.conversationKey}'"
                }
                add(
                    BackupEntry(
                        archivePath = "$CONVERSATIONS_PATH/${state.name}",
                        file = state,
                        sha256 = backupSha256File(state),
                        byteLength = state.length(),
                    ),
                )
            }
        }.sortedBy { it.archivePath }

        val createdAt = Instant.now().toString()
        val manifest = JSONObject()
            .put("format", BACKUP_FORMAT)
            .put("formatVersion", BACKUP_VERSION)
            .put("createdAt", createdAt)
            .put("generationId", generation.id)
            .put("sqliteIncluded", true)
            .put("sqliteMergeAllowed", false)
            .put("restoreMode", "read_only_generation")
            .put("rawEvidenceIncluded", true)
            .put("conversationStateIncluded", true)
            .put("conversationCount", summaries.size)
            .put("fileCount", entries.size)
        generation.createdAt?.let { manifest.put("sourceCreatedAt", it) }
        manifest.put(
            "files",
            JSONArray(
                entries.map { entry ->
                    JSONObject()
                        .put("path", entry.archivePath)
                        .put("sha256", entry.sha256)
                        .put("byteLength", entry.byteLength)
                },
            ),
        )

        val manifestBytes = manifest.toString(2).toByteArray(Charsets.UTF_8)
        val checksums = sortedMapOf<String, String>()
        entries.forEach { checksums[it.archivePath] = it.sha256 }
        checksums[MANIFEST_PATH] = backupSha256Bytes(manifestBytes)
        val sums = (checksums.entries.joinToString("\n") { "${it.value}  ${it.key}" } + "\n")
            .toByteArray(Charsets.UTF_8)

        resolver.openOutputStream(destinationUri, "w")?.use { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { writeBackupFile(zip, it.archivePath, it.file) }
                writeBackupBytes(zip, MANIFEST_PATH, manifestBytes)
                writeBackupBytes(zip, CHECKSUMS_PATH, sums)
            }
        } ?: error("Unable to open Working Data backup destination")

        val verificationCopy = File(appContext.cacheDir, "working-backup-verify-${UUID.randomUUID()}.zip")
        try {
            resolver.openInputStream(destinationUri)?.use { input ->
                FileOutputStream(verificationCopy, false).use { output ->
                    input.copyTo(output, COPY_BUFFER_BYTES)
                    output.flush()
                    output.fd.sync()
                }
            } ?: error("Unable to re-open written Working Data backup")
            val verified = verifyBackup(verificationCopy)
            require(verified.conversationCount == summaries.size)
            require(verified.generationId == generation.id)
            return AndroidWorkingBackupResult(
                destinationUri = destinationUri.toString(),
                archiveSha256 = backupSha256File(verificationCopy),
                generationId = generation.id,
                fileCount = entries.size,
                byteCount = entries.sumOf { it.byteLength },
            )
        } finally {
            verificationCopy.delete()
        }
    }

    fun importFromUri(
        resolver: ContentResolver,
        sourceUri: Uri,
    ): AndroidWorkingBackupImportResult {
        val archive = File(appContext.cacheDir, "working-backup-import-${UUID.randomUUID()}.zip")
        val staging = File(appContext.cacheDir, "working-backup-stage-${UUID.randomUUID()}")
        try {
            resolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(archive, false).use { output ->
                    input.copyTo(output, COPY_BUFFER_BYTES)
                    output.flush()
                    output.fd.sync()
                }
            } ?: error("Unable to open Working Data backup")

            val verified = verifyBackup(archive)
            check(staging.mkdirs()) { "Unable to create Working Data restore staging" }
            val database = File(staging, SQLITE_PATH)
            val states = File(staging, CONVERSATIONS_PATH).also {
                check(it.mkdirs()) { "Unable to create restored conversation-state staging" }
            }

            ZipFile(archive).use { zip ->
                verified.files.forEach { item ->
                    val destination = when {
                        item.path == SQLITE_PATH -> database
                        item.path.startsWith("$CONVERSATIONS_PATH/") ->
                            File(states, item.path.removePrefix("$CONVERSATIONS_PATH/"))
                        else -> error("Unsupported verified Working Data backup path '${item.path}'")
                    }
                    extractVerified(zip, item, destination)
                }
            }

            val restored = manager.importReadOnlyGeneration(
                databaseSource = database,
                conversationStateSource = states,
                sourceCreatedAt = verified.sourceCreatedAt ?: verified.createdAt,
            )
            return AndroidWorkingBackupImportResult(
                generationId = restored.id,
                conversationCount = verified.conversationCount,
                restoredBytes = restored.snapshotBytes,
            )
        } finally {
            staging.deleteRecursively()
            archive.delete()
        }
    }

    private fun verifyBackup(archive: File): VerifiedBackup {
        require(archive.isFile) { "Working Data backup does not exist" }
        ZipFile(archive).use { zip ->
            val names = linkedSetOf<String>()
            val archiveEntries = zip.entries()
            while (archiveEntries.hasMoreElements()) {
                val entry = archiveEntries.nextElement()
                require(!entry.isDirectory) { "Working Data backup contains directory entries" }
                require(isSafeBackupPath(entry.name)) { "Unsafe Working Data backup path" }
                require(names.add(entry.name)) { "Duplicate Working Data backup path" }
            }

            val manifestEntry = zip.getEntry(MANIFEST_PATH) ?: error("Backup lacks $MANIFEST_PATH")
            val sumsEntry = zip.getEntry(CHECKSUMS_PATH) ?: error("Backup lacks $CHECKSUMS_PATH")
            val manifest = JSONObject(zip.getInputStream(manifestEntry).bufferedReader().use { it.readText() })
            require(manifest.getString("format") == BACKUP_FORMAT)
            require(manifest.getInt("formatVersion") == BACKUP_VERSION)
            require(manifest.getBoolean("sqliteIncluded")) { "Working Data backup must include SQLite" }
            require(!manifest.getBoolean("sqliteMergeAllowed")) { "Foreign SQLite merging must remain disabled" }
            require(manifest.getString("restoreMode") == "read_only_generation")
            require(manifest.getBoolean("rawEvidenceIncluded"))
            require(manifest.getBoolean("conversationStateIncluded"))

            val checksums = mutableMapOf<String, String>()
            zip.getInputStream(sumsEntry).bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() }.forEach { line ->
                    val split = line.indexOf("  ")
                    require(split == 64) { "Malformed Working Data checksum line" }
                    val sha = line.substring(0, split).lowercase()
                    val path = line.substring(split + 2)
                    require(sha.matches(SHA256_REGEX))
                    require(isSafeBackupPath(path))
                    require(checksums.put(path, sha) == null) { "Duplicate Working Data checksum" }
                }
            }
            require(names.filter { it != CHECKSUMS_PATH }.sorted() == checksums.keys.sorted()) {
                "Working Data backup checksum inventory mismatch"
            }
            checksums.forEach { (path, expected) ->
                val entry = zip.getEntry(path) ?: error("Working Data backup lacks '$path'")
                val actual = zip.getInputStream(entry).use(::backupSha256Stream)
                require(actual == expected) { "Working Data backup checksum mismatch for '$path'" }
            }

            val filesArray = manifest.getJSONArray("files")
            val files = buildList {
                for (index in 0 until filesArray.length()) {
                    val item = filesArray.getJSONObject(index)
                    val path = item.getString("path")
                    val sha = item.getString("sha256").lowercase()
                    val byteLength = item.getLong("byteLength")
                    require(isAllowedGenerationPath(path)) { "Unsupported Working Data backup path '$path'" }
                    require(checksums[path] == sha)
                    val entry = zip.getEntry(path) ?: error("Working Data backup lacks '$path'")
                    require(entry.size == byteLength) { "Working Data backup byte length mismatch for '$path'" }
                    add(VerifiedBackupFile(path, sha, byteLength))
                }
            }
            require(files.size == manifest.getInt("fileCount"))
            require(files.count { it.path == SQLITE_PATH } == 1) { "Working Data backup must contain one SQLite file" }
            require(files.count { it.path.startsWith("$CONVERSATIONS_PATH/") } == manifest.getInt("conversationCount"))
            require((files.map { it.path } + MANIFEST_PATH).sorted() == names.filter { it != CHECKSUMS_PATH }.sorted())

            return VerifiedBackup(
                generationId = manifest.getString("generationId"),
                createdAt = manifest.getString("createdAt"),
                sourceCreatedAt = manifest.optString("sourceCreatedAt").takeIf { it.isNotBlank() },
                conversationCount = manifest.getInt("conversationCount"),
                files = files,
            )
        }
    }

    private fun extractVerified(zip: ZipFile, item: VerifiedBackupFile, destination: File) {
        destination.parentFile?.let { check(it.exists() || it.mkdirs()) }
        val entry = zip.getEntry(item.path) ?: error("Working Data backup lacks '${item.path}'")
        zip.getInputStream(entry).use { input ->
            FileOutputStream(destination, false).use { output ->
                input.copyTo(output, COPY_BUFFER_BYTES)
                output.flush()
                output.fd.sync()
            }
        }
        require(destination.length() == item.byteLength)
        require(backupSha256File(destination) == item.sha256) {
            "Restored Working Data file checksum mismatch for '${item.path}'"
        }
    }

    private data class BackupEntry(
        val archivePath: String,
        val file: File,
        val sha256: String,
        val byteLength: Long,
    )

    private data class VerifiedBackup(
        val generationId: String,
        val createdAt: String,
        val sourceCreatedAt: String?,
        val conversationCount: Int,
        val files: List<VerifiedBackupFile>,
    )

    private data class VerifiedBackupFile(
        val path: String,
        val sha256: String,
        val byteLength: Long,
    )

    companion object {
        const val BACKUP_FORMAT = "bke-dna-working-sqlite-backup"
        const val BACKUP_VERSION = 2
        private const val SQLITE_PATH = "working.sqlite"
        private const val CONVERSATIONS_PATH = "conversations"
        private const val MANIFEST_PATH = "manifest.json"
        private const val CHECKSUMS_PATH = "SHA256SUMS"
        private const val COPY_BUFFER_BYTES = 128 * 1024
        private val SHA256_REGEX = Regex("[0-9a-f]{64}")
    }
}

object AndroidWorkingStorage {
    fun workingBytes(context: Context): Long {
        val root = AndroidDnaPaths.capturesRoot(context.applicationContext)
        val evidenceBytes = root.walkTopDown()
            .filter { it.isFile && "export-staging" !in it.path && "import-staging" !in it.path }
            .sumOf { it.length() }
        val database = context.applicationContext.getDatabasePath(AndroidCaptureIndex.DATABASE_NAME)
        val databaseBytes = listOf(
            database,
            File(database.path + "-wal"),
            File(database.path + "-shm"),
        ).filter { it.isFile }.sumOf { it.length() }
        return evidenceBytes + databaseBytes
    }

    fun shouldNotify(context: Context): Boolean =
        DnaReconciliationContract.shouldNotifyStorage(workingBytes(context))
}

data class AndroidWorkingBackupResult(
    val destinationUri: String,
    val archiveSha256: String,
    val generationId: String,
    val fileCount: Int,
    val byteCount: Long,
)

data class AndroidWorkingBackupImportResult(
    val generationId: String,
    val conversationCount: Int,
    val restoredBytes: Long,
)

private const val BACKUP_ZIP_TIME_MS = 315532800000L

private fun isSafeBackupPath(path: String): Boolean =
    path.isNotBlank() &&
        !path.startsWith('/') &&
        !path.startsWith('\\') &&
        !path.contains('\\') &&
        path.split('/').none { it.isBlank() || it == "." || it == ".." }

private fun isAllowedGenerationPath(path: String): Boolean {
    if (!isSafeBackupPath(path)) return false
    if (path == "working.sqlite") return true
    if (!path.startsWith("conversations/")) return false
    val name = path.removePrefix("conversations/")
    return '/' !in name && name.matches(Regex("[0-9a-f]{64}\\.json"))
}

private fun writeBackupFile(zip: ZipOutputStream, path: String, file: File) {
    require(isSafeBackupPath(path))
    val crc = CRC32()
    file.inputStream().use { input ->
        val buffer = ByteArray(128 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) crc.update(buffer, 0, count)
        }
    }
    val entry = ZipEntry(path).apply {
        method = ZipEntry.STORED
        size = file.length()
        compressedSize = size
        this.crc = crc.value
        time = BACKUP_ZIP_TIME_MS
    }
    zip.putNextEntry(entry)
    file.inputStream().use { it.copyTo(zip, 128 * 1024) }
    zip.closeEntry()
}

private fun writeBackupBytes(zip: ZipOutputStream, path: String, bytes: ByteArray) {
    require(isSafeBackupPath(path))
    val crc = CRC32().apply { update(bytes) }
    val entry = ZipEntry(path).apply {
        method = ZipEntry.STORED
        size = bytes.size.toLong()
        compressedSize = size
        this.crc = crc.value
        time = BACKUP_ZIP_TIME_MS
    }
    zip.putNextEntry(entry)
    zip.write(bytes)
    zip.closeEntry()
}

private fun backupSha256File(file: File): String = file.inputStream().use(::backupSha256Stream)
private fun backupSha256Bytes(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun backupSha256Stream(input: java.io.InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(128 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count > 0) digest.update(buffer, 0, count)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
