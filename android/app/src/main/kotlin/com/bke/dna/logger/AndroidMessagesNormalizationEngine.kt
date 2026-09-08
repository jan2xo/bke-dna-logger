package com.bke.dna.logger

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant

/**
 * Normalizes the modern root `messages` array representation independently of
 * generic-mapping-graph-v0. This representation does not expose parent/child
 * graph edges, so none are invented here.
 */
class AndroidMessagesNormalizationEngine(context: android.content.Context) {
    private val captureRoot = AndroidDnaPaths.capturesRoot(context.applicationContext)
    private val bodiesDirectory = File(captureRoot, "bodies")
    private val classificationsDirectory = File(captureRoot, "classifications")
    private val normalizedDirectory = File(captureRoot, "normalized").also {
        check(it.exists() || it.mkdirs()) { "Unable to create Android normalized directory" }
    }

    fun normalizeCandidate(sourceSha256: String): AndroidNormalizationResult? {
        require(SHA256.matches(sourceSha256)) { "Expected lowercase SHA-256 source identity" }
        val classificationPath = File(classificationsDirectory, "$sourceSha256.json")
        if (!classificationPath.isFile) return null

        val classificationRoot = JSONObject(classificationPath.readText())
        if (classificationRoot.getJSONObject("classification").getString("kind") != CANDIDATE_KIND) {
            return null
        }

        val outputPath = File(normalizedDirectory, "$sourceSha256.json")
        if (outputPath.isFile) return readExistingResult(outputPath, sourceSha256)

        val bodyPath = File(bodiesDirectory, "$sourceSha256.body")
        if (!bodyPath.isFile) {
            Log.d(TAG, "BKE DNA normalization: normalization_skip_body_missing")
            return null
        }
        if (bodyPath.length() > MAX_BODY_BYTES) {
            Log.d(TAG, "BKE DNA normalization: normalization_skip_body_oversize")
            return null
        }

        val root = try {
            val tokener = JSONTokener(bodyPath.readText(Charsets.UTF_8))
            val value = tokener.nextValue()
            if (tokener.nextClean() != '\u0000' || value !is JSONObject) {
                Log.d(TAG, "BKE DNA normalization: normalization_skip_invalid_messages_representation")
                return null
            }
            value
        } catch (_: Exception) {
            Log.d(TAG, "BKE DNA normalization: normalization_skip_invalid_messages_representation")
            return null
        }

        val messages = root.optJSONArray("messages") ?: run {
            Log.d(TAG, "BKE DNA normalization: normalization_skip_no_root_messages_array")
            return null
        }
        val conversationNativeId = scalarToString(root.opt("conversation_id"))
            ?.takeIf { it.isNotBlank() }
            ?: run {
                Log.d(TAG, "BKE DNA normalization: normalization_skip_missing_conversation_id")
                return null
            }
        val sourceCurrentNodeId = scalarToString(root.opt("current_node"))?.takeIf { it.isNotBlank() }

        val nodes = buildList {
            for (index in 0 until messages.length()) {
                val message = messages.optJSONObject(index) ?: continue
                parseMessage(message)?.let(::add)
            }
        }
        if (nodes.isEmpty()) {
            Log.d(TAG, "BKE DNA normalization: normalization_skip_no_identified_messages")
            return null
        }

        val knownMessageIds = nodes.mapTo(linkedSetOf()) { it.nodeNativeId }
        val currentNodeNativeId = sourceCurrentNodeId?.takeIf { it in knownMessageIds }
        val currentNodeFound = currentNodeNativeId != null

        val normalized = JSONObject()
            .put("sourceSha256", sourceSha256)
            .put("parser", PARSER)
            .put("conversationNativeId", conversationNativeId)
            .put("currentNodeNativeId", currentNodeNativeId ?: JSONObject.NULL)
            .put("coverageStatus", "indeterminate")
            .put("coverageBasis", COVERAGE_BASIS)
            .put("rootFound", false)
            .put("currentNodeFound", currentNodeFound)
            .put("currentLeafFound", false)
            .put("parentChainComplete", false)
            .put("cycleDetected", false)
            .put("unresolvedParentNativeIds", JSONArray())
            .put("unresolvedChildNativeIds", JSONArray())
            .put("nodes", JSONArray(nodes.map { it.toJson() }))
            .put("normalizedAt", Instant.now().toString())

        writeDerivativeAtomically(outputPath, normalized.toString(2))
        Log.d(TAG, "BKE DNA normalization: messages_array_normalization_complete")
        return AndroidNormalizationResult(sourceSha256, conversationNativeId, outputPath)
    }

    private fun parseMessage(message: JSONObject): NormalizedMessageNode? {
        val messageNativeId = scalarToString(message.opt("id"))?.takeIf { it.isNotBlank() }
            ?: return null
        val role = message.optJSONObject("author")?.let { scalarToString(it.opt("role")) }
        val createdAt = scalarToString(message.opt("create_time"))
        val content = if (message.has("content")) message.opt("content") else null
        val contentJson = if (content == null) null else compactJson(content)
        val textParts = if (content is JSONObject) stringArray(content.optJSONArray("parts")) else emptyList()

        return NormalizedMessageNode(
            nodeNativeId = messageNativeId,
            messageNativeId = messageNativeId,
            role = role,
            createdAt = createdAt,
            textParts = textParts,
            contentJson = contentJson,
        )
    }

    private fun readExistingResult(path: File, sourceSha256: String): AndroidNormalizationResult? {
        val root = runCatching { JSONObject(path.readText()) }.getOrNull() ?: return null
        if (root.optString("sourceSha256") != sourceSha256) return null
        val conversationNativeId = scalarToString(root.opt("conversationNativeId"))?.takeIf { it.isNotBlank() }
            ?: return null
        return AndroidNormalizationResult(sourceSha256, conversationNativeId, path)
    }

    private fun writeDerivativeAtomically(target: File, text: String) {
        val temp = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temp, false).use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            try {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun stringArray(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                scalarToString(array.opt(index))?.takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun scalarToString(value: Any?): String? = when (value) {
        null, JSONObject.NULL -> null
        is String -> value
        is Number, is Boolean -> value.toString()
        else -> null
    }

    private fun compactJson(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject, is JSONArray -> value.toString()
        is String -> JSONObject.quote(value)
        is Number, is Boolean -> value.toString()
        else -> JSONObject.quote(value.toString())
    }

    private data class NormalizedMessageNode(
        val nodeNativeId: String,
        val messageNativeId: String,
        val role: String?,
        val createdAt: String?,
        val textParts: List<String>,
        val contentJson: String?,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("nodeNativeId", nodeNativeId)
            .put("messageNativeId", messageNativeId)
            .put("parentNativeId", JSONObject.NULL)
            .put("childNativeIds", JSONArray())
            .put("role", role ?: JSONObject.NULL)
            .put("createdAt", createdAt ?: JSONObject.NULL)
            .put("textParts", JSONArray(textParts))
            .put("contentJson", contentJson ?: JSONObject.NULL)
    }

    companion object {
        private const val TAG = "BkeDnaNormalizer"
        private const val MAX_BODY_BYTES = 16L * 1024 * 1024
        private const val CANDIDATE_KIND = "conversation_payload_candidate"
        private const val PARSER = "messages-array-v0"
        private const val COVERAGE_BASIS = "messages_array_no_graph_edges"
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}
