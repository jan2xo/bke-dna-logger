package com.bke.dna.logger

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.time.Instant

/**
 * Normalizes modern ChatGPT message collections independently of
 * generic-mapping-graph-v0. Message collections may be root-level or carried
 * inside one explicit JSON envelope. This representation does not expose
 * parent/child graph edges, so none are invented here.
 */
class AndroidMessagesNormalizationEngine(context: android.content.Context) {
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

        val root = try {
            val tokener = JSONTokener(String(rawBytes, Charsets.UTF_8))
            val value = tokener.nextValue()
            if (tokener.nextClean() != '\u0000' || value !is JSONObject) {
                Log.d(TAG, "BKE DNA normalization: normalization_skip_invalid_messages_representation")
                return null
            }
            value
        } catch (_: Exception) {
            Log.d(TAG, "BKE DNA normalization: normalization_skip_invalid_messages_representation")
            return null
        }

        val envelope = locateMessagesEnvelope(root) ?: run {
            Log.d(TAG, "BKE DNA normalization: normalization_skip_no_unambiguous_messages_envelope")
            return null
        }
        Log.d(TAG, "BKE DNA normalization: messages_envelope_found")

        val identity = resolveConversationIdentity(root, envelope.container, sourceSha256) ?: return null
        Log.d(TAG, "BKE DNA normalization: messages_identity_resolved")

        val sourceCurrentNodeId = scalarToString(envelope.container.opt("current_node"))
            ?.takeIf { it.isNotBlank() }
            ?: scalarToString(root.opt("current_node"))?.takeIf { it.isNotBlank() }

        val nodes = messageObjects(envelope.messages)
            .mapNotNull(::parseMessage)
            .distinctBy { it.nodeNativeId }
        Log.d(TAG, "BKE DNA normalization: messages_nodes_parsed_${nodes.size}")
        if (nodes.isEmpty()) {
            Log.d(TAG, "BKE DNA normalization: normalization_skip_no_identified_messages")
            return null
        }

        val knownMessageIds = nodes.mapTo(linkedSetOf()) { it.nodeNativeId }
        val currentNodeNativeId = sourceCurrentNodeId?.takeIf { it in knownMessageIds }
        val currentNodeFound = currentNodeNativeId != null
        val parser = if (envelope.container === root && envelope.messages is JSONArray) {
            PARSER
        } else {
            PARSER_ENVELOPE
        }

        val normalized = JSONObject()
            .put("sourceSha256", sourceSha256)
            .put("parser", parser)
            .put("conversationNativeId", identity.conversationNativeId)
            .put("conversationIdentityBasis", identity.basis)
            .put("currentNodeNativeId", currentNodeNativeId ?: JSONObject.NULL)
            .put("coverageStatus", "indeterminate")
            .put("coverageBasis", COVERAGE_BASIS)
            .put("rootFound", false)
            .put("currentNodeFound", currentNodeFound)
            .put("currentLeafFound", false)
            .put("parentChainComplete", false)
            .put("cycleDetected", false)
            .put("unresolvedParentNativeIds", JSONArray())
            .put("unresolvedChildNativeIds", JSONArray())
            .put("nodes", JSONArray(nodes.map { it.toJson() }))
            .put("normalizedAt", Instant.now().toString())

