package com.bke.dna.logger

import android.content.Context
import android.database.sqlite.SQLiteDatabase

/**
 * Read-only federation across Latest and all saved Working Data SQLite generations.
 *
 * Library queries touch only lightweight logical_conversation rows. Full logical
 * state, raw payloads and human derivatives are resolved only after the owner
 * opens or exports a specific conversation.
 *
 * Owner-facing titles come from captured title evidence, never from a synthesized
 * first user prompt or a visible conversation UUID fallback. Ordinary blank
 * library loads hydrate only bounded conversation-list metadata evidence; an
 * explicit title search may perform the heavier exact-conversation evidence pass.
 */
class AndroidUnifiedConversationLibrary(context: Context) {
    private val appContext = context.applicationContext
    private val workingData = AndroidWorkingDataManager(appContext)
    private val titleCatalog = AndroidConversationTitleCatalog(appContext)

    fun search(
        query: String = "",
        limit: Int = DEFAULT_PAGE_SIZE,
    ): List<AndroidUnifiedConversationSummary> {
        require(limit in 1..MAX_PAGE_SIZE) { "Unified conversation page size is out of range" }
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) {
            // Fast path: conversation-list responses are bounded metadata and can
            // hydrate real titles without scanning multi-megabyte message RAW.
            titleCatalog.refreshFromConversationListEvidence()
        } else {
            // Explicit search is allowed to perform the full evidence recovery.
            titleCatalog.refreshFromEvidence()
        }
        val titleMatchedIds = if (normalizedQuery.isBlank()) {
            emptyList()
        } else {
            titleCatalog.matchingConversationIds(normalizedQuery, MAX_METADATA_ROWS_PER_GENERATION)
        }
        val generations = workingData.listWorkingData()
        var perGenerationLimit = maxOf(limit, MIN_PER_GENERATION_LIMIT)
        var merged = emptyList<AndroidUnifiedConversationSummary>()

        while (true) {
            val rows = generations.flatMap { generation ->
                runCatching {
                    queryGeneration(
                        generation = generation,
                        query = normalizedQuery,
                        titleMatchedIds = titleMatchedIds,
                        limit = perGenerationLimit,
                    )
                }.getOrDefault(emptyList())
            }
            merged = rows
                .groupBy { it.conversationNativeId }
                .map { (_, copies) -> mergeCopies(copies) }
                .sortedWith(
                    compareByDescending<AndroidUnifiedConversationSummary> { it.stateObservedThrough }
                        .thenBy { it.displayTitle.lowercase() }
                        .thenBy { it.conversationNativeId },
                )

            if (merged.size >= limit || perGenerationLimit >= MAX_METADATA_ROWS_PER_GENERATION) break
            perGenerationLimit = (perGenerationLimit * 2)
                .coerceAtMost(MAX_METADATA_ROWS_PER_GENERATION)
        }

