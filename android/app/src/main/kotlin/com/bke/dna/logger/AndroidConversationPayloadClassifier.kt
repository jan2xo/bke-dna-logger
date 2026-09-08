package com.bke.dna.logger

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Native Kotlin parity for the desktop conversation-payload classifier. */
object AndroidConversationPayloadClassifier {
    fun classify(body: ByteArray, contentType: String?): AndroidPayloadClassification {
        if (!looksTextual(contentType)) {
            return AndroidPayloadClassification.other("non_textual_content_type")
        }

        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body))
                .toString()
        } catch (_: CharacterCodingException) {
            return AndroidPayloadClassification.other("invalid_utf8")
        }

        val signals = linkedSetOf<String>()
        var score = 0
        var parsedJson = false

        parseJsonValue(text)?.let { value ->
            parsedJson = true
            score += scoreJson(value, signals)
        } ?: run {
            if (contentType?.contains("text/event-stream", ignoreCase = true) == true) {
                text.lineSequence().forEach { line ->
                    val trimmed = line.trim()
                    if (!trimmed.startsWith("data:", ignoreCase = true)) return@forEach
                    val candidate = trimmed.substring(5).trim()
                    if (candidate.isEmpty() || candidate == "[DONE]") return@forEach
                    parseJsonValue(candidate)?.let { eventValue ->
                        parsedJson = true
                        score += scoreJson(eventValue, signals)
                    }
                }
            }
        }

        if (!parsedJson) {
            return AndroidPayloadClassification.other("no_json_structure")
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

    private fun scoreJson(root: Any?, signals: MutableSet<String>): Int {
        var score = 0
        val stack = ArrayDeque<JsonFrame>()
        stack.addLast(JsonFrame(root, null))

        while (stack.isNotEmpty()) {
            val frame = stack.removeLast()
            when (val current = frame.value) {
                is JSONObject -> {
                    val keys = current.keys()
                    while (keys.hasNext()) {
                        val name = keys.next()
                        val value = current.opt(name)
                        score += scoreProperty(name, value, signals)
                        stack.addLast(JsonFrame(value, name))
                    }
                }
                is JSONArray -> {
                    for (index in 0 until current.length()) {
                        stack.addLast(JsonFrame(current.opt(index), frame.propertyName))
                    }
                }
                is String -> {
                    if (frame.propertyName.equals("role", ignoreCase = true) &&
                        current in setOf("user", "assistant", "system", "tool")
                    ) {
                        score += addSignal(signals, "recognized_message_role", 10)
                    }
                }
            }
        }

        if ("mapping" in signals && "message" in signals &&
            ("parent" in signals || "children" in signals)
        ) {
            score += addSignal(signals, "conversation_graph_shape", 18)
        }
        if ("author" in signals && "content" in signals && "recognized_message_role" in signals) {
            score += addSignal(signals, "authored_message_shape", 14)
        }
        return score
    }

    private fun scoreProperty(
        name: String,
        value: Any?,
        signals: MutableSet<String>,
    ): Int = when (name.lowercase()) {
        "mapping" -> if (value is JSONObject) addSignal(signals, "mapping", 12) else 0
        "messages" -> if (value is JSONArray || value is JSONObject) addSignal(signals, "messages", 8) else 0
        "message" -> if (value is JSONObject) addSignal(signals, "message", 6) else 0
        "author" -> if (value is JSONObject) addSignal(signals, "author", 5) else 0
        "role" -> addSignal(signals, "role", 4)
        "content" -> addSignal(signals, "content", 4)
        "parts" -> if (value is JSONArray) addSignal(signals, "parts", 3) else 0
        "parent" -> addSignal(signals, "parent", 5)
        "children" -> if (value is JSONArray) addSignal(signals, "children", 5) else 0
        "conversation_id" -> addSignal(signals, "conversation_id", 8)
        "current_node" -> addSignal(signals, "current_node", 6)
        else -> 0
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

    private fun parseJsonValue(text: String): Any? = try {
        val tokener = JSONTokener(text)
        val value = tokener.nextValue()
        if (value !is JSONObject && value !is JSONArray) return null
        if (tokener.nextClean() != '\u0000') return null
        value
    } catch (_: Exception) {
        null
    }

    private data class JsonFrame(val value: Any?, val propertyName: String?)
}

data class AndroidPayloadClassification(
    val kind: String,
    val score: Int,
    val confidence: String,
    val signals: List<String>,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("kind", kind)
        .put("score", score)
        .put("confidence", confidence)
        .put("signals", JSONArray(signals))

    companion object {
        fun other(signal: String) = AndroidPayloadClassification("other", 0, "low", listOf(signal))
    }
}
