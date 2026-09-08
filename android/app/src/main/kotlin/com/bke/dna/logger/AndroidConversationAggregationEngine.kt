package com.bke.dna.logger

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Native Android logical-conversation aggregator.
 *
 * Raw evidence and normalized snapshots remain immutable inputs. This engine
 * unions snapshots by conversationNativeId, preserves graph/revision/source
 * provenance, recomputes structural coverage, writes one logical conversation
 * state file, then projects that derivative state into Android SQLite.
 */
class AndroidConversationAggregationEngine(context: Context) : AutoCloseable {
    private val captureRoot = AndroidDnaPaths.capturesRoot(context.applicationContext)
    private val normalizedDirectory = File(captureRoot, "normalized")
    private val observationsDirectory = File(captureRoot, "observations")
    private val conversationsDirectory = File(captureRoot, "conversations").also {
        check(it.exists() || it.mkdirs()) { "Unable to create Android conversation directory" }
    }
    private val index = AndroidCaptureIndex(context.applicationContext)

    fun aggregateAll(): List<AndroidLogicalConversation> = readSnapshots()
        .groupBy { it.conversationNativeId }
        .toSortedMap()
        .mapNotNull { (conversationNativeId, group) ->
            if (group.isEmpty()) null else aggregateConversationState(conversationNativeId, group)
        }

    /** Rebuild only the logical conversation touched by a live normalized capture. */
    fun aggregateConversation(conversationNativeId: String): AndroidLogicalConversation? {
        require(conversationNativeId.isNotBlank())
        val snapshots = readSnapshots().filter { it.conversationNativeId == conversationNativeId }
        return if (snapshots.isEmpty()) null else aggregateConversationState(conversationNativeId, snapshots)
    }

    override fun close() = index.close()

