using System.Text.Json;

namespace BKE.Dna.Logger.Core.Protocol;

public static class DnaWireProtocol
{
    public const int MaxMessageBytes = 2 * 1024 * 1024;

    public const string CaptureStartType = "capture_start";
    public const string CaptureChunkType = "capture_chunk";
    public const string CaptureEndType = "capture_end";
    public const string DomWitnessType = "dom_witness";

    public static string ReadAndValidateType(ReadOnlySpan<byte> utf8Json)
    {
        if (utf8Json.Length is 0 or > MaxMessageBytes)
        {
            throw new InvalidDataException(
                $"DNA wire message length {utf8Json.Length} is outside the allowed range.");
        }

        using var document = JsonDocument.Parse(utf8Json);
        if (!document.RootElement.TryGetProperty("type", out var typeElement))
        {
            throw new InvalidDataException("DNA wire message has no type.");
        }

        var type = typeElement.GetString();
        if (!IsSupportedType(type))
        {
            throw new InvalidDataException($"Unknown DNA wire message type '{type}'.");
        }

        return type!;
    }

    public static bool IsSupportedType(string? type)
        => type is CaptureStartType or CaptureChunkType or CaptureEndType or DomWitnessType;
}
