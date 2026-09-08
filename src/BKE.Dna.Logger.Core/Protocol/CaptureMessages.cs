namespace BKE.Dna.Logger.Core.Protocol;

public sealed record CaptureStart(
    string Type,
    string CaptureId,
    string PageUrl,
    string RequestUrl,
    string Method,
    int Status,
    string? ContentType,
    string Initiator,
    string CapturedAt,
    long? ByteLength,
    string Fidelity);

public sealed record CaptureChunk(
    string Type,
    string CaptureId,
    int Sequence,
    string Base64);

public sealed record CaptureEnd(
    string Type,
    string CaptureId,
    long? ByteLength);
