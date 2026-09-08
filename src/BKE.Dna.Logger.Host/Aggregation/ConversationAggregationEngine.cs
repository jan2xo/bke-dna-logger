using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace BKE.Dna.Logger.Host.Aggregation;

internal sealed class ConversationAggregationEngine
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        WriteIndented = true
    };

    private readonly string _captureRoot;
    private readonly string _normalizedDirectory;
    private readonly string _conversationsDirectory;

    public ConversationAggregationEngine(string captureRoot)
    {
        _captureRoot = Path.GetFullPath(captureRoot);
        _normalizedDirectory = Path.Combine(_captureRoot, "normalized");
        _conversationsDirectory = Path.Combine(_captureRoot, "conversations");
        Directory.CreateDirectory(_conversationsDirectory);
    }

    public void TryAggregateAll()
    {
        try
        {
            AggregateAll();
        }
        catch (Exception error)
        {
            Console.Error.WriteLine($"BKE DNA conversation aggregation skipped; source evidence is intact: {error.Message}");
        }
    }

    private void AggregateAll()
    {
        if (!Directory.Exists(_normalizedDirectory))
        {
            return;
        }

        var observedAtBySource = ReadObservationTimes();
        var snapshots = new List<Snapshot>();
        foreach (var path in Directory.EnumerateFiles(_normalizedDirectory, "*.json"))
        {
            try
            {
                var snapshot = ReadSnapshot(path, observedAtBySource);
                if (snapshot is not null)
                {
                    snapshots.Add(snapshot);
                }
            }
            catch
            {
                // One unsupported normalized derivative cannot block other conversations.
            }
        }

        foreach (var group in snapshots.GroupBy(static item => item.ConversationNativeId, StringComparer.Ordinal))
        {
            WriteConversation(group.Key, group.ToArray());
        }
    }

    private Dictionary<string, string> ReadObservationTimes()
    {
        var result = new Dictionary<string, string>(StringComparer.Ordinal);
        var directory = Path.Combine(_captureRoot, "observations");
        if (!Directory.Exists(directory))
        {
            return result;
        }

        foreach (var path in Directory.EnumerateFiles(directory, "*.json"))
        {
            try
            {
                using var document = JsonDocument.Parse(File.ReadAllBytes(path));
                var root = document.RootElement;
                var sourceSha = RequiredString(root, "sha256");
                var capturedAt = RequiredString(root.GetProperty("capture"), "capturedAt");
                if (!result.TryGetValue(sourceSha, out var current) ||
                    string.CompareOrdinal(capturedAt, current) > 0)
                {
                    result[sourceSha] = capturedAt;
                }
            }
            catch
            {
                // Ignore malformed observation metadata.
            }
        }

        return result;
    }

    private static Snapshot? ReadSnapshot(string path, IReadOnlyDictionary<string, string> observedAtBySource)
    {
        using var document = JsonDocument.Parse(File.ReadAllBytes(path));
        var root = document.RootElement;
        var conversationNativeId = OptionalString(root, "conversationNativeId");
        if (string.IsNullOrWhiteSpace(conversationNativeId))
        {
            return null;
        }

        var sourceSha = RequiredString(root, "sourceSha256");
        var normalizedAt = RequiredString(root, "normalizedAt");
        var observedAt = observedAtBySource.TryGetValue(sourceSha, out var capturedAt)
            ? capturedAt
            : normalizedAt;

        var nodes = new List<SnapshotNode>();
        foreach (var node in root.GetProperty("nodes").EnumerateArray())
        {
            nodes.Add(new SnapshotNode(
                NodeNativeId: RequiredString(node, "nodeNativeId"),
                MessageNativeId: OptionalString(node, "messageNativeId"),
                ParentNativeId: OptionalString(node, "parentNativeId"),
                ChildNativeIds: ReadStringArray(node, "childNativeIds"),
                Role: OptionalString(node, "role"),
                CreatedAt: OptionalString(node, "createdAt"),
                TextParts: ReadStringArray(node, "textParts"),
                ContentJson: OptionalString(node, "contentJson")));
        }

        return new Snapshot(
            SourceSha256: sourceSha,
            ConversationNativeId: conversationNativeId,
            CurrentNodeNativeId: OptionalString(root, "currentNodeNativeId"),
            CoverageStatus: RequiredString(root, "coverageStatus"),
            CoverageBasis: RequiredString(root, "coverageBasis"),
            ObservedAt: observedAt,
            Nodes: nodes);
    }

    private void WriteConversation(string conversationNativeId, IReadOnlyList<Snapshot> inputSnapshots)
    {
        var snapshots = inputSnapshots
            .OrderBy(static item => item.ObservedAt, StringComparer.Ordinal)
            .ThenBy(static item => item.SourceSha256, StringComparer.Ordinal)
            .ToArray();
        if (snapshots.Length == 0)
        {
            return;
        }

        var nodeMap = new Dictionary<string, NodeAccumulator>(StringComparer.Ordinal);
        foreach (var snapshot in snapshots)
        {
            foreach (var node in snapshot.Nodes)
            {
                if (!nodeMap.TryGetValue(node.NodeNativeId, out var accumulator))
                {
                    accumulator = new NodeAccumulator(node.NodeNativeId);
                    nodeMap.Add(node.NodeNativeId, accumulator);
                }

                accumulator.Observe(node, snapshot.SourceSha256, snapshot.ObservedAt);
            }
        }

        var nodes = nodeMap.Values
            .Select(static accumulator => accumulator.ToRecord())
            .OrderBy(static node => node.NodeNativeId, StringComparer.Ordinal)
            .ToArray();

        var latest = snapshots[^1];
        var coverage = ComputeCoverage(latest.CurrentNodeNativeId, nodes);
        var conversationKey = Sha256Text(conversationNativeId);
        var state = new AggregatedConversation(
            ConversationKey: conversationKey,
            ConversationNativeId: conversationNativeId,
            CurrentNodeNativeId: latest.CurrentNodeNativeId,
            StateObservedThrough: latest.ObservedAt,
            CoverageStatus: coverage.Status,
            CoverageBasis: "multi_snapshot_structural_union",
            RootFound: coverage.RootFound,
            CurrentNodeFound: coverage.CurrentNodeFound,
            CurrentLeafFound: coverage.CurrentLeafFound,
            ParentChainComplete: coverage.ParentChainComplete,
            CycleDetected: coverage.CycleDetected,
            UnresolvedParentNativeIds: coverage.UnresolvedParents,
            UnresolvedChildNativeIds: coverage.UnresolvedChildren,
            Sources: snapshots.Select(static snapshot => new AggregatedSource(
                snapshot.SourceSha256,
                snapshot.ObservedAt,
                snapshot.CurrentNodeNativeId,
                snapshot.CoverageStatus,
                snapshot.CoverageBasis)).ToArray(),
            Nodes: nodes);

        var outputPath = Path.Combine(_conversationsDirectory, $"{conversationKey}.json");
        var tempPath = $"{outputPath}.{Guid.NewGuid():N}.tmp";
        try
        {
            File.WriteAllText(tempPath, JsonSerializer.Serialize(state, JsonOptions));
            File.Move(tempPath, outputPath, overwrite: true);
        }
        finally
        {
            if (File.Exists(tempPath))
            {
                File.Delete(tempPath);
            }
        }
    }

    private static CoverageResult ComputeCoverage(
        string? currentNodeNativeId,
        IReadOnlyList<AggregatedNode> nodes)
    {
        var byId = nodes.ToDictionary(static node => node.NodeNativeId, StringComparer.Ordinal);
        var known = byId.Keys.ToHashSet(StringComparer.Ordinal);
        var unresolvedParents = nodes
            .SelectMany(static node => node.ParentNativeIds)
            .Where(parent => !known.Contains(parent))
            .Distinct(StringComparer.Ordinal)
            .OrderBy(static item => item, StringComparer.Ordinal)
            .ToArray();
        var unresolvedChildren = nodes
            .SelectMany(static node => node.ChildNativeIds)
            .Where(child => !known.Contains(child))
            .Distinct(StringComparer.Ordinal)
            .OrderBy(static item => item, StringComparer.Ordinal)
            .ToArray();
        var rootFound = nodes.Any(static node => node.ParentNativeIds.Count == 0);
        var currentFound = currentNodeNativeId is not null && byId.ContainsKey(currentNodeNativeId);
        var currentLeaf = currentFound && byId[currentNodeNativeId!].ChildNativeIds.Count == 0;
        var cycleDetected = HasCycle(nodes, byId);
        var parentChainComplete = currentFound && CanReachRoot(currentNodeNativeId!, byId, new HashSet<string>(StringComparer.Ordinal));

        string status;
        if (unresolvedParents.Length > 0 || unresolvedChildren.Length > 0 || cycleDetected ||
            (currentNodeNativeId is not null && !currentFound))
        {
            status = "partial";
        }
        else if (currentNodeNativeId is null || !rootFound)
        {
            status = "indeterminate";
        }
        else if (currentFound && currentLeaf && parentChainComplete)
        {
            status = "complete";
        }
        else
        {
            status = "partial";
        }

        return new CoverageResult(
            status,
            rootFound,
            currentFound,
            currentLeaf,
            parentChainComplete,
            cycleDetected,
            unresolvedParents,
            unresolvedChildren);
    }

    private static bool CanReachRoot(
        string nodeId,
        IReadOnlyDictionary<string, AggregatedNode> byId,
        HashSet<string> visiting)
    {
        if (!byId.TryGetValue(nodeId, out var node) || !visiting.Add(nodeId))
        {
            return false;
        }

        try
        {
            if (node.ParentNativeIds.Count == 0)
            {
                return true;
            }

            return node.ParentNativeIds
                .Where(byId.ContainsKey)
                .Any(parent => CanReachRoot(parent, byId, visiting));
        }
        finally
        {
            visiting.Remove(nodeId);
        }
    }

    private static bool HasCycle(
        IReadOnlyList<AggregatedNode> nodes,
        IReadOnlyDictionary<string, AggregatedNode> byId)
    {
        var visited = new HashSet<string>(StringComparer.Ordinal);
        var visiting = new HashSet<string>(StringComparer.Ordinal);

        bool Visit(string nodeId)
        {
            if (visiting.Contains(nodeId))
            {
                return true;
            }
            if (!visited.Add(nodeId))
            {
                return false;
            }

            visiting.Add(nodeId);
            if (byId.TryGetValue(nodeId, out var node))
            {
                foreach (var child in node.ChildNativeIds.Where(byId.ContainsKey))
                {
                    if (Visit(child))
                    {
                        return true;
                    }
                }
            }
            visiting.Remove(nodeId);
            return false;
        }

        return nodes.Any(node => Visit(node.NodeNativeId));
    }

    private static IReadOnlyList<string> ReadStringArray(JsonElement element, string propertyName)
    {
        if (!element.TryGetProperty(propertyName, out var value) || value.ValueKind != JsonValueKind.Array)
        {
            return Array.Empty<string>();
        }

        return value.EnumerateArray()
            .Where(static item => item.ValueKind == JsonValueKind.String)
            .Select(static item => item.GetString())
            .Where(static item => !string.IsNullOrWhiteSpace(item))
            .Select(static item => item!)
            .ToArray();
    }

    private static string RequiredString(JsonElement element, string propertyName)
    {
        if (!element.TryGetProperty(propertyName, out var value) || value.ValueKind != JsonValueKind.String)
        {
            throw new InvalidDataException($"Required JSON string '{propertyName}' is missing.");
        }
        return value.GetString()!;
    }

    private static string? OptionalString(JsonElement element, string propertyName)
    {
        if (!element.TryGetProperty(propertyName, out var value) || value.ValueKind == JsonValueKind.Null)
        {
            return null;
        }
        return value.ValueKind == JsonValueKind.String ? value.GetString() : null;
    }

    private static string Sha256Text(string value)
        => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(value))).ToLowerInvariant();

    private sealed class NodeAccumulator
    {
        private readonly HashSet<string> _messageNativeIds = new(StringComparer.Ordinal);
        private readonly HashSet<string> _parentNativeIds = new(StringComparer.Ordinal);
        private readonly HashSet<string> _childNativeIds = new(StringComparer.Ordinal);
        private readonly HashSet<string> _roles = new(StringComparer.Ordinal);
        private readonly Dictionary<string, RevisionAccumulator> _revisions = new(StringComparer.Ordinal);

        public NodeAccumulator(string nodeNativeId) => NodeNativeId = nodeNativeId;

        public string NodeNativeId { get; }

        public void Observe(SnapshotNode node, string sourceSha256, string observedAt)
        {
            if (!string.IsNullOrWhiteSpace(node.MessageNativeId)) _messageNativeIds.Add(node.MessageNativeId);
            if (!string.IsNullOrWhiteSpace(node.ParentNativeId)) _parentNativeIds.Add(node.ParentNativeId);
            foreach (var child in node.ChildNativeIds) _childNativeIds.Add(child);
            if (!string.IsNullOrWhiteSpace(node.Role)) _roles.Add(node.Role);

            if (!string.IsNullOrWhiteSpace(node.ContentJson))
            {
                var revisionSha = Sha256Text(node.ContentJson);
                if (!_revisions.TryGetValue(revisionSha, out var revision))
                {
                    revision = new RevisionAccumulator(revisionSha, node.ContentJson, node.TextParts, observedAt);
                    _revisions.Add(revisionSha, revision);
                }
                revision.Observe(sourceSha256, observedAt);
            }
        }

        public AggregatedNode ToRecord()
            => new(
                NodeNativeId,
                _messageNativeIds.OrderBy(static item => item, StringComparer.Ordinal).ToArray(),
                _parentNativeIds.OrderBy(static item => item, StringComparer.Ordinal).ToArray(),
                _childNativeIds.OrderBy(static item => item, StringComparer.Ordinal).ToArray(),
                _roles.OrderBy(static item => item, StringComparer.Ordinal).ToArray(),
                _revisions.Values
                    .Select(static item => item.ToRecord())
                    .OrderBy(static item => item.RevisionSha256, StringComparer.Ordinal)
                    .ToArray());
    }

    private sealed class RevisionAccumulator
    {
        private readonly HashSet<string> _sourceSha256s = new(StringComparer.Ordinal);

        public RevisionAccumulator(
            string revisionSha256,
            string contentJson,
            IReadOnlyList<string> textParts,
            string observedAt)
        {
            RevisionSha256 = revisionSha256;
            ContentJson = contentJson;
            TextParts = textParts.ToArray();
            FirstObservedAt = observedAt;
            LastObservedAt = observedAt;
        }

        public string RevisionSha256 { get; }
        public string ContentJson { get; }
        public IReadOnlyList<string> TextParts { get; }
        public string FirstObservedAt { get; private set; }
        public string LastObservedAt { get; private set; }

        public void Observe(string sourceSha256, string observedAt)
        {
            _sourceSha256s.Add(sourceSha256);
            if (string.CompareOrdinal(observedAt, FirstObservedAt) < 0) FirstObservedAt = observedAt;
            if (string.CompareOrdinal(observedAt, LastObservedAt) > 0) LastObservedAt = observedAt;
        }

        public AggregatedRevision ToRecord()
            => new(
                RevisionSha256,
                ContentJson,
                TextParts,
                _sourceSha256s.OrderBy(static item => item, StringComparer.Ordinal).ToArray(),
                FirstObservedAt,
                LastObservedAt);
    }

    private sealed record Snapshot(
        string SourceSha256,
        string ConversationNativeId,
        string? CurrentNodeNativeId,
        string CoverageStatus,
        string CoverageBasis,
        string ObservedAt,
        IReadOnlyList<SnapshotNode> Nodes);

    private sealed record SnapshotNode(
        string NodeNativeId,
        string? MessageNativeId,
        string? ParentNativeId,
        IReadOnlyList<string> ChildNativeIds,
        string? Role,
        string? CreatedAt,
        IReadOnlyList<string> TextParts,
        string? ContentJson);

    private sealed record CoverageResult(
        string Status,
        bool RootFound,
        bool CurrentNodeFound,
        bool CurrentLeafFound,
        bool ParentChainComplete,
        bool CycleDetected,
        IReadOnlyList<string> UnresolvedParents,
        IReadOnlyList<string> UnresolvedChildren);

    private sealed record AggregatedConversation(
        string ConversationKey,
        string ConversationNativeId,
        string? CurrentNodeNativeId,
        string StateObservedThrough,
        string CoverageStatus,
        string CoverageBasis,
        bool RootFound,
        bool CurrentNodeFound,
        bool CurrentLeafFound,
        bool ParentChainComplete,
        bool CycleDetected,
        IReadOnlyList<string> UnresolvedParentNativeIds,
        IReadOnlyList<string> UnresolvedChildNativeIds,
        IReadOnlyList<AggregatedSource> Sources,
        IReadOnlyList<AggregatedNode> Nodes);

    private sealed record AggregatedSource(
        string SourceSha256,
        string ObservedAt,
        string? CurrentNodeNativeId,
        string CoverageStatus,
        string CoverageBasis);

    private sealed record AggregatedNode(
        string NodeNativeId,
        IReadOnlyList<string> MessageNativeIds,
        IReadOnlyList<string> ParentNativeIds,
        IReadOnlyList<string> ChildNativeIds,
        IReadOnlyList<string> Roles,
        IReadOnlyList<AggregatedRevision> Revisions);

    private sealed record AggregatedRevision(
        string RevisionSha256,
        string ContentJson,
        IReadOnlyList<string> TextParts,
        IReadOnlyList<string> SourceSha256s,
        string FirstObservedAt,
        string LastObservedAt);
}
