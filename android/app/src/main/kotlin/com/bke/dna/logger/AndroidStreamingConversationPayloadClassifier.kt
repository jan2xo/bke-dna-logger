package com.bke.dna.logger

import android.util.JsonReader
import android.util.JsonToken
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Bounded-memory classifier for RAW sources that exceed the legacy
 * materialization ceiling.
 *
 * Classification is also bounded in work: oversized sources are inspected only
 * through a fixed prefix budget. Conversation signals occur in structural JSON
 * metadata near the front of supported ChatGPT payloads; a pathological giant
 * response must never monopolize the single derivation lane indefinitely.
 */
object AndroidStreamingConversationPayloadClassifier {
    fun classify(input: InputStream, contentType: String?): AndroidPayloadClassification {
        if (!looksTextual(contentType)) {
            return AndroidPayloadClassification.other("non_textual_content_type")
        }

        val bounded = ScanBudgetInputStream(input, MAX_SCAN_BYTES)
        return if (contentType?.contains("text/event-stream", ignoreCase = true) == true) {
            classifyEventStream(bounded)
        } else {
            classifyJson(bounded)
        }
    }

    private fun classifyJson(input: InputStream): AndroidPayloadClassification {
        val state = ScoreState()
        return try {
            JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                scoreValue(reader, null, state, 0)
                if (reader.peek() != JsonToken.END_DOCUMENT) {
                    return AndroidPayloadClassification.other("no_json_structure")
                }
            }
            state.finish()
        } catch (_: ScanBudgetExceeded) {
            state.finishWithSignal(SCAN_LIMIT_SIGNAL)
        } catch (_: Exception) {
            AndroidPayloadClassification.other("no_json_structure")
        }
    }

    private fun classifyEventStream(input: InputStream): AndroidPayloadClassification {
        val state = ScoreState()
        var parsedJson = false
        return try {
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).useLines { lines ->
                lines.forEach { line ->
                    val trimmed = line.trim()
                    if (!trimmed.startsWith("data:", ignoreCase = true)) return@forEach
                    val candidate = trimmed.substring(5).trim()
                    if (candidate.isEmpty() || candidate == "[DONE]") return@forEach
                    val valid = try {
                        JsonReader(candidate.reader()).use { reader ->
                            scoreValue(reader, null, state, 0)
                            reader.peek() == JsonToken.END_DOCUMENT
                        }
                    } catch (_: Exception) {
                        false
                    }
                    if (valid) parsedJson = true
                }
            }
            if (!parsedJson) {
                AndroidPayloadClassification.other("no_json_structure")
            } else {
                state.finish()
            }
        } catch (_: ScanBudgetExceeded) {
            if (!parsedJson && state.signals.isEmpty()) {
                AndroidPayloadClassification.other(SCAN_LIMIT_SIGNAL)
            } else {
                state.finishWithSignal(SCAN_LIMIT_SIGNAL)
            }
        }
    }

    private fun scoreValue(
        reader: JsonReader,
        propertyName: String?,
        state: ScoreState,
        depth: Int,
    ) {
        require(depth <= MAX_JSON_DEPTH) { "JSON nesting exceeds classifier limit" }
        when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                when (propertyName?.lowercase()) {
                    "mapping" -> state.addSignal("mapping", 12)
                    "messages" -> state.addSignal("messages", 8)
                    "message" -> state.addSignal("message", 6)
                    "author" -> state.addSignal("author", 5)
                }
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    scorePropertyName(name, reader.peek(), state)
                    scoreValue(reader, name, state, depth + 1)
                }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> {
                when (propertyName?.lowercase()) {
                    "messages" -> state.addSignal("messages", 8)
                    "parts" -> state.addSignal("parts", 3)
                    "children" -> state.addSignal("children", 5)
                }
                reader.beginArray()
                while (reader.hasNext()) {
                    scoreValue(reader, propertyName, state, depth + 1)
                }
                reader.endArray()
            }
            JsonToken.STRING -> {
                if (propertyName.equals("role", ignoreCase = true)) {
                    val value = reader.nextString()
                    if (value in RECOGNIZED_ROLES) {
                        state.addSignal("recognized_message_role", 10)
                    }
                } else {
                    // Do not materialize giant message/content strings merely to classify shape.
                    reader.skipValue()
                }
            }
            JsonToken.NUMBER,
            JsonToken.BOOLEAN,
            JsonToken.NULL -> reader.skipValue()
            else -> reader.skipValue()
        }
    }

    private fun scorePropertyName(
        name: String,
        token: JsonToken,
        state: ScoreState,
    ) {
        when (name.lowercase()) {
            "mapping" -> if (token == JsonToken.BEGIN_OBJECT) state.addSignal("mapping", 12)
            "messages" -> if (token == JsonToken.BEGIN_ARRAY || token == JsonToken.BEGIN_OBJECT) {
                state.addSignal("messages", 8)
            }
            "message" -> if (token == JsonToken.BEGIN_OBJECT) state.addSignal("message", 6)
            "author" -> if (token == JsonToken.BEGIN_OBJECT) state.addSignal("author", 5)
            "role" -> state.addSignal("role", 4)
            "content" -> state.addSignal("content", 4)
            "parts" -> if (token == JsonToken.BEGIN_ARRAY) state.addSignal("parts", 3)
            "parent" -> state.addSignal("parent", 5)
            "children" -> if (token == JsonToken.BEGIN_ARRAY) state.addSignal("children", 5)
            "conversation_id" -> state.addSignal("conversation_id", 8)
            "current_node" -> state.addSignal("current_node", 6)
        }
    }

    private class ScoreState {
        val signals = linkedSetOf<String>()
        private var score = 0

        fun addSignal(signal: String, points: Int) {
            if (signals.add(signal)) score += points
            applyShapeBonuses()
        }

        fun finish(): AndroidPayloadClassification = classification(score, signals)

        fun finishWithSignal(signal: String): AndroidPayloadClassification {
            signals.add(signal)
            return classification(score, signals)
        }

        private fun applyShapeBonuses() {
            if (
                "conversation_graph_shape" !in signals &&
                "mapping" in signals &&
                "message" in signals &&
                ("parent" in signals || "children" in signals)
            ) {
                signals.add("conversation_graph_shape")
                score += 18
            }
            if (
                "authored_message_shape" !in signals &&
                "author" in signals &&
                "content" in signals &&
                "recognized_message_role" in signals
            ) {
                signals.add("authored_message_shape")
                score += 14
            }
        }
    }

    private fun classification(
        rawScore: Int,
        signals: Set<String>,
    ): AndroidPayloadClassification {
        val score = rawScore.coerceAtMost(100)
        val kind = if (score >= CANDIDATE_SCORE) "conversation_payload_candidate" else "other"
        val confidence = when {
            score >= HIGH_CONFIDENCE_SCORE -> "high"
            score >= CANDIDATE_SCORE -> "medium"
            else -> "low"
        }
        return AndroidPayloadClassification(kind, score, confidence, signals.sorted())
    }

    private fun looksTextual(contentType: String?): Boolean {
        if (contentType.isNullOrBlank()) return true
        return contentType.contains("json", ignoreCase = true) ||
            contentType.contains("text/", ignoreCase = true) ||
            contentType.contains("event-stream", ignoreCase = true) ||
            contentType.contains("ndjson", ignoreCase = true)
    }

    private class ScanBudgetInputStream(
        private val delegate: InputStream,
        private val maxBytes: Long,
    ) : InputStream() {
        private var consumed = 0L

        override fun read(): Int {
            if (consumed >= maxBytes) throw ScanBudgetExceeded()
            val value = delegate.read()
            if (value >= 0) consumed += 1
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (consumed >= maxBytes) throw ScanBudgetExceeded()
            val remaining = (maxBytes - consumed).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val allowed = minOf(length, remaining)
            if (allowed <= 0) throw ScanBudgetExceeded()
            val read = delegate.read(buffer, offset, allowed)
            if (read > 0) consumed += read
            return read
        }

        // The owner of the exact RAW stream controls its lifecycle.
        override fun close() = Unit
    }

    private class ScanBudgetExceeded : IOException()

    private const val MAX_JSON_DEPTH = 256
    private const val MAX_SCAN_BYTES = 16L * 1024L * 1024L
    private const val CANDIDATE_SCORE = 28
    private const val HIGH_CONFIDENCE_SCORE = 55
    private const val SCAN_LIMIT_SIGNAL = "classifier_scan_limit"
    private val RECOGNIZED_ROLES = setOf("user", "assistant", "system", "tool")
}
