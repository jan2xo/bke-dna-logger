package com.bke.dna.logger

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.time.Instant

/** Native Kotlin parity for the desktop generic mapping-graph normalizer. */
class AndroidGraphNormalizationEngine(context: android.content.Context) {
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
            val existing = readExistingResult(payload, sourceSha256)
            if (existing == null) {
                Log.d(TAG, "BKE DNA normalization: normalization_skip_existing_result_unusable")
            }
            return existing
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

        val rootValue = try {
            val tokener = JSONTokener(String(rawBytes, Charsets.UTF_8))
            val value = tokener.nextValue()
            if (tokener.nextClean() != '\u0000') {
                Log.d(TAG, "BKE DNA normalization: normalization_skip_invalid_json")
                return null
            }
            value
        } catch (_: Exception) {
            Log.d(TAG, "BKE DNA normalization: normalization_skip_invalid_json")
            return null
        }

        logCandidateStructure(rootValue)

        val root = rootValue as? JSONObject ?: run {
            Log.d(TAG, "BKE DNA normalization: normalization_skip_not_object")
            return null
        }
        val mapping = root.optJSONObject("mapping") ?: run {
            Log.d(TAG, "BKE DNA normalization: normalization_skip_no_root_mapping")
            return null
        }

        val knownNodeIds = linkedSetOf<String>()
        val mappingKeys = mapping.keys()
        while (mappingKeys.hasNext()) knownNodeIds += mappingKeys.next()

        val nodes = mutableListOf<NormalizedNode>()
        knownNodeIds.forEach { nodeId ->
            mapping.optJSONObject(nodeId)?.let { node -> nodes += parseNode(nodeId, node) }
        }

        val unresolvedParents = nodes.mapNotNull { it.parentNativeId }
            .filterNot { it in knownNodeIds }
            .distinct()
            .sorted()
        val unresolvedChildren = nodes.flatMap { it.childNativeIds }
            .filterNot { it in knownNodeIds }
            .distinct()
            .sorted()
        val conversationNativeId = scalarToString(root.opt("conversation_id"))
        val currentNodeNativeId = scalarToString(root.opt("current_node"))
        val byId = nodes.associateBy { it.nodeNativeId }
        val rootFound = nodes.any { it.parentNativeId.isNullOrBlank() }
        val currentNodeFound = !currentNodeNativeId.isNullOrBlank() && currentNodeNativeId in byId
        val currentLeafFound = currentNodeFound && byId.getValue(currentNodeNativeId!!).childNativeIds.isEmpty()
        val parentChain = evaluateParentChain(currentNodeNativeId, byId)
        val coverageStatus = determineCoverageStatus(
            nodeCount = nodes.size,
            currentNodeNativeId = currentNodeNativeId,
            rootFound = rootFound,
            currentNodeFound = currentNodeFound,
            currentLeafFound = currentLeafFound,
            parentChainComplete = parentChain.complete,
            cycleDetected = parentChain.cycleDetected,
            unresolvedParentCount = unresolvedParents.size,
            unresolvedChildCount = unresolvedChildren.size,
        )

        val normalizedAt = Instant.now().toString()
        val normalized = JSONObject()
            .put("sourceSha256", sourceSha256)
            .put("parser", PARSER)
            .put("conversationNativeId", conversationNativeId ?: JSONObject.NULL)
            .put("currentNodeNativeId", currentNodeNativeId ?: JSONObject.NULL)
            .put("coverageStatus", coverageStatus)
            .put("coverageBasis", COVERAGE_BASIS)
            .put("rootFound", rootFound)
            .put("currentNodeFound", currentNodeFound)
            .put("currentLeafFound", currentLeafFound)
            .put("parentChainComplete", parentChain.complete)
            .put("cycleDetected", parentChain.cycleDetected)
            .put("unresolvedParentNativeIds", JSONArray(unresolvedParents))
            .put("unresolvedChildNativeIds", JSONArray(unresolvedChildren))
            .put("nodes", JSONArray(nodes.map { it.toJson() }))
            .put("normalizedAt", normalizedAt)

