package com.bke.dna.logger

import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import java.io.File

/**
 * Bounded interactive reader sources.
 *
 * CLEAN pages are resolved from the selected Working Data SQLite projection.
 * RAW pages resolve exact source bytes through SQLite first, with legacy shared
 * SHA-addressed .body fallback only for generations that predate RAW-in-SQLite.
 */
class AndroidCleanConversationPager(
    generation: AndroidWorkingDataGeneration,
    private val conversationKey: String,
) : AutoCloseable {
    private val database = SQLiteDatabase.openDatabase(
        generation.databaseFile.absolutePath,
        null,
        SQLiteDatabase.OPEN_READONLY,
    )
    private val orderedNodes = loadOrderedHumanNodes()

    fun loadPage(startOffset: Int, pageSize: Int = DEFAULT_PAGE_SIZE): AndroidCleanPage {
        require(startOffset >= 0)
        require(pageSize in 1..MAX_PAGE_SIZE)
        if (startOffset >= orderedNodes.size) {
            return AndroidCleanPage(emptyList(), startOffset, null, orderedNodes.size)
        }

        val turns = mutableListOf<AndroidCleanTurn>()
        var cursor = startOffset
        while (cursor < orderedNodes.size && turns.size < pageSize) {
            val batchEnd = minOf(cursor + NODE_BATCH_SIZE, orderedNodes.size)
            val batch = orderedNodes.subList(cursor, batchEnd)
            val latestText = latestTextFor(batch.map { it.nodeNativeId })
            batch.forEach { ref ->
                if (turns.size >= pageSize) return@forEach
                val text = latestText[ref.nodeNativeId]?.trim().orEmpty()
                if (text.isNotBlank()) {
                    turns += AndroidCleanTurn(ref.speaker, text)
                }
                cursor += 1
            }
        }

        return AndroidCleanPage(
            turns = turns,
            startOffset = startOffset,
            nextOffset = cursor.takeIf { it < orderedNodes.size },
            totalCandidateNodes = orderedNodes.size,
        )
    }

    private fun loadOrderedHumanNodes(): List<CleanNodeRef> {
        val cursor = database.query(
            "logical_message_node",
            arrayOf("node_native_id", "roles_json", "created_at_values_json"),
            "conversation_key = ?",
            arrayOf(conversationKey),
            null,
            null,
            null,
        )
        cursor.use {
            return buildList {
                while (it.moveToNext()) {
                    val speaker = cleanSpeaker(it.getString(1)) ?: continue
                    val created = JSONArray(it.getString(2))
                    val sortKey = if (created.length() == 0) "~" else created.optString(0, "~")
                    add(
                        CleanNodeRef(
                            nodeNativeId = it.getString(0),
                            speaker = speaker,
                            sortKey = sortKey,
                        ),
                    )
                }
            }.sortedWith(compareBy<CleanNodeRef> { it.sortKey }.thenBy { it.nodeNativeId })
        }
    }

    private fun latestTextFor(nodeIds: List<String>): Map<String, String> {
        if (nodeIds.isEmpty()) return emptyMap()
        val placeholders = nodeIds.joinToString(",") { "?" }
        val args = arrayOf(conversationKey, *nodeIds.toTypedArray())
        val cursor = database.query(
            "logical_message_revision",
            arrayOf("node_native_id", "text_parts_json", "last_observed_at"),
            "conversation_key = ? AND node_native_id IN ($placeholders)",
            args,
            null,
            null,
            "node_native_id ASC, last_observed_at DESC",
        )
        cursor.use {
            val result = linkedMapOf<String, String>()
            while (it.moveToNext()) {
                val nodeId = it.getString(0)
                if (nodeId in result) continue
                val parts = JSONArray(it.getString(1))
                result[nodeId] = (0 until parts.length())
                    .map { index -> parts.optString(index) }
                    .joinToString("\n\n")
            }
            return result
        }
    }

    private fun cleanSpeaker(rolesJson: String): String? {
        val roles = JSONArray(rolesJson)
        val normalized = (0 until roles.length())
            .map { roles.optString(it).lowercase() }
            .filter { it.isNotBlank() }
            .toSet()
        return when (normalized) {
            setOf("user") -> AndroidHumanExportService.CLEAN_JAN
            setOf("assistant") -> AndroidHumanExportService.CLEAN_RIGHT_HAND
            else -> null
        }
    }

    override fun close() {
        database.close()
    }

    private data class CleanNodeRef(
        val nodeNativeId: String,
        val speaker: String,
        val sortKey: String,
    )

    companion object {
        const val DEFAULT_PAGE_SIZE = 40
        private const val MAX_PAGE_SIZE = 100
        private const val NODE_BATCH_SIZE = 64
    }
}

data class AndroidCleanTurn(
    val speaker: String,
    val text: String,
)

data class AndroidCleanPage(
    val turns: List<AndroidCleanTurn>,
    val startOffset: Int,
    val nextOffset: Int?,
    val totalCandidateNodes: Int,
)

