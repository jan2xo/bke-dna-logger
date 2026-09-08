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
 * Manual working-evidence backup.
 *
 * SQLite is deliberately excluded: it is a rebuildable local projection and
 * cross-device SQLite merging is forbidden. Backups contain the evidence needed
 * to rebuild normalized/logical state on another BKE DNA Logger installation.
 */
class AndroidWorkingBackupService(context: Context) {
    private val appContext = context.applicationContext
    private val captureRoot = AndroidDnaPaths.capturesRoot(appContext)

    fun backupToUri(resolver: ContentResolver, destinationUri: Uri): AndroidWorkingBackupResult {
        val files = collectEvidenceFiles()
        val entries = files.map { file ->
            val relative = file.relativeTo(captureRoot).invariantSeparatorsPath
            BackupEntry(
                archivePath = "working/$relative",
                file = file,
                sha256 = backupSha256File(file),
                byteLength = file.length(),
            )
        }.sortedBy { it.archivePath }

        val manifest = JSONObject()
            .put("format", BACKUP_FORMAT)
            .put("formatVersion", 1)
            .put("createdAt", Instant.now().toString())
            .put("sqliteIncluded", false)
            .put("sqliteMergeAllowed", DnaReconciliationContract.MERGE_SQLITE_ACROSS_DEVICES)
            .put("fileCount", entries.size)
            .put(
                "files",
                JSONArray(
                    entries.map {
                        JSONObject()
                            .put("path", it.archivePath)
                            .put("sha256", it.sha256)
                            .put("byteLength", it.byteLength)
                    },
                ),
            )
        val manifestBytes = manifest.toString(2).toByteArray(Charsets.UTF_8)
        val checksums = sortedMapOf<String, String>()
        entries.forEach { checksums[it.archivePath] = it.sha256 }
        checksums["manifest.json"] = backupSha256Bytes(manifestBytes)
        val sums = (checksums.entries.joinToString("\n") { "${it.value}  ${it.key}" } + "\n")
            .toByteArray(Charsets.UTF_8)

        resolver.openOutputStream(destinationUri, "w")?.let { rawOutput ->
            rawOutput.use { output ->
                ZipOutputStream(output).use { zip ->
                    entries.forEach { writeBackupFile(zip, it.archivePath, it.file) }
                    writeBackupBytes(zip, "manifest.json", manifestBytes)
                    writeBackupBytes(zip, "SHA256SUMS", sums)
                }
            }
        } ?: error("Unable to open backup destination")

        val archiveSha256 = resolver.openInputStream(destinationUri)?.use(::backupSha256Stream)
            ?: error("Unable to verify written backup")
        return AndroidWorkingBackupResult(
            destinationUri = destinationUri.toString(),
            archiveSha256 = archiveSha256,
            fileCount = entries.size,
            byteCount = entries.sumOf { it.byteLength },
        )
    }

