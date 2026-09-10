package com.bke.dna.logger

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Shared derived title index above Working Data generations.
 *
 * Saved SQLite generations remain immutable. Titles are recovered only from
 * explicit captured title evidence and keyed by conversationNativeId. Two
 * evidence lanes are supported:
 *
 * 1. lightweight ChatGPT conversation-list responses, where an object carries
 *    an explicit conversation id and title together; this lane is safe to run
 *    before ordinary library rendering because those metadata responses are
 *    bounded and do not require conversation normalization;
 * 2. normalized conversation sources and their exact RAW bodies, used by the
 *    explicit/full evidence refresh path.
 *
 * No user-message title synthesis is allowed.
 */
class AndroidConversationTitleCatalog(context: Context) {
    private val appContext = context.applicationContext
    private val captureRoot = AndroidDnaPaths.capturesRoot(appContext)
    private val catalogFile = File(captureRoot, CATALOG_FILE_NAME)

    @Volatile private var cached: CatalogState? = null

    /**
     * Lightweight title recovery for the ordinary conversation library.
     * Only captured conversation-list responses are inspected. Large message
     * bodies are never scanned here.
     */
    @Synchronized
    fun refreshFromConversationListEvidence() {
        val state = readCatalog()
        val generations = AndroidWorkingDataManager(appContext).listWorkingData()
        val changed = refreshConversationListEvidence(state, generations)
        if (changed) writeCatalog(state)
        cached = state
    }