/**
 * Exact RAW stream pager. Only one bounded source window is materialized at a
 * time. New generations read compressed RAW chunks from their selected SQLite;
 * legacy generations can still fall back to shared SHA-addressed .body files.
 */
class AndroidRawConversationPager(
    private val generation: AndroidWorkingDataGeneration,
    private val conversationKey: String,
    private val captureRoot: File,
) : AutoCloseable {
    private val database = SQLiteDatabase.openDatabase(
        generation.databaseFile.absolutePath,
        null,
        SQLiteDatabase.OPEN_READONLY,
    )
    private val sources = loadSources()

    fun firstCursor(): AndroidRawCursor? =
        if (sources.isEmpty()) null else AndroidRawCursor(sourceIndex = 0, byteOffset = 0L)

    fun loadPage(cursor: AndroidRawCursor): AndroidRawPage {
        require(cursor.sourceIndex in sources.indices)
        require(cursor.byteOffset >= 0L)
        val source = sources[cursor.sourceIndex]
        val resolved = AndroidRawSourceAccess.readPage(
            generation = generation,
            captureRoot = captureRoot,
            sourceSha256 = source.sha256,
            byteOffset = cursor.byteOffset,
            maxBytes = RAW_PAGE_BYTES,
        ) ?: return AndroidRawPage(
            cursor = cursor,
            text = rawHeader(source) + "\n[RAW BODY IS NOT RESIDENT IN THIS WORKING DATA]\n",
            nextCursor = nextSourceCursor(cursor.sourceIndex),
            sourceCount = sources.size,
        )

        if (cursor.byteOffset >= resolved.sourceLength || resolved.bytes.isEmpty()) {
            return AndroidRawPage(
                cursor = cursor,
                text = rawHeader(source) + "\n[END OF SOURCE]\n",
                nextCursor = nextSourceCursor(cursor.sourceIndex),
                sourceCount = sources.size,
            )
        }

        val safeLength = safeUtf8PrefixLength(resolved.bytes, resolved.bytes.size)
        val consumed = if (safeLength > 0) safeLength else resolved.bytes.size
        val text = String(resolved.bytes, 0, consumed, Charsets.UTF_8)
        val nextOffset = cursor.byteOffset + consumed
        val nextCursor = if (nextOffset < resolved.sourceLength) {
            AndroidRawCursor(cursor.sourceIndex, nextOffset)
        } else {
            nextSourceCursor(cursor.sourceIndex)
        }
        val prefix = if (cursor.byteOffset == 0L) rawHeader(source) else ""
        return AndroidRawPage(
            cursor = cursor,
            text = prefix + text,
            nextCursor = nextCursor,
            sourceCount = sources.size,
        )
    }

    private fun loadSources(): List<RawSource> {
        val cursor = database.query(
            "conversation_source",
            arrayOf("source_sha256", "observed_at"),
            "conversation_key = ?",
            arrayOf(conversationKey),
            null,
            null,
            "observed_at ASC, source_sha256 ASC",
        )
        cursor.use {
            return buildList {
                while (it.moveToNext()) {
                    add(RawSource(it.getString(0), it.getString(1)))
                }
            }
        }
    }

    private fun nextSourceCursor(sourceIndex: Int): AndroidRawCursor? =
        (sourceIndex + 1).takeIf { it < sources.size }?.let { AndroidRawCursor(it, 0L) }

    private fun rawHeader(source: RawSource): String = buildString {
        append("SOURCE SHA-256: ")
        append(source.sha256)
        append('\n')
        append("Observed at: ")
        append(source.observedAt)
        append("\n\n")
    }

    private fun safeUtf8PrefixLength(bytes: ByteArray, length: Int): Int {
        if (length == 0) return 0
        var index = length - 1
        var continuationBytes = 0
        while (index >= 0 && bytes[index].toInt() and 0xC0 == 0x80) {
            continuationBytes += 1
            index -= 1
        }
        if (index < 0) return 0
        val lead = bytes[index].toInt() and 0xFF
        val expectedLength = when {
            lead and 0x80 == 0 -> 1
            lead and 0xE0 == 0xC0 -> 2
            lead and 0xF0 == 0xE0 -> 3
            lead and 0xF8 == 0xF0 -> 4
            else -> 1
        }
        val actualLength = continuationBytes + 1
        return if (actualLength < expectedLength) index else length
    }

    override fun close() {
        database.close()
    }

    private data class RawSource(
        val sha256: String,
        val observedAt: String,
    )

    companion object {
        const val RAW_PAGE_BYTES = 64 * 1024
    }
}

data class AndroidRawCursor(
    val sourceIndex: Int,
    val byteOffset: Long,
)

data class AndroidRawPage(
    val cursor: AndroidRawCursor,
    val text: String,
    val nextCursor: AndroidRawCursor?,
    val sourceCount: Int,
)
