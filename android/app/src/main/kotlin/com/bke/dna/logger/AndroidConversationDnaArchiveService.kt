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
 * Manual Android conversation .dna v2 exporter.
 *
 * .dna is a portable owner export, not the ordinary Working Data durability
 * authority. New SQLite-backed RAW is materialized only into export-temporary
 * files while the deterministic archive is built; permanent loose bodies are
 * never recreated.
 */
class AndroidConversationDnaArchiveService(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val captureRoot = AndroidDnaPaths.capturesRoot(appContext)
    private val stagingDirectory = File(captureRoot, "export-staging").also { directory ->
        check(directory.exists() || directory.mkdirs()) { "Unable to create Android DNA export staging directory" }
        directory.listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith(".raw-") && it.name.endsWith(".tmp") }
            .forEach(File::delete)
    }
    private val latestGeneration by lazy {
        AndroidWorkingDataManager(appContext).generation(AndroidWorkingDataManager.LATEST_ID)
    }
    private val index = AndroidCaptureIndex(appContext)

    fun exportToUri(
        conversationIdentity: String,
        resolver: ContentResolver,
        destinationUri: Uri,
    ): AndroidConversationDnaArchiveResult {
        check(!DnaReconciliationContract.AUTOMATIC_DNA_EXPORT) {
            "Conversation DNA export must remain an explicit owner action"
        }

        val build = buildArchive(conversationIdentity)
        try {
            val verified = AndroidConversationDnaV2Verifier.verify(build.archive)
            require(verified.archiveId == build.archiveId)
            require(verified.conversationKey == build.conversationKey)

            resolver.openOutputStream(destinationUri, "w")?.use { output ->
                build.archive.inputStream().use { input -> input.copyTo(output, 128 * 1024) }
                output.flush()
            } ?: error("Unable to open owner-selected DNA export destination")

            val destinationSha256 = resolver.openInputStream(destinationUri)?.use(::sha256Stream)
                ?: error("Unable to re-open owner-selected DNA export destination")
            require(destinationSha256 == verified.archiveSha256) {
                "Persisted conversation .dna differs from the verified archive bytes"
            }

            val archivedAt = Instant.now().toString()
            index.recordVerifiedConversationArchive(
                conversationKey = verified.conversationKey,
                sourceSha256s = verified.sourceSha256s,
                archiveId = verified.archiveId,
                archiveSha256 = verified.archiveSha256,
                archivedAt = archivedAt,
            )

            return AndroidConversationDnaArchiveResult(
                archiveId = verified.archiveId,
                conversationKey = verified.conversationKey,
                conversationNativeId = verified.conversationNativeId,
                sourceSha256s = verified.sourceSha256s,
                archiveSha256 = verified.archiveSha256,
                destinationUri = destinationUri.toString(),
                archivedAt = archivedAt,
                entryCount = verified.entryCount,
            )
        } finally {
            build.archive.delete()
        }
    }

    override fun close() = index.close()

    private fun buildArchive(conversationIdentity: String): ArchiveBuild {
        val conversationKey = resolveConversationKey(conversationIdentity)
        val statePath = File(captureRoot, "conversations/$conversationKey.json")
        require(statePath.isFile) { "Aggregated Android conversation state was not found" }

        val state = JSONObject(statePath.readText())
        require(state.getString("conversationKey") == conversationKey) {
            "Aggregated conversation key does not match its file identity"
        }

        val conversationNativeId = state.getString("conversationNativeId")
        val sources = state.getJSONArray("sources").objects()
            .map { it.getString("sourceSha256").lowercase() }
            .distinct()
            .sorted()
        require(sources.isNotEmpty()) { "Cannot archive a conversation without raw source payloads" }
        sources.forEach(::requireSha256)

        val evidence = mutableListOf(
            EvidenceSource.fromFile(
                archivePath = "conversation/state.json",
                kind = "conversation_state",
                file = statePath,
                sourceSha256 = null,
            ),
        )
        val temporaryRaw = mutableListOf<File>()
        val pageUrls = linkedSetOf<String>()
        val witnessIds = linkedSetOf<String>()

        sources.forEach { sourceSha ->
            val raw = materializeRawSource(sourceSha).also(temporaryRaw::add)
            val normalized = File(captureRoot, "normalized/$sourceSha.json")
            require(normalized.isFile) {
                "Conversation source '$sourceSha' is missing normalized evidence"
            }

            val rawEvidence = EvidenceSource.fromFile(
                "sources/$sourceSha/raw.body",
                "raw_body",
                raw,
                sourceSha,
            )
            require(rawEvidence.sha256 == sourceSha) {
                "Raw body bytes no longer hash to source identity '$sourceSha'"
            }
            evidence += rawEvidence
            evidence += EvidenceSource.fromFile(
                "sources/$sourceSha/normalized.json",
                "normalized_snapshot",
                normalized,
                sourceSha,
            )

            val classification = File(captureRoot, "classifications/$sourceSha.json")
            if (classification.isFile) {
                evidence += EvidenceSource.fromFile(
                    "sources/$sourceSha/classification.json",
                    "classification",
                    classification,
                    sourceSha,
                )
            }

            var observationCount = 0
            File(captureRoot, "observations").listFiles().orEmpty()
                .filter { it.isFile && it.extension == "json" }
                .sortedBy { it.name }
                .forEach { observation ->
                    runCatching {
                        val root = JSONObject(observation.readText())
                        if (root.getString("sha256").lowercase() != sourceSha) return@runCatching
                        root.optJSONObject("capture")?.optString("pageUrl")
                            ?.takeIf { it.isNotBlank() }
                            ?.let(pageUrls::add)
                        evidence += EvidenceSource.fromFile(
                            "sources/$sourceSha/observations/${observation.name}",
                            "capture_observation",
                            observation,
                            sourceSha,
                        )
                        observationCount += 1
                    }
                }
            require(observationCount > 0) {
                "Conversation source '$sourceSha' has no valid capture observation"
            }
        }

        File(captureRoot, "witnesses").listFiles().orEmpty()
            .filter { it.isFile && it.extension == "json" }
            .sortedBy { it.name }
            .forEach { witness ->
                runCatching {
                    val root = JSONObject(witness.readText())
                    if (root.getString("pageUrl") !in pageUrls) return@runCatching
                    witnessIds += root.getString("witnessId")
                    evidence += EvidenceSource.fromFile(
                        "witnesses/${witness.name}",
                        "dom_witness",
                        witness,
                        null,
                    )
                }
            }

        File(captureRoot, "reconciliations").listFiles().orEmpty()
            .filter { it.isFile && it.extension == "json" }
            .sortedBy { it.name }
            .forEach { reconciliation ->
                runCatching {
                    val root = JSONObject(reconciliation.readText())
                    if (root.getString("witnessId") !in witnessIds) return@runCatching
                    evidence += EvidenceSource.fromFile(
                        "reconciliations/${reconciliation.name}",
                        "reconciliation",
                        reconciliation,
                        null,
                    )
                }
            }

        val orderedEvidence = evidence.sortedBy { it.archivePath }
        val identityMaterial = orderedEvidence.joinToString("\n") {
            "${it.archivePath}\t${it.sha256}\t${it.byteLength}\t${it.sourceSha256.orEmpty()}"
        }
        val archiveId = "dna-conversation-v2-${sha256Bytes(identityMaterial.toByteArray())}"

        val manifest = JSONObject()
            .put("format", "bke-dna")
            .put("formatVersion", 2)
            .put("scope", DnaReconciliationContract.ARCHIVE_SCOPE)
            .put("archiveId", archiveId)
            .put("conversationKey", conversationKey)
            .put("conversationNativeId", conversationNativeId)
            .put("currentNodeNativeId", state.optNullableStringLocal("currentNodeNativeId") ?: JSONObject.NULL)
            .put("coverageStatus", state.getString("coverageStatus"))
            .put("coverageBasis", state.getString("coverageBasis"))
            .put("sourceSha256s", JSONArray(sources))
            .put(
                "evidence",
                JSONArray(
                    orderedEvidence.map { item ->
                        JSONObject()
                            .put("path", item.archivePath)
                            .put("kind", item.kind)
                            .put("sourceSha256", item.sourceSha256 ?: JSONObject.NULL)
                            .put("sha256", item.sha256)
                            .put("byteLength", item.byteLength)
                    },
                ),
            )

        val manifestBytes = manifest.toString(2).toByteArray(Charsets.UTF_8)
        val checksums = sortedMapOf<String, String>()
        orderedEvidence.forEach { checksums[it.archivePath] = it.sha256 }
        checksums["manifest.json"] = sha256Bytes(manifestBytes)
        val checksumBytes = (
            checksums.entries.joinToString("\n") { "${it.value}  ${it.key}" } + "\n"
            ).toByteArray(Charsets.UTF_8)

        val archive = File(stagingDirectory, ".${archiveId}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(archive, false).use { fileOutput ->
                val zip = ZipOutputStream(fileOutput)
                try {
                    orderedEvidence.forEach { writeStoredFile(zip, it.archivePath, it.file) }
                    writeStoredBytes(zip, "manifest.json", manifestBytes)
                    writeStoredBytes(zip, "SHA256SUMS", checksumBytes)
                    zip.finish()
                    zip.flush()
                    fileOutput.fd.sync()
                } finally {
                    zip.close()
                }
            }
        } catch (error: Throwable) {
            archive.delete()
            throw error
        } finally {
            temporaryRaw.forEach(File::delete)
        }

        return ArchiveBuild(archiveId, conversationKey, archive)
    }

    private fun materializeRawSource(sourceSha256: String): File {
        val temporary = File(stagingDirectory, ".raw-$sourceSha256-${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary, false).use { output ->
                require(
                    AndroidRawSourceAccess.writeExactSource(
                        generation = latestGeneration,
                        captureRoot = captureRoot,
                        sourceSha256 = sourceSha256,
                        output = output,
                    ),
                ) { "Conversation source '$sourceSha256' is missing RAW evidence" }
                output.flush()
                output.fd.sync()
            }
            require(sha256File(temporary) == sourceSha256) {
                "Materialized RAW no longer hashes to source identity '$sourceSha256'"
            }
            return temporary
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    private fun resolveConversationKey(identity: String): String {
        val normalized = identity.trim()
        if (normalized.matches(SHA256_REGEX) &&
            File(captureRoot, "conversations/${normalized.lowercase()}.json").isFile
        ) {
            return normalized.lowercase()
        }
        return sha256Bytes(normalized.toByteArray(Charsets.UTF_8))
    }

    private data class ArchiveBuild(
        val archiveId: String,
        val conversationKey: String,
        val archive: File,
    )

    private data class EvidenceSource(
        val archivePath: String,
        val kind: String,
        val file: File,
        val sourceSha256: String?,
        val sha256: String,
        val byteLength: Long,
    ) {
        companion object {
            fun fromFile(
                archivePath: String,
                kind: String,
                file: File,
                sourceSha256: String?,
            ): EvidenceSource {
                requireSafeArchivePath(archivePath)
                return EvidenceSource(
                    archivePath,
                    kind,
                    file,
                    sourceSha256,
                    sha256File(file),
                    file.length(),
                )
            }
        }
    }
}

