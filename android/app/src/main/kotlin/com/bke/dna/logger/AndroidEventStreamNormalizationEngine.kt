package com.bke.dna.logger

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.time.Instant

/**
 * Normalizes conversation messages carried in a captured text/event-stream.
 * The stream order is evidence: later observations of the same message id
 * replace earlier incremental versions. No graph edges are synthesized.
 */
class AndroidEventStreamNormalizationEngine(context: android.content.Context) {
    private val appContext = context.applicationContext

    fun normalizeCandidate(sourceSha256: String): AndroidNormalizationResult? {
        require(SHA256.matches(sourceSha256)) { "Expected lowercase SHA-256 source identity" }
        val classificationPayload = AndroidDerivativeSourceAccess.readClassification(appContext, sourceSha256)
            ?: return null
        val classificationRoot = JSONObject(classificationPayload)
        if (classificationRoot.getJSONObject("classification").getString("kind") != CANDIDATE_KIND) {
            return null
        }

        AndroidDerivativeSourceAccess.readNormalized(appContext, sourceSha256)?.let { payload ->
            return readExistingResult(payload, sourceSha256)
        }

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

        val events = parseEventValues(String(rawBytes, Charsets.UTF_8))
        if (events.isEmpty()) {
            Log.d(TAG, "BKE DNA normalization: normalization_skip_no_event_stream_json")
            return null
        }

        val conversationIds = linkedSetOf<String>()
        val currentNodeObservations = mutableListOf<String>()
        val messagesById = linkedMapOf<String, JSONObject>()

        events.forEach { event ->
            collectScalarValues(event, "conversation_id").forEach(conversationIds::add)
            currentNodeObservations += collectScalarValues(event, "current_node")
            collectAuthoredMessages(event).forEach { message ->
                val id = scalarToString(message.opt("id"))?.takeIf { it.isNotBlank() } ?: return@forEach
                messagesById[id] = message
            }
        }

        if (messagesById.isEmpty()) {
            Log.d(TAG, "BKE DNA normalization: normalization_skip_no_identified_messages")
            return null
        }
        if (conversationIds.size > 1) {
            Log.d(TAG, "BKE DNA normalization: normalization_skip_ambiguous_conversation_identity")
            return null
        }

        val identity = conversationIds.singleOrNull()?.let {
            AndroidConversationIdentityResolution(it, "event_stream_conversation_id")
        } ?: AndroidConversationSourceIdentity.resolve(appContext, sourceSha256)
            ?: run {
                Log.d(TAG, "BKE DNA normalization: normalization_skip_missing_conversation_id")
                return null
            }

        val nodes = messagesById.values.mapNotNull(::parseMessage)
        if (nodes.isEmpty()) {
            Log.d(TAG, "BKE DNA normalization: normalization_skip_no_identified_messages")
            return null
        }
        val knownMessageIds = nodes.mapTo(linkedSetOf()) { it.nodeNativeId }
        val currentNodeNativeId = currentNodeObservations.lastOrNull()?.takeIf { it in knownMessageIds }

        val normalized = JSONObject()
            .put("sourceSha256", sourceSha256)
            .put("parser", PARSER)
            .put("conversationNativeId", identity.conversationNativeId)
            .put("conversationIdentityBasis", identity.basis)
            .put("currentNodeNativeId", currentNodeNativeId ?: JSONObject.NULL)
            .put("coverageStatus", "indeterminate")
            .put("coverageBasis", COVERAGE_BASIS)
            .put("rootFound", false)
            .put("currentNodeFound", currentNodeNativeId != null)
            .put("currentLeafFound", false)
            .put("parentChainComplete", false)
            .put("cycleDetected", false)
            .put("unresolvedParentNativeIds", JSONArray())
            .put("unresolvedChildNativeIds", JSONArray())
            .put("nodes", JSONArray(nodes.map { it.toJson() }))
            .put("normalizedAt", Instant.now().toString())

        AndroidDerivativeStore(appContext).use { store ->
            store.putNormalizedJson(sourceSha256, normalized.toString(2))
        }
        Log.d(TAG, "BKE DNA normalization: event_stream_normalization_complete")
        return AndroidNormalizationResult(sourceSha256, identity.conversationNativeId, null)
    }

