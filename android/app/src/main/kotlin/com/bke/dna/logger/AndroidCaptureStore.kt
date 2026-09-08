package com.bke.dna.logger

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * Android counterpart of the desktop CaptureStore.
 * Streams into app-private partial files, hashes raw bytes, deduplicates bodies,
 * persists immutable observations, projects the observation into SQLite, then
 * schedules classification/normalization as a best-effort background derivative.
 */
class AndroidCaptureStore(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val root = AndroidDnaPaths.capturesRoot(appContext)
    private val bodies = File(root, "bodies").also { it.mkdirs() }
    private val observations = File(root, "observations").also { it.mkdirs() }
    private val partial = File(root, "partial").also { it.mkdirs() }
    private val sessions = mutableMapOf<String, Session>()
    private val index = AndroidCaptureIndex(appContext)

    fun accept(json: JSONObject): String {
        return when (json.getString("type")) {
            "capture_start" -> start(json)
            "capture_chunk" -> append(json)
            "capture_end" -> end(json)
            "dom_witness" -> "dom_witness"
            else -> error("Unsupported DNA wire type")
        }
    }

    private fun start(json: JSONObject): String {
        val captureId = requireCaptureId(json)
        require(!sessions.containsKey(captureId)) { "Capture '$captureId' has already started" }
        val byteLength = if (json.has("byteLength") && !json.isNull("byteLength")) {
            json.getLong("byteLength").also { require(it >= 0) { "Capture byte length cannot be negative" } }
        } else {
            null
        }

        sessions[captureId] = Session(
            start = JSONObject(json.toString()),
            declaredLength = byteLength,
            file = File(partial, "$captureId.part"),
        )
        return "capture_start"
    }

    private fun append(json: JSONObject): String {
        val captureId = requireCaptureId(json)
        val session = sessions[captureId] ?: error("Capture '$captureId' has not started")
        val sequence = json.getInt("sequence")
        require(sequence == session.nextSequence) {
            "Capture '$captureId' expected sequence ${session.nextSequence}, received $sequence"
        }

        val bytes = try {
            Base64.decode(json.getString("base64"), Base64.DEFAULT)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Capture '$captureId' contains invalid base64", error)
        }
        session.append(bytes)
        return "capture_chunk"
    }

    private fun end(json: JSONObject): String {
        val captureId = requireCaptureId(json)
        val session = sessions.remove(captureId) ?: error("Capture '$captureId' has not started")
        val result = session.finish()
        val finalDeclaredLength = if (json.has("byteLength") && !json.isNull("byteLength")) {
            json.getLong("byteLength")
        } else {
            session.declaredLength
        }
        require(finalDeclaredLength != null && finalDeclaredLength >= 0) {
            "Capture '$captureId' did not declare a final byte length"
        }
        require(result.byteLength == finalDeclaredLength) {
            "Capture '$captureId' declared $finalDeclaredLength bytes but received ${result.byteLength}"
        }

        val bodyName = "${result.sha256}.body"
        val body = File(bodies, bodyName)
        if (body.exists()) {
            result.partial.delete()
        } else {
            check(result.partial.renameTo(body)) { "Unable to promote completed raw capture" }
        }

        val storedAt = Instant.now().toString()
        val observation = JSONObject()
            .put("capture", session.start)
            .put("sha256", result.sha256)
            .put("byteLength", result.byteLength)
            .put("bodyPath", "bodies/$bodyName")
            .put("storedAt", storedAt)
        File(observations, "$captureId.json").writeText(observation.toString(2))

        index.record(
            JSONObjectObservation(
                captureId = captureId,
                sha256 = result.sha256,
                byteLength = result.byteLength,
                bodyPath = "bodies/$bodyName",
                pageUrl = session.start.optNullableString("pageUrl"),
                requestUrl = session.start.optNullableString("requestUrl"),
                method = session.start.optNullableString("method"),
                status = if (session.start.has("status") && !session.start.isNull("status")) session.start.getInt("status") else null,
                contentType = session.start.optNullableString("contentType"),
                initiator = session.start.optNullableString("initiator"),
                capturedAt = session.start.optNullableString("capturedAt"),
                fidelity = session.start.optNullableString("fidelity"),
                storedAt = storedAt,
            ),
        )

        // Raw bytes + immutable observation + live capture index are durable now.
        // Do not keep Gecko/native-message ACK waiting while JSON classification,
        // normalization and reconciliation process a large conversation body.
        DERIVATION_EXECUTOR.execute {
            AndroidLiveDerivationPipeline(appContext).processCompletedCapture(
                bodyFile = body,
                sourceSha256 = result.sha256,
                byteLength = result.byteLength,
                contentType = session.start.optNullableString("contentType"),
            )
        }
        return "capture_end"
    }

    override fun close() {
        sessions.values.forEach { it.close() }
        sessions.clear()
        index.close()
    }

    private fun requireCaptureId(json: JSONObject): String {
        val value = json.getString("captureId")
        UUID.fromString(value)
        return value
    }

    private class Session(
        val start: JSONObject,
        val declaredLength: Long?,
        val file: File,
    ) : AutoCloseable {
        private val output = FileOutputStream(file, false)
        private val digest = MessageDigest.getInstance("SHA-256")
        var nextSequence = 0
            private set
        var byteLength = 0L
            private set
        private var finished = false

        fun append(bytes: ByteArray) {
            check(!finished) { "Capture has already been finalized" }
            output.write(bytes)
            digest.update(bytes)
            byteLength += bytes.size
            nextSequence += 1
        }

        fun finish(): Result {
            check(!finished) { "Capture has already been finalized" }
            finished = true
            output.flush()
            output.fd.sync()
            output.close()
            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
            return Result(sha256, byteLength, file)
        }

        override fun close() {
            if (!finished) {
                finished = true
                output.close()
                file.delete()
            }
        }
    }

    private data class Result(val sha256: String, val byteLength: Long, val partial: File)

    companion object {
        private val DERIVATION_EXECUTOR = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "bke-dna-derivation").apply { isDaemon = true }
        }

        fun awaitBackgroundDerivationIdle() {
            val barrier = CountDownLatch(1)
            DERIVATION_EXECUTOR.execute { barrier.countDown() }
            barrier.await()
        }
    }
}

private fun JSONObject.optNullableString(key: String): String? =
    if (has(key) && !isNull(key)) getString(key) else null
