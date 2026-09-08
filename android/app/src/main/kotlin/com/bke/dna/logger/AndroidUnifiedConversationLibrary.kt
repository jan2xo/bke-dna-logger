package com.bke.dna.logger

import android.content.Context
import android.database.sqlite.SQLiteDatabase

/**
 * Read-only federation across Latest and all saved Working Data SQLite generations.
 *
 * Library queries touch only lightweight logical_conversation rows. Full logical
 * state, raw payloads and human derivatives are resolved only after the owner
 * opens or exports a specific conversation.
 */
class AndroidUnifiedConversationLibrary(context: Context) {
    private val appContext = context.applicationContext
    private val workingData = AndroidWorkingDataManager(appContext)

    fun search(
        query: String = "",
        limit: Int = DEFAULT_PAGE_SIZE,
    ): List<AndroidUnifiedConversationSummary> {
        require(limit in 1..MAX_PAGE_SIZE) { "Unified conversation page size is out of range" }
        val normalizedQuery = query.trim()
        val generations = workingData.listWorkingData()
        var perGenerationLimit = maxOf(limit, MIN_PER_GENERATION_LIMIT)
        var merged = emptyList<AndroidUnifiedConversationSummary>()

        while (true) {
            val rows = generations.flatMap { generation ->
                queryGeneration(generation, normalizedQuery, perGenerationLimit)
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
            queryExact(generation, conversationNativeId)
        }
        require(copies.isNotEmpty()) { "Conversation does not exist in any Working Data generation" }
        return preferred(copies).toLocation(copies.map { it.generation.id }.distinct())
    }

    private fun mergeCopies(copies: List<LibraryRow>): AndroidUnifiedConversationSummary {
        val primary = preferred(copies)
        val bestTitle = primary.displayTitle?.takeIf { it.isNotBlank() }
            ?: copies.asSequence().mapNotNull { it.displayTitle?.takeIf(String::isNotBlank) }.firstOrNull()
            ?: primary.conversationNativeId
        val generationIds = copies.map { it.generation.id }.distinct()
        return AndroidUnifiedConversationSummary(
            conversationNativeId = primary.conversationNativeId,
            conversationKey = primary.conversationKey,
            displayTitle = bestTitle,
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
        limit: Int,
    ): List<LibraryRow> = openReadOnly(generation).use { database ->
        val hasDisplayTitle = hasColumn(database, "logical_conversation", "display_title")
        val selection: String?
        val selectionArgs: Array<String>?
        if (query.isBlank()) {
            selection = null
            selectionArgs = null
        } else if (hasDisplayTitle) {
            selection = "display_title LIKE ? COLLATE NOCASE OR conversation_native_id LIKE ? COLLATE NOCASE"
            val pattern = "%$query%"
            selectionArgs = arrayOf(pattern, pattern)
        } else {
            selection = "conversation_native_id LIKE ? COLLATE NOCASE"
            selectionArgs = arrayOf("%$query%")
        }
        queryRows(database, generation, selection, selectionArgs, limit)
    }

    private fun queryRows(
        database: SQLiteDatabase,
        generation: AndroidWorkingDataGeneration,
        selection: String?,
        selectionArgs: Array<String>?,
        limit: Int,
    ): List<LibraryRow> {
        val hasDisplayTitle = hasColumn(database, "logical_conversation", "display_title")
        val projection = arrayOf(
            "conversation_key",
            "conversation_native_id",
            if (hasDisplayTitle) "display_title" else "NULL AS display_title",
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
                            displayTitle = if (it.isNull(2)) null else it.getString(2),
                            stateObservedThrough = it.getString(3),
                            coverageStatus = it.getString(4),
                            sourceCount = it.getInt(5),
                            nodeCount = it.getInt(6),
                            dnaArchived = it.getInt(7) != 0,
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

    private fun hasColumn(database: SQLiteDatabase, table: String, column: String): Boolean {
        database.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == column) return true
            }
        }
        return false
    }

    private data class LibraryRow(
        val generation: AndroidWorkingDataGeneration,
        val conversationKey: String,
        val conversationNativeId: String,
        val displayTitle: String?,
        val stateObservedThrough: String,
        val coverageStatus: String,
        val sourceCount: Int,
        val nodeCount: Int,
        val dnaArchived: Boolean,
    ) {
        fun toLocation(allGenerationIds: List<String>) = AndroidUnifiedConversationLocation(
            conversationNativeId = conversationNativeId,
            conversationKey = conversationKey,
            displayTitle = displayTitle ?: conversationNativeId,
            stateObservedThrough = stateObservedThrough,
            generation = generation,
            allGenerationIds = allGenerationIds,
        )
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 40
        const val MAX_PAGE_SIZE = 10_000
        private const val MIN_PER_GENERATION_LIMIT = 40
        private const val MAX_METADATA_ROWS_PER_GENERATION = 10_000
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
