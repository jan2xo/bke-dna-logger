package com.bke.dna.logger

import android.util.JsonWriter
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.time.Instant

/**
 * Normalizes modern ChatGPT message collections independently of
 * generic-mapping-graph-v0. Message collections may be root-level or carried
 * inside one explicit JSON envelope. This representation does not expose
 * parent/child graph edges, so none are invented here. Display titles are
 * carried only when the captured payload itself supplies title evidence.
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

        AndroidDerivativeSourceAccess.readNormalizedMetadata(appContext, sourceSha256)?.let { existing ->
            if (existing.sourceSha256 == sourceSha256 && existing.conversationNativeId.isNotBlank()) {
                return AndroidNormalizationResult(sourceSha256, existing.conversationNativeId, null)
            }
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

        val displayTitle = resolveDisplayTitle(
            capturedTitle(root.opt("title")),
            capturedTitle(envelope.container.opt("title")),
        )
        if (displayTitle != null) {
            Log.d(TAG, "BKE DNA normalization: messages_display_title_found")
        }

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
        val normalizedAt = Instant.now().toString()

        Log.d(TAG, "BKE DNA normalization: messages_derivative_write_started")
        try {
            AndroidChunkedNormalizedStore(appContext).use { store ->
                store.putNormalizedJsonStream(
                    sourceSha256 = sourceSha256,
                    conversationNativeId = identity.conversationNativeId,
                    normalizedAt = normalizedAt,
                ) { writer ->
                    writeNormalizedPayload(
                        writer = writer,
                        sourceSha256 = sourceSha256,
                        parser = parser,
                        identity = identity,
                        displayTitle = displayTitle,
                        currentNodeNativeId = currentNodeNativeId,
                        currentNodeFound = currentNodeFound,
                        nodes = nodes,
                        normalizedAt = normalizedAt,
                    )
                }
            }
        } catch (error: OutOfMemoryError) {
            Log.e(TAG, "BKE DNA normalization: messages_derivative_out_of_memory", error)
            throw error
        }
        Log.d(TAG, "BKE DNA normalization: messages_derivative_write_complete")
        Log.d(TAG, "BKE DNA normalization: messages_array_normalization_complete")
        return AndroidNormalizationResult(sourceSha256, identity.conversationNativeId, null)
    }

    private fun writeNormalizedPayload(
        writer: JsonWriter,
        sourceSha256: String,
        parser: String,
        identity: AndroidConversationIdentityResolution,
        displayTitle: String?,
        currentNodeNativeId: String?,
        currentNodeFound: Boolean,
        nodes: List<NormalizedMessageNode>,
        normalizedAt: String,
    ) {
        writer.beginObject()
        writer.name("sourceSha256").value(sourceSha256)
        writer.name("parser").value(parser)
        writer.name("conversationNativeId").value(identity.conversationNativeId)
        writer.name("conversationIdentityBasis").value(identity.basis)
        if (displayTitle != null) writer.name("displayTitle").value(displayTitle)
        writer.name("currentNodeNativeId")
        if (currentNodeNativeId == null) writer.nullValue() else writer.value(currentNodeNativeId)
        writer.name("coverageStatus").value("indeterminate")
        writer.name("coverageBasis").value(COVERAGE_BASIS)
        writer.name("rootFound").value(false)
        writer.name("currentNodeFound").value(currentNodeFound)
        writer.name("currentLeafFound").value(false)
        writer.name("parentChainComplete").value(false)
        writer.name("cycleDetected").value(false)
        writer.name("unresolvedParentNativeIds").beginArray().endArray()
        writer.name("unresolvedChildNativeIds").beginArray().endArray()
        writer.name("nodes").beginArray()
        nodes.forEach { it.writeJson(writer) }
        writer.endArray()
        writer.name("normalizedAt").value(normalizedAt)
        writer.endObject()
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

    private fun resolveDisplayTitle(rootTitle: String?, envelopeTitle: String?): String? {
        val capturedTitles = linkedSetOf<String>()
        rootTitle?.takeIf { it.isNotBlank() }?.let(capturedTitles::add)
        envelopeTitle?.takeIf { it.isNotBlank() }?.let(capturedTitles::add)
        if (capturedTitles.size > 1) {
            Log.d(TAG, "BKE DNA normalization: messages_display_title_conflict")
            return null
        }
        return capturedTitles.singleOrNull()
    }

    private fun capturedTitle(value: Any?): String? =
        (value as? String)?.trim()?.takeIf { it.isNotBlank() }?.take(DISPLAY_TITLE_LIMIT)

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
        fun writeJson(writer: JsonWriter) {
            writer.beginObject()
            writer.name("nodeNativeId").value(nodeNativeId)
            writer.name("messageNativeId").value(messageNativeId)
            writer.name("parentNativeId").nullValue()
            writer.name("childNativeIds").beginArray().endArray()
            writer.name("role")
            if (role == null) writer.nullValue() else writer.value(role)
            writer.name("createdAt")
            if (createdAt == null) writer.nullValue() else writer.value(createdAt)
            writer.name("textParts").beginArray()
            textParts.forEach(writer::value)
            writer.endArray()
            writer.name("contentJson")
            if (contentJson == null) writer.nullValue() else writer.value(contentJson)
            writer.endObject()
        }
    }

    companion object {
        private const val TAG = "BkeDnaNormalizer"
        private const val MAX_BODY_BYTES = 16L * 1024 * 1024
        private const val MAX_ENVELOPE_VALUES = 4096
        private const val DISPLAY_TITLE_LIMIT = 240
        private const val CANDIDATE_KIND = "conversation_payload_candidate"
        private const val PARSER = "messages-array-v0"
        private const val PARSER_ENVELOPE = "messages-envelope-v1"
        private const val COVERAGE_BASIS = "messages_array_no_graph_edges"
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val RECOGNIZED_ROLES = setOf("user", "assistant", "system", "tool")
    }
}