    /** Full evidence refresh used by explicit title search/recovery. */
    @Synchronized
    fun refreshFromEvidence() {
        val state = readCatalog()
        val generations = AndroidWorkingDataManager(appContext).listWorkingData()
        val seenSources = linkedSetOf<String>()
        var changed = refreshConversationListEvidence(state, generations)

        for (generation in generations) {
            val sourceSha256s = runCatching {
                AndroidDerivativeSourceAccess.listNormalizedSourceSha256s(generation, captureRoot)
            }.getOrDefault(emptyList())
            for (sourceSha256 in sourceSha256s) {
                if (!seenSources.add(sourceSha256) || sourceSha256 in state.indexedSources) continue
                val normalizedPayload = runCatching {
                    AndroidDerivativeSourceAccess.readNormalized(generation, captureRoot, sourceSha256)
                }.getOrNull() ?: continue
                val normalized = runCatching { JSONObject(normalizedPayload) }.getOrNull() ?: continue
                val conversationNativeId = normalized.optString("conversationNativeId").trim()
                    .takeIf { it.isNotBlank() }
                    ?: continue
                val observedAt = normalized.optString("normalizedAt").trim()

                val normalizedTitle = normalized.optNullableTitle("displayTitle")
                val rawTitleResult = if (normalizedTitle == null) {
                    readCapturedTitle(sourceSha256, conversationNativeId, generations)
                } else {
                    RawTitleResult(null, inspected = false)
                }

                val title = normalizedTitle ?: rawTitleResult.title
                if (title != null) {
                    updateTitle(
                        state = state,
                        conversationNativeId = conversationNativeId,
                        title = title,
                        observedAt = observedAt,
                        sourceSha256 = sourceSha256,
                    )
                }

                if (normalizedTitle != null || rawTitleResult.inspected) {
                    state.indexedSources += sourceSha256
                    changed = true
                }
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

    private fun refreshConversationListEvidence(
        state: CatalogState,
        generations: List<AndroidWorkingDataGeneration>,
    ): Boolean {
        var changed = false
        val evidence = generations
            .flatMap(::conversationListCaptures)
            .distinctBy { "${it.generation.id}:${it.sourceSha256}" }
            .sortedBy { it.observedAt }

        for (capture in evidence) {
            if (capture.sourceSha256 in state.indexedMetadataSources) continue
            val bytes = runCatching {
                AndroidRawSourceAccess.readAllBytes(
                    generation = capture.generation,
                    captureRoot = captureRoot,
                    sourceSha256 = capture.sourceSha256,
                    maxBytes = MAX_CONVERSATION_LIST_BYTES,
                )
            }.getOrNull() ?: continue

            val root = runCatching {
                val tokener = JSONTokener(String(bytes, Charsets.UTF_8))
                val value = tokener.nextValue()
                if (tokener.nextClean() != '\u0000') null else value
            }.getOrNull() ?: continue

            val candidates = linkedMapOf<String, MutableSet<String>>()
            collectConversationListTitles(root, candidates, depth = 0)
            candidates.forEach { (conversationNativeId, titles) ->
                if (titles.size != 1) {
                    if (titles.size > 1) Log.d(TAG, "BKE DNA title: conversations_list_title_conflict")
                    return@forEach
                }
                updateTitle(
                    state = state,
                    conversationNativeId = conversationNativeId,
                    title = titles.single(),
                    observedAt = capture.observedAt,
                    sourceSha256 = capture.sourceSha256,
                )
                Log.d(TAG, "BKE DNA title: conversations_list_title_found")
            }

            state.indexedMetadataSources += capture.sourceSha256
            changed = true
        }
        return changed
    }

    private fun conversationListCaptures(
        generation: AndroidWorkingDataGeneration,
    ): List<ConversationListCapture> = runCatching {
        if (generation.isLatest) {
            AndroidCaptureIndex(appContext).use { index ->
                queryConversationListCaptures(index.readableDatabase, generation)
            }
        } else {
            SQLiteDatabase.openDatabase(
                generation.databaseFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { database ->
                queryConversationListCaptures(database, generation)
            }
        }
    }.getOrDefault(emptyList())

    private fun queryConversationListCaptures(
        database: SQLiteDatabase,
        generation: AndroidWorkingDataGeneration,
    ): List<ConversationListCapture> {
        val cursor = database.query(
            "captures",
            arrayOf("sha256", "request_url", "captured_at"),
            "request_url IS NOT NULL",
            null,
            null,
            null,
            "captured_at ASC, capture_id ASC",
        )
        cursor.use {
            return buildList {
                while (it.moveToNext()) {
                    val sourceSha256 = it.getString(0)
                    val requestUrl = it.getString(1)
                    if (!SHA256.matches(sourceSha256) || !isConversationListRequest(requestUrl)) continue
                    add(
                        ConversationListCapture(
                            generation = generation,
                            sourceSha256 = sourceSha256,
                            observedAt = if (it.isNull(2)) "" else it.getString(2),
                        ),
                    )
                }
            }
        }
    }

    private fun isConversationListRequest(rawUrl: String?): Boolean {
        if (rawUrl.isNullOrBlank()) return false
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return false
        val segments = uri.path.orEmpty().split('/').filter { it.isNotBlank() }
        val index = segments.indexOfLast { it.equals("conversations", ignoreCase = true) }
        return index >= 0 && index == segments.lastIndex
    }

    /**
     * Extract only evidence where one JSON object binds a title to one explicit
     * conversation identity. This supports common conversation-list shapes such
     * as {items:[{id,title}, ...]} without depending on one fixed envelope.
     */
    private fun collectConversationListTitles(
        value: Any?,
        candidates: MutableMap<String, MutableSet<String>>,
        depth: Int,
    ) {
        if (depth > MAX_METADATA_JSON_DEPTH) return
        when (value) {
            is JSONObject -> {
                val identityCandidates = linkedSetOf<String>()
                for (key in CONVERSATION_ID_KEYS) {
                    val id = value.opt(key)
                        ?.takeUnless { it == JSONObject.NULL }
                        ?.toString()
                        ?.trim()
                        ?.takeIf(CONVERSATION_ID::matches)
                    if (id != null) identityCandidates += id
                }
                val title = value.optNullableTitle("title")?.take(DISPLAY_TITLE_LIMIT)
                if (title != null && identityCandidates.size == 1) {
                    candidates.getOrPut(identityCandidates.single()) { linkedSetOf() }.add(title)
                }

                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val child = value.opt(key)
                    if (child is JSONObject || child is JSONArray) {
                        collectConversationListTitles(child, candidates, depth + 1)
                    }
                }
            }
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    val child = value.opt(index)
                    if (child is JSONObject || child is JSONArray) {
                        collectConversationListTitles(child, candidates, depth + 1)
                    }
                }
            }
        }
    }

    private fun readCapturedTitle(
        sourceSha256: String,
        conversationNativeId: String,
        generations: List<AndroidWorkingDataGeneration>,
    ): RawTitleResult {
        // Latest/legacy RAW can be scanned without materializing the payload.
        // Root titles remain the strongest exact-conversation evidence here.
        val streamed = runCatching {
            AndroidRawSourceAccess.withExactInputStream(appContext, sourceSha256) { input ->
                runCatching {
                    JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                        readCapturedRootTitle(reader, conversationNativeId)
                    }
                }.fold(
                    onSuccess = { RawTitleResult(it, inspected = true) },
                    onFailure = { RawTitleResult(null, inspected = true) },
                )
            }
        }.getOrNull()
        if (streamed != null) return streamed

        // Historical pre-streaming generations retain the bounded compatibility
        // path. A payload that exceeds that bound is NOT considered inspected.
        var bytes: ByteArray? = null
        var inspected = false
        for (generation in generations) {
            val source = runCatching {
                AndroidRawSourceAccess.readAllBytes(
                    generation = generation,
                    captureRoot = captureRoot,
                    sourceSha256 = sourceSha256,
                    maxBytes = MAX_LEGACY_BODY_BYTES,
                )
            }
            if (source.isSuccess) {
                val resolved = source.getOrNull()
                if (resolved != null) {
                    bytes = resolved
                    inspected = true
                    break
                }
            }
        }
        if (bytes == null) return RawTitleResult(null, inspected)

        val root = runCatching {
            val tokener = JSONTokener(String(bytes, Charsets.UTF_8))
            val value = tokener.nextValue()
            if (tokener.nextClean() != '\u0000') null else value as? JSONObject
        }.getOrNull() ?: return RawTitleResult(null, inspected = true)

        val candidates = linkedMapOf<String, MutableSet<String>>()
        collectConversationListTitles(root, candidates, depth = 0)
        candidates[conversationNativeId]?.singleOrNull()?.let { title ->
            Log.d(TAG, "BKE DNA title: candidate_bound_title")
            return RawTitleResult(title, inspected = true)
        }

        if (root.has("title")) {
            Log.d(TAG, "BKE DNA title: candidate_root_title")
        }
        val titleValue = root.opt("title")
        if (titleValue is String) {
            Log.d(TAG, "BKE DNA title: candidate_title_string")
        }
        return RawTitleResult(
            title = (titleValue as? String)?.trim()?.takeIf { it.isNotBlank() }?.take(DISPLAY_TITLE_LIMIT),
            inspected = true,
        )
    }

