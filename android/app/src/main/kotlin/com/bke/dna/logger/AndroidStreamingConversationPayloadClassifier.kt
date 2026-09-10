package com.bke.dna.logger

import android.util.JsonReader
import android.util.JsonToken
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Bounded-memory parity classifier for RAW sources that exceed the legacy
 * materialization ceiling. It preserves the same scoring/signals without
 * constructing one giant String/JSONObject tree.
 */
object AndroidStreamingConversationPayloadClassifier {
    fun classify(input: InputStream, contentType: String?): AndroidPayloadClassification {
        if (!looksTextual(contentType)) {
            return AndroidPayloadClassification.other("non_textual_content_type")
        }

        return if (contentType?.contains("text/event-stream", ignoreCase = true) == true) {
            classifyEventStream(input)
        } else {
            classifyJson(input)
        }
    }

    private fun classifyJson(input: InputStream): AndroidPayloadClassification {
        val signals = linkedSetOf<String>()
        val score = try {
            JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                val valueScore = scoreValue(reader, null, signals, 0)
                if (reader.peek() != JsonToken.END_DOCUMENT) {
                    return AndroidPayloadClassification.other("no_json_structure")
                }
                valueScore
            }
        } catch (_: Exception) {
            return AndroidPayloadClassification.other("no_json_structure")
        }
        return finish(score, signals)
    }

    private fun classifyEventStream(input: InputStream): AndroidPayloadClassification {
        val signals = linkedSetOf<String>()
        var score = 0
        var parsedJson = false
        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).useLines { lines ->
            lines.forEach { line ->
                val trimmed = line.trim()
                if (!trimmed.startsWith("data:", ignoreCase = true)) return@forEach
                val candidate = trimmed.substring(5).trim()
                if (candidate.isEmpty() || candidate == "[DONE]") return@forEach
                val eventScore = try {
                    JsonReader(candidate.reader()).use { reader ->
                        val current = scoreValue(reader, null, signals, 0)
                        if (reader.peek() != JsonToken.END_DOCUMENT) return@use null
                        current
                    }
                } catch (_: Exception) {
                    null
                }
                if (eventScore != null) {
                    parsedJson = true
                    score += eventScore
                }
            }
        }
        if (!parsedJson) return AndroidPayloadClassification.other("no_json_structure")
        return finish(score, signals)
    }

    private fun scoreValue(
        reader: JsonReader,
        propertyName: String?,
        signals: MutableSet<String>,
        depth: Int,
    ): Int {
        require(depth <= MAX_JSON_DEPTH) { "JSON nesting exceeds classifier limit" }
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                var score = when (propertyName?.lowercase()) {
                    "mapping" -> addSignal(signals, "mapping", 12)
                    "messages" -> addSignal(signals, "messages", 8)
                    "message" -> addSignal(signals, "message", 6)
                    "author" -> addSignal(signals, "author", 5)
                    else -> 0
                }
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    score += scorePropertyName(name, reader.peek(), signals)
                    score += scoreValue(reader, name, signals, depth + 1)
                }
                reader.endObject()
                score
            }
            JsonToken.BEGIN_ARRAY -> {
                var score = when (propertyName?.lowercase()) {
                    "messages" -> addSignal(signals, "messages", 8)
                    "parts" -> addSignal(signals, "parts", 3)
                    "children" -> addSignal(signals, "children", 5)
                    else -> 0
                }
                reader.beginArray()
                while (reader.hasNext()) {
                    score += scoreValue(reader, propertyName, signals, depth + 1)
                }
                reader.endArray()
                score
            }
            JsonToken.STRING -> {
                val value = reader.nextString()
                if (propertyName.equals("role", ignoreCase = true) && value in RECOGNIZED_ROLES) {
                    addSignal(signals, "recognized_message_role", 10)
                } else {
                    0
                }
            }
            JsonToken.NUMBER -> {
                reader.nextString()
                0
            }
            JsonToken.BOOLEAN -> {
                reader.nextBoolean()
                0
            }
            JsonToken.NULL -> {
                reader.nextNull()
                0
            }
            else -> {
                reader.skipValue()
                0
            }
        }
    }

    private fun scorePropertyName(
        name: String,
        token: JsonToken,
        signals: MutableSet<String>,
    ): Int = when (name.lowercase()) {
        "mapping" -> if (token == JsonToken.BEGIN_OBJECT) addSignal(signals, "mapping", 12) else 0
        "messages" -> if (token == JsonToken.BEGIN_ARRAY || token == JsonToken.BEGIN_OBJECT) {
            addSignal(signals, "messages", 8)
        } else 0
        "message" -> if (token == JsonToken.BEGIN_OBJECT) addSignal(signals, "message", 6) else 0
        "author" -> if (token == JsonToken.BEGIN_OBJECT) addSignal(signals, "author", 5) else 0
        "role" -> addSignal(signals, "role", 4)
        "content" -> addSignal(signals, "content", 4)
        "parts" -> if (token == JsonToken.BEGIN_ARRAY) addSignal(signals, "parts", 3) else 0
        "parent" -> addSignal(signals, "parent", 5)
        "children" -> if (token == JsonToken.BEGIN_ARRAY) addSignal(signals, "children", 5) else 0
        "conversation_id" -> addSignal(signals, "conversation_id", 8)
        "current_node" -> addSignal(signals, "current_node", 6)
        else -> 0
    }

    private fun finish(rawScore: Int, signals: MutableSet<String>): AndroidPayloadClassification {
        var score = rawScore
        if ("mapping" in signals && "message" in signals &&
            ("parent" in signals || "children" in signals)
        ) {
            score += addSignal(signals, "conversation_graph_shape", 18)
        }
        if ("author" in signals && "content" in signals && "recognized_message_role" in signals) {
            score += addSignal(signals, "authored_message_shape", 14)
        }
        score = score.coerceAtMost(100)
        val kind = if (score >= 28) "conversation_payload_candidate" else "other"
        val confidence = when {
            score >= 55 -> "high"
            score >= 28 -> "medium"
            else -> "low"
        }
        return AndroidPayloadClassification(kind, score, confidence, signals.sorted())
    }

    private fun addSignal(signals: MutableSet<String>, signal: String, points: Int): Int =
        if (signals.add(signal)) points else 0

    private fun looksTextual(contentType: String?): Boolean {
        if (contentType.isNullOrBlank()) return true
        return contentType.contains("json", ignoreCase = true) ||
            contentType.contains("text/", ignoreCase = true) ||
            contentType.contains("event-stream", ignoreCase = true) ||
            contentType.contains("ndjson", ignoreCase = true)
    }

    private const val MAX_JSON_DEPTH = 256
    private val RECOGNIZED_ROLES = setOf("user", "assistant", "system", "tool")
}