/** Strict verifier for canonical conversation-scoped BKE DNA v2 archives. */
object AndroidConversationDnaV2Verifier {
    fun verify(archive: File): AndroidConversationDnaArchiveVerification {
        require(archive.isFile) { "Conversation DNA archive does not exist" }

        val verification = ZipFile(archive).use { zip ->
            val entries = linkedMapOf<String, java.util.zip.ZipEntry>()
            val iterator = zip.entries()
            while (iterator.hasMoreElements()) {
                val entry = iterator.nextElement()
                require(!entry.isDirectory) { "Conversation DNA must not contain directory entries" }
                requireSafeArchivePath(entry.name)
                require(entries.put(entry.name, entry) == null) {
                    "Conversation DNA contains duplicate entry '${entry.name}'"
                }
            }

            val manifestEntry = entries["manifest.json"]
                ?: error("Conversation DNA must contain manifest.json")
            val sumsEntry = entries["SHA256SUMS"]
                ?: error("Conversation DNA must contain SHA256SUMS")
            val manifest = JSONObject(readZipText(zip, manifestEntry, 4L * 1024 * 1024))
            require(manifest.getString("format") == "bke-dna")
            require(manifest.getInt("formatVersion") == 2)
            require(manifest.getString("scope") == DnaReconciliationContract.ARCHIVE_SCOPE)

            val archiveId = manifest.getString("archiveId")
            val conversationKey = manifest.getString("conversationKey").lowercase()
            requireSha256(conversationKey)
            val conversationNativeId = manifest.getString("conversationNativeId")
            require(conversationNativeId.isNotBlank())
            require(conversationKey == sha256Bytes(conversationNativeId.toByteArray(Charsets.UTF_8))) {
                "Conversation DNA conversation key does not match native conversation identity"
            }

            val sourceSha256s = manifest.getJSONArray("sourceSha256s").strings()
                .map(String::lowercase)
            require(sourceSha256s.isNotEmpty() && sourceSha256s.distinct().size == sourceSha256s.size)
            sourceSha256s.forEach(::requireSha256)

            val checksums = parseChecksums(readZipText(zip, sumsEntry, 32L * 1024 * 1024))
            val payloadNames = entries.keys.filter { it != "SHA256SUMS" }.sorted()
            require(payloadNames == checksums.keys.sorted()) {
                "Conversation DNA SHA256SUMS does not describe exactly every payload entry"
            }
            payloadNames.forEach { name ->
                val actual = zip.getInputStream(entries.getValue(name)).use(::sha256Stream)
                require(actual == checksums.getValue(name)) {
                    "Checksum mismatch for conversation DNA entry '$name'"
                }
            }

            val evidenceArray = manifest.getJSONArray("evidence")
            val evidence = buildList {
                for (index in 0 until evidenceArray.length()) {
                    val item = evidenceArray.getJSONObject(index)
                    val path = item.getString("path")
                    requireSafeArchivePath(path)
                    val source = if (item.isNull("sourceSha256")) null else item.getString("sourceSha256").lowercase()
                    source?.let {
                        requireSha256(it)
                        require(it in sourceSha256s) { "Evidence references source outside manifest source set" }
                    }
                    val sha256 = item.getString("sha256").lowercase()
                    requireSha256(sha256)
                    val byteLength = item.getLong("byteLength")
                    require(byteLength >= 0)
                    require(entries[path]?.size == byteLength) {
                        "Conversation DNA evidence metadata mismatch for '$path'"
                    }
                    require(checksums[path] == sha256) {
                        "Conversation DNA manifest checksum mismatch for '$path'"
                    }
                    add(ManifestEvidence(path, item.getString("kind"), source, sha256, byteLength))
                }
            }
            require(evidence.map { it.path }.distinct().size == evidence.size) {
                "Conversation DNA manifest contains duplicate evidence paths"
            }
            val manifestPaths = (evidence.map { it.path } + "manifest.json").sorted()
            require(manifestPaths == payloadNames) {
                "Conversation DNA manifest references do not match payload entries"
            }

            val identityMaterial = evidence.sortedBy { it.path }.joinToString("\n") {
                "${it.path}\t${it.sha256}\t${it.byteLength}\t${it.sourceSha256.orEmpty()}"
            }
            require(archiveId == "dna-conversation-v2-${sha256Bytes(identityMaterial.toByteArray())}") {
                "Conversation DNA archive identity does not match its evidence set"
            }

            val stateEvidence = evidence.filter { it.kind == "conversation_state" }
            require(stateEvidence.size == 1 && stateEvidence.single().path == "conversation/state.json") {
                "Conversation DNA must contain exactly one conversation/state.json"
            }
            val state = JSONObject(readZipText(zip, entries.getValue("conversation/state.json"), 32L * 1024 * 1024))
            require(state.getString("conversationKey") == conversationKey)
            require(state.getString("conversationNativeId") == conversationNativeId)
            require(state.getString("coverageStatus") == manifest.getString("coverageStatus"))
            require(state.getString("coverageBasis") == manifest.getString("coverageBasis"))
            val stateSources = state.getJSONArray("sources").objects()
                .map { it.getString("sourceSha256").lowercase() }
                .distinct()
                .sorted()
            require(stateSources == sourceSha256s.sorted()) {
                "Conversation DNA state source set differs from manifest source set"
            }

            sourceSha256s.forEach { source ->
                val raw = evidence.filter { it.kind == "raw_body" && it.sourceSha256 == source }
                require(raw.size == 1)
                require(raw.single().path == "sources/$source/raw.body" && raw.single().sha256 == source) {
                    "Conversation DNA raw body identity mismatch for '$source'"
                }
                require(evidence.count { it.kind == "normalized_snapshot" && it.sourceSha256 == source } == 1)
                require(evidence.count { it.kind == "capture_observation" && it.sourceSha256 == source } >= 1)
            }

            PartialVerification(
                archiveId = archiveId,
                conversationKey = conversationKey,
                conversationNativeId = conversationNativeId,
                sourceSha256s = sourceSha256s.sorted(),
                entryCount = entries.size,
            )
        }

        return AndroidConversationDnaArchiveVerification(
            archiveId = verification.archiveId,
            conversationKey = verification.conversationKey,
            conversationNativeId = verification.conversationNativeId,
            sourceSha256s = verification.sourceSha256s,
            archiveSha256 = sha256File(archive),
            entryCount = verification.entryCount,
        )
    }

