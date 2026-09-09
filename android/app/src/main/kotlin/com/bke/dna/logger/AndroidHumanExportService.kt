package com.bke.dna.logger

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Human derivatives for one logical conversation.
 *
 * CLEAN is deliberately strict: only actual user and assistant conversational
 * text survives, labelled JAN and RIGHT-HAND. Tool calls/results, system or
 * developer messages, diagnostics and archaeology metadata are excluded.
 *
 * RAW is the unfiltered captured conversation source payload stream. New
 * Working Data generations resolve RAW from their SQLite; historical pre-PR4
 * generations retain the legacy SHA-addressed .body fallback.
 */
class AndroidHumanExportService(
    context: Context,
    conversationStateDirectory: File? = null,
    private val generation: AndroidWorkingDataGeneration? = null,
) {
    private val appContext = context.applicationContext
    private val captureRoot = AndroidDnaPaths.capturesRoot(appContext)
    private val stateDirectory = conversationStateDirectory
        ?: generation?.conversationStateDirectory
        ?: File(captureRoot, "conversations")

    fun describe(conversationKey: String): AndroidConversationExportDescriptor {
        val state = readState(conversationKey)
        val conversationNativeId = state.getString("conversationNativeId")
        val sourceShas = sourceShas(state)

        val title = sourceShas.asReversed().firstNotNullOfOrNull { source ->
            runCatching {
                val bytes = AndroidRawSourceAccess.readAllBytes(
                    resolvedGeneration(),
                    captureRoot,
                    source,
                    MAX_TITLE_SOURCE_BYTES,
                ) ?: return@runCatching null
                JSONObject(String(bytes, Charsets.UTF_8))
                    .optString("title")
                    .trim()
                    .takeIf { it.isNotBlank() }
            }.getOrNull()
        } ?: deriveTitleFromState(state) ?: conversationNativeId

        val earliestInstant = state.getJSONArray("nodes").let { nodes ->
            buildList {
                for (index in 0 until nodes.length()) {
                    val node = nodes.getJSONObject(index)
                    val created = node.optJSONArray("createdAtValues") ?: continue
                    for (valueIndex in 0 until created.length()) {
                        parseHistoricalInstant(created.optString(valueIndex))?.let(::add)
                    }
                }
            }.minOrNull()
        }
        val historicalDate = earliestInstant?.atZone(ZoneOffset.UTC)?.toLocalDate()
        val safeTitle = sanitizeFileName(title).ifBlank { "conversation" }
        val fileBase = "${historicalDate?.toString() ?: "unknown-date"} - $safeTitle"

        return AndroidConversationExportDescriptor(
            conversationKey = conversationKey,
            conversationNativeId = conversationNativeId,
            title = title,
            historicalDate = historicalDate,
            fileBase = fileBase,
        )
    }

    /**
     * Bytes attributable to this conversation outside shared SQLite allocation.
     * SQLite page allocation, including SQLite-backed RAW, is deliberately not
     * guessed at conversation granularity.
     */
    fun conversationWorkingBytes(conversationKey: String): Long {
        val state = readState(conversationKey)
        val sourceShas = sourceShas(state).toSet()
        val files = linkedSetOf<File>()
        files += statePath(conversationKey)

        sourceShas.forEach { source ->
            files += File(captureRoot, "classifications/$source.json")
            files += File(captureRoot, "normalized/$source.json")
            if (!hasSqliteRaw(source)) files += File(captureRoot, "bodies/$source.body")
        }

        for (directoryName in SOURCE_REFERENCING_DIRECTORIES) {
            val directory = File(captureRoot, directoryName)
            if (!directory.isDirectory) continue
            directory.walkTopDown()
                .filter { it.isFile && it.length() <= MAX_REFERENCE_SCAN_BYTES }
                .forEach { file ->
                    val referenced = file.nameWithoutExtension in sourceShas || runCatching {
                        val text = file.readText(Charsets.UTF_8)
                        sourceShas.any(text::contains)
                    }.getOrDefault(false)
                    if (referenced) files += file
                }
        }

        return files.filter { it.isFile }.sumOf { it.length() }
    }

    fun renderCleanMarkdown(conversationKey: String): String =
        renderCleanMarkdown(readState(conversationKey))

    /** Explicit export helper; interactive reading must use the bounded pager. */
    fun renderRawMarkdown(conversationKey: String): String {
        val output = ByteArrayOutputStream()
        renderRawToStream(readState(conversationKey), describe(conversationKey), output)
        return output.toString(Charsets.UTF_8.name())
    }

    fun exportCleanMarkdownToUri(
        conversationKey: String,
        resolver: ContentResolver,
        destinationUri: Uri,
    ) = exportMarkdown(destinationUri, resolver, renderCleanMarkdown(conversationKey))

    fun exportRawMarkdownToUri(
        conversationKey: String,
        resolver: ContentResolver,
        destinationUri: Uri,
    ) {
        check(!DnaReconciliationContract.AUTOMATIC_MARKDOWN_EXPORT) {
            "Markdown export must remain an explicit owner action"
        }
        val state = readState(conversationKey)
        val descriptor = describe(conversationKey)
        resolver.openOutputStream(destinationUri, "w")?.use { output ->
            renderRawToStream(state, descriptor, output)
            output.flush()
        } ?: error("Unable to open owner-selected Markdown export destination")
    }

    /** Backward-compatible alias: the former single Markdown export is CLEAN. */
    fun exportMarkdownToUri(
        conversationKey: String,
        resolver: ContentResolver,
        destinationUri: Uri,
    ) = exportCleanMarkdownToUri(conversationKey, resolver, destinationUri)

    private fun exportMarkdown(destinationUri: Uri, resolver: ContentResolver, markdown: String) {
        check(!DnaReconciliationContract.AUTOMATIC_MARKDOWN_EXPORT) {
            "Markdown export must remain an explicit owner action"
        }
        resolver.openOutputStream(destinationUri, "w")?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
            writer.write(markdown)
            writer.flush()
        } ?: error("Unable to open owner-selected Markdown export destination")
    }

    private fun statePath(conversationKey: String) = File(stateDirectory, "$conversationKey.json")

    private fun readState(conversationKey: String): JSONObject {
        val path = statePath(conversationKey)
        require(path.isFile) { "Logical conversation state does not exist in selected Working Data" }
        return JSONObject(path.readText())
    }

    private fun sourceShas(state: JSONObject): List<String> = state.getJSONArray("sources").let { array ->
        (0 until array.length()).map { array.getJSONObject(it).getString("sourceSha256") }.distinct()
    }

    private fun resolvedGeneration(): AndroidWorkingDataGeneration {
        generation?.let { return it }
        val statePath = stateDirectory.absolutePath
        return AndroidWorkingDataManager(appContext).listWorkingData()
            .firstOrNull { it.conversationStateDirectory.absolutePath == statePath }
            ?: AndroidWorkingDataManager(appContext).generation(AndroidWorkingDataManager.LATEST_ID)
    }

    private fun hasSqliteRaw(sourceSha256: String): Boolean = runCatching {
        AndroidRawEvidenceStore(resolvedGeneration()).use { it.contains(sourceSha256) }
    }.getOrDefault(false)

    private fun deriveTitleFromState(state: JSONObject): String? {
        for (node in orderedNodes(state)) {
            if (cleanSpeaker(node) != CLEAN_JAN) continue
            val latest = latestRevision(node) ?: continue
            val text = revisionText(latest).trim()
            if (text.isNotBlank()) return text.take(80)
        }
        return null
    }

    private fun renderCleanMarkdown(state: JSONObject): String = buildString {
        var wroteTurn = false
        orderedNodes(state).forEach { node ->
            val speaker = cleanSpeaker(node) ?: return@forEach
            val latest = latestRevision(node) ?: return@forEach
            val text = revisionText(latest).trim()
            if (text.isBlank()) return@forEach

            if (wroteTurn) appendLine()
            appendLine(speaker)
            appendLine()
            appendLine(text)
            wroteTurn = true
        }
    }.trimEnd() + "\n"

    private fun renderRawToStream(
        state: JSONObject,
        descriptor: AndroidConversationExportDescriptor,
        output: OutputStream,
    ) {
        fun writeText(value: String) {
            output.write(value.toByteArray(Charsets.UTF_8))
        }

        writeText("# ${descriptor.title} — RAW\n\n")
        writeText("Unfiltered captured conversation payloads. Nothing below is CLEAN-filtered.\n\n")

        val sources = state.getJSONArray("sources").let { array ->
            (0 until array.length()).map { array.getJSONObject(it) }
        }.sortedWith(
            compareBy<JSONObject> { it.optString("observedAt") }
                .thenBy { it.getString("sourceSha256") },
        )

        sources.forEachIndexed { index, source ->
            val sha = source.getString("sourceSha256")
            writeText("## SOURCE ${index + 1} — $sha\n\n")
            writeText("Observed at: ${source.optString("observedAt", "not exposed")}\n\n")

            observationsForSource(sha).forEach { observation ->
                writeText("### CAPTURE OBSERVATION — ${observation.name}\n\n")
                observation.inputStream().use { it.copyTo(output, 128 * 1024) }
                writeText("\n\n")
            }

            writeText("### RAW BODY\n\n")
            require(
                AndroidRawSourceAccess.writeExactSource(
                    generation = resolvedGeneration(),
                    captureRoot = captureRoot,
                    sourceSha256 = sha,
                    output = output,
                ),
            ) { "RAW source body '$sha' is missing" }
            writeText("\n\n")
        }
    }

    private fun observationsForSource(sourceSha256: String): List<File> {
        val directory = File(captureRoot, "observations")
        if (!directory.isDirectory) return emptyList()
        return directory.listFiles().orEmpty()
            .filter { it.isFile && it.extension == "json" }
            .filter { file ->
                runCatching { JSONObject(file.readText()).optString("sha256") == sourceSha256 }
                    .getOrDefault(false)
            }
            .sortedBy { it.name }
    }

    private fun orderedNodes(state: JSONObject): List<JSONObject> = state.getJSONArray("nodes").let { array ->
        (0 until array.length()).map { array.getJSONObject(it) }
    }.sortedWith(
        compareBy<JSONObject> { node ->
            val created = node.optJSONArray("createdAtValues")
            if (created == null || created.length() == 0) "~" else created.optString(0, "~")
        }.thenBy { it.getString("nodeNativeId") },
    )

    private fun latestRevision(node: JSONObject): JSONObject? {
        val revisions = node.optJSONArray("revisions") ?: return null
        if (revisions.length() == 0) return null
        return (0 until revisions.length())
            .map { revisions.getJSONObject(it) }
            .maxByOrNull { it.optString("lastObservedAt") }
    }

    private fun revisionText(revision: JSONObject): String {
        val parts = revision.optJSONArray("textParts") ?: return ""
        return (0 until parts.length()).joinToString("\n\n") { parts.optString(it) }
    }

    private fun cleanSpeaker(node: JSONObject): String? {
        val roles = node.optJSONArray("roles") ?: return null
        val normalized = (0 until roles.length())
            .map { roles.optString(it).lowercase() }
            .filter { it.isNotBlank() }
            .toSet()
        return when (normalized) {
            setOf("user") -> CLEAN_JAN
            setOf("assistant") -> CLEAN_RIGHT_HAND
            else -> null
        }
    }

    private fun parseHistoricalInstant(value: String?): Instant? {
        if (value.isNullOrBlank()) return null
        return runCatching { Instant.parse(value) }.getOrNull()
            ?: runCatching {
                val seconds = BigDecimal(value)
                val wholeSeconds = seconds.toLong()
                val nanos = seconds.subtract(BigDecimal(wholeSeconds))
                    .movePointRight(9)
                    .toInt()
                    .coerceIn(0, 999_999_999)
                Instant.ofEpochSecond(wholeSeconds, nanos.toLong())
            }.getOrNull()
    }

    private fun sanitizeFileName(value: String): String = value
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .trimEnd('.')
        .take(100)

    companion object {
        const val CLEAN_JAN = "JAN"
        const val CLEAN_RIGHT_HAND = "RIGHT-HAND"
        private const val MAX_TITLE_SOURCE_BYTES = 16L * 1024 * 1024
        private const val MAX_REFERENCE_SCAN_BYTES = 2L * 1024 * 1024
        private val SOURCE_REFERENCING_DIRECTORIES = listOf("observations", "witnesses", "reconciliations")
    }
}

data class AndroidConversationExportDescriptor(
    val conversationKey: String,
    val conversationNativeId: String,
    val title: String,
    val historicalDate: LocalDate?,
    val fileBase: String,
)
