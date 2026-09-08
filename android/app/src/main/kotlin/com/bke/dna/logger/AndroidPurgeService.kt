package com.bke.dna.logger

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.time.Instant

/**
 * Conservative local-evidence garbage collection.
 *
 * A source can be deleted only when the Latest logical conversation and every
 * source row are already backed by a manually written + re-read + verified .dna.
 * Sources referenced by another logical conversation in any Working Data
 * generation are retained. If even one saved generation cannot be inspected,
 * purge fails closed. SQLite/library rows and logical conversation state stay
 * resident so purge does not erase the owner-facing conversation.
 */
class AndroidPurgeService(context: Context) {
    private val appContext = context.applicationContext
    private val captureRoot = AndroidDnaPaths.capturesRoot(appContext)
    private val workingDataRoot = AndroidDnaPaths.workingDataRoot(appContext)
    private val purgeDirectory = File(captureRoot, "purges").also {
        check(it.exists() || it.mkdirs()) { "Unable to create purge receipt directory" }
    }

    fun plan(conversationNativeId: String): AndroidPurgePlan {
        require(conversationNativeId.isNotBlank())
        val durability = loadLatestDurability(conversationNativeId)
            ?: return AndroidPurgePlan.blocked(conversationNativeId, "Conversation is not present in Latest Working Data")
        if (!durability.conversationArchived) {
            return AndroidPurgePlan.blocked(conversationNativeId, "Conversation does not have a verified .dna archive")
        }
        if (durability.sources.isEmpty()) {
            return AndroidPurgePlan.blocked(conversationNativeId, "Conversation has no indexed source evidence")
        }
        if (durability.sources.any { !it.archived }) {
            return AndroidPurgePlan.blocked(conversationNativeId, "Verified .dna does not cover every source row")
        }
        if (durability.archiveId.isNullOrBlank() || durability.archiveSha256.isNullOrBlank()) {
            return AndroidPurgePlan.blocked(conversationNativeId, "Conversation archive durability metadata is incomplete")
        }
        if (durability.sources.any {
                it.archiveId != durability.archiveId || it.archiveSha256 != durability.archiveSha256
            }
        ) {
            return AndroidPurgePlan.blocked(conversationNativeId, "Conversation sources do not share one verified .dna identity")
        }

        val allSources = durability.sources.map { it.sha256 }.distinct().sorted()
        val eligibleSources = try {
            allSources.filterNot { source -> referencedByOtherConversation(source, conversationNativeId) }
        } catch (_: Exception) {
            return AndroidPurgePlan.blocked(
                conversationNativeId,
                "Unable to prove source exclusivity across every Working Data generation",
            )
        }

        val observationFiles = observationFilesFor(eligibleSources.toSet())
        val evidenceFiles = buildList {
            eligibleSources.forEach { source -> addAll(sourceFiles(source)) }
            addAll(observationFiles)
        }.distinctBy { it.absolutePath }.filter { it.isFile }

        return AndroidPurgePlan(
            conversationNativeId = conversationNativeId,
            conversationKey = durability.conversationKey,
            archiveId = durability.archiveId,
            archiveSha256 = durability.archiveSha256,
            archivedAt = durability.archivedAt,
            allSourceSha256s = allSources,
            purgeableSourceSha256s = eligibleSources,
            bytesReclaimable = evidenceFiles.sumOf { it.length() },
            blockedReason = null,
        )
    }

