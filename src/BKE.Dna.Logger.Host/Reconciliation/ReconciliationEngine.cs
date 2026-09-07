using System.Text;
using System.Text.Json;

namespace BKE.Dna.Logger.Host.Reconciliation;

internal sealed class ReconciliationEngine
{
    private const long MaxBodyBytes = 16L * 1024 * 1024;

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        WriteIndented = true
    };

    private readonly string _bodiesDirectory;
    private readonly string _observationsDirectory;
    private readonly string _classificationsDirectory;
    private readonly string _witnessesDirectory;
    private readonly string _reconciliationsDirectory;

    public ReconciliationEngine(string captureRoot)
    {
        var root = Path.GetFullPath(captureRoot);
        _bodiesDirectory = Path.Combine(root, "bodies");
        _observationsDirectory = Path.Combine(root, "observations");
        _classificationsDirectory = Path.Combine(root, "classifications");
        _witnessesDirectory = Path.Combine(root, "witnesses");
        _reconciliationsDirectory = Path.Combine(root, "reconciliations");
        Directory.CreateDirectory(_reconciliationsDirectory);
    }

    public void ReconcileAll()
    {
        if (!Directory.Exists(_witnessesDirectory) || !Directory.Exists(_observationsDirectory))
        {
            return;
        }

        foreach (var witnessPath in Directory.EnumerateFiles(_witnessesDirectory, "*.json"))
        {
            try
            {
                ReconcileWitness(witnessPath);
            }
            catch
            {
                // Reconciliation is derivative metadata. Never threaten raw capture or witness persistence.
            }
        }
    }

    private void ReconcileWitness(string witnessPath)
    {
        using var witnessDocument = JsonDocument.Parse(File.ReadAllBytes(witnessPath));
        var witnessRoot = witnessDocument.RootElement;
        var witnessId = witnessRoot.GetProperty("witnessId").GetString()
            ?? throw new InvalidDataException("Witness has no ID.");
        var pageUrl = witnessRoot.GetProperty("pageUrl").GetString()
            ?? throw new InvalidDataException("Witness has no page URL.");
        var snippets = witnessRoot.GetProperty("snippets")
            .EnumerateArray()
            .Select(static item => Normalize(item.GetString() ?? string.Empty))
            .Where(static item => item.Length >= 8)
            .Distinct(StringComparer.Ordinal)
            .ToArray();

        var candidates = FindCandidates(pageUrl);
        var matches = new List<ReconciliationMatch>();

        foreach (var candidate in candidates.Values)
        {
            var bodyPath = Path.Combine(_bodiesDirectory, $"{candidate.Sha256}.body");
            if (!File.Exists(bodyPath))
            {
                continue;
            }

            var bodyInfo = new FileInfo(bodyPath);
            if (bodyInfo.Length > MaxBodyBytes)
            {
                continue;
            }

            var searchableStrings = ExtractSearchableStrings(File.ReadAllBytes(bodyPath));
            var matched = snippets
                .Where(snippet => searchableStrings.Any(value => ContainsEither(value, snippet)))
                .ToArray();

            if (matched.Length == 0)
            {
                continue;
            }

            matches.Add(new ReconciliationMatch(
                candidate.Sha256,
                candidate.CaptureIds.OrderBy(static id => id, StringComparer.Ordinal).ToArray(),
                candidate.ClassificationScore,
                matched,
                snippets.Length,
                snippets.Length == 0 ? 0 : Math.Round((double)matched.Length / snippets.Length, 4)));
        }

        var result = new ReconciliationResult(
            witnessId,
            pageUrl,
            matches.Count > 0 ? "corroborated" : "unmatched",
            matches
                .OrderByDescending(static match => match.MatchRatio)
                .ThenByDescending(static match => match.ClassificationScore)
                .ToArray(),
            DateTimeOffset.UtcNow.ToString("O"));

        var outputPath = Path.Combine(_reconciliationsDirectory, $"{witnessId}.json");
        File.WriteAllText(outputPath, JsonSerializer.Serialize(result, JsonOptions));
    }

    private Dictionary<string, Candidate> FindCandidates(string pageUrl)
    {
        var result = new Dictionary<string, Candidate>(StringComparer.Ordinal);

        foreach (var observationPath in Directory.EnumerateFiles(_observationsDirectory, "*.json"))
        {
            try
            {
                using var observationDocument = JsonDocument.Parse(File.ReadAllBytes(observationPath));
                var root = observationDocument.RootElement;
                var capture = root.GetProperty("capture");
                if (!string.Equals(capture.GetProperty("pageUrl").GetString(), pageUrl, StringComparison.Ordinal))
                {
                    continue;
                }

                var sha256 = root.GetProperty("sha256").GetString();
                var captureId = capture.GetProperty("captureId").GetString();
                if (string.IsNullOrWhiteSpace(sha256) || string.IsNullOrWhiteSpace(captureId))
                {
                    continue;
                }

                if (!TryGetCandidateScore(sha256, out var score))
                {
                    continue;
                }

                if (!result.TryGetValue(sha256, out var candidate))
                {
                    candidate = new Candidate(sha256, score);
                    result.Add(sha256, candidate);
                }

                candidate.CaptureIds.Add(captureId);
            }
            catch
            {
                // One malformed derivative observation must not prevent reconciliation of the rest.
            }
        }

        return result;
    }

    private bool TryGetCandidateScore(string sha256, out int score)
    {
        score = 0;
        var path = Path.Combine(_classificationsDirectory, $"{sha256}.json");
        if (!File.Exists(path))
        {
            return false;
        }

        try
        {
            using var document = JsonDocument.Parse(File.ReadAllBytes(path));
            var classification = document.RootElement.GetProperty("classification");
            if (!string.Equals(
                    classification.GetProperty("kind").GetString(),
                    "conversation_payload_candidate",
                    StringComparison.Ordinal))
            {
                return false;
            }

            score = classification.GetProperty("score").GetInt32();
            return true;
        }
        catch
        {
            return false;
        }
    }

    private static IReadOnlyList<string> ExtractSearchableStrings(byte[] body)
    {
        var values = new List<string>();
        try
        {
            using var document = JsonDocument.Parse(body);
            var stack = new Stack<JsonElement>();
            stack.Push(document.RootElement);

            while (stack.Count > 0)
            {
                var current = stack.Pop();
                switch (current.ValueKind)
                {
                    case JsonValueKind.Object:
                        foreach (var property in current.EnumerateObject())
                        {
                            stack.Push(property.Value);
                        }
                        break;
                    case JsonValueKind.Array:
                        foreach (var item in current.EnumerateArray())
                        {
                            stack.Push(item);
                        }
                        break;
                    case JsonValueKind.String:
                        var value = Normalize(current.GetString() ?? string.Empty);
                        if (value.Length >= 8)
                        {
                            values.Add(value);
                        }
                        break;
                }
            }
        }
        catch (JsonException)
        {
            var raw = Normalize(Encoding.UTF8.GetString(body));
            if (raw.Length >= 8)
            {
                values.Add(raw);
            }
        }

        return values;
    }

    private static bool ContainsEither(string left, string right)
        => left.Contains(right, StringComparison.Ordinal) || right.Contains(left, StringComparison.Ordinal);

    private static string Normalize(string value)
        => string.Join(' ', value.Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries));

    private sealed class Candidate
    {
        public Candidate(string sha256, int classificationScore)
        {
            Sha256 = sha256;
            ClassificationScore = classificationScore;
        }

        public string Sha256 { get; }
        public int ClassificationScore { get; }
        public HashSet<string> CaptureIds { get; } = new(StringComparer.Ordinal);
    }

    private sealed record ReconciliationResult(
        string WitnessId,
        string PageUrl,
        string Status,
        IReadOnlyList<ReconciliationMatch> Matches,
        string ReconciledAt);

    private sealed record ReconciliationMatch(
        string Sha256,
        IReadOnlyList<string> CaptureIds,
        int ClassificationScore,
        IReadOnlyList<string> MatchedSnippets,
        int WitnessSnippetCount,
        double MatchRatio);
}