    private fun parseEventValues(text: String): List<Any> {
        val values = mutableListOf<Any>()
        val dataLines = mutableListOf<String>()

        fun flush() {
            if (dataLines.isEmpty()) return
            val payload = dataLines.joinToString("\n").trim()
            dataLines.clear()
            if (payload.isEmpty() || payload == "[DONE]") return
            parseJsonValue(payload)?.let(values::add)
        }

        text.lineSequence().forEach { line ->
            if (line.isEmpty()) {
                flush()
            } else if (line.startsWith("data:", ignoreCase = true)) {
                dataLines += line.substringAfter(':').trimStart()
            }
        }
        flush()
        return values
    }

    private fun collectAuthoredMessages(root: Any): List<JSONObject> {
        val messages = linkedMapOf<String, JSONObject>()
        val stack = ArrayDeque<Any>()
        stack.addLast(root)
        var visited = 0

        while (stack.isNotEmpty() && visited < MAX_EVENT_VALUES) {
            val current = stack.removeLast()
            visited += 1
            when (current) {
                is JSONObject -> {
                    if (isIdentifiedAuthoredMessage(current)) {
                        scalarToString(current.opt("id"))?.let { messages[it] = current }
                    }
                    val iterator = current.keys()
                    while (iterator.hasNext()) {
                        when (val child = current.opt(iterator.next())) {
                            is JSONObject, is JSONArray -> stack.addLast(child)
                        }
                    }
                }
                is JSONArray -> {
                    for (index in current.length() - 1 downTo 0) {
                        when (val child = current.opt(index)) {
                            is JSONObject, is JSONArray -> stack.addLast(child)
                        }
                    }
                }
            }
        }
        return messages.values.toList()
    }

    private fun collectScalarValues(root: Any, fieldName: String): List<String> {
        val values = mutableListOf<String>()
        val stack = ArrayDeque<Any>()
        stack.addLast(root)
        var visited = 0

        while (stack.isNotEmpty() && visited < MAX_EVENT_VALUES) {
            val current = stack.removeLast()
            visited += 1
            when (current) {
                is JSONObject -> {
                    scalarToString(current.opt(fieldName))
                        ?.takeIf { it.isNotBlank() }
                        ?.let(values::add)
                    val iterator = current.keys()
                    while (iterator.hasNext()) {
                        when (val child = current.opt(iterator.next())) {
                            is JSONObject, is JSONArray -> stack.addLast(child)
                        }
                    }
                }
                is JSONArray -> {
                    for (index in current.length() - 1 downTo 0) {
                        when (val child = current.opt(index)) {
                            is JSONObject, is JSONArray -> stack.addLast(child)
                        }
                    }
                }
            }
        }
        return values
    }

    private fun isIdentifiedAuthoredMessage(message: JSONObject): Boolean {
        val id = scalarToString(message.opt("id"))?.takeIf { it.isNotBlank() } ?: return false
        val role = message.optJSONObject("author")
            ?.let { scalarToString(it.opt("role")) }
            ?.lowercase()
        return id.isNotBlank() && role in RECOGNIZED_ROLES && message.has("content")
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

    private fun readExistingResult(payloadJson: String, sourceSha256: String): AndroidNormalizationResult? {
        val root = runCatching { JSONObject(payloadJson) }.getOrNull() ?: return null
        if (root.optString("sourceSha256") != sourceSha256) return null
        val conversationNativeId = scalarToString(root.opt("conversationNativeId"))?.takeIf { it.isNotBlank() }
            ?: return null
        return AndroidNormalizationResult(sourceSha256, conversationNativeId, null)
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
        private const val MAX_EVENT_VALUES = 8192
        private const val CANDIDATE_KIND = "conversation_payload_candidate"
        private const val PARSER = "event-stream-messages-v1"
        private const val COVERAGE_BASIS = "messages_array_no_graph_edges"
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val RECOGNIZED_ROLES = setOf("user", "assistant", "system", "tool")
    }
}