    fun purge(conversationNativeId: String, confirmation: String): AndroidPurgeResult {
        require(confirmation == CONFIRMATION_TEXT) { "Type exact confirmation '$CONFIRMATION_TEXT'" }
        val current = plan(conversationNativeId)
        require(current.blockedReason == null) { current.blockedReason ?: "Purge is blocked" }
        val conversationKey = requireNotNull(current.conversationKey)

        val observationFiles = observationFilesFor(current.purgeableSourceSha256s.toSet())
        val evidenceFiles = buildList {
            current.purgeableSourceSha256s.forEach { source -> addAll(sourceFiles(source)) }
            addAll(observationFiles)
        }.distinctBy { it.absolutePath }.filter { it.isFile }

        var reclaimed = 0L
        evidenceFiles.forEach { file ->
            val size = file.length()
            check(file.delete() || !file.exists()) { "Unable to purge ${file.name}" }
            reclaimed += size
        }

        val purgedAt = Instant.now().toString()
        val receipt = JSONObject()
            .put("format", "bke-dna-local-purge")
            .put("formatVersion", 1)
            .put("conversationNativeId", current.conversationNativeId)
            .put("conversationKey", conversationKey)
            .put("archiveId", current.archiveId)
            .put("archiveSha256", current.archiveSha256)
            .put("archivedAt", current.archivedAt ?: JSONObject.NULL)
            .put("purgedAt", purgedAt)
            .put("bytesReclaimed", reclaimed)
            .put("sourceSha256s", JSONArray(current.purgeableSourceSha256s))
            .put("logicalStateRetained", true)
            .put("sqliteRetained", true)
        writeDurably(File(purgeDirectory, "$conversationKey.json"), receipt.toString(2))

        return AndroidPurgeResult(
            conversationsPurged = 1,
            sourcesPurged = current.purgeableSourceSha256s.size,
            bytesReclaimed = reclaimed,
            purgedAt = purgedAt,
        )
    }

    fun purgeAllVerified(confirmation: String): AndroidPurgeResult {
        require(confirmation == CONFIRMATION_TEXT) { "Type exact confirmation '$CONFIRMATION_TEXT'" }
        val ids = latestArchivedConversationIds()
        var conversations = 0
        var sources = 0
        var bytes = 0L
        var latestPurgeAt = Instant.now().toString()
        ids.forEach { conversationNativeId ->
            val plan = plan(conversationNativeId)
            if (plan.blockedReason != null || plan.bytesReclaimable <= 0L) return@forEach
            val result = purge(conversationNativeId, confirmation)
            conversations += result.conversationsPurged
            sources += result.sourcesPurged
            bytes += result.bytesReclaimed
            latestPurgeAt = result.purgedAt
        }
        return AndroidPurgeResult(conversations, sources, bytes, latestPurgeAt)
    }

    fun isLocallyPurged(conversationKey: String): Boolean =
        File(purgeDirectory, "$conversationKey.json").isFile

    private fun latestArchivedConversationIds(): List<String> {
        val index = AndroidCaptureIndex(appContext)
        try {
            index.readableDatabase.query(
                "logical_conversation",
                arrayOf("conversation_native_id"),
                "dna_archived = 1",
                null,
                null,
                null,
                "state_observed_through ASC",
            ).use { cursor ->
                return buildList {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
            }
        } finally {
            index.close()
        }
    }

    private fun loadLatestDurability(conversationNativeId: String): LatestDurability? {
        val index = AndroidCaptureIndex(appContext)
        try {
            val db = index.readableDatabase
            val conversation = db.query(
                "logical_conversation",
                arrayOf(
                    "conversation_key",
                    "dna_archived",
                    "dna_archive_id",
                    "dna_archive_sha256",
                    "archived_at",
                ),
                "conversation_native_id = ?",
                arrayOf(conversationNativeId),
                null,
                null,
                null,
                "1",
            ).use { cursor ->
                if (!cursor.moveToFirst()) return null
                ConversationDurability(
                    conversationKey = cursor.getString(0),
                    archived = cursor.getInt(1) != 0,
                    archiveId = if (cursor.isNull(2)) null else cursor.getString(2),
                    archiveSha256 = if (cursor.isNull(3)) null else cursor.getString(3),
                    archivedAt = if (cursor.isNull(4)) null else cursor.getString(4),
                )
            }

            val sources = db.query(
                "conversation_source",
                arrayOf("source_sha256", "dna_archived", "dna_archive_id", "dna_archive_sha256"),
                "conversation_key = ?",
                arrayOf(conversation.conversationKey),
                null,
                null,
                "source_sha256 ASC",
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            SourceDurability(
                                sha256 = cursor.getString(0),
                                archived = cursor.getInt(1) != 0,
                                archiveId = if (cursor.isNull(2)) null else cursor.getString(2),
                                archiveSha256 = if (cursor.isNull(3)) null else cursor.getString(3),
                            ),
                        )
                    }
                }
            }

