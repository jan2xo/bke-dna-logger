using System.Text;
using System.Text.Json;

namespace BKE.Dna.Logger.Host.Classification;

internal static class ConversationPayloadClassifier
{
    private static readonly UTF8Encoding StrictUtf8 = new(false, true);

    public static PayloadClassification Classify(ReadOnlySpan<byte> body, string? contentType)
    {
        if (!LooksTextual(contentType))
        {
            return PayloadClassification.Other("non_textual_content_type");
        }

        string text;
        try
        {
            text = StrictUtf8.GetString(body);
        }
        catch (DecoderFallbackException)
        {
            return PayloadClassification.Other("invalid_utf8");
        }

        var signals = new HashSet<string>(StringComparer.Ordinal);
        var score = 0;
        var parsedJson = false;

        if (TryParseJson(text, out var document))
        {
            using (document)
            {
                parsedJson = true;
                score += ScoreJson(document.RootElement, signals);
            }
        }
        else if (contentType?.Contains("text/event-stream", StringComparison.OrdinalIgnoreCase) == true)
        {
            foreach (var line in text.Split('\n'))
            {
                var trimmed = line.Trim();
                if (!trimmed.StartsWith("data:", StringComparison.OrdinalIgnoreCase))
                {
                    continue;
                }

                var candidate = trimmed[5..].Trim();
                if (candidate.Length == 0 || candidate == "[DONE]")
                {
                    continue;
                }

                if (!TryParseJson(candidate, out var eventDocument))
                {
                    continue;
                }

                using (eventDocument)
                {
                    parsedJson = true;
                    score += ScoreJson(eventDocument.RootElement, signals);
                }
            }
        }

        if (!parsedJson)
        {
            return PayloadClassification.Other("no_json_structure");
        }

        score = Math.Min(score, 100);
        var kind = score >= 28
            ? "conversation_payload_candidate"
            : "other";
        var confidence = score >= 55
            ? "high"
            : score >= 28
                ? "medium"
                : "low";

        return new PayloadClassification(
            kind,
            score,
            confidence,
            signals.OrderBy(static signal => signal, StringComparer.Ordinal).ToArray());
    }

    private static int ScoreJson(JsonElement element, HashSet<string> signals)
    {
        var score = 0;
        var stack = new Stack<(JsonElement Element, string? PropertyName)>();
        stack.Push((element, null));

        while (stack.Count > 0)
        {
            var (current, propertyName) = stack.Pop();
            switch (current.ValueKind)
            {
                case JsonValueKind.Object:
                    foreach (var property in current.EnumerateObject())
                    {
                        score += ScoreProperty(property.Name, property.Value, signals);
                        stack.Push((property.Value, property.Name));
                    }
                    break;
                case JsonValueKind.Array:
                    foreach (var child in current.EnumerateArray())
                    {
                        stack.Push((child, propertyName));
                    }
                    break;
                case JsonValueKind.String:
                    if (string.Equals(propertyName, "role", StringComparison.OrdinalIgnoreCase))
                    {
                        var role = current.GetString();
                        if (role is "user" or "assistant" or "system" or "tool")
                        {
                            score += AddSignal(signals, "recognized_message_role", 10);
                        }
                    }
                    break;
            }
        }

        if (signals.Contains("mapping") && signals.Contains("message") &&
            (signals.Contains("parent") || signals.Contains("children")))
        {
            score += AddSignal(signals, "conversation_graph_shape", 18);
        }

        if (signals.Contains("author") && signals.Contains("content") &&
            signals.Contains("recognized_message_role"))
        {
            score += AddSignal(signals, "authored_message_shape", 14);
        }

        return score;
    }

    private static int ScoreProperty(string name, JsonElement value, HashSet<string> signals)
    {
        return name.ToLowerInvariant() switch
        {
            "mapping" when value.ValueKind == JsonValueKind.Object => AddSignal(signals, "mapping", 12),
            "messages" when value.ValueKind is JsonValueKind.Array or JsonValueKind.Object => AddSignal(signals, "messages", 8),
            "message" when value.ValueKind == JsonValueKind.Object => AddSignal(signals, "message", 6),
            "author" when value.ValueKind == JsonValueKind.Object => AddSignal(signals, "author", 5),
            "role" => AddSignal(signals, "role", 4),
            "content" => AddSignal(signals, "content", 4),
            "parts" when value.ValueKind == JsonValueKind.Array => AddSignal(signals, "parts", 3),
            "parent" => AddSignal(signals, "parent", 5),
            "children" when value.ValueKind == JsonValueKind.Array => AddSignal(signals, "children", 5),
            "conversation_id" => AddSignal(signals, "conversation_id", 8),
            "current_node" => AddSignal(signals, "current_node", 6),
            _ => 0
        };
    }

    private static int AddSignal(HashSet<string> signals, string signal, int points)
        => signals.Add(signal) ? points : 0;

    private static bool LooksTextual(string? contentType)
    {
        if (string.IsNullOrWhiteSpace(contentType))
        {
            return true;
        }

        return contentType.Contains("json", StringComparison.OrdinalIgnoreCase) ||
               contentType.Contains("text/", StringComparison.OrdinalIgnoreCase) ||
               contentType.Contains("event-stream", StringComparison.OrdinalIgnoreCase) ||
               contentType.Contains("ndjson", StringComparison.OrdinalIgnoreCase);
    }

    private static bool TryParseJson(string text, out JsonDocument document)
    {
        try
        {
            document = JsonDocument.Parse(text);
            return true;
        }
        catch (JsonException)
        {
            document = null!;
            return false;
        }
    }
}

internal sealed record PayloadClassification(
    string Kind,
    int Score,
    string Confidence,
    IReadOnlyList<string> Signals)
{
    public static PayloadClassification Other(string signal)
        => new("other", 0, "low", new[] { signal });
}
