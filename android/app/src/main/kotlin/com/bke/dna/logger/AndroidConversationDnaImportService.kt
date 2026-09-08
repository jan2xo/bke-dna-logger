package com.bke.dna.logger

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile

/**
 * Native Kotlin importer for portable conversation-scoped .dna archives.
 *
 * This never attaches or merges another device's SQLite database. Archives are
 * independently verified, staged, then admitted into this device's local
 * evidence store. Imported normalized evidence is then reconciled into this
 * device's own logical conversation projection. Manual archive export remains
 * a separate owner action.
 */
class AndroidConversationDnaImportService(context: Context) {
    private val appContext = context.applicationContext
    private val captureRoot = AndroidDnaPaths.capturesRoot(appContext)

    fun importVerified(archives: List<File>): AndroidConversationDnaImportResult {
        require(archives.isNotEmpty()) { "At least one conversation .dna archive is required" }

        val verified = archives.map(AndroidConversationDnaVerifier::verify)
        val conversationNativeId = verified.first().conversationNativeId
        require(verified.all { it.conversationNativeId == conversationNativeId }) {
            "Cross-device reconciliation cannot mix different conversationNativeId values"
        }

        val stagingRoot = File(captureRoot, "import-staging/${UUID.randomUUID()}")
        check(stagingRoot.mkdirs()) { "Unable to create DNA import staging directory" }
        try {
            val staged = linkedMapOf<String, StagedFile>()
            verified.forEach { archive -> stageArchive(archive, stagingRoot, staged) }
            staged.values.sortedBy { it.relativeTarget }.forEach(::commitStaged)

            val logicalConversation = AndroidConversationAggregationEngine(appContext).use { engine ->
                engine.aggregateAll().singleOrNull { it.conversationNativeId == conversationNativeId }
            } ?: error("Imported conversation DNA did not produce a logical conversation state")

            return AndroidConversationDnaImportResult(
                contractId = DnaReconciliationContract.CONTRACT_ID,
                conversationNativeId = conversationNativeId,
                conversationKey = logicalConversation.conversationKey,
                archiveCount = verified.size,
                sourceSha256s = verified.flatMap { it.sourceSha256s }.distinct().sorted(),
                importedFileCount = staged.size,
                coverageStatus = logicalConversation.coverageStatus,
                nodeCount = logicalConversation.nodes.size,
            )
        } finally {
            stagingRoot.deleteRecursively()
        }
    }

    private fun stageArchive(
        verified: VerifiedConversationDna,
        stagingRoot: File,
        staged: MutableMap<String, StagedFile>,
    ) {
        val sourceSet = verified.sourceSha256s.toSet()
        ZipFile(verified.archive).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val target = mapTarget(entry.name, sourceSet) ?: continue
                val stage = File(stagingRoot, UUID.randomUUID().toString())
                val digest = MessageDigest.getInstance("SHA-256")
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(stage, false).use { output ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                        }
                        output.flush()
                        output.fd.sync()
                    }
                }
                val sha256 = digest.digest().toHex()
                if (target.expectedSha256 != null) {
                    require(sha256 == target.expectedSha256) {
                        "Imported raw source does not match SHA-256 identity '${target.expectedSha256}'"
                    }
                }

                val existing = staged[target.relativeTarget]
                if (existing != null) {
                    require(existing.sha256 == sha256) {
                        "Conflicting cross-device evidence target '${target.relativeTarget}'"
                    }
                    stage.delete()
                } else {
                    staged[target.relativeTarget] = StagedFile(target.relativeTarget, stage, sha256)
                }
            }
        }
    }

    private fun mapTarget(path: String, sourceSet: Set<String>): ImportTarget? {
        sourceSet.forEach { sourceSha ->
            val prefix = "sources/$sourceSha/"
            if (!path.startsWith(prefix)) return@forEach
            val remainder = path.removePrefix(prefix)
            return when {
                remainder == "raw.body" -> ImportTarget("bodies/$sourceSha.body", sourceSha)
                remainder == "normalized.json" -> ImportTarget("normalized/$sourceSha.json", null)
                remainder == "classification.json" -> ImportTarget("classifications/$sourceSha.json", null)
                remainder.startsWith("observations/") -> {
                    val name = remainder.removePrefix("observations/")
                    require(name.isNotBlank() && !name.contains('/') && !name.contains('\\')) {
                        "Conversation DNA observation path is not a single safe file name"
                    }
                    ImportTarget("observations/$name", null)
                }
                else -> null
            }
        }

        if (path.startsWith("witnesses/")) {
            val name = path.removePrefix("witnesses/")
            require(name.isNotBlank() && !name.contains('/') && !name.contains('\\')) {
                "Conversation DNA witness path is not a single safe file name"
            }
            return ImportTarget("witnesses/$name", null)
        }
        if (path.startsWith("reconciliations/")) {
            val name = path.removePrefix("reconciliations/")
            require(name.isNotBlank() && !name.contains('/') && !name.contains('\\')) {
                "Conversation DNA reconciliation path is not a single safe file name"
            }
            return ImportTarget("reconciliations/$name", null)
        }
        return null
    }

    private fun commitStaged(item: StagedFile) {
        val target = File(captureRoot, item.relativeTarget).canonicalFile
        val root = captureRoot.canonicalFile
        require(target.path.startsWith(root.path + File.separator)) {
            "Imported evidence target escaped the capture root"
        }
        target.parentFile?.let { parent -> check(parent.exists() || parent.mkdirs()) }

        if (target.exists()) {
            require(sha256File(target) == item.sha256) {
                "Existing local evidence conflicts with imported '${item.relativeTarget}'"
            }
            item.stage.delete()
            return
        }
        check(item.stage.renameTo(target)) { "Unable to promote staged DNA evidence" }
    }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private data class ImportTarget(val relativeTarget: String, val expectedSha256: String?)
    private data class StagedFile(val relativeTarget: String, val stage: File, val sha256: String)
}

