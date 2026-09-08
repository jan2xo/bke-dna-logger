package com.bke.dna.logger

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant

/**
 * Derivative live pipeline invoked only after raw body, observation, and SQLite
 * capture projection are durable. Failures here never invalidate raw evidence.
 */
class AndroidLiveDerivationPipeline(context: Context) {
    private val appContext = context.applicationContext
    private val captureRoot = AndroidDnaPaths.capturesRoot(appContext)
    private val classificationsDirectory = File(captureRoot, "classifications").also {
        check(it.exists() || it.mkdirs()) { "Unable to create Android classification directory" }
    }
    private val normalizer = AndroidGraphNormalizationEngine(appContext)

    fun processCompletedCapture(
        bodyFile: File,
        sourceSha256: String,
        byteLength: Long,
        contentType: String?,
    ) {
        try {
            ensureClassification(bodyFile, sourceSha256, byteLength, contentType)
            val normalized = normalizer.normalizeCandidate(sourceSha256) ?: return
            AndroidConversationAggregationEngine(appContext).use { engine ->
                engine.aggregateConversation(normalized.conversationNativeId)
            }
        } catch (_: Exception) {
            // Classification, normalization, and aggregation are derivatives.
            // Raw evidence and its immutable observation are already durable.
        }
    }

    private fun ensureClassification(
        bodyFile: File,
        sourceSha256: String,
        byteLength: Long,
        contentType: String?,
    ) {
        val target = File(classificationsDirectory, "$sourceSha256.json")
        if (target.isFile) return

        var errorType: String? = null
        val classification = try {
            if (byteLength > MAX_CLASSIFICATION_BYTES) {
                AndroidPayloadClassification.other("classification_size_limit")
            } else {
                AndroidConversationPayloadClassifier.classify(bodyFile.readBytes(), contentType)
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
        writeDerivativeAtomically(target, envelope.toString(2))
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

    companion object {
        private const val MAX_CLASSIFICATION_BYTES = 16L * 1024 * 1024
    }
}
