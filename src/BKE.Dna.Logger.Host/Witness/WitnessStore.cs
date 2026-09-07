using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using BKE.Dna.Logger.Host.Protocol;

namespace BKE.Dna.Logger.Host.Witness;

internal sealed class WitnessStore
{
    private const int MaxSnippets = 32;
    private const int MaxSnippetChars = 512;

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        WriteIndented = true
    };

    private readonly string _witnessDirectory;

    public WitnessStore(string captureRoot)
    {
        _witnessDirectory = Path.Combine(Path.GetFullPath(captureRoot), "witnesses");
        Directory.CreateDirectory(_witnessDirectory);
    }

    public void Record(DomWitness witness)
    {
        Validate(witness);

        var normalized = witness.Snippets
            .Select(Normalize)
            .Where(static snippet => snippet.Length > 0)
            .Distinct(StringComparer.Ordinal)
            .ToArray();

        var fingerprintMaterial = $"{witness.PageUrl}\n{string.Join("\n", normalized)}";
        var fingerprint = Convert.ToHexString(
            SHA256.HashData(Encoding.UTF8.GetBytes(fingerprintMaterial))).ToLowerInvariant();

        var envelope = new WitnessEnvelope(
            witness.WitnessId,
            witness.PageUrl,
            witness.ObservedAt,
            normalized,
            fingerprint,
            DateTimeOffset.UtcNow.ToString("O"));

        var path = Path.Combine(_witnessDirectory, $"{witness.WitnessId}.json");
        File.WriteAllText(path, JsonSerializer.Serialize(envelope, JsonOptions));
    }

    private static void Validate(DomWitness witness)
    {
        if (!Guid.TryParse(witness.WitnessId, out _))
        {
            throw new InvalidDataException("DOM witness ID must be a GUID.");
        }

        if (!Uri.TryCreate(witness.PageUrl, UriKind.Absolute, out var pageUri) ||
            !string.Equals(pageUri.Scheme, Uri.UriSchemeHttps, StringComparison.OrdinalIgnoreCase))
        {
            throw new InvalidDataException("DOM witness page URL must be absolute HTTPS.");
        }

        if (witness.Snippets.Count is 0 or > MaxSnippets)
        {
            throw new InvalidDataException($"DOM witness must contain between 1 and {MaxSnippets} snippets.");
        }

        if (witness.Snippets.Any(static snippet => snippet.Length > MaxSnippetChars))
        {
            throw new InvalidDataException($"DOM witness snippet exceeds {MaxSnippetChars} characters.");
        }
    }

    private static string Normalize(string value)
        => string.Join(' ', value.Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries));

    private sealed record WitnessEnvelope(
        string WitnessId,
        string PageUrl,
        string ObservedAt,
        IReadOnlyList<string> Snippets,
        string FingerprintSha256,
        string StoredAt);
}
