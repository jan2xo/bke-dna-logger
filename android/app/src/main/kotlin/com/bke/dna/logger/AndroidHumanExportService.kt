package com.bke.dna.logger

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Owner-facing human derivatives for one logical conversation.
 *
 * CLEAN.md is intentionally token-efficient for continuation tools such as
 * Codex/Claude. RAW.md preserves every reconciled revision and full readable
 * content exposed by normalization. Neither derivative replaces SQLite or the
 * durable `.dna` archive, which retains the source evidence/provenance.
 */
class AndroidHumanExportService(context: Context) {
    private val captureRoot = AndroidDnaPaths.capturesRoot(context.applicationContext)

    fun describe(conversationKey: String): AndroidConversationExportDescriptor {
        val state = readState(conversationKey)
        val conversationNativeId = state.getString("conversationNativeId")
        val sourceShas = sourceShas(state)

        val title = sourceShas.asReversed().firstNotNullOfOrNull { source ->
            runCatching {
                JSONObject(File(captureRoot, "bodies/$source.body").readText())
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
     * Bytes attributable to this conversation in the working evidence store.
     * SQLite page allocation is intentionally excluded because SQLite is a shared
     * rebuildable projection and cannot be honestly assigned per conversation.
     */
    fun conversationWorkingBytes(conversationKey: String): Long {
        val state = readState(conversationKey)
        val sourceShas = sourceShas(state).toSet()
        val files = linkedSetOf<File>()
        files += statePath(conversationKey)

        sourceShas.forEach { source ->
            files += File(captureRoot, "bodies/$source.body")
            files += File(captureRoot, "classifications/$source.json")
            files += File(captureRoot, "normalized/$source.json")
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
        renderCleanMarkdown(readState(conversationKey), describe(conversationKey))

    fun renderRawMarkdown(conversationKey: String): String =
        renderRawMarkdown(readState(conversationKey), describe(conversationKey))

    fun exportCleanMarkdownToUri(
        conversationKey: String,
        resolver: ContentResolver,
        destinationUri: Uri,
    ) = exportMarkdown(destinationUri, resolver, renderCleanMarkdown(conversationKey))

    fun exportRawMarkdownToUri(
        conversationKey: String,
        resolver: ContentResolver,
        destinationUri: Uri,
    ) = exportMarkdown(destinationUri, resolver, renderRawMarkdown(conversationKey))

    /** Backward-compatible alias: the former single Markdown export is now CLEAN.md. */
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

    private fun statePath(conversationKey: String) = File(captureRoot, "conversations/$conversationKey.json")

    private fun readState(conversationKey: String): JSONObject {
        val path = statePath(conversationKey)
        require(path.isFile) { "Logical conversation state does not exist" }
        return JSONObject(path.readText())
    }

    private fun sourceShas(state: JSONObject): List<String> = state.getJSONArray("sources").let { array ->
        (0 until array.length()).map { array.getJSONObject(it).getString("sourceSha256") }.distinct()
    }

    private fun deriveTitleFromState(state: JSONObject): String? {
        for (node in orderedNodes(state)) {
            if (primaryRole(node) != "user") continue
            val latest = latestRevision(node) ?: continue
            val text = revisionText(latest).trim()
            if (text.isNotBlank()) return text.take(80)
        }
        return null
    }

    private fun renderCleanMarkdown(
        state: JSONObject,
        descriptor: AndroidConversationExportDescriptor,
    ): String = buildString {
        appendLine("# ${descriptor.title}")
        appendLine()
        appendLine("- Conversation ID: `${descriptor.conversationNativeId}`")
        appendLine("- Historical date: ${descriptor.historicalDate?.toString() ?: "not exposed"}")
        appendLine("- Coverage: ${state.getString("coverageStatus")} (${state.getString("coverageBasis")})")
        appendLine("- Working state observed through: ${state.getString("stateObservedThrough")}")
        appendLine()
        appendLine("> CLEAN.md — token-efficient continuation derivative. Large structured tool/result payloads are collapsed deterministically. Use RAW.md or `.dna` when exact evidence is required.")
        appendLine()

        orderedNodes(state).forEach { node ->
            val latest = latestRevision(node) ?: return@forEach
            val role = primaryRole(node)
            val timestamp = primaryTimestamp(node)
            val rawText = revisionText(latest).trim().ifBlank {
                latest.optNullableString("contentJson").orEmpty().trim()
            }
            if (rawText.isBlank()) return@forEach

            val cleaned = cleanContent(rawText, role, node.getString("nodeNativeId"))
            appendLine("## ${role.replaceFirstChar { it.uppercase() }} — $timestamp")
            appendLine()
            appendLine(cleaned)
            appendLine()

            val revisions = node.optJSONArray("revisions")
            if ((revisions?.length() ?: 0) > 1) {
                appendLine("_Observed revisions: ${revisions!!.length()}; CLEAN shows the latest. RAW.md and `.dna` preserve the revision history._")
                appendLine()
            }
        }
    }

    private fun renderRawMarkdown(
        state: JSONObject,
        descriptor: AndroidConversationExportDescriptor,
    ): String = buildString {
        appendLine("# ${descriptor.title} — RAW")
        appendLine()
        appendLine("- Conversation ID: `${descriptor.conversationNativeId}`")
        appendLine("- Historical date: ${descriptor.historicalDate?.toString() ?: "not exposed"}")
        appendLine("- Coverage: ${state.getString("coverageStatus")} (${state.getString("coverageBasis")})")
        appendLine("- Working state observed through: ${state.getString("stateObservedThrough")}")
        appendLine()
        appendLine("> RAW.md — human-readable reconciled chronology with all observed revisions. Network bodies and full provenance remain canonical in `.dna` / working evidence.")
        appendLine()

        orderedNodes(state).forEach { node ->
            val role = primaryRole(node)
            val timestamp = primaryTimestamp(node)
            val nodeId = node.getString("nodeNativeId")
            val revisions = node.optJSONArray("revisions") ?: return@forEach
            if (revisions.length() == 0) return@forEach
            val revisionList = (0 until revisions.length())
                .map { revisions.getJSONObject(it) }
                .sortedWith(compareBy<JSONObject> { it.optString("firstObservedAt") }.thenBy { it.optString("revisionSha256") })

            appendLine("## ${role.replaceFirstChar { it.uppercase() }} — $timestamp")
            appendLine()
            appendLine("Node: `$nodeId`")
            appendLine()

            revisionList.forEachIndexed { index, revision ->
                if (revisionList.size > 1) {
                    appendLine("### Revision ${index + 1}/${revisionList.size}")
                    appendLine()
                    appendLine("- First observed: ${revision.optString("firstObservedAt", "not exposed")}")
                    appendLine("- Last observed: ${revision.optString("lastObservedAt", "not exposed")}")
                    appendLine("- Revision SHA-256: `${revision.optString("revisionSha256", "not exposed")}`")
                    appendLine()
                }

                val text = revisionText(revision).trim()
                val contentJson = revision.optNullableString("contentJson").orEmpty().trim()
                when {
                    text.isNotBlank() -> appendLine(text)
                    contentJson.isNotBlank() -> appendLine(contentJson)
                    else -> appendLine("_[No readable content exposed by this normalized revision.]_")
                }
                appendLine()
            }

            if ((node.optJSONArray("childNativeIds")?.length() ?: 0) > 1) {
                appendLine("_Branch point: `$nodeId`._")
                appendLine()
            }
        }
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

    private fun primaryRole(node: JSONObject): String {
        val roles = node.optJSONArray("roles")
        return if (roles != null && roles.length() > 0) roles.optString(0, "unknown") else "unknown"
    }

    private fun primaryTimestamp(node: JSONObject): String {
        val created = node.optJSONArray("createdAtValues")
        return if (created != null && created.length() > 0) created.optString(0) else "not exposed"
    }

    private fun cleanContent(text: String, role: String, nodeId: String): String {
        val limit = if (role == "tool") CLEAN_TOOL_TEXT_LIMIT else CLEAN_MESSAGE_TEXT_LIMIT
        if (text.length <= limit) return text

        summarizeStructuredPayload(text, nodeId)?.let { return it }
        val retained = text.take(limit)
        return buildString {
            append(retained)
            appendLine()
            appendLine()
            append("_[CLEAN truncated ${text.length - retained.length} characters; full content: RAW.md / `.dna`; node `$nodeId`.]_")
        }
    }

    private fun summarizeStructuredPayload(text: String, nodeId: String): String? {
        val value = runCatching {
            val tokener = JSONTokener(text)
            val parsed = tokener.nextValue()
            if (tokener.nextClean() != '\u0000') null else parsed
        }.getOrNull() ?: return null

        return when (value) {
            is JSONObject -> {
                val keys = value.keys().asSequence().toList().sorted()
                val scalars = IMPORTANT_TOOL_SCALARS.mapNotNull { key ->
                    if (!value.has(key) || value.isNull(key)) return@mapNotNull null
                    val raw = value.opt(key)
                    if (raw is JSONObject || raw is JSONArray) return@mapNotNull null
                    "$key=${raw.toString().take(CLEAN_SCALAR_LIMIT)}"
                }
                buildString {
                    append("[Structured tool/result payload collapsed for CLEAN")
                    append(" · ${text.length} chars")
                    if (keys.isNotEmpty()) append(" · keys: ${keys.take(CLEAN_KEY_LIMIT).joinToString(", ")}")
                    appendLine("]")
                    if (scalars.isNotEmpty()) appendLine(scalars.joinToString(" · "))
                    append("Raw evidence: RAW.md / `.dna` · node `$nodeId`")
                }
            }
            is JSONArray -> buildString {
                append("[Structured tool/result array collapsed for CLEAN · ${text.length} chars · ${value.length()} items]")
                appendLine()
                append("Raw evidence: RAW.md / `.dna` · node `$nodeId`")
            }
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
        private const val CLEAN_TOOL_TEXT_LIMIT = 1_200
        private const val CLEAN_MESSAGE_TEXT_LIMIT = 8_000
        private const val CLEAN_SCALAR_LIMIT = 240
        private const val CLEAN_KEY_LIMIT = 16
        private const val MAX_REFERENCE_SCAN_BYTES = 2L * 1024 * 1024
        private val SOURCE_REFERENCING_DIRECTORIES = listOf("observations", "witnesses", "reconciliations")
        private val IMPORTANT_TOOL_SCALARS = listOf(
            "title",
            "name",
            "status",
            "conclusion",
            "state",
            "number",
            "sha",
            "head_sha",
            "message",
            "merged",
            "mergeable",
            "url",
            "html_url",
        )
    }
}

data class AndroidConversationExportDescriptor(
    val conversationKey: String,
    val conversationNativeId: String,
    val title: String,
    val historicalDate: LocalDate?,
    val fileBase: String,
)

private fun JSONObject.optNullableString(key: String): String? =
    if (has(key) && !isNull(key)) optString(key, null) else null