    private data class PartialVerification(
        val archiveId: String,
        val conversationKey: String,
        val conversationNativeId: String,
        val sourceSha256s: List<String>,
        val entryCount: Int,
    )

    private data class ManifestEvidence(
        val path: String,
        val kind: String,
        val sourceSha256: String?,
        val sha256: String,
        val byteLength: Long,
    )
}

data class AndroidConversationDnaArchiveVerification(
    val archiveId: String,
    val conversationKey: String,
    val conversationNativeId: String,
    val sourceSha256s: List<String>,
    val archiveSha256: String,
    val entryCount: Int,
)

data class AndroidConversationDnaArchiveResult(
    val archiveId: String,
    val conversationKey: String,
    val conversationNativeId: String,
    val sourceSha256s: List<String>,
    val archiveSha256: String,
    val destinationUri: String,
    val archivedAt: String,
    val entryCount: Int,
)

private val SHA256_REGEX = Regex("[0-9a-fA-F]{64}")
private const val DETERMINISTIC_ZIP_TIME_MS = 315532800000L

private fun requireSha256(value: String) {
    require(value.matches(SHA256_REGEX)) { "Expected a 64-character SHA-256 value" }
}

private fun requireSafeArchivePath(path: String) {
    require(
        path.isNotBlank() &&
            !path.startsWith('/') &&
            !path.startsWith('\\') &&
            !path.contains('\\') &&
            path.split('/').none { it.isBlank() || it == "." || it == ".." },
    ) { "Unsafe conversation DNA path '$path'" }
}