    private fun readSnapshots(): List<Snapshot> {
        if (!normalizedDirectory.isDirectory) return emptyList()
        val observedAtBySource = readObservationTimes()
        return normalizedDirectory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension == "json" }
            .sortedBy { it.name }
            .mapNotNull { path ->
                runCatching { readSnapshot(path, observedAtBySource) }.getOrNull()
            }
    }

    private fun readObservationTimes(): Map<String, String> {
        if (!observationsDirectory.isDirectory) return emptyMap()
        val latest = linkedMapOf<String, String>()

        observationsDirectory.listFiles().orEmpty()
            .filter { it.isFile && it.extension == "json" }
            .sortedBy { it.name }
            .forEach { path ->
                runCatching {
                    val root = JSONObject(path.readText())
                    val sourceSha = root.getString("sha256")
                    val capturedAt = root.getJSONObject("capture").getString("capturedAt")
                    val current = latest[sourceSha]
                    if (current == null || capturedAt > current) latest[sourceSha] = capturedAt
                }
            }

        return latest
    }

    private fun readSnapshot(
        path: File,
        observedAtBySource: Map<String, String>,
    ): Snapshot? {
        val root = JSONObject(path.readText())
        val conversationNativeId = root.optNullableString("conversationNativeId")
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val sourceSha256 = root.getString("sourceSha256")
        val normalizedAt = root.getString("normalizedAt")
        val observedAt = observedAtBySource[sourceSha256] ?: normalizedAt
        val nodeArray = root.getJSONArray("nodes")
        val nodes = buildList {
            for (index in 0 until nodeArray.length()) {
                val node = nodeArray.getJSONObject(index)
                add(
                    SnapshotNode(
                        nodeNativeId = node.getString("nodeNativeId"),
                        messageNativeId = node.optNullableString("messageNativeId"),
                        parentNativeId = node.optNullableString("parentNativeId"),
                        childNativeIds = node.optStringList("childNativeIds"),
                        role = node.optNullableString("role"),
                        createdAt = node.optNullableString("createdAt"),
                        textParts = node.optStringList("textParts"),
                        contentJson = node.optNullableString("contentJson"),
                    ),
                )
            }
        }

        return Snapshot(
            sourceSha256 = sourceSha256,
            conversationNativeId = conversationNativeId,
            currentNodeNativeId = root.optNullableString("currentNodeNativeId"),
            coverageStatus = root.getString("coverageStatus"),
            coverageBasis = root.getString("coverageBasis"),
            observedAt = observedAt,
            nodes = nodes,
        )
    }

    private fun aggregateConversationState(
        conversationNativeId: String,
        inputSnapshots: List<Snapshot>,
    ): AndroidLogicalConversation {
        val snapshots = inputSnapshots.sortedWith(
            compareBy<Snapshot> { it.observedAt }.thenBy { it.sourceSha256 },
        )
        val nodeMap = linkedMapOf<String, NodeAccumulator>()
        snapshots.forEach { snapshot ->
            snapshot.nodes.forEach { node ->
                nodeMap.getOrPut(node.nodeNativeId) { NodeAccumulator(node.nodeNativeId) }
                    .observe(node, snapshot.sourceSha256, snapshot.observedAt)
            }
        }

        val nodes = nodeMap.values.map { it.toRecord() }.sortedBy { it.nodeNativeId }
        val latest = snapshots.last()
        val graphSnapshots = snapshots.filter { it.coverageBasis == GRAPH_COVERAGE_BASIS }
        val latestGraphSnapshot = graphSnapshots.lastOrNull()
        val graphNodes = if (graphSnapshots.isEmpty()) {
            emptyList()
        } else {
            val graphNodeMap = linkedMapOf<String, NodeAccumulator>()
            graphSnapshots.forEach { snapshot ->
                snapshot.nodes.forEach { node ->
                    graphNodeMap.getOrPut(node.nodeNativeId) { NodeAccumulator(node.nodeNativeId) }
                        .observe(node, snapshot.sourceSha256, snapshot.observedAt)
                }
            }
            graphNodeMap.values.map { it.toRecord() }.sortedBy { it.nodeNativeId }
        }
        val stateCurrentNodeNativeId = latestGraphSnapshot?.currentNodeNativeId ?: latest.currentNodeNativeId
        val coverage = if (latestGraphSnapshot != null) {
            computeCoverage(latestGraphSnapshot.currentNodeNativeId, graphNodes)
        } else {
            computeGraphlessCoverage(latest.currentNodeNativeId, nodes)
        }
        val logicalCoverageBasis = if (latestGraphSnapshot != null) {
            LOGICAL_GRAPH_COVERAGE_BASIS
        } else {
            MESSAGES_COVERAGE_BASIS
        }

        val conversationKey = sha256Text(conversationNativeId)
        val state = AndroidLogicalConversation(
            conversationKey = conversationKey,
            conversationNativeId = conversationNativeId,
            currentNodeNativeId = stateCurrentNodeNativeId,
            stateObservedThrough = latest.observedAt,
            coverageStatus = coverage.status,
            coverageBasis = logicalCoverageBasis,
            rootFound = coverage.rootFound,
            currentNodeFound = coverage.currentNodeFound,
            currentLeafFound = coverage.currentLeafFound,
            parentChainComplete = coverage.parentChainComplete,
            cycleDetected = coverage.cycleDetected,
            unresolvedParentNativeIds = coverage.unresolvedParents,
            unresolvedChildNativeIds = coverage.unresolvedChildren,
            sources = snapshots.map {
                AndroidLogicalSource(
                    sourceSha256 = it.sourceSha256,
                    observedAt = it.observedAt,
                    currentNodeNativeId = it.currentNodeNativeId,
                    coverageStatus = it.coverageStatus,
                    coverageBasis = it.coverageBasis,
                )
            },
            nodes = nodes,
        )

        val relativePath = "conversations/$conversationKey.json"
        writeState(File(captureRoot, relativePath), state.toJson().toString(2))
        index.replaceLogicalConversation(state, relativePath)
        return state
    }

    private fun writeState(target: File, text: String) {
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

    private fun computeGraphlessCoverage(
        currentNodeNativeId: String?,
        nodes: List<AndroidLogicalNode>,
    ): CoverageResult {
        val currentFound = currentNodeNativeId != null && nodes.any { it.nodeNativeId == currentNodeNativeId }
        return CoverageResult(
            status = "indeterminate",
            rootFound = false,
            currentNodeFound = currentFound,
            currentLeafFound = false,
            parentChainComplete = false,
            cycleDetected = false,
            unresolvedParents = emptyList(),
            unresolvedChildren = emptyList(),
        )
    }

    private fun computeCoverage(
        currentNodeNativeId: String?,
        nodes: List<AndroidLogicalNode>,
    ): CoverageResult {
        val byId = nodes.associateBy { it.nodeNativeId }
        val known = byId.keys
        val unresolvedParents = nodes.flatMap { it.parentNativeIds }
            .filterNot { it in known }
            .distinct()
            .sorted()
        val unresolvedChildren = nodes.flatMap { it.childNativeIds }
            .filterNot { it in known }
            .distinct()
            .sorted()
        val rootFound = nodes.any { it.parentNativeIds.isEmpty() }
        val currentNode = currentNodeNativeId
        val currentFound = currentNode != null && currentNode in byId
        val currentLeaf = currentNode != null && currentFound && byId.getValue(currentNode).childNativeIds.isEmpty()
        val cycleDetected = hasCycle(nodes, byId)
        val parentChainComplete = currentNode != null && currentFound && canReachRoot(currentNode, byId, linkedSetOf())

        val status = when {
            unresolvedParents.isNotEmpty() ||
                unresolvedChildren.isNotEmpty() ||
                cycleDetected ||
                (currentNodeNativeId != null && !currentFound) -> "partial"
            currentNodeNativeId == null || !rootFound -> "indeterminate"
            currentFound && currentLeaf && parentChainComplete -> "complete"
            else -> "partial"
        }

        return CoverageResult(
            status = status,
            rootFound = rootFound,
            currentNodeFound = currentFound,
            currentLeafFound = currentLeaf,
            parentChainComplete = parentChainComplete,
            cycleDetected = cycleDetected,
            unresolvedParents = unresolvedParents,
            unresolvedChildren = unresolvedChildren,
        )
    }

    private fun canReachRoot(
        nodeId: String,
        byId: Map<String, AndroidLogicalNode>,
        visiting: MutableSet<String>,
    ): Boolean {
        val node = byId[nodeId] ?: return false
        if (!visiting.add(nodeId)) return false
        try {
            if (node.parentNativeIds.isEmpty()) return true
            return node.parentNativeIds
                .filter { it in byId }
                .any { canReachRoot(it, byId, visiting) }
        } finally {
            visiting.remove(nodeId)
        }
    }

    private fun hasCycle(
        nodes: List<AndroidLogicalNode>,
        byId: Map<String, AndroidLogicalNode>,
    ): Boolean {
        val visited = linkedSetOf<String>()
        val visiting = linkedSetOf<String>()

        fun visit(nodeId: String): Boolean {
            if (nodeId in visiting) return true
            if (!visited.add(nodeId)) return false
            visiting.add(nodeId)
            val cycle = byId[nodeId]?.childNativeIds
                .orEmpty()
                .filter { it in byId }
                .any(::visit)
            visiting.remove(nodeId)
            return cycle
        }

        return nodes.any { visit(it.nodeNativeId) }
    }

    private class NodeAccumulator(private val nodeNativeId: String) {
        private val messageNativeIds = linkedSetOf<String>()
        private val parentNativeIds = linkedSetOf<String>()
        private val childNativeIds = linkedSetOf<String>()
        private val roles = linkedSetOf<String>()
        private val createdAtValues = linkedSetOf<String>()
        private val revisions = linkedMapOf<String, RevisionAccumulator>()

        fun observe(node: SnapshotNode, sourceSha256: String, observedAt: String) {
            node.messageNativeId?.takeIf { it.isNotBlank() }?.let(messageNativeIds::add)
            node.parentNativeId?.takeIf { it.isNotBlank() }?.let(parentNativeIds::add)
            childNativeIds.addAll(node.childNativeIds)
            node.role?.takeIf { it.isNotBlank() }?.let(roles::add)
            node.createdAt?.takeIf { it.isNotBlank() }?.let(createdAtValues::add)

            node.contentJson?.takeIf { it.isNotBlank() }?.let { contentJson ->
                val revisionSha256 = sha256Text(contentJson)
                revisions.getOrPut(revisionSha256) {
                    RevisionAccumulator(
                        revisionSha256 = revisionSha256,
                        contentJson = contentJson,
                        textParts = node.textParts,
                        firstObservedAt = observedAt,
                        lastObservedAt = observedAt,
                    )
                }.observe(sourceSha256, observedAt)
            }
        }

        fun toRecord() = AndroidLogicalNode(
            nodeNativeId = nodeNativeId,
            messageNativeIds = messageNativeIds.sorted(),
            parentNativeIds = parentNativeIds.sorted(),
            childNativeIds = childNativeIds.sorted(),
            roles = roles.sorted(),
            createdAtValues = createdAtValues.sorted(),
            revisions = revisions.values.map { it.toRecord() }.sortedBy { it.revisionSha256 },
        )
    }

    private class RevisionAccumulator(
        private val revisionSha256: String,
        private val contentJson: String,
        private val textParts: List<String>,
        private var firstObservedAt: String,
        private var lastObservedAt: String,
    ) {
        private val sourceSha256s = linkedSetOf<String>()

        fun observe(sourceSha256: String, observedAt: String) {
            sourceSha256s.add(sourceSha256)
            if (observedAt < firstObservedAt) firstObservedAt = observedAt
            if (observedAt > lastObservedAt) lastObservedAt = observedAt
        }

        fun toRecord() = AndroidLogicalRevision(
            revisionSha256 = revisionSha256,
            contentJson = contentJson,
            textParts = textParts,
            sourceSha256s = sourceSha256s.sorted(),
            firstObservedAt = firstObservedAt,
            lastObservedAt = lastObservedAt,
        )
    }

    private data class Snapshot(
        val sourceSha256: String,
        val conversationNativeId: String,
        val currentNodeNativeId: String?,
        val coverageStatus: String,
        val coverageBasis: String,
        val observedAt: String,
        val nodes: List<SnapshotNode>,
    )

    private data class SnapshotNode(
        val nodeNativeId: String,
        val messageNativeId: String?,
        val parentNativeId: String?,
        val childNativeIds: List<String>,
        val role: String?,
        val createdAt: String?,
        val textParts: List<String>,
        val contentJson: String?,
    )

    private data class CoverageResult(
        val status: String,
        val rootFound: Boolean,
        val currentNodeFound: Boolean,
        val currentLeafFound: Boolean,
        val parentChainComplete: Boolean,
        val cycleDetected: Boolean,
        val unresolvedParents: List<String>,
        val unresolvedChildren: List<String>,
    )

    companion object {
        private const val GRAPH_COVERAGE_BASIS = "structural_graph_closure_only"
        private const val LOGICAL_GRAPH_COVERAGE_BASIS = "multi_snapshot_structural_union"
        private const val MESSAGES_COVERAGE_BASIS = "messages_array_no_graph_edges"
    }
}

