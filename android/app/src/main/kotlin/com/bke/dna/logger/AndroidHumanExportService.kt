package com.bke.dna.logger

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Human-readable derivative export. `.dna` remains the durable graph source. */
class AndroidHumanExportService(context: Context) {
    private val captureRoot = AndroidDnaPaths.capturesRoot(context.applicationContext)

    fun describe(conversationKey: String): AndroidConversationExportDescriptor {
        val state = readState(conversationKey)
        val conversationNativeId = state.getString("conversationNativeId")
        val sourceShas = state.getJSONArray("sources").let { array ->
            (0 until array.length()).map { array.getJSONObject(it).getString("sourceSha256") }
        }

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

    fun exportMarkdownToUri(
        conversationKey: String,
        resolver: ContentResolver,
        destinationUri: Uri,
    ) {
        check(!DnaReconciliationContract.AUTOMATIC_MARKDOWN_EXPORT) {
            "Markdown export must remain an explicit owner action"
        }
        val markdown = renderMarkdown(readState(conversationKey), describe(conversationKey))
        resolver.openOutputStream(destinationUri, "w")?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
            writer.write(markdown)
            writer.flush()
        } ?: error("Unable to open owner-selected Markdown export destination")
    }

    private fun readState(conversationKey: String): JSONObject {
        val path = File(captureRoot, "conversations/$conversationKey.json")
        require(path.isFile) { "Logical conversation state does not exist" }
        return JSONObject(path.readText())
    }

    private fun deriveTitleFromState(state: JSONObject): String? {
        val nodes = state.getJSONArray("nodes")
        for (index in 0 until nodes.length()) {
            val node = nodes.getJSONObject(index)
            val roles = node.optJSONArray("roles") ?: continue
            if ((0 until roles.length()).none { roles.optString(it) == "user" }) continue
            val revisions = node.optJSONArray("revisions") ?: continue
            if (revisions.length() == 0) continue
            val latest = (0 until revisions.length())
                .map { revisions.getJSONObject(it) }
                .maxByOrNull { it.optString("lastObservedAt") }
                ?: continue
            val parts = latest.optJSONArray("textParts") ?: continue
            val text = (0 until parts.length()).joinToString(" ") { parts.optString(it) }.trim()
            if (text.isNotBlank()) return text.take(80)
        }
        return null
    }

    private fun renderMarkdown(
        state: JSONObject,
        descriptor: AndroidConversationExportDescriptor,
    ): String {
        val nodes = state.getJSONArray("nodes").let { array ->
            (0 until array.length()).map { array.getJSONObject(it) }
        }.sortedWith(
            compareBy<JSONObject> { node ->
                val created = node.optJSONArray("createdAtValues")
                if (created == null || created.length() == 0) "~" else created.optString(0, "~")
            }.thenBy { it.getString("nodeNativeId") },
        )

        return buildString {
            appendLine("# ${descriptor.title}")
            appendLine()
            appendLine("- Conversation ID: `${descriptor.conversationNativeId}`")
            appendLine("- Historical date: ${descriptor.historicalDate?.toString() ?: "not exposed"}")
            appendLine("- Coverage: ${state.getString("coverageStatus")} (${state.getString("coverageBasis")})")
            appendLine("- Working state observed through: ${state.getString("stateObservedThrough")}")
            appendLine()
            appendLine("> Human-readable derivative. The `.dna` archive preserves graph IDs, branches, revisions, and evidence provenance.")
            appendLine()

            nodes.forEach { node ->
                val revisions = node.optJSONArray("revisions") ?: return@forEach
                if (revisions.length() == 0) return@forEach
                val revisionList = (0 until revisions.length()).map { revisions.getJSONObject(it) }
                val latest = revisionList.maxByOrNull { it.optString("lastObservedAt") } ?: return@forEach
                val roles = node.optJSONArray("roles")
                val role = if (roles != null && roles.length() > 0) roles.optString(0, "unknown") else "unknown"
                val created = node.optJSONArray("createdAtValues")
                val timestamp = if (created != null && created.length() > 0) created.optString(0) else "not exposed"
                val parts = latest.optJSONArray("textParts")
                val text = if (parts == null) "" else (0 until parts.length())
                    .joinToString("\n\n") { parts.optString(it) }
                    .trim()
                if (text.isBlank()) return@forEach

                appendLine("## ${role.replaceFirstChar { it.uppercase() }} — $timestamp")
                appendLine()
                appendLine(text)
                appendLine()
                if (revisionList.size > 1) {
                    appendLine("_This node has ${revisionList.size} observed revisions; the `.dna` archive preserves all of them._")
                    appendLine()
                }
                if ((node.optJSONArray("childNativeIds")?.length() ?: 0) > 1) {
                    appendLine("_Branch point: `${node.getString("nodeNativeId")}`._")
                    appendLine()
                }
            }
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
}

data class AndroidConversationExportDescriptor(
    val conversationKey: String,
    val conversationNativeId: String,
    val title: String,
    val historicalDate: LocalDate?,
    val fileBase: String,
)