private fun parseChecksums(text: String): Map<String, String> {
    val result = sortedMapOf<String, String>()
    text.lineSequence().filter { it.isNotBlank() }.forEach { raw ->
        val line = raw.trimEnd('\r')
        val separator = line.indexOf("  ")
        require(separator == 64) { "Malformed conversation DNA SHA256SUMS line" }
        val sha = line.substring(0, separator).lowercase()
        val path = line.substring(separator + 2)
        requireSha256(sha)
        requireSafeArchivePath(path)
        require(result.put(path, sha) == null) { "Duplicate conversation DNA checksum path" }
    }
    return result
}

private fun readZipText(zip: ZipFile, entry: java.util.zip.ZipEntry, maxBytes: Long): String {
    require(entry.size in 0..maxBytes) { "Conversation DNA entry '${entry.name}' exceeds verification size limit" }
    return zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
}

private fun writeStoredFile(zip: ZipOutputStream, path: String, file: File) {
    requireSafeArchivePath(path)
    val entry = ZipEntry(path).apply {
        method = ZipEntry.STORED
        size = file.length()
        compressedSize = size
        crc = crc32File(file)
        time = DETERMINISTIC_ZIP_TIME_MS
    }
    zip.putNextEntry(entry)
    file.inputStream().use { it.copyTo(zip, 128 * 1024) }
    zip.closeEntry()
}