data class AndroidLogicalConversation(
    val conversationKey: String,
    val conversationNativeId: String,
    val currentNodeNativeId: String?,
    val stateObservedThrough: String,
    val coverageStatus: String,
    val coverageBasis: String,
    val rootFound: Boolean,
    val currentNodeFound: Boolean,
    val currentLeafFound: Boolean,
    val parentChainComplete: Boolean,
    val cycleDetected: Boolean,
    val unresolvedParentNativeIds: List<String>,
    val unresolvedChildNativeIds: List<String>,
    val sources: List<AndroidLogicalSource>,
    val nodes: List<AndroidLogicalNode>,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("conversationKey", conversationKey)
        .put("conversationNativeId", conversationNativeId)
        .put("currentNodeNativeId", currentNodeNativeId ?: JSONObject.NULL)
        .put("stateObservedThrough", stateObservedThrough)
        .put("coverageStatus", coverageStatus)
        .put("coverageBasis", coverageBasis)
        .put("rootFound", rootFound)
        .put("currentNodeFound", currentNodeFound)
        .put("currentLeafFound", currentLeafFound)
        .put("parentChainComplete", parentChainComplete)
        .put("cycleDetected", cycleDetected)
        .put("unresolvedParentNativeIds", JSONArray(unresolvedParentNativeIds))
        .put("unresolvedChildNativeIds", JSONArray(unresolvedChildNativeIds))
        .put("sources", JSONArray(sources.map { it.toJson() }))
        .put("nodes", JSONArray(nodes.map { it.toJson() }))
}