        return merged.take(limit)
    }

    fun resolve(conversationNativeId: String): AndroidUnifiedConversationLocation {
        require(conversationNativeId.isNotBlank())
        val copies = workingData.listWorkingData().mapNotNull { generation ->
            runCatching { queryExact(generation, conversationNativeId) }.getOrNull()
        }
        require(copies.isNotEmpty()) { "Conversation does not exist in any readable Working Data generation" }
        val displayTitle = titleCatalog.titleFor(conversationNativeId) ?: UNTITLED_TITLE
        return preferred(copies).toLocation(
            allGenerationIds = copies.map { it.generation.id }.distinct(),
            displayTitle = displayTitle,
        )
    }

    private fun mergeCopies(copies: List<LibraryRow>): AndroidUnifiedConversationSummary {
        val primary = preferred(copies)
        val generationIds = copies.map { it.generation.id }.distinct()
        return AndroidUnifiedConversationSummary(
            conversationNativeId = primary.conversationNativeId,
            conversationKey = primary.conversationKey,
            displayTitle = titleCatalog.titleFor(primary.conversationNativeId) ?: UNTITLED_TITLE,
            stateObservedThrough = primary.stateObservedThrough,
            coverageStatus = primary.coverageStatus,
            sourceCount = copies.maxOf { it.sourceCount },
            nodeCount = copies.maxOf { it.nodeCount },
            generationCount = generationIds.size,
            hasLatest = copies.any { it.generation.isLatest },
            dnaArchived = copies.any { it.dnaArchived },
        )
    }

    private fun preferred(copies: List<LibraryRow>): LibraryRow = copies.maxWith(
        compareBy<LibraryRow> { it.stateObservedThrough }
            .thenBy { if (it.generation.isLatest) 1 else 0 }
            .thenBy { it.generation.createdAt.orEmpty() },
    )

    private fun queryExact(
        generation: AndroidWorkingDataGeneration,
        conversationNativeId: String,
    ): LibraryRow? = openReadOnly(generation).use { database ->
        queryRows(
            database = database,
            generation = generation,
            selection = "conversation_native_id = ?",
            selectionArgs = arrayOf(conversationNativeId),
            limit = 1,
        ).firstOrNull()
    }

    private fun queryGeneration(
        generation: AndroidWorkingDataGeneration,
        query: String,
        titleMatchedIds: List<String>,
        limit: Int,
    ): List<LibraryRow> = openReadOnly(generation).use { database ->
        if (query.isBlank()) {
            return@use queryRows(database, generation, null, null, limit)
        }

        val direct = queryRows(
            database = database,
            generation = generation,
            selection = "conversation_native_id LIKE ? COLLATE NOCASE",
            selectionArgs = arrayOf("%$query%"),
            limit = limit,
        )
        if (titleMatchedIds.isEmpty()) return@use direct

        val titleRows = queryRowsByConversationIds(
            database = database,
            generation = generation,
            conversationNativeIds = titleMatchedIds,
            limit = limit,
        )
        (direct + titleRows)
            .distinctBy { it.conversationNativeId }
            .sortedWith(
                compareByDescending<LibraryRow> { it.stateObservedThrough }
                    .thenBy { it.conversationNativeId },
            )
            .take(limit)
    }

    private fun queryRowsByConversationIds(
        database: SQLiteDatabase,
        generation: AndroidWorkingDataGeneration,
        conversationNativeIds: List<String>,
        limit: Int,
    ): List<LibraryRow> {
        val result = mutableListOf<LibraryRow>()
        conversationNativeIds.asSequence()
            .take(MAX_METADATA_ROWS_PER_GENERATION)
            .chunked(SQLITE_ID_BATCH)
            .forEach { batch ->
                if (result.size >= limit) return@forEach
                val placeholders = batch.joinToString(",") { "?" }
                result += queryRows(
                    database = database,
                    generation = generation,
                    selection = "conversation_native_id IN ($placeholders)",
                    selectionArgs = batch.toTypedArray(),
                    limit = minOf(batch.size, limit - result.size),
                )
            }
        return result.distinctBy { it.conversationNativeId }.take(limit)
    }

    private fun queryRows(
        database: SQLiteDatabase,
        generation: AndroidWorkingDataGeneration,
        selection: String?,
        selectionArgs: Array<String>?,
        limit: Int,
    ): List<LibraryRow> {
        val projection = arrayOf(
            "conversation_key",
            "conversation_native_id",
            "state_observed_through",
            "coverage_status",
            "source_count",
            "node_count",
            "dna_archived",
        )
        val cursor = database.query(
            "logical_conversation",
            projection,
            selection,
            selectionArgs,
            null,
            null,
            "state_observed_through DESC, conversation_key ASC",
            limit.toString(),
        )
        cursor.use {
            return buildList {
                while (it.moveToNext()) {
                    add(
                        LibraryRow(
                            generation = generation,
                            conversationKey = it.getString(0),
                            conversationNativeId = it.getString(1),
                            stateObservedThrough = it.getString(2),
                            coverageStatus = it.getString(3),
                            sourceCount = it.getInt(4),
                            nodeCount = it.getInt(5),
                            dnaArchived = it.getInt(6) != 0,
                        ),
                    )
                }
            }
        }
    }

    private fun openReadOnly(generation: AndroidWorkingDataGeneration): SQLiteDatabase =
        SQLiteDatabase.openDatabase(
            generation.databaseFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )

    private data class LibraryRow(
        val generation: AndroidWorkingDataGeneration,
        val conversationKey: String,
        val conversationNativeId: String,
        val stateObservedThrough: String,
        val coverageStatus: String,
        val sourceCount: Int,
        val nodeCount: Int,
        val dnaArchived: Boolean,
    ) {
        fun toLocation(
            allGenerationIds: List<String>,
            displayTitle: String,
        ) = AndroidUnifiedConversationLocation(
            conversationNativeId = conversationNativeId,
            conversationKey = conversationKey,
            displayTitle = displayTitle,
            stateObservedThrough = stateObservedThrough,
            generation = generation,
            allGenerationIds = allGenerationIds,
        )
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 40
        const val MAX_PAGE_SIZE = 10_000
        const val UNTITLED_TITLE = "Untitled conversation"
        private const val MIN_PER_GENERATION_LIMIT = 40
        private const val MAX_METADATA_ROWS_PER_GENERATION = 10_000
        private const val SQLITE_ID_BATCH = 400
    }
}

data class AndroidUnifiedConversationSummary(
    val conversationNativeId: String,
    val conversationKey: String,
    val displayTitle: String,
    val stateObservedThrough: String,
    val coverageStatus: String,
    val sourceCount: Int,
    val nodeCount: Int,
    val generationCount: Int,
    val hasLatest: Boolean,
    val dnaArchived: Boolean,
)

data class AndroidUnifiedConversationLocation(
    val conversationNativeId: String,
    val conversationKey: String,
    val displayTitle: String,
    val stateObservedThrough: String,
    val generation: AndroidWorkingDataGeneration,
    val allGenerationIds: List<String>,
)