object AndroidConversationDnaVerifier {
    fun verify(archive: File): VerifiedConversationDna {
        require(archive.isFile) { "Conversation DNA archive does not exist" }

        ZipFile(archive).use { zip ->
            val names = mutableSetOf<String>()
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val name = entries.nextElement().name
                require(isSafeArchivePath(name)) { "Unsafe conversation DNA entry path '$name'" }
                require(names.add(name)) { "Conversation DNA contains duplicate entry '$name'" }
            }

            val manifestEntry = zip.getEntry("manifest.json")
                ?: error("Conversation DNA must contain manifest.json")
            val sumsEntry = zip.getEntry("SHA256SUMS")
                ?: error("Conversation DNA must contain SHA256SUMS")
            val manifest = JSONObject(zip.getInputStream(manifestEntry).bufferedReader().use { it.readText() })
            require(manifest.getString("format") == "bke-dna")
            require(manifest.getInt("formatVersion") == 2)
            require(manifest.getString("scope") == DnaReconciliationContract.ARCHIVE_SCOPE)

            val conversationNativeId = manifest.getString("conversationNativeId")
            require(conversationNativeId.isNotBlank()) { "Conversation DNA lacks conversationNativeId" }
            val sourceArray = manifest.getJSONArray("sourceSha256s")
            val sources = (0 until sourceArray.length()).map { sourceArray.getString(it).lowercase() }
            require(sources.isNotEmpty() && sources.distinct().size == sources.size)
            sources.forEach { require(it.matches(Regex("[0-9a-f]{64}"))) { "Invalid source SHA-256" } }

            val checksums = parseChecksums(zip.getInputStream(sumsEntry).bufferedReader().use { it.readText() })
            val payloadNames = names.filter { it != "SHA256SUMS" }.sorted()
            require(payloadNames == checksums.keys.sorted()) {
                "Conversation DNA SHA256SUMS does not describe exactly every payload entry"
            }
            payloadNames.forEach { name ->
                val digest = MessageDigest.getInstance("SHA-256")
                zip.getInputStream(zip.getEntry(name)).use { input ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count > 0) digest.update(buffer, 0, count)
                    }
                }
                require(digest.digest().toHex() == checksums.getValue(name)) {
                    "Checksum mismatch for conversation DNA entry '$name'"
                }
            }

            sources.forEach { source ->
                val rawPath = "sources/$source/raw.body"
                require(rawPath in names) { "Conversation DNA source '$source' lacks raw body" }
                require(checksums[rawPath] == source) { "Conversation DNA raw body identity mismatch" }
                require("sources/$source/normalized.json" in names) {
                    "Conversation DNA source '$source' lacks normalized snapshot"
                }
                require(names.any { it.startsWith("sources/$source/observations/") }) {
                    "Conversation DNA source '$source' lacks capture observation"
                }
            }

            return VerifiedConversationDna(archive, conversationNativeId, sources.sorted())
        }
    }

    private fun parseChecksums(text: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val split = line.indexOf("  ")
            require(split == 64) { "Malformed SHA256SUMS line" }
            val sha = line.substring(0, split).lowercase()
            val path = line.substring(split + 2)
            require(sha.matches(Regex("[0-9a-f]{64}")) && isSafeArchivePath(path))
            require(result.put(path, sha) == null) { "Duplicate SHA256SUMS path" }
        }
        return result
    }

    private fun isSafeArchivePath(path: String): Boolean =
        path.isNotBlank() &&
            !path.startsWith('/') &&
            !path.startsWith('\\') &&
            !path.contains("..") &&
            !path.contains('\\')
}

data class VerifiedConversationDna(
    val archive: File,
    val conversationNativeId: String,
    val sourceSha256s: List<String>,
)

data class AndroidConversationDnaImportResult(
    val contractId: String,
    val conversationNativeId: String,
    val conversationKey: String,
    val archiveCount: Int,
    val sourceSha256s: List<String>,
    val importedFileCount: Int,
    val coverageStatus: String,
    val nodeCount: Int,
)

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
