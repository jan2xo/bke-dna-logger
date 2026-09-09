package com.bke.dna.logger

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * Android counterpart of the desktop CaptureStore.
 * Streams into app-private partial files, hashes raw bytes, promotes completed
 * captures only into durable temporary RAW staging, persists immutable
 * observations + the SQLite capture projection, then enqueues RAW ingestion and
 * semantic derivation in the durable Working Data queue.
 */
class AndroidCaptureStore(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val root = AndroidDnaPaths.capturesRoot(appContext)
    private val staging = File(root, "staging").also { it.mkdirs() }
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
        AndroidDerivationScheduler.captureStarted()
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

        return try {
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

            val route = classifyCaptureRoute(session.start.optNullableString("requestUrl"))
            Log.d(
                CAPTURE_DIAGNOSTIC_TAG,
                "DNA capture complete: source_${result.sha256.take(SOURCE_PREFIX_LENGTH)} $route bytes_${result.byteLength}",
            )

            // Completion is represented by a SHA-addressed staging file. It remains
            // durable across process death until RAW_INGEST has verified exact bytes
            // from SQLite. No compression happens on this Gecko ACK path.
            val stagingName = "${result.sha256}.raw"
            val stagedRaw = File(staging, stagingName)
            if (stagedRaw.exists()) {
                require(stagedRaw.length() == result.byteLength) {
                    "Existing RAW staging source length does not match capture"
                }
                check(result.partial.delete()) { "Unable to discard duplicate RAW staging capture" }
            } else {
                check(result.partial.renameTo(stagedRaw)) { "Unable to promote completed RAW staging capture" }
            }

            val storedAt = Instant.now().toString()
            val observation = JSONObject()
                .put("capture", session.start)
                .put("sha256", result.sha256)
                .put("byteLength", result.byteLength)
                .put("bodyPath", "staging/$stagingName")
                .put("storedAt", storedAt)
            File(observations, "$captureId.json").writeText(observation.toString(2))

            index.record(
                JSONObjectObservation(
                    captureId = captureId,
                    sha256 = result.sha256,
                    byteLength = result.byteLength,
                    bodyPath = "staging/$stagingName",
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

            // Staging + immutable observation + live capture index are durable now.
            // Queue RAW_INGEST first; the breathing scheduler owns compression,
            // round-trip verification, staging cleanup and later semantic stages.
            AndroidDerivationScheduler.enqueue(
                context = appContext,
                bodyFile = stagedRaw,
                sourceSha256 = result.sha256,
                byteLength = result.byteLength,
                contentType = session.start.optNullableString("contentType"),
            )
            "capture_end"
        } finally {
            // Keep capture priority active through every durable capture_end write
            // and queue enqueue. Derivation may resume only after completion has
            // either fully succeeded or failed and released capture accounting.
            AndroidDerivationScheduler.captureFinished()
        }
    }

    override fun close() {
        val interruptedCaptures = sessions.size
        sessions.values.forEach { it.close() }
        sessions.clear()
        repeat(interruptedCaptures) { AndroidDerivationScheduler.captureFinished() }
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
        private const val CAPTURE_DIAGNOSTIC_TAG = "BkeDnaGeckoView"
        private const val SOURCE_PREFIX_LENGTH = 8
        private const val ROUTE_CONVERSATION = "capture_route_conversation"
        private const val ROUTE_CONVERSATIONS_LIST = "capture_route_conversations_list"
        private const val ROUTE_BACKEND_API = "capture_route_backend_api"
        private const val ROUTE_PUBLIC_API = "capture_route_public_api"
        private const val ROUTE_OTHER = "capture_route_other"

        private fun classifyCaptureRoute(requestUrl: String?): String {
            if (requestUrl.isNullOrBlank()) return ROUTE_OTHER
            val uri = runCatching { URI(requestUrl) }.getOrNull() ?: return ROUTE_OTHER
            val host = uri.host?.lowercase() ?: return ROUTE_OTHER
            val chatGptHost = host == "chatgpt.com" ||
                host.endsWith(".chatgpt.com") ||
                host == "chat.openai.com"
            if (!chatGptHost) return ROUTE_OTHER

            val path = uri.path.orEmpty()
            return when {
                path == "/backend-api/conversations" ||
                    path.startsWith("/backend-api/conversations/") -> ROUTE_CONVERSATIONS_LIST
                path == "/backend-api/conversation" ||
                    path.startsWith("/backend-api/conversation/") -> ROUTE_CONVERSATION
                path.startsWith("/backend-api/") -> ROUTE_BACKEND_API
                path.startsWith("/public-api/") -> ROUTE_PUBLIC_API
                else -> ROUTE_OTHER
            }
        }

        fun awaitBackgroundDerivationIdle(context: Context) {
            AndroidDerivationScheduler.awaitIdle(context.applicationContext)
        }
    }
}

private fun JSONObject.optNullableString(key: String): String? =
    if (has(key) && !isNull(key)) getString(key) else null
