package com.bke.dna.logger

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant

/** Native Kotlin parity for the desktop generic mapping-graph normalizer. */
class AndroidGraphNormalizationEngine(context: android.content.Context) {
    private val captureRoot = AndroidDnaPaths.capturesRoot(context.applicationContext)
    private val bodiesDirectory = File(captureRoot, "bodies")
    private val classificationsDirectory = File(captureRoot, "classifications")
    private val normalizedDirectory = File(captureRoot, "normalized").also {
        check(it.exists() || it.mkdirs()) { "Unable to create Android normalized directory" }
    }

    fun normalizeCandidate(sourceSha256: String): AndroidNormalizationResult? {
        require(SHA256.matches(sourceSha256)) { "Expected lowercase SHA-256 source identity" }
        val classificationPath = File(classificationsDirectory, "$sourceSha256.json")
        if (!classificationPath.isFile) return null

        val classificationRoot = JSONObject(classificationPath.readText())
        if (classificationRoot.getJSONObject("classification").getString("kind") != CANDIDATE_KIND) {
            return null
        }

        val outputPath = File(normalizedDirectory, "$sourceSha256.json")
        if (outputPath.isFile) {
            return readExistingResult(outputPath, sourceSha256)
        }

        val bodyPath = File(bodiesDirectory, "$sourceSha256.body")
        if (!bodyPath.isFile || bodyPath.length() > MAX_BODY_BYTES) return null

        val root = try {
            JSONObject(bodyPath.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            return null
        }
        val mapping = root.optJSONObject("mapping") ?: return null

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

        writeDerivativeAtomically(outputPath, normalized.toString(2))
        return conversationNativeId?.takeIf { it.isNotBlank() }?.let {
            AndroidNormalizationResult(sourceSha256, it, outputPath)
        }
    }

    private fun readExistingResult(path: File, sourceSha256: String): AndroidNormalizationResult? {
        val root = runCatching { JSONObject(path.readText()) }.getOrNull() ?: return null
        if (root.optString("sourceSha256") != sourceSha256) return null
        val conversationNativeId = root.optNullableString("conversationNativeId")?.takeIf { it.isNotBlank() }
            ?: return null
        return AndroidNormalizationResult(sourceSha256, conversationNativeId, path)
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

    private fun writeDerivativeAtomically(target: File, text: String) {
        val temp = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temp, false).use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            try {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
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
    val normalizedFile: File,
)

private fun JSONObject.optNullableString(key: String): String? =
    if (has(key) && !isNull(key)) optString(key, null) else null
