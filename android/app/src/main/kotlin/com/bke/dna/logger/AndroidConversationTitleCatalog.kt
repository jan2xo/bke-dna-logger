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
 * exact RAW conversation payload belonging to each normalized source and keyed
 * by conversationNativeId. New generations read RAW from SQLite; pre-PR4
 * generations can still fall back to shared SHA-addressed bodies. No
 * user-message title synthesis is allowed.
 */
class AndroidConversationTitleCatalog(context: Context) {
    private val appContext = context.applicationContext
    private val captureRoot = AndroidDnaPaths.capturesRoot(appContext)
    private val normalizedDirectory = File(captureRoot, "normalized")
    private val catalogFile = File(captureRoot, CATALOG_FILE_NAME)

    @Volatile private var cached: CatalogState? = null

    @Synchronized
    fun refreshFromEvidence() {
        val state = readCatalog()
        val generations = AndroidWorkingDataManager(appContext).listWorkingData()
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
                val rawTitleResult = if (normalizedTitle == null) {
                    readCapturedTitle(sourceSha256, generations)
                } else {
                    RawTitleResult(null, inspected = false)
                }

                val title = normalizedTitle ?: rawTitleResult.title
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

                // RAW is persisted before normalization. Once a source can be
                // resolved from any Working Data generation (or a normalized
                // title already exists), the source is completely inspected for
                // this catalog format and does not need repeated full reads.
                if (normalizedTitle != null || rawTitleResult.inspected) {
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

    private fun readCapturedTitle(
        sourceSha256: String,
        generations: List<AndroidWorkingDataGeneration>,
    ): RawTitleResult {
        var bytes: ByteArray? = null
        var inspected = false
        for (generation in generations) {
            val source = runCatching {
                AndroidRawSourceAccess.readAllBytes(
                    generation = generation,
                    captureRoot = captureRoot,
                    sourceSha256 = sourceSha256,
                    maxBytes = MAX_BODY_BYTES,
                )
            }
            if (source.isSuccess) {
                val resolved = source.getOrNull()
                if (resolved != null) {
                    bytes = resolved
                    inspected = true
                    break
                }
            } else if (source.exceptionOrNull() is IllegalArgumentException) {
                // A resolved source that exceeds the bounded title parser has
                // still been inspected for this catalog format.
                inspected = true
                break
            }
        }
        if (bytes == null) return RawTitleResult(null, inspected)

        val root = runCatching {
            val tokener = JSONTokener(String(bytes, Charsets.UTF_8))
            val value = tokener.nextValue()
            if (tokener.nextClean() != '\u0000') null else value as? JSONObject
        }.getOrNull() ?: return RawTitleResult(null, inspected = true)

        if (root.has("title")) {
            Log.d(TAG, "BKE DNA title: candidate_root_title")
        }
        val titleValue = root.opt("title")
        if (titleValue is String) {
            Log.d(TAG, "BKE DNA title: candidate_title_string")
        }
        return RawTitleResult(
            title = (titleValue as? String)?.trim()?.takeIf { it.isNotBlank() },
            inspected = true,
        )
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

    private data class RawTitleResult(
        val title: String?,
        val inspected: Boolean,
    )

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
