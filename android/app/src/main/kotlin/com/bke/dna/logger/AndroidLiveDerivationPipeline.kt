package com.bke.dna.logger

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.time.Instant

/**
 * Derivative live pipeline invoked only after exact RAW is verified inside
 * Working Data SQLite. Failures here never invalidate RAW evidence.
 *
 * The scheduler owns pacing. This pipeline exposes coarse stages so the single
 * derivation lane can persist progress and deliberately breathe between them.
 */
class AndroidLiveDerivationPipeline(context: Context) {
    private val appContext = context.applicationContext
    private val normalizer = AndroidConversationNormalizationDispatcher(appContext)

    fun processCompletedCapture(
        sourceSha256: String,
        byteLength: Long,
        contentType: String?,
        onStage: (String) -> Unit = {},
    ): Boolean {
        return try {
            Log.d(TAG, "BKE DNA derivation: started")
            Log.d(
                TAG,
                "BKE DNA derivation: source_${sourceSha256.take(SOURCE_PREFIX_LENGTH)} bytes_$byteLength",
            )

            onStage(STAGE_CLASSIFYING)
            ensureClassification(sourceSha256, byteLength, contentType)
            logClassificationOutcome(readClassificationOutcome(sourceSha256))

            onStage(STAGE_NORMALIZING)
            val normalized = normalizer.normalizeCandidate(sourceSha256)
            if (normalized == null) {
                Log.d(TAG, "BKE DNA derivation: normalization_skipped")
                true
            } else {
                Log.d(TAG, "BKE DNA derivation: normalization_complete")

                onStage(STAGE_RECONCILING)
                AndroidConversationAggregationEngine(appContext).use { engine ->
                    engine.aggregateConversation(normalized.conversationNativeId)
                }
                Log.d(TAG, "BKE DNA derivation: reconciliation_complete")
                true
            }
        } catch (error: Exception) {
            // Classification, normalization, and aggregation are derivatives.
            // Exact RAW evidence is already verified inside Working Data SQLite.
            // Log only the exception class and stack trace; never log RAW payloads.
            val errorType = error.javaClass.simpleName.ifBlank { "Exception" }
            Log.d(TAG, "BKE DNA derivation: derivative_failed")
            Log.d(TAG, "BKE DNA derivation: derivative_failed_$errorType")
            Log.d(TAG, "BKE DNA derivation: derivative_failed_stack", error)
            false
        }
    }

    private fun ensureClassification(
        sourceSha256: String,
        byteLength: Long,
        contentType: String?,
    ) {
        if (AndroidDerivativeSourceAccess.readClassification(appContext, sourceSha256) != null) return

        var errorType: String? = null
        val classification = try {
            if (byteLength > MAX_CLASSIFICATION_BYTES) {
                AndroidPayloadClassification.other("classification_size_limit")
            } else {
                val bytes = AndroidRawSourceAccess.readAllBytes(
                    appContext,
                    sourceSha256,
                    MAX_CLASSIFICATION_BYTES,
                ) ?: error("RAW source is not available")
                AndroidConversationPayloadClassifier.classify(bytes, contentType)
            }
        } catch (error: Exception) {
            errorType = error.javaClass.simpleName
            AndroidPayloadClassification.other("classifier_error")
        }

        val envelope = JSONObject()
            .put("sha256", sourceSha256)
            .put("byteLength", byteLength)
            .put("contentType", contentType ?: JSONObject.NULL)
            .put("classifiedAt", Instant.now().toString())
            .put("classification", classification.toJson())
            .put("errorType", errorType ?: JSONObject.NULL)
        AndroidDerivativeStore(appContext).use { store ->
            store.putClassificationJson(sourceSha256, envelope.toString(2))
        }
    }

    private fun readClassificationOutcome(sourceSha256: String): ClassificationOutcome {
        val payload = AndroidDerivativeSourceAccess.readClassification(appContext, sourceSha256)
            ?: error("Classification derivative is not available")
        val classification = JSONObject(payload).getJSONObject("classification")
        val signals = buildSet {
            val array = classification.getJSONArray("signals")
            for (index in 0 until array.length()) {
                add(array.getString(index))
            }
        }
        return ClassificationOutcome(
            kind = classification.getString("kind"),
            score = classification.getInt("score"),
            confidence = classification.getString("confidence"),
            signals = signals,
        )
    }

