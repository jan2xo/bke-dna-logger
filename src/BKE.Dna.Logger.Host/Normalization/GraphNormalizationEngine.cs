using System.Globalization;
using System.Text.Json;

namespace BKE.Dna.Logger.Host.Normalization;

internal sealed class GraphNormalizationEngine
{
    private const long MaxBodyBytes = 16L * 1024 * 1024;
    private const string CoverageBasis = "structural_graph_closure_only";

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        WriteIndented = true
    };

    private readonly string _bodiesDirectory;
    private readonly string _classificationsDirectory;
    private readonly string _normalizedDirectory;

    public GraphNormalizationEngine(string captureRoot)
    {
        var root = Path.GetFullPath(captureRoot);
        _bodiesDirectory = Path.Combine(root, "bodies");
        _classificationsDirectory = Path.Combine(root, "classifications");
        _normalizedDirectory = Path.Combine(root, "normalized");
        Directory.CreateDirectory(_normalizedDirectory);
    }

    public void NormalizeAllCandidates()
    {
        if (!Directory.Exists(_classificationsDirectory))
        {
            return;
        }

        foreach (var classificationPath in Directory.EnumerateFiles(_classificationsDirectory, "*.json"))
        {
            try
            {
                NormalizeCandidate(classificationPath);
            }
            catch
            {
                // Normalization is derivative. Never invalidate raw evidence or classification.
            }
        }
    }

    private void NormalizeCandidate(string classificationPath)
    {
        using var classificationDocument = JsonDocument.Parse(File.ReadAllBytes(classificationPath));
        var root = classificationDocument.RootElement;
        var classification = root.GetProperty("classification");
        if (!string.Equals(
                classification.GetProperty("kind").GetString(),
                "conversation_payload_candidate",
                StringComparison.Ordinal))
        {
            return;
        }

        var sha256 = root.GetProperty("sha256").GetString();
        if (string.IsNullOrWhiteSpace(sha256))
        {
            return;
        }

        var outputPath = Path.Combine(_normalizedDirectory, $"{sha256}.json");
        if (File.Exists(outputPath))
        {
            return;
        }

        var bodyPath = Path.Combine(_bodiesDirectory, $"{sha256}.body");
        if (!File.Exists(bodyPath) || new FileInfo(bodyPath).Length > MaxBodyBytes)
        {
            return;
        }

        using var document = JsonDocument.Parse(File.ReadAllBytes(bodyPath));
        if (document.RootElement.ValueKind != JsonValueKind.Object ||
            !TryGetPropertyIgnoreCase(document.RootElement, "mapping", out var mapping) ||
            mapping.ValueKind != JsonValueKind.Object)
        {
            return;
        }

        var nodes = new List<NormalizedNode>();
        var knownNodeIds = new HashSet<string>(StringComparer.Ordinal);

        foreach (var nodeProperty in mapping.EnumerateObject())
        {
            knownNodeIds.Add(nodeProperty.Name);
            if (nodeProperty.Value.ValueKind != JsonValueKind.Object)
            {
                continue;
            }

            nodes.Add(ParseNode(nodeProperty.Name, nodeProperty.Value));
        }

        var unresolvedParents = nodes
            .Where(static node => !string.IsNullOrWhiteSpace(node.ParentNativeId))
            .Select(static node => node.ParentNativeId!)
            .Where(parent => !knownNodeIds.Contains(parent))
            .Distinct(StringComparer.Ordinal)
            .OrderBy(static parent => parent, StringComparer.Ordinal)
            .ToArray();

        var unresolvedChildren = nodes
            .SelectMany(static node => node.ChildNativeIds)
            .Where(child => !knownNodeIds.Contains(child))
            .Distinct(StringComparer.Ordinal)
            .OrderBy(static child => child, StringComparer.Ordinal)
            .ToArray();

        var conversationNativeId = TryGetScalarString(document.RootElement, "conversation_id");
        var currentNodeNativeId = TryGetScalarString(document.RootElement, "current_node");
        var nodeById = nodes.ToDictionary(static node => node.NodeNativeId, StringComparer.Ordinal);

        var rootFound = nodes.Any(static node => string.IsNullOrWhiteSpace(node.ParentNativeId));
        var currentNodeFound = !string.IsNullOrWhiteSpace(currentNodeNativeId) &&
                               nodeById.ContainsKey(currentNodeNativeId);
        var currentLeafFound = currentNodeFound &&
                               nodeById[currentNodeNativeId!].ChildNativeIds.Count == 0;
        var (parentChainComplete, cycleDetected) = EvaluateParentChain(currentNodeNativeId, nodeById);

        var coverageStatus = DetermineCoverageStatus(
            nodes.Count,
            currentNodeNativeId,
            rootFound,
            currentNodeFound,
            currentLeafFound,
            parentChainComplete,
            cycleDetected,
            unresolvedParents.Length,
            unresolvedChildren.Length);

        var normalized = new NormalizedConversationGraph(
            sha256,
            "generic-mapping-graph-v0",
            conversationNativeId,
            currentNodeNativeId,
            coverageStatus,
            CoverageBasis,
            rootFound,
            currentNodeFound,
            currentLeafFound,
            parentChainComplete,
            cycleDetected,
            unresolvedParents,
            unresolvedChildren,
            nodes,
            DateTimeOffset.UtcNow.ToString("O"));

        File.WriteAllText(outputPath, JsonSerializer.Serialize(normalized, JsonOptions));
    }

    private static string DetermineCoverageStatus(
        int nodeCount,
        string? currentNodeNativeId,
        bool rootFound,
        bool currentNodeFound,
        bool currentLeafFound,
        bool parentChainComplete,
        bool cycleDetected,
        int unresolvedParentCount,
        int unresolvedChildCount)
    {
        if (nodeCount == 0 || string.IsNullOrWhiteSpace(currentNodeNativeId))
        {
            return "indeterminate";
        }

        if (unresolvedParentCount > 0 || unresolvedChildCount > 0 ||
            !currentNodeFound || !parentChainComplete || cycleDetected)
        {
            return "partial";
        }

        if (rootFound && currentLeafFound)
        {
            return "complete";
        }

        return "indeterminate";
    }

    private static (bool Complete, bool CycleDetected) EvaluateParentChain(
        string? currentNodeNativeId,
        IReadOnlyDictionary<string, NormalizedNode> nodeById)
    {
        if (string.IsNullOrWhiteSpace(currentNodeNativeId))
        {
            return (false, false);
        }

        var visited = new HashSet<string>(StringComparer.Ordinal);
        var cursor = currentNodeNativeId;

        while (!string.IsNullOrWhiteSpace(cursor))
        {
            if (!visited.Add(cursor))
            {
                return (false, true);
            }

            if (!nodeById.TryGetValue(cursor, out var node))
            {
                return (false, false);
            }

            if (string.IsNullOrWhiteSpace(node.ParentNativeId))
            {
                return (true, false);
            }

            cursor = node.ParentNativeId;
        }

        return (false, false);
    }

    private static NormalizedNode ParseNode(string mappingNodeId, JsonElement node)
    {
        var parent = TryGetScalarString(node, "parent");
        var children = TryGetStringArray(node, "children");

        if (!TryGetPropertyIgnoreCase(node, "message", out var message) ||
            message.ValueKind != JsonValueKind.Object)
        {
            return new NormalizedNode(
                mappingNodeId,
                null,
                parent,
                children,
                null,
                null,
                Array.Empty<string>(),
                null);
        }

        var messageNativeId = TryGetScalarString(message, "id");
        string? role = null;
        if (TryGetPropertyIgnoreCase(message, "author", out var author) &&
            author.ValueKind == JsonValueKind.Object)
        {
            role = TryGetScalarString(author, "role");
        }

        var createdAt = TryGetScalarString(message, "create_time");
        var textParts = new List<string>();
        string? contentJson = null;

        if (TryGetPropertyIgnoreCase(message, "content", out var content))
        {
            contentJson = content.GetRawText();
            if (content.ValueKind == JsonValueKind.Object &&
                TryGetPropertyIgnoreCase(content, "parts", out var parts) &&
                parts.ValueKind == JsonValueKind.Array)
            {
                foreach (var part in parts.EnumerateArray())
                {
                    if (part.ValueKind == JsonValueKind.String)
                    {
                        textParts.Add(part.GetString() ?? string.Empty);
                    }
                }
            }
        }

        return new NormalizedNode(
            mappingNodeId,
            messageNativeId,
            parent,
            children,
            role,
            createdAt,
            textParts,
            contentJson);
    }

    private static IReadOnlyList<string> TryGetStringArray(JsonElement element, string propertyName)
    {
        if (!TryGetPropertyIgnoreCase(element, propertyName, out var array) ||
            array.ValueKind != JsonValueKind.Array)
        {
            return Array.Empty<string>();
        }

        return array.EnumerateArray()
            .Select(ScalarToString)
            .Where(static value => !string.IsNullOrWhiteSpace(value))
            .Select(static value => value!)
            .ToArray();
    }

    private static string? TryGetScalarString(JsonElement element, string propertyName)
    {
        return TryGetPropertyIgnoreCase(element, propertyName, out var value)
            ? ScalarToString(value)
            : null;
    }

    private static string? ScalarToString(JsonElement value)
    {
        return value.ValueKind switch
        {
            JsonValueKind.String => value.GetString(),
            JsonValueKind.Number => value.TryGetInt64(out var integer)
                ? integer.ToString(CultureInfo.InvariantCulture)
                : value.GetDouble().ToString("R", CultureInfo.InvariantCulture),
            JsonValueKind.True => "true",
            JsonValueKind.False => "false",
            _ => null
        };
    }

    private static bool TryGetPropertyIgnoreCase(JsonElement element, string name, out JsonElement value)
    {
        if (element.TryGetProperty(name, out value))
        {
            return true;
        }

        foreach (var property in element.EnumerateObject())
        {
            if (string.Equals(property.Name, name, StringComparison.OrdinalIgnoreCase))
            {
                value = property.Value;
                return true;
            }
        }

        value = default;
        return false;
    }

    private sealed record NormalizedConversationGraph(
        string SourceSha256,
        string Parser,
        string? ConversationNativeId,
        string? CurrentNodeNativeId,
        string CoverageStatus,
        string CoverageBasis,
        bool RootFound,
        bool CurrentNodeFound,
        bool CurrentLeafFound,
        bool ParentChainComplete,
        bool CycleDetected,
        IReadOnlyList<string> UnresolvedParentNativeIds,
        IReadOnlyList<string> UnresolvedChildNativeIds,
        IReadOnlyList<NormalizedNode> Nodes,
        string NormalizedAt);

    private sealed record NormalizedNode(
        string NodeNativeId,
        string? MessageNativeId,
        string? ParentNativeId,
        IReadOnlyList<string> ChildNativeIds,
        string? Role,
        string? CreatedAt,
        IReadOnlyList<string> TextParts,
        string? ContentJson);
}