        Log.d(TAG, "BKE DNA normalization: messages_derivative_write_started")
        AndroidDerivativeStore(appContext).use { store ->
            store.putNormalizedJson(sourceSha256, normalized.toString(2))
        }
        Log.d(TAG, "BKE DNA normalization: messages_derivative_write_complete")
        Log.d(TAG, "BKE DNA normalization: messages_array_normalization_complete")
        return AndroidNormalizationResult(sourceSha256, identity.conversationNativeId, null)
    }

    private fun locateMessagesEnvelope(root: JSONObject): MessageEnvelope? {
        val rootMessages = root.optJSONArray("messages") ?: root.optJSONObject("messages")
        if (rootMessages != null && messageObjects(rootMessages).any(::isIdentifiedAuthoredMessage)) {
            return MessageEnvelope(root, rootMessages)
        }

        val candidates = mutableListOf<MessageEnvelope>()
        val stack = ArrayDeque<Any>()
        val rootKeys = buildList {
            val iterator = root.keys()
            while (iterator.hasNext()) add(iterator.next())
        }.sorted()
        rootKeys.forEach { key ->
            if (key == "messages") return@forEach
            when (val child = root.opt(key)) {
                is JSONObject, is JSONArray -> stack.addLast(child)
            }
        }
        var visited = 0

        while (stack.isNotEmpty() && visited < MAX_ENVELOPE_VALUES) {
            val current = stack.removeLast()
            visited += 1
            when (current) {
                is JSONObject -> {
                    val messages = current.opt("messages")
                    if ((messages is JSONArray || messages is JSONObject) &&
                        messageObjects(messages).any(::isIdentifiedAuthoredMessage)
                    ) {
                        candidates += MessageEnvelope(current, messages)
                    }

                    val keys = buildList {
                        val iterator = current.keys()
                        while (iterator.hasNext()) add(iterator.next())
                    }.sorted()
                    keys.forEach { key ->
                        if (key == "messages") return@forEach
                        when (val child = current.opt(key)) {
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

        return candidates.singleOrNull()
    }

    private fun resolveConversationIdentity(
        root: JSONObject,
        envelope: JSONObject,
        sourceSha256: String,
    ): AndroidConversationIdentityResolution? {
        val payloadIds = linkedSetOf<String>()
        scalarToString(envelope.opt("conversation_id"))
            ?.takeIf { it.isNotBlank() }
            ?.let(payloadIds::add)
        scalarToString(root.opt("conversation_id"))
            ?.takeIf { it.isNotBlank() }
            ?.let(payloadIds::add)

        if (payloadIds.size > 1) {
            Log.d(TAG, "BKE DNA normalization: normalization_skip_ambiguous_conversation_identity")
            return null
        }
        payloadIds.singleOrNull()?.let { id ->
            return AndroidConversationIdentityResolution(id, "payload_conversation_id")
        }

        val metadataIdentity = AndroidConversationSourceIdentity.resolve(appContext, sourceSha256)
        if (metadataIdentity == null) {
            Log.d(TAG, "BKE DNA normalization: normalization_skip_missing_conversation_id")
            return null
        }
        return metadataIdentity
    }

    private fun messageObjects(messages: Any): List<JSONObject> = when (messages) {
        is JSONArray -> buildList {
            for (index in 0 until messages.length()) {
                val item = messages.optJSONObject(index) ?: continue
                val direct = if (isIdentifiedAuthoredMessage(item)) item else item.optJSONObject("message")
                if (direct != null) add(direct)
            }
        }
        is JSONObject -> buildList {
            val keys = buildList {
                val iterator = messages.keys()
                while (iterator.hasNext()) add(iterator.next())
            }.sorted()
            keys.forEach { key ->
                val item = messages.optJSONObject(key) ?: return@forEach
                val direct = if (isIdentifiedAuthoredMessage(item)) item else item.optJSONObject("message")
                if (direct != null) add(direct)
            }
        }
        else -> emptyList()
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

    private data class MessageEnvelope(
        val container: JSONObject,
        val messages: Any,
    )

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
        private const val MAX_ENVELOPE_VALUES = 4096
        private const val CANDIDATE_KIND = "conversation_payload_candidate"
        private const val PARSER = "messages-array-v0"
        private const val PARSER_ENVELOPE = "messages-envelope-v1"
        private const val COVERAGE_BASIS = "messages_array_no_graph_edges"
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val RECOGNIZED_ROLES = setOf("user", "assistant", "system", "tool")
    }
}
