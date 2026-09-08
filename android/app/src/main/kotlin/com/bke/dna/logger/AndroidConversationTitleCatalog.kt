package com.bke.dna.logger

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Shared derived title index above Working Data generations.
 *
 * Saved SQLite generations remain immutable. Titles are recovered from the
 * immutable raw conversation payload belonging to each normalized source and
 * keyed by conversationNativeId. No user-message title synthesis is allowed.
 */
class AndroidConversationTitleCatalog(context: Context) {
    private val captureRoot = AndroidDnaPaths.capturesRoot(context.applicationContext)
    private val normalizedDirectory = File(captureRoot, "normalized")
    private val bodiesDirectory = File(captureRoot, "bodies")
    private val catalogFile = File(captureRoot, CATALOG_FILE_NAME)

    @Volatile private var cached: CatalogState? = null

    @Synchronized
    fun refreshFromEvidence() {
        val state = readCatalog()
        var changed = false

        normalizedDirectory.listFiles().orEmpty()
            .filter { it.isFile && it.extension == "json" }
            .sortedBy { it.name }
            .forEach { normalizedPath ->
                val normalized = runCatching { JSONObject(normalizedPath.readText()) }.getOrNull()
                    ?: return@forEach
                val sourceSha256 = normalized.optString("sourceSha256").takeIf(SHA256::matches)
                    ?: return@forEach
                if (sourceSha256 in state.indexedSources) return@forEach
                val conversationNativeId = normalized.optString("conversationNativeId").trim()
                    .takeIf { it.isNotBlank() }
                    ?: return@forEach
                val observedAt = normalized.optString("normalizedAt").trim()

                val normalizedTitle = normalized.optNullableTitle("displayTitle")
                val rawBody = File(bodiesDirectory, "$sourceSha256.body")
                val rawTitle = if (normalizedTitle == null && rawBody.isFile) {
                    readCapturedTitle(rawBody)
                } else {
                    null
                }

                val title = normalizedTitle ?: rawTitle
                if (title != null) {
                    val previous = state.titles[conversationNativeId]
                    if (previous == null || observedAt >= previous.observedAt) {
                        state.titles[conversationNativeId] = TitleRecord(
                            title = title,
                            observedAt = observedAt,
                            sourceSha256 = sourceSha256,
                        )
                    }
                }

                // Raw evidence is persisted before normalization. If it exists,
                // this source has been completely inspected for the current catalog format.
                if (normalizedTitle != null || rawBody.isFile) {
                    state.indexedSources += sourceSha256
                    changed = true
                }
            }

        if (changed) writeCatalog(state)
        cached = state
    }

    fun titleFor(conversationNativeId: String): String? =
        state().titles[conversationNativeId]?.title

    fun matchingConversationIds(query: String, limit: Int): List<String> {
        require(limit > 0)
        val needle = query.trim().lowercase()
        if (needle.isBlank()) return emptyList()
        return state().titles.entries
            .asSequence()
            .filter { (_, record) -> record.title.lowercase().contains(needle) }
            .sortedByDescending { (_, record) -> record.observedAt }
            .map { it.key }
            .take(limit)
            .toList()
    }

    private fun state(): CatalogState = cached ?: synchronized(this) {
        cached ?: readCatalog().also { cached = it }
    }

    private fun readCapturedTitle(bodyPath: File): String? {
        if (bodyPath.length() > MAX_BODY_BYTES) return null
        val root = runCatching {
            val tokener = JSONTokener(bodyPath.readText(Charsets.UTF_8))
            val value = tokener.nextValue()
            if (tokener.nextClean() != '\u0000') null else value as? JSONObject
        }.getOrNull() ?: return null

        if (root.has("title")) {
            Log.d(TAG, "BKE DNA title: candidate_root_title")
        }
        val titleValue = root.opt("title")
        if (titleValue is String) {
            Log.d(TAG, "BKE DNA title: candidate_title_string")
        }
        return (titleValue as? String)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun readCatalog(): CatalogState {
        if (!catalogFile.isFile) return CatalogState()
        val root = runCatching { JSONObject(catalogFile.readText()) }.getOrNull()
            ?: return CatalogState()
        if (root.optInt("formatVersion") != FORMAT_VERSION) return CatalogState()

        val indexed = linkedSetOf<String>()
        val indexedArray = root.optJSONArray("indexedSources") ?: JSONArray()
        for (index in 0 until indexedArray.length()) {
            indexedArray.optString(index).takeIf(SHA256::matches)?.let(indexed::add)
        }

        val titles = linkedMapOf<String, TitleRecord>()
        val titlesObject = root.optJSONObject("titles") ?: JSONObject()
        val keys = titlesObject.keys()
        while (keys.hasNext()) {
            val conversationNativeId = keys.next()
            val record = titlesObject.optJSONObject(conversationNativeId) ?: continue
            val title = record.optString("title").trim().takeIf { it.isNotBlank() } ?: continue
            val sourceSha256 = record.optString("sourceSha256").takeIf(SHA256::matches) ?: continue
            titles[conversationNativeId] = TitleRecord(
                title = title,
                observedAt = record.optString("observedAt"),
                sourceSha256 = sourceSha256,
            )
        }
        return CatalogState(indexed, titles)
    }

    private fun writeCatalog(state: CatalogState) {
        val titlesObject = JSONObject()
        state.titles.toSortedMap().forEach { (conversationNativeId, record) ->
            titlesObject.put(
                conversationNativeId,
                JSONObject()
                    .put("title", record.title)
                    .put("observedAt", record.observedAt)
                    .put("sourceSha256", record.sourceSha256),
            )
        }
        val root = JSONObject()
            .put("formatVersion", FORMAT_VERSION)
            .put("indexedSources", JSONArray(state.indexedSources.sorted()))
            .put("titles", titlesObject)

        val temp = File(catalogFile.parentFile, ".${catalogFile.name}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temp, false).use { output ->
                output.write(root.toString(2).toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            try {
                Files.move(
                    temp.toPath(),
                    catalogFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), catalogFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private data class CatalogState(
        val indexedSources: MutableSet<String> = linkedSetOf(),
        val titles: MutableMap<String, TitleRecord> = linkedMapOf(),
    )

    private data class TitleRecord(
        val title: String,
        val observedAt: String,
        val sourceSha256: String,
    )

    companion object {
        private const val TAG = "BkeDnaTitles"
        private const val CATALOG_FILE_NAME = "conversation-title-catalog.json"
        private const val FORMAT_VERSION = 1
        private const val MAX_BODY_BYTES = 16L * 1024 * 1024
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

private fun JSONObject.optNullableTitle(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).trim().takeIf { it.isNotBlank() } else null