    private fun logClassificationOutcome(outcome: ClassificationOutcome) {
        val event = when {
            outcome.kind == CANDIDATE_KIND && outcome.confidence == "high" ->
                "classification_candidate_high"
            outcome.kind == CANDIDATE_KIND ->
                "classification_candidate_medium"
            "classification_size_limit" in outcome.signals ->
                "classification_other_size_limit"
            "classifier_error" in outcome.signals ->
                "classification_other_error"
            "non_textual_content_type" in outcome.signals ->
                "classification_other_non_textual"
            "invalid_utf8" in outcome.signals ->
                "classification_other_invalid_utf8"
            "no_json_structure" in outcome.signals ->
                "classification_other_no_json"
            else ->
                "classification_other_low_score"
        }
        Log.d(TAG, "BKE DNA derivation: $event")
        when {
            event == "classification_other_low_score" -> logLowScoreClassifierShape(outcome)
            outcome.kind == CANDIDATE_KIND -> logCandidateClassifierShape(outcome)
        }
    }

    private fun logLowScoreClassifierShape(outcome: ClassificationOutcome) {
        when (outcome.score) {
            in 0..9 -> Log.d(TAG, "BKE DNA derivation: classifier_score_0_9")
            in 10..19 -> Log.d(TAG, "BKE DNA derivation: classifier_score_10_19")
            in 20..27 -> Log.d(TAG, "BKE DNA derivation: classifier_score_20_27")
            else -> Log.d(TAG, "BKE DNA derivation: classifier_score_unexpected")
        }
        logClassifierSignals(outcome)
    }

    private fun logCandidateClassifierShape(outcome: ClassificationOutcome) {
        logClassifierSignals(outcome)
    }

    private fun logClassifierSignals(outcome: ClassificationOutcome) {
        if ("mapping" in outcome.signals) Log.d(TAG, "BKE DNA derivation: classifier_signal_mapping")
        if ("messages" in outcome.signals) Log.d(TAG, "BKE DNA derivation: classifier_signal_messages")
        if ("message" in outcome.signals) Log.d(TAG, "BKE DNA derivation: classifier_signal_message")
        if ("author" in outcome.signals) Log.d(TAG, "BKE DNA derivation: classifier_signal_author")
        if ("role" in outcome.signals) Log.d(TAG, "BKE DNA derivation: classifier_signal_role")
        if ("content" in outcome.signals) Log.d(TAG, "BKE DNA derivation: classifier_signal_content")
        if ("parts" in outcome.signals) Log.d(TAG, "BKE DNA derivation: classifier_signal_parts")
        if ("parent" in outcome.signals) Log.d(TAG, "BKE DNA derivation: classifier_signal_parent")
        if ("children" in outcome.signals) Log.d(TAG, "BKE DNA derivation: classifier_signal_children")
        if ("conversation_id" in outcome.signals) Log.d(TAG, "BKE DNA derivation: classifier_signal_conversation_id")
        if ("current_node" in outcome.signals) Log.d(TAG, "BKE DNA derivation: classifier_signal_current_node")
        if ("recognized_message_role" in outcome.signals) Log.d(TAG, "BKE DNA derivation: classifier_signal_recognized_role")
        if ("conversation_graph_shape" in outcome.signals) Log.d(TAG, "BKE DNA derivation: classifier_signal_graph_shape")
        if ("authored_message_shape" in outcome.signals) Log.d(TAG, "BKE DNA derivation: classifier_signal_authored_shape")
    }

    private data class ClassificationOutcome(
        val kind: String,
        val score: Int,
        val confidence: String,
        val signals: Set<String>,
    )

    companion object {
        const val STAGE_CLASSIFYING = "CLASSIFYING"
        const val STAGE_NORMALIZING = "NORMALIZING"
        const val STAGE_RECONCILING = "RECONCILING"

        private const val TAG = "BkeDnaDerivation"
        private const val CANDIDATE_KIND = "conversation_payload_candidate"
        private const val SOURCE_PREFIX_LENGTH = 8
        private const val MAX_CLASSIFICATION_BYTES = 16L * 1024 * 1024
    }
}
