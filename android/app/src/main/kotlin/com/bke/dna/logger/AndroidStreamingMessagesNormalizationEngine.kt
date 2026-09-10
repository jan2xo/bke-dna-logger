package com.bke.dna.logger

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.InputStreamReader
import java.time.Instant

/**
 * Bounded-input normalizer for modern messages payloads that exceed the legacy
 * 16 MiB materialization ceiling. RAW is read through AndroidRawSourceAccess as
 * a stream; only one message object is materialized at a time.
 *
 * Semantic rules intentionally match AndroidMessagesNormalizationEngine:
 * graph edges are never invented, payload identity wins when unambiguous, and
 * one explicit messages envelope must be identifiable. Display titles are
 * carried forward only when the captured payload supplies an actual title.
 */
class AndroidStreamingMessagesNormalizationEngine(context: android.content.Context) {
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

        val scan = try {
            AndroidRawSourceAccess.withExactInputStream(appContext, sourceSha256) { input ->
                JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { reader -> scanPayload(reader) }
            }
        } catch (_: Exception) {
            Log.d(TAG, "BKE DNA normalization: normalization_skip_invalid_messages_representation")
            return null
        }
        if (scan == null) {
            Log.d(TAG, "BKE DNA normalization: normalization_skip_body_missing")
            return null
        }
        if (!scan.validRoot) {
            Log.d(TAG, "BKE DNA normalization: normalization_skip_invalid_messages_representation")
            return null
        }
        val envelope = scan.candidates.singleOrNull() ?: run {
            Log.d(TAG, "BKE DNA normalization: normalization_skip_no_unambiguous_messages_envelope")
            return null
        }
        Log.d(TAG, "BKE DNA normalization: messages_envelope_found")

        val identity = resolveConversationIdentity(
            rootConversationId = scan.rootConversationId,
            envelopeConversationId = envelope.conversationId,
            sourceSha256 = sourceSha256,
        ) ?: return null
        Log.d(TAG, "BKE DNA normalization: messages_identity_resolved")

        val displayTitle = resolveDisplayTitle(scan.rootTitle, envelope.title)
        if (displayTitle != null) {
            Log.d(TAG, "BKE DNA normalization: messages_display_title_found")
        }

        val nodes = envelope.nodes.distinctBy { it.nodeNativeId }
        Log.d(TAG, "BKE DNA normalization: messages_nodes_parsed_${nodes.size}")
        if (nodes.isEmpty()) {
            Log.d(TAG, "BKE DNA normalization: normalization_skip_no_identified_messages")
            return null
        }

        val sourceCurrentNodeId = envelope.currentNodeId?.takeIf { it.isNotBlank() }
            ?: scan.rootCurrentNodeId?.takeIf { it.isNotBlank() }
        val knownMessageIds = nodes.mapTo(linkedSetOf()) { it.nodeNativeId }
        val currentNodeNativeId = sourceCurrentNodeId?.takeIf { it in knownMessageIds }
        val currentNodeFound = currentNodeNativeId != null
        val parser = if (envelope.depth == 0 && envelope.messagesWasArray) PARSER else PARSER_ENVELOPE
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

