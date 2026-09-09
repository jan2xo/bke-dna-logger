package com.bke.dna.logger

import android.util.Log
import org.json.JSONObject
import org.json.JSONTokener

/** Selects a representation-specific normalizer without weakening any parser. */
class AndroidConversationNormalizationDispatcher(context: android.content.Context) {
    private val appContext = context.applicationContext
    private val graphNormalizer = AndroidGraphNormalizationEngine(appContext)
    private val messagesNormalizer = AndroidMessagesNormalizationEngine(appContext)
    private val eventStreamNormalizer = AndroidEventStreamNormalizationEngine(appContext)

    fun normalizeCandidate(sourceSha256: String): AndroidNormalizationResult? {
        require(SHA256.matches(sourceSha256)) { "Expected lowercase SHA-256 source identity" }
        val classificationPayload = AndroidDerivativeSourceAccess.readClassification(appContext, sourceSha256)
            ?: return null
        val classificationRoot = runCatching { JSONObject(classificationPayload) }.getOrNull() ?: return null
        val classification = classificationRoot.optJSONObject("classification") ?: return null
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

        val rawText = String(rawBytes, Charsets.UTF_8)
        val contentType = classificationRoot.opt("contentType")
            ?.takeUnless { it == JSONObject.NULL }
            ?.toString()
        if (contentType?.contains("text/event-stream", ignoreCase = true) == true ||
            looksLikeEventStream(rawText)
        ) {
            Log.d(TAG, "BKE DNA normalization: normalization_representation_event_stream")
            return eventStreamNormalizer.normalizeCandidate(sourceSha256)
        }

        val root = try {
            val tokener = JSONTokener(rawText)
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

        val classifierSignals = buildSet {
            val array = classification.optJSONArray("signals")
            if (array != null) {
                for (index in 0 until array.length()) add(array.optString(index))
            }
        }

        return when {
            root.optJSONObject("mapping") != null -> {
                Log.d(TAG, "BKE DNA normalization: normalization_representation_mapping_graph")
                graphNormalizer.normalizeCandidate(sourceSha256)
            }
            root.optJSONArray("messages") != null ||
                root.optJSONObject("messages") != null ||
                "messages" in classifierSignals -> {
                Log.d(TAG, "BKE DNA normalization: normalization_representation_messages_array")
                messagesNormalizer.normalizeCandidate(sourceSha256)
            }
            else -> {
                Log.d(TAG, "BKE DNA normalization: normalization_skip_unsupported_representation")
                null
            }
        }
    }

    private fun looksLikeEventStream(text: String): Boolean = text.lineSequence()
        .firstOrNull { it.isNotBlank() }
        ?.trimStart()
        ?.startsWith("data:", ignoreCase = true)
        ?: false

    companion object {
        private const val TAG = "BkeDnaNormalizer"
        private const val MAX_BODY_BYTES = 16L * 1024 * 1024
        private const val CANDIDATE_KIND = "conversation_payload_candidate"
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}