data class AndroidLogicalSource(
    val sourceSha256: String,
    val observedAt: String,
    val currentNodeNativeId: String?,
    val coverageStatus: String,
    val coverageBasis: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("sourceSha256", sourceSha256)
        .put("observedAt", observedAt)
        .put("currentNodeNativeId", currentNodeNativeId ?: JSONObject.NULL)
        .put("coverageStatus", coverageStatus)
        .put("coverageBasis", coverageBasis)
}

data class AndroidLogicalNode(
    val nodeNativeId: String,
    val messageNativeIds: List<String>,
    val parentNativeIds: List<String>,
    val childNativeIds: List<String>,
    val roles: List<String>,
    val createdAtValues: List<String>,
    val revisions: List<AndroidLogicalRevision>,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("nodeNativeId", nodeNativeId)
        .put("messageNativeIds", JSONArray(messageNativeIds))
        .put("parentNativeIds", JSONArray(parentNativeIds))
        .put("childNativeIds", JSONArray(childNativeIds))
        .put("roles", JSONArray(roles))
        .put("createdAtValues", JSONArray(createdAtValues))
        .put("revisions", JSONArray(revisions.map { it.toJson() }))
}

data class AndroidLogicalRevision(
    val revisionSha256: String,
    val contentJson: String,
    val textParts: List<String>,
    val sourceSha256s: List<String>,
    val firstObservedAt: String,
    val lastObservedAt: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("revisionSha256", revisionSha256)
        .put("contentJson", contentJson)
        .put("textParts", JSONArray(textParts))
        .put("sourceSha256s", JSONArray(sourceSha256s))
        .put("firstObservedAt", firstObservedAt)
        .put("lastObservedAt", lastObservedAt)
}

private fun JSONObject.optNullableString(key: String): String? =
    if (has(key) && !isNull(key)) getString(key) else null

private fun JSONObject.optStringList(key: String): List<String> {
    val array = optJSONArray(key) ?: return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            if (!array.isNull(index)) array.optString(index)?.takeIf { it.isNotBlank() }?.let(::add)
        }
    }
}

private fun sha256Text(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).toLowerHex()

private fun ByteArray.toLowerHex(): String = joinToString("") { "%02x".format(it) }
