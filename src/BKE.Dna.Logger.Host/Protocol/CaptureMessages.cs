namespace BKE.Dna.Logger.Host.Protocol;

internal sealed record CaptureStart(
    string Type,
    string CaptureId,
    string PageUrl,
    string RequestUrl,
    string Method,
    int Status,
    string? ContentType,
    string Initiator,
    string CapturedAt,
    long ByteLength,
    string Fidelity);

internal sealed record CaptureChunk(
    string Type,
    string CaptureId,
    int Sequence,
    string Base64);

internal sealed record CaptureEnd(
    string Type,
    string CaptureId);