            return LatestDurability(
                conversationKey = conversation.conversationKey,
                conversationArchived = conversation.archived,
                archiveId = conversation.archiveId,
                archiveSha256 = conversation.archiveSha256,
                archivedAt = conversation.archivedAt,
                sources = sources,
            )
        } finally {
            index.close()
        }
    }

    private fun referencedByOtherConversation(sourceSha256: String, targetConversationNativeId: String): Boolean {
        val databases = buildList {
            add(appContext.getDatabasePath(AndroidCaptureIndex.DATABASE_NAME))
            workingDataRoot.listFiles().orEmpty()
                .filter { it.isDirectory && it.name.startsWith("wd-") }
                .sortedBy { it.name }
                .forEach { directory ->
                    val snapshot = File(directory, "working.sqlite")
                    require(snapshot.isFile) { "Saved Working Data has no SQLite snapshot" }
                    add(snapshot)
                }
        }

        databases.forEach { databaseFile ->
            require(databaseFile.isFile) { "Working Data SQLite does not exist" }
            SQLiteDatabase.openDatabase(
                databaseFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { database ->
                database.rawQuery(
                    """
                    SELECT 1
                    FROM conversation_source cs
                    JOIN logical_conversation lc ON lc.conversation_key = cs.conversation_key
                    WHERE cs.source_sha256 = ? AND lc.conversation_native_id <> ?
                    LIMIT 1
                    """.trimIndent(),
                    arrayOf(sourceSha256, targetConversationNativeId),
                ).use { cursor ->
                    if (cursor.moveToFirst()) return true
                }
            }
        }
        return false
    }

    private fun sourceFiles(sourceSha256: String): List<File> = listOf(
        File(captureRoot, "bodies/$sourceSha256.body"),
        File(captureRoot, "normalized/$sourceSha256.json"),
        File(captureRoot, "classifications/$sourceSha256.json"),
    )

    private fun observationFilesFor(sourceSha256s: Set<String>): List<File> {
        if (sourceSha256s.isEmpty()) return emptyList()
        return File(captureRoot, "observations").listFiles().orEmpty()
            .filter { it.isFile && it.extension == "json" }
            .filter { file ->
                runCatching { JSONObject(file.readText()).optString("sha256") in sourceSha256s }
                    .getOrDefault(false)
            }
    }

    private fun writeDurably(destination: File, text: String) {
        FileOutputStream(destination, false).use { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
    }

    private data class ConversationDurability(
        val conversationKey: String,
        val archived: Boolean,
        val archiveId: String?,
        val archiveSha256: String?,
        val archivedAt: String?,
    )

    private data class SourceDurability(
        val sha256: String,
        val archived: Boolean,
        val archiveId: String?,
        val archiveSha256: String?,
    )

    private data class LatestDurability(
        val conversationKey: String,
        val conversationArchived: Boolean,
        val archiveId: String?,
        val archiveSha256: String?,
        val archivedAt: String?,
        val sources: List<SourceDurability>,
    )

    companion object {
        const val CONFIRMATION_TEXT = "jan2x"
    }
}

data class AndroidPurgePlan(
    val conversationNativeId: String,
    val conversationKey: String?,
    val archiveId: String?,
    val archiveSha256: String?,
    val archivedAt: String?,
    val allSourceSha256s: List<String>,
    val purgeableSourceSha256s: List<String>,
    val bytesReclaimable: Long,
    val blockedReason: String?,
) {
    companion object {
        fun blocked(conversationNativeId: String, reason: String) = AndroidPurgePlan(
            conversationNativeId = conversationNativeId,
            conversationKey = null,
            archiveId = null,
            archiveSha256 = null,
            archivedAt = null,
            allSourceSha256s = emptyList(),
            purgeableSourceSha256s = emptyList(),
            bytesReclaimable = 0,
            blockedReason = reason,
        )
    }
}

data class AndroidPurgeResult(
    val conversationsPurged: Int,
    val sourcesPurged: Int,
    val bytesReclaimed: Long,
    val purgedAt: String,
)