    private fun scanPayload(reader: JsonReader): PayloadScan {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return PayloadScan(false, null, null, null, emptyList())
        }
        val candidates = mutableListOf<EnvelopeCandidate>()
        val root = scanObject(reader, 0, candidates)
        if (reader.peek() != JsonToken.END_DOCUMENT) {
            return PayloadScan(false, null, null, null, emptyList())
        }
        return PayloadScan(true, root.conversationId, root.currentNodeId, root.title, candidates)
    }

    private fun scanObject(
        reader: JsonReader,
        depth: Int,
        candidates: MutableList<EnvelopeCandidate>,
    ): ObjectMeta {
        require(depth <= MAX_JSON_DEPTH) { "JSON nesting exceeds normalizer limit" }
        var conversationId: String? = null
        var currentNodeId: String? = null
        var title: String? = null
        var messagesNodes: List<ParsedNode>? = null
        var messagesWasArray = false

        reader.beginObject()
        while (reader.hasNext()) {
            when (val name = reader.nextName()) {
                "conversation_id" -> conversationId = readScalarString(reader)
                "current_node" -> currentNodeId = readScalarString(reader)
                "title" -> title = readCapturedTitle(reader)
                "messages" -> {
                    when (reader.peek()) {
                        JsonToken.BEGIN_ARRAY -> {
                            messagesWasArray = true
                            messagesNodes = parseMessagesArray(reader)
                        }
                        JsonToken.BEGIN_OBJECT -> {
                            messagesWasArray = false
                            messagesNodes = parseMessagesObject(reader)
                        }
                        else -> scanValue(reader, depth + 1, candidates)
                    }
                }
                else -> scanValue(reader, depth + 1, candidates)
            }
        }
        reader.endObject()

        val nodes = messagesNodes
        if (nodes != null && nodes.any { it.identifiedAuthored }) {
            candidates += EnvelopeCandidate(
                conversationId = conversationId,
                currentNodeId = currentNodeId,
                title = title,
                nodes = nodes.map { it.node },
                messagesWasArray = messagesWasArray,
                depth = depth,
            )
        }
        return ObjectMeta(conversationId, currentNodeId, title)
    }

    private fun scanValue(
        reader: JsonReader,
        depth: Int,
        candidates: MutableList<EnvelopeCandidate>,
    ) {
        require(depth <= MAX_JSON_DEPTH) { "JSON nesting exceeds normalizer limit" }
        when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> scanObject(reader, depth, candidates)
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                while (reader.hasNext()) scanValue(reader, depth + 1, candidates)
                reader.endArray()
            }
            else -> reader.skipValue()
        }
    }

    private fun parseMessagesArray(reader: JsonReader): List<ParsedNode> = buildList {
        reader.beginArray()
        while (reader.hasNext()) {
            val item = readJsonValue(reader)
            parsedNodeFromItem(item)?.let(::add)
        }
        reader.endArray()
    }

    private fun parseMessagesObject(reader: JsonReader): List<ParsedNode> {
        val keyed = mutableListOf<Pair<String, ParsedNode>>()
        reader.beginObject()
        while (reader.hasNext()) {
            val key = reader.nextName()
            val item = readJsonValue(reader)
            parsedNodeFromItem(item)?.let { keyed += key to it }
        }
        reader.endObject()
        return keyed.sortedBy { it.first }.map { it.second }
    }

    private fun parsedNodeFromItem(item: Any?): ParsedNode? {
        val objectItem = item as? JSONObject ?: return null
        val identifiedDirect = isIdentifiedAuthoredMessage(objectItem)
        val message = if (identifiedDirect) objectItem else objectItem.optJSONObject("message") ?: return null
        val node = parseMessage(message) ?: return null
        return ParsedNode(node, isIdentifiedAuthoredMessage(message))
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
        return NormalizedMessageNode(messageNativeId, messageNativeId, role, createdAt, textParts, contentJson)
    }

    private fun resolveConversationIdentity(
        rootConversationId: String?,
        envelopeConversationId: String?,
        sourceSha256: String,
    ): AndroidConversationIdentityResolution? {
        val payloadIds = linkedSetOf<String>()
        envelopeConversationId?.takeIf { it.isNotBlank() }?.let(payloadIds::add)
        rootConversationId?.takeIf { it.isNotBlank() }?.let(payloadIds::add)
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

    private fun readCapturedTitle(reader: JsonReader): String? = when (reader.peek()) {
        JsonToken.STRING -> reader.nextString().trim().takeIf { it.isNotBlank() }?.take(DISPLAY_TITLE_LIMIT)
        else -> {
            reader.skipValue()
            null
        }
    }

    private fun readScalarString(reader: JsonReader): String? = when (reader.peek()) {
        JsonToken.STRING, JsonToken.NUMBER -> reader.nextString()
        JsonToken.BOOLEAN -> reader.nextBoolean().toString()
        JsonToken.NULL -> {
            reader.nextNull()
            null
        }
        else -> {
            reader.skipValue()
            null
        }
    }

    private fun readJsonValue(reader: JsonReader, depth: Int = 0): Any? {
        require(depth <= MAX_JSON_DEPTH) { "JSON nesting exceeds message limit" }
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> JSONObject().also { objectValue ->
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    objectValue.put(name, readJsonValue(reader, depth + 1) ?: JSONObject.NULL)
                }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> JSONArray().also { arrayValue ->
                reader.beginArray()
                while (reader.hasNext()) {
                    arrayValue.put(readJsonValue(reader, depth + 1) ?: JSONObject.NULL)
                }
                reader.endArray()
            }
            JsonToken.STRING -> reader.nextString()
            JsonToken.NUMBER -> JSONTokener(reader.nextString()).nextValue()
            JsonToken.BOOLEAN -> reader.nextBoolean()
            JsonToken.NULL -> {
                reader.nextNull()
                JSONObject.NULL
            }
            else -> {
                reader.skipValue()
                JSONObject.NULL
            }
        }
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

    private data class PayloadScan(
        val validRoot: Boolean,
        val rootConversationId: String?,
        val rootCurrentNodeId: String?,
        val rootTitle: String?,
        val candidates: List<EnvelopeCandidate>,
    )

    private data class ObjectMeta(
        val conversationId: String?,
        val currentNodeId: String?,
        val title: String?,
    )

    private data class EnvelopeCandidate(
        val conversationId: String?,
        val currentNodeId: String?,
        val title: String?,
        val nodes: List<NormalizedMessageNode>,
        val messagesWasArray: Boolean,
        val depth: Int,
    )

    private data class ParsedNode(val node: NormalizedMessageNode, val identifiedAuthored: Boolean)

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
        private const val MAX_JSON_DEPTH = 256
        private const val DISPLAY_TITLE_LIMIT = 120
        private const val CANDIDATE_KIND = "conversation_payload_candidate"
        private const val PARSER = "messages-array-v0"
        private const val PARSER_ENVELOPE = "messages-envelope-v1"
        private const val COVERAGE_BASIS = "messages_array_no_graph_edges"
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val RECOGNIZED_ROLES = setOf("user", "assistant", "system", "tool")
    }
}
