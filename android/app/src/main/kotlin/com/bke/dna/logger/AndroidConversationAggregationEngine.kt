package com.bke.dna.logger

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Native Android logical-conversation aggregator.
 *
 * Raw evidence and normalized snapshots remain immutable inputs. New normalized
 * snapshots are resolved from Working Data SQLite while pre-PR5 loose snapshots
 * remain readable through the compatibility accessor. This engine unions
 * snapshots by conversationNativeId, preserves graph/revision/source provenance,
 * recomputes structural coverage, writes one logical conversation state file,
 * then projects that derivative state into Android SQLite.
 */
class AndroidConversationAggregationEngine(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val captureRoot = AndroidDnaPaths.capturesRoot(appContext)
    private val observationsDirectory = File(captureRoot, "observations")
    private val conversationsDirectory = File(captureRoot, "conversations").also {
        check(it.exists() || it.mkdirs()) { "Unable to create Android conversation directory" }
    }
    private val index = AndroidCaptureIndex(appContext)

    fun aggregateAll(): List<AndroidLogicalConversation> = readSnapshots()
        .groupBy { it.conversationNativeId }
        .toSortedMap()
        .mapNotNull { (conversationNativeId, group) ->
            if (group.isEmpty()) null else aggregateConversationState(conversationNativeId, group)
        }

    /** Rebuild only the logical conversation touched by a live normalized capture. */
    fun aggregateConversation(conversationNativeId: String): AndroidLogicalConversation? {
        require(conversationNativeId.isNotBlank())
        val snapshots = readSnapshots(conversationNativeId)
        return if (snapshots.isEmpty()) null else aggregateConversationState(conversationNativeId, snapshots)
    }

    override fun close() = index.close()

    private fun readSnapshots(targetConversationNativeId: String? = null): List<Snapshot> {
        val observedAtBySource = readObservationTimes()
        return AndroidDerivativeSourceAccess.listNormalizedSourceSha256s(appContext)
            .mapNotNull { sourceSha256 ->
                val metadata = AndroidDerivativeSourceAccess.readNormalizedMetadata(appContext, sourceSha256)
                    ?: return@mapNotNull null
                if (
                    targetConversationNativeId != null &&
                    metadata.conversationNativeId != targetConversationNativeId
                ) {
                    return@mapNotNull null
                }
                runCatching {
                    AndroidDerivativeSourceAccess.withNormalizedJsonReader(
                        appContext,
                        sourceSha256,
                    ) { reader ->
                        readSnapshot(reader, observedAtBySource)
                    }
                }.getOrNull()
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
        reader: JsonReader,
        observedAtBySource: Map<String, String>,
    ): Snapshot? {
        var sourceSha256: String? = null
        var conversationNativeId: String? = null
        var currentNodeNativeId: String? = null
        var coverageStatus: String? = null
        var coverageBasis: String? = null
        var normalizedAt: String? = null
        val nodes = mutableListOf<SnapshotNode>()

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "sourceSha256" -> sourceSha256 = nextNullableString(reader)
                "conversationNativeId" -> conversationNativeId = nextNullableString(reader)
                "currentNodeNativeId" -> currentNodeNativeId = nextNullableString(reader)
                "coverageStatus" -> coverageStatus = nextNullableString(reader)
                "coverageBasis" -> coverageBasis = nextNullableString(reader)
                "normalizedAt" -> normalizedAt = nextNullableString(reader)
                "nodes" -> {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        readSnapshotNode(reader)?.let(nodes::add)
                    }
                    reader.endArray()
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        val source = sourceSha256?.takeIf { it.isNotBlank() } ?: return null
        val conversation = conversationNativeId?.takeIf { it.isNotBlank() } ?: return null
        val normalized = normalizedAt?.takeIf { it.isNotBlank() } ?: return null
        val observedAt = observedAtBySource[source] ?: normalized

        return Snapshot(
            sourceSha256 = source,
            conversationNativeId = conversation,
            currentNodeNativeId = currentNodeNativeId,
            coverageStatus = coverageStatus ?: "indeterminate",
            coverageBasis = coverageBasis ?: MESSAGES_COVERAGE_BASIS,
            observedAt = observedAt,
            nodes = nodes,
        )
    }

    private fun readSnapshotNode(reader: JsonReader): SnapshotNode? {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return null
        }

        var nodeNativeId: String? = null
        var messageNativeId: String? = null
        var parentNativeId: String? = null
        var role: String? = null
        var createdAt: String? = null
        var contentJson: String? = null
        var childNativeIds = emptyList<String>()
        var textParts = emptyList<String>()

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "nodeNativeId" -> nodeNativeId = nextNullableString(reader)
                "messageNativeId" -> messageNativeId = nextNullableString(reader)
                "parentNativeId" -> parentNativeId = nextNullableString(reader)
                "childNativeIds" -> childNativeIds = readStringList(reader)
                "role" -> role = nextNullableString(reader)
                "createdAt" -> createdAt = nextNullableString(reader)
                "textParts" -> textParts = readStringList(reader)
                "contentJson" -> contentJson = nextNullableString(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        val nodeId = nodeNativeId?.takeIf { it.isNotBlank() } ?: return null
        return SnapshotNode(
            nodeNativeId = nodeId,
            messageNativeId = messageNativeId,
            parentNativeId = parentNativeId,
            childNativeIds = childNativeIds,
            role = role,
            createdAt = createdAt,
            textParts = textParts,
            contentJson = contentJson,
        )
    }

    private fun nextNullableString(reader: JsonReader): String? =
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            null
        } else {
            reader.nextString()
        }

    private fun readStringList(reader: JsonReader): List<String> = buildList {
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() == JsonToken.NULL) {
                reader.nextNull()
            } else {
                reader.nextString().takeIf { it.isNotBlank() }?.let(::add)
            }
        }
        reader.endArray()
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
        writeState(File(captureRoot, relativePath), state)
        index.replaceLogicalConversation(state, relativePath)
        return state
    }

    private fun writeState(target: File, state: AndroidLogicalConversation) {
        val temp = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temp, false).use { output ->
                val writer = JsonWriter(OutputStreamWriter(output, Charsets.UTF_8))
                writer.setIndent("  ")
                writeLogicalConversation(writer, state)
                writer.flush()
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

    private fun writeLogicalConversation(writer: JsonWriter, state: AndroidLogicalConversation) {
        writer.beginObject()
        writer.name("conversationKey").value(state.conversationKey)
        writer.name("conversationNativeId").value(state.conversationNativeId)
        writer.name("currentNodeNativeId")
        if (state.currentNodeNativeId == null) writer.nullValue() else writer.value(state.currentNodeNativeId)
        writer.name("stateObservedThrough").value(state.stateObservedThrough)
        writer.name("coverageStatus").value(state.coverageStatus)
        writer.name("coverageBasis").value(state.coverageBasis)
        writer.name("rootFound").value(state.rootFound)
        writer.name("currentNodeFound").value(state.currentNodeFound)
        writer.name("currentLeafFound").value(state.currentLeafFound)
        writer.name("parentChainComplete").value(state.parentChainComplete)
        writer.name("cycleDetected").value(state.cycleDetected)
        writeStringList(writer, "unresolvedParentNativeIds", state.unresolvedParentNativeIds)
        writeStringList(writer, "unresolvedChildNativeIds", state.unresolvedChildNativeIds)

        writer.name("sources").beginArray()
        state.sources.forEach { source ->
            writer.beginObject()
            writer.name("sourceSha256").value(source.sourceSha256)
            writer.name("observedAt").value(source.observedAt)
            writer.name("currentNodeNativeId")
            if (source.currentNodeNativeId == null) writer.nullValue() else writer.value(source.currentNodeNativeId)
            writer.name("coverageStatus").value(source.coverageStatus)
            writer.name("coverageBasis").value(source.coverageBasis)
            writer.endObject()
        }
        writer.endArray()

        writer.name("nodes").beginArray()
        state.nodes.forEach { node ->
            writer.beginObject()
            writer.name("nodeNativeId").value(node.nodeNativeId)
            writeStringList(writer, "messageNativeIds", node.messageNativeIds)
            writeStringList(writer, "parentNativeIds", node.parentNativeIds)
            writeStringList(writer, "childNativeIds", node.childNativeIds)
            writeStringList(writer, "roles", node.roles)
            writeStringList(writer, "createdAtValues", node.createdAtValues)
            writer.name("revisions").beginArray()
            node.revisions.forEach { revision ->
                writer.beginObject()
                writer.name("revisionSha256").value(revision.revisionSha256)
                writer.name("contentJson").value(revision.contentJson)
                writeStringList(writer, "textParts", revision.textParts)
                writeStringList(writer, "sourceSha256s", revision.sourceSha256s)
                writer.name("firstObservedAt").value(revision.firstObservedAt)
                writer.name("lastObservedAt").value(revision.lastObservedAt)
                writer.endObject()
            }
            writer.endArray()
            writer.endObject()
        }
        writer.endArray()
        writer.endObject()
    }

    private fun writeStringList(writer: JsonWriter, name: String, values: List<String>) {
        writer.name(name).beginArray()
        values.forEach(writer::value)
        writer.endArray()
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

private fun sha256Text(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).toLowerHex()

private fun ByteArray.toLowerHex(): String = joinToString("") { "%02x".format(it) }