    fun importFromUri(resolver: ContentResolver, sourceUri: Uri): AndroidWorkingBackupImportResult {
        val temp = File(appContext.cacheDir, "dna-backup-import-${UUID.randomUUID()}.zip")
        try {
            resolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(temp, false).use { output ->
                    input.copyTo(output, 128 * 1024)
                    output.flush()
                    output.fd.sync()
                }
            } ?: error("Unable to open working backup")

            val verified = verifyBackup(temp)
            val staging = File(captureRoot, "import-staging/backup-${UUID.randomUUID()}")
            check(staging.mkdirs()) { "Unable to create backup import staging directory" }
            try {
                val staged = mutableListOf<StagedBackupFile>()
                ZipFile(temp).use { zip ->
                    verified.files.forEach { item ->
                        val relative = item.path.removePrefix("working/")
                        require(isAllowedEvidenceRelativePath(relative)) {
                            "Working backup contains unsupported evidence path '$relative'"
                        }
                        val stage = File(staging, UUID.randomUUID().toString())
                        zip.getInputStream(zip.getEntry(item.path)).use { input ->
                            FileOutputStream(stage, false).use { output ->
                                input.copyTo(output, 128 * 1024)
                                output.flush()
                                output.fd.sync()
                            }
                        }
                        require(backupSha256File(stage) == item.sha256)
                        staged += StagedBackupFile(relative, stage, item.sha256)
                    }
                }

                var imported = 0
                var deduplicated = 0
                staged.sortedBy { it.relativePath }.forEach { item ->
                    val target = File(captureRoot, item.relativePath).canonicalFile
                    val root = captureRoot.canonicalFile
                    require(target.path.startsWith(root.path + File.separator)) {
                        "Working backup target escaped capture root"
                    }
                    target.parentFile?.let { check(it.exists() || it.mkdirs()) }
                    if (target.exists()) {
                        require(backupSha256File(target) == item.sha256) {
                            "Existing local evidence conflicts with backup '${item.relativePath}'"
                        }
                        item.stage.delete()
                        deduplicated += 1
                    } else {
                        check(item.stage.renameTo(target)) { "Unable to promote backup evidence" }
                        imported += 1
                    }
                }

                val conversations = AndroidConversationAggregationEngine(appContext).use { it.aggregateAll() }
                return AndroidWorkingBackupImportResult(
                    importedFileCount = imported,
                    deduplicatedFileCount = deduplicated,
                    conversationCount = conversations.size,
                )
            } finally {
                staging.deleteRecursively()
            }
        } finally {
            temp.delete()
        }
    }

    private fun collectEvidenceFiles(): List<File> = EVIDENCE_DIRECTORIES.flatMap { directoryName ->
        val directory = File(captureRoot, directoryName)
        if (!directory.isDirectory) emptyList() else directory.walkTopDown()
            .filter { it.isFile }
            .toList()
    }

    private fun verifyBackup(archive: File): VerifiedBackup {
        ZipFile(archive).use { zip ->
            val names = linkedSetOf<String>()
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                require(!entry.isDirectory)
                require(isSafeBackupPath(entry.name))
                require(names.add(entry.name)) { "Duplicate working backup path" }
            }
            val manifestEntry = zip.getEntry("manifest.json") ?: error("Backup lacks manifest.json")
            val sumsEntry = zip.getEntry("SHA256SUMS") ?: error("Backup lacks SHA256SUMS")
            val manifest = JSONObject(zip.getInputStream(manifestEntry).bufferedReader().use { it.readText() })
            require(manifest.getString("format") == BACKUP_FORMAT)
            require(manifest.getInt("formatVersion") == 1)
            require(!manifest.getBoolean("sqliteIncluded")) {
                "Cross-device working backups must not import SQLite"
            }
            require(!manifest.getBoolean("sqliteMergeAllowed")) {
                "Cross-device SQLite merging must remain disabled"
            }

            val checksums = mutableMapOf<String, String>()
            zip.getInputStream(sumsEntry).bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() }.forEach { line ->
                    val split = line.indexOf("  ")
                    require(split == 64)
                    val sha = line.substring(0, split).lowercase()
                    val path = line.substring(split + 2)
                    require(sha.matches(Regex("[0-9a-f]{64}")))
                    require(isSafeBackupPath(path))
                    require(checksums.put(path, sha) == null)
                }
            }
            require(names.filter { it != "SHA256SUMS" }.sorted() == checksums.keys.sorted())
            checksums.forEach { (path, expected) ->
                val actual = zip.getInputStream(zip.getEntry(path)).use(::backupSha256Stream)
                require(actual == expected) { "Working backup checksum mismatch for '$path'" }
            }

            val filesArray = manifest.getJSONArray("files")
            val files = buildList {
                for (index in 0 until filesArray.length()) {
                    val item = filesArray.getJSONObject(index)
                    val path = item.getString("path")
                    val sha = item.getString("sha256").lowercase()
                    val byteLength = item.getLong("byteLength")
                    require(path.startsWith("working/") && isSafeBackupPath(path))
                    require(checksums[path] == sha)
                    require(zip.getEntry(path)?.size == byteLength)
                    add(VerifiedBackupFile(path, sha))
                }
            }
            require(files.size == manifest.getInt("fileCount"))
            require((files.map { it.path } + "manifest.json").sorted() == names.filter { it != "SHA256SUMS" }.sorted())
            return VerifiedBackup(files)
        }
    }

    private fun isAllowedEvidenceRelativePath(relativePath: String): Boolean {
        if (!isSafeBackupPath(relativePath)) return false
        val first = relativePath.substringBefore('/')
        return first in EVIDENCE_DIRECTORIES && '/' in relativePath
    }

    private data class BackupEntry(
        val archivePath: String,
        val file: File,
        val sha256: String,
        val byteLength: Long,
    )

    private data class StagedBackupFile(
        val relativePath: String,
        val stage: File,
        val sha256: String,
    )

    private data class VerifiedBackup(val files: List<VerifiedBackupFile>)
    private data class VerifiedBackupFile(val path: String, val sha256: String)

    companion object {
        const val BACKUP_FORMAT = "bke-dna-working-backup"
        private val EVIDENCE_DIRECTORIES = setOf(
            "bodies",
            "observations",
            "normalized",
            "classifications",
            "witnesses",
            "reconciliations",
        )
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
    val fileCount: Int,
    val byteCount: Long,
)

data class AndroidWorkingBackupImportResult(
    val importedFileCount: Int,
    val deduplicatedFileCount: Int,
    val conversationCount: Int,
)

private const val BACKUP_ZIP_TIME_MS = 315532800000L

private fun isSafeBackupPath(path: String): Boolean =
    path.isNotBlank() &&
        !path.startsWith('/') &&
        !path.startsWith('\\') &&
        !path.contains('\\') &&
        path.split('/').none { it.isBlank() || it == "." || it == ".." }

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