private fun writeStoredBytes(zip: ZipOutputStream, path: String, bytes: ByteArray) {
    requireSafeArchivePath(path)
    val crc = CRC32().apply { update(bytes) }.value
    val entry = ZipEntry(path).apply {
        method = ZipEntry.STORED
        size = bytes.size.toLong()
        compressedSize = size
        this.crc = crc
        time = DETERMINISTIC_ZIP_TIME_MS
    }
    zip.putNextEntry(entry)
    zip.write(bytes)
    zip.closeEntry()
}

private fun crc32File(file: File): Long {
    val crc = CRC32()
    file.inputStream().use { input ->
        val buffer = ByteArray(128 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) crc.update(buffer, 0, count)
        }
    }
    return crc.value
}

private fun sha256File(file: File): String = file.inputStream().use(::sha256Stream)

private fun sha256Stream(input: java.io.InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(128 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count > 0) digest.update(buffer, 0, count)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun sha256Bytes(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun JSONArray.objects(): List<JSONObject> =
    (0 until length()).map { getJSONObject(it) }

private fun JSONArray.strings(): List<String> =
    (0 until length()).map { getString(it) }

private fun JSONObject.optNullableStringLocal(name: String): String? {
    if (!has(name) || isNull(name)) return null
    return optString(name).takeIf { it.isNotBlank() }
}