        if (conversationNativeId.isNullOrBlank()) {
            Log.d(TAG, "BKE DNA normalization: normalization_skip_missing_conversation_id")
            return null
        }
        AndroidDerivativeStore(appContext).use { store ->
            store.putNormalizedJson(sourceSha256, normalized.toString(2))
        }
        return AndroidNormalizationResult(sourceSha256, conversationNativeId, null)
    }

    private fun logCandidateStructure(rootValue: Any?) {
        when (rootValue) {
            is JSONObject -> Log.d(TAG, "BKE DNA normalization: candidate_root_object")
            is JSONArray -> Log.d(TAG, "BKE DNA normalization: candidate_root_array")
            else -> Log.d(TAG, "BKE DNA normalization: candidate_root_other")
        }

        val root = rootValue as? JSONObject
        if (root?.optJSONObject("mapping") != null) {
            Log.d(TAG, "BKE DNA normalization: candidate_root_mapping")
        }
        if (root?.has("conversation_id") == true) {
            Log.d(TAG, "BKE DNA normalization: candidate_root_conversation_id")
        }
        if (root?.has("current_node") == true) {
            Log.d(TAG, "BKE DNA normalization: candidate_root_current_node")
        }

        val rootMessages = root?.opt("messages")
        if (root?.has("messages") == true) {
            Log.d(TAG, "BKE DNA normalization: candidate_root_messages")
        }

        val nested = findNestedCandidateStructure(rootValue)
        if (nested.mapping) {
            Log.d(TAG, "BKE DNA normalization: candidate_nested_mapping")
        }
        if (nested.conversationId) {
            Log.d(TAG, "BKE DNA normalization: candidate_nested_conversation_id")
        }
        if (nested.currentNode) {
            Log.d(TAG, "BKE DNA normalization: candidate_nested_current_node")
        }
        if (nested.messages) {
            Log.d(TAG, "BKE DNA normalization: candidate_nested_messages")
        }
        if (rootMessages is JSONArray || nested.messagesArray) {
            Log.d(TAG, "BKE DNA normalization: candidate_messages_array")
        }
        if (rootMessages is JSONObject || nested.messagesObject) {
            Log.d(TAG, "BKE DNA normalization: candidate_messages_object")
        }
    }

    private fun findNestedCandidateStructure(rootValue: Any?): CandidateStructurePresence {
        val stack = ArrayDeque<Any>()
        addChildren(rootValue, stack)

        var mapping = false
        var conversationId = false
        var currentNode = false
        var messages = false
        var messagesArray = false
        var messagesObject = false
        while (
            stack.isNotEmpty() &&
            !(mapping && conversationId && currentNode && messages && messagesArray && messagesObject)
        ) {
            when (val current = stack.removeLast()) {
                is JSONObject -> {
                    if (current.optJSONObject("mapping") != null) mapping = true
                    if (current.has("conversation_id")) conversationId = true
                    if (current.has("current_node")) currentNode = true
                    if (current.has("messages")) {
                        messages = true
                        when (current.opt("messages")) {
                            is JSONArray -> messagesArray = true
                            is JSONObject -> messagesObject = true
                        }
                    }
                    addChildren(current, stack)
                }
                is JSONArray -> addChildren(current, stack)
            }
        }
        return CandidateStructurePresence(
            mapping = mapping,
            conversationId = conversationId,
            currentNode = currentNode,
            messages = messages,
            messagesArray = messagesArray,
            messagesObject = messagesObject,
        )
    }

    private fun addChildren(value: Any?, stack: ArrayDeque<Any>) {
        when (value) {
            is JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext()) {
                    val child = value.opt(keys.next())
                    if (child is JSONObject || child is JSONArray) stack.addLast(child)
                }
            }
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    val child = value.opt(index)
                    if (child is JSONObject || child is JSONArray) stack.addLast(child)
                }
            }
        }
    }

    private fun readExistingResult(payloadJson: String, sourceSha256: String): AndroidNormalizationResult? {
        val root = runCatching { JSONObject(payloadJson) }.getOrNull() ?: return null
        if (root.optString("sourceSha256") != sourceSha256) return null
        val conversationNativeId = root.optNullableString("conversationNativeId")?.takeIf { it.isNotBlank() }
            ?: return null
        return AndroidNormalizationResult(sourceSha256, conversationNativeId, null)
    }

    private fun parseNode(nodeNativeId: String, node: JSONObject): NormalizedNode {
        val parent = scalarToString(node.opt("parent"))
        val children = stringArray(node.optJSONArray("children"))
        val message = node.optJSONObject("message")
            ?: return NormalizedNode(nodeNativeId, null, parent, children, null, null, emptyList(), null)

        val messageNativeId = scalarToString(message.opt("id"))
        val role = message.optJSONObject("author")?.let { scalarToString(it.opt("role")) }
        val createdAt = scalarToString(message.opt("create_time"))
        val content = if (message.has("content")) message.opt("content") else null
        val contentJson = if (content == null) null else compactJson(content)
        val textParts = if (content is JSONObject) stringArray(content.optJSONArray("parts")) else emptyList()

        return NormalizedNode(
            nodeNativeId,
            messageNativeId,
            parent,
            children,
            role,
            createdAt,
            textParts,
            contentJson,
        )
    }

    private fun evaluateParentChain(
        currentNodeNativeId: String?,
        byId: Map<String, NormalizedNode>,
    ): ParentChainResult {
        if (currentNodeNativeId.isNullOrBlank()) return ParentChainResult(false, false)
        val visited = linkedSetOf<String>()
        var cursor: String? = currentNodeNativeId
        while (!cursor.isNullOrBlank()) {
            if (!visited.add(cursor)) return ParentChainResult(false, true)
            val node = byId[cursor] ?: return ParentChainResult(false, false)
            if (node.parentNativeId.isNullOrBlank()) return ParentChainResult(true, false)
            cursor = node.parentNativeId
        }
        return ParentChainResult(false, false)
    }

    private fun determineCoverageStatus(
        nodeCount: Int,
        currentNodeNativeId: String?,
        rootFound: Boolean,
        currentNodeFound: Boolean,
        currentLeafFound: Boolean,
        parentChainComplete: Boolean,
        cycleDetected: Boolean,
        unresolvedParentCount: Int,
        unresolvedChildCount: Int,
    ): String {
        if (nodeCount == 0 || currentNodeNativeId.isNullOrBlank()) return "indeterminate"
        if (unresolvedParentCount > 0 || unresolvedChildCount > 0 ||
            !currentNodeFound || !parentChainComplete || cycleDetected
        ) return "partial"
        if (rootFound && currentLeafFound) return "complete"
        return "indeterminate"
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

    private data class CandidateStructurePresence(
        val mapping: Boolean,
        val conversationId: Boolean,
        val currentNode: Boolean,
        val messages: Boolean,
        val messagesArray: Boolean,
        val messagesObject: Boolean,
    )

    private data class ParentChainResult(val complete: Boolean, val cycleDetected: Boolean)

    private data class NormalizedNode(
        val nodeNativeId: String,
        val messageNativeId: String?,
        val parentNativeId: String?,
        val childNativeIds: List<String>,
        val role: String?,
        val createdAt: String?,
        val textParts: List<String>,
        val contentJson: String?,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("nodeNativeId", nodeNativeId)
            .put("messageNativeId", messageNativeId ?: JSONObject.NULL)
            .put("parentNativeId", parentNativeId ?: JSONObject.NULL)
            .put("childNativeIds", JSONArray(childNativeIds))
            .put("role", role ?: JSONObject.NULL)
            .put("createdAt", createdAt ?: JSONObject.NULL)
            .put("textParts", JSONArray(textParts))
            .put("contentJson", contentJson ?: JSONObject.NULL)
    }

    companion object {
        private const val TAG = "BkeDnaNormalizer"
        private const val MAX_BODY_BYTES = 16L * 1024 * 1024
        private const val CANDIDATE_KIND = "conversation_payload_candidate"
        private const val PARSER = "generic-mapping-graph-v0"
        private const val COVERAGE_BASIS = "structural_graph_closure_only"
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

data class AndroidNormalizationResult(
    val sourceSha256: String,
    val conversationNativeId: String,
    val normalizedFile: File?,
)

private fun JSONObject.optNullableString(key: String): String? =
    if (has(key) && !isNull(key)) optString(key, null) else null
