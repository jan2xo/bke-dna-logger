package com.bke.dna.logger

import android.util.Log
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File

/** Selects a representation-specific normalizer without weakening either parser. */
class AndroidConversationNormalizationDispatcher(context: android.content.Context) {
    private val appContext = context.applicationContext
    private val captureRoot = AndroidDnaPaths.capturesRoot(appContext)
    private val classificationsDirectory = File(captureRoot, "classifications")
    private val graphNormalizer = AndroidGraphNormalizationEngine(appContext)
    private val messagesNormalizer = AndroidMessagesNormalizationEngine(appContext)

    fun normalizeCandidate(sourceSha256: String): AndroidNormalizationResult? {
        require(SHA256.matches(sourceSha256)) { "Expected lowercase SHA-256 source identity" }
        val classificationPath = File(classificationsDirectory, "$sourceSha256.json")
        if (!classificationPath.isFile) return null

        val classification = runCatching {
            JSONObject(classificationPath.readText()).getJSONObject("classification")
        }.getOrNull() ?: return null
        if (classification.optString("kind") != CANDIDATE_KIND) return null

        val rawBytes = try {
            AndroidRawSourceAccess.readAllBytes(appContext, sourceSha256, MAX_BODY_BYTES)
        } catch (_: IllegalArgumentException) {
            Log.d(TAG, "BKE DNA normalization: normalization_skip_body_oversize")
            return null
        }
        if (rawBytes == null) {
            Log.d(TAG, "BKE DNA normalization: normalization_skip_body_missing")
            return null
        }

        val root = try {
            val tokener = JSONTokener(String(rawBytes, Charsets.UTF_8))
            val value = tokener.nextValue()
            if (tokener.nextClean() != '\u0000' || value !is JSONObject) {
                Log.d(TAG, "BKE DNA normalization: normalization_skip_unsupported_representation")
                return null
            }
            value
        } catch (_: Exception) {
            Log.d(TAG, "BKE DNA normalization: normalization_skip_unsupported_representation")
            return null
        }

        return when {
            root.optJSONObject("mapping") != null -> {
                Log.d(TAG, "BKE DNA normalization: normalization_representation_mapping_graph")
                graphNormalizer.normalizeCandidate(sourceSha256)
            }
            root.optJSONArray("messages") != null -> {
                Log.d(TAG, "BKE DNA normalization: normalization_representation_messages_array")
                messagesNormalizer.normalizeCandidate(sourceSha256)
            }
            else -> {
                Log.d(TAG, "BKE DNA normalization: normalization_skip_unsupported_representation")
                null
            }
        }
    }

    companion object {
        private const val TAG = "BkeDnaNormalizer"
        private const val MAX_BODY_BYTES = 16L * 1024 * 1024
        private const val CANDIDATE_KIND = "conversation_payload_candidate"
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}