    private fun readCapturedRootTitle(reader: JsonReader, conversationNativeId: String): String? {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return null
        }

        var rootTitle: String? = null
        val rootIds = linkedSetOf<String>()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            when {
                name == "title" -> {
                    Log.d(TAG, "BKE DNA title: candidate_root_title")
                    if (reader.peek() == JsonToken.STRING) {
                        rootTitle = reader.nextString().trim().takeIf { it.isNotBlank() }?.take(DISPLAY_TITLE_LIMIT)
                        if (rootTitle != null) Log.d(TAG, "BKE DNA title: candidate_title_string")
                    } else {
                        reader.skipValue()
                    }
                }
                name in CONVERSATION_ID_KEYS -> {
                    val id = when (reader.peek()) {
                        JsonToken.STRING, JsonToken.NUMBER -> reader.nextString().trim()
                        else -> {
                            reader.skipValue()
                            null
                        }
                    }
                    if (id != null && CONVERSATION_ID.matches(id)) rootIds += id
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        if (reader.peek() != JsonToken.END_DOCUMENT) return null
        if (rootTitle == null) return null
        if (rootIds.isNotEmpty() && conversationNativeId !in rootIds) return null
        return rootTitle
    }

    private fun updateTitle(
        state: CatalogState,
        conversationNativeId: String,
        title: String,
        observedAt: String,
        sourceSha256: String,
    ) {
        val previous = state.titles[conversationNativeId]
        if (previous == null || observedAt >= previous.observedAt) {
            state.titles[conversationNativeId] = TitleRecord(
                title = title,
                observedAt = observedAt,
                sourceSha256 = sourceSha256,
            )
        }
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

        val indexedMetadata = linkedSetOf<String>()
        val metadataArray = root.optJSONArray("indexedMetadataSources") ?: JSONArray()
        for (index in 0 until metadataArray.length()) {
            metadataArray.optString(index).takeIf(SHA256::matches)?.let(indexedMetadata::add)
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
        return CatalogState(indexed, indexedMetadata, titles)
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
            .put("indexedMetadataSources", JSONArray(state.indexedMetadataSources.sorted()))
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

    private data class ConversationListCapture(
        val generation: AndroidWorkingDataGeneration,
        val sourceSha256: String,
        val observedAt: String,
    )

    private data class RawTitleResult(
        val title: String?,
        val inspected: Boolean,
    )

    private data class CatalogState(
        val indexedSources: MutableSet<String> = linkedSetOf(),
        val indexedMetadataSources: MutableSet<String> = linkedSetOf(),
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
        private const val FORMAT_VERSION = 3
        private const val MAX_LEGACY_BODY_BYTES = 16L * 1024 * 1024
        private const val MAX_CONVERSATION_LIST_BYTES = 8L * 1024 * 1024
        private const val MAX_METADATA_JSON_DEPTH = 32
        private const val DISPLAY_TITLE_LIMIT = 240
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val CONVERSATION_ID = Regex("[A-Za-z0-9][A-Za-z0-9_-]{7,127}")
        private val CONVERSATION_ID_KEYS = setOf("id", "conversation_id", "conversationId")
    }
}

private fun JSONObject.optNullableTitle(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).trim().takeIf { it.isNotBlank() } else null
