using System.Security.Cryptography;
using System.Text.Json;
using BKE.Dna.Logger.Host.Classification;
using BKE.Dna.Logger.Host.Protocol;

namespace BKE.Dna.Logger.Host.Capture;

internal sealed class CaptureStore : IDisposable
{
    private const long MaxClassificationBytes = 16L * 1024 * 1024;

    private static readonly JsonSerializerOptions ObservationJsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        WriteIndented = true
    };

    private readonly string _bodiesDirectory;
    private readonly string _observationsDirectory;
    private readonly string _classificationsDirectory;
    private readonly string _partialDirectory;
    private readonly Dictionary<string, CaptureSession> _sessions = new(StringComparer.Ordinal);

    public CaptureStore(string captureRoot)
    {
        var root = Path.GetFullPath(captureRoot);
        _bodiesDirectory = Path.Combine(root, "bodies");
        _observationsDirectory = Path.Combine(root, "observations");
        _classificationsDirectory = Path.Combine(root, "classifications");
        _partialDirectory = Path.Combine(root, "partial");

        Directory.CreateDirectory(_bodiesDirectory);
        Directory.CreateDirectory(_observationsDirectory);
        Directory.CreateDirectory(_classificationsDirectory);
        Directory.CreateDirectory(_partialDirectory);
    }

    public void Start(CaptureStart message)
    {
        ValidateCaptureId(message.CaptureId);

        if (message.ByteLength < 0)
        {
            throw new InvalidDataException("Capture byte length cannot be negative.");
        }

        if (_sessions.ContainsKey(message.CaptureId))
        {
            throw new InvalidDataException($"Capture '{message.CaptureId}' has already started.");
        }

        var partialPath = Path.Combine(_partialDirectory, $"{message.CaptureId}.part");
        _sessions.Add(message.CaptureId, new CaptureSession(message, partialPath));
    }

    public void Append(CaptureChunk message)
    {
        var session = GetSession(message.CaptureId);
        if (message.Sequence != session.NextSequence)
        {
            throw new InvalidDataException(
                $"Capture '{message.CaptureId}' expected sequence {session.NextSequence}, received {message.Sequence}.");
        }

        byte[] bytes;
        try
        {
            bytes = Convert.FromBase64String(message.Base64);
        }
        catch (FormatException error)
        {
            throw new InvalidDataException($"Capture '{message.CaptureId}' contains invalid base64.", error);
        }

        session.Append(bytes);
    }

    public void End(CaptureEnd message)
    {
        var session = GetSession(message.CaptureId);
        _sessions.Remove(message.CaptureId);

        var result = session.FinalizeCapture();
        if (result.ByteLength != session.Start.ByteLength)
        {
            throw new InvalidDataException(
                $"Capture '{message.CaptureId}' declared {session.Start.ByteLength} bytes but received {result.ByteLength}.");
        }

        var bodyFileName = $"{result.Sha256}.body";
        var bodyPath = Path.Combine(_bodiesDirectory, bodyFileName);

        if (File.Exists(bodyPath))
        {
            File.Delete(result.PartialPath);
        }
        else
        {
            File.Move(result.PartialPath, bodyPath);
        }

        var observation = new CaptureObservation(
            session.Start,
            result.Sha256,
            result.ByteLength,
            Path.Combine("bodies", bodyFileName).Replace('\\', '/'),
            DateTimeOffset.UtcNow.ToString("O"));

        var observationPath = Path.Combine(_observationsDirectory, $"{message.CaptureId}.json");
        File.WriteAllText(
            observationPath,
            JsonSerializer.Serialize(observation, ObservationJsonOptions));

        WriteClassificationWithoutAffectingEvidence(
            bodyPath,
            result.Sha256,
            result.ByteLength,
            session.Start.ContentType);
    }

    public void Dispose()
    {
        foreach (var session in _sessions.Values)
        {
            session.Dispose();
        }

        _sessions.Clear();
    }

    private void WriteClassificationWithoutAffectingEvidence(
        string bodyPath,
        string sha256,
        long byteLength,
        string? contentType)
    {
        var classificationPath = Path.Combine(_classificationsDirectory, $"{sha256}.json");
        if (File.Exists(classificationPath))
        {
            return;
        }

        PayloadClassification classification;
        string? errorType = null;

        try
        {
            classification = byteLength > MaxClassificationBytes
                ? PayloadClassification.Other("classification_size_limit")
                : ConversationPayloadClassifier.Classify(File.ReadAllBytes(bodyPath), contentType);
        }
        catch (Exception error)
        {
            classification = PayloadClassification.Other("classifier_error");
            errorType = error.GetType().Name;
        }

        var envelope = new ClassificationEnvelope(
            sha256,
            byteLength,
            contentType,
            DateTimeOffset.UtcNow.ToString("O"),
            classification,
            errorType);

        try
        {
            File.WriteAllText(
                classificationPath,
                JsonSerializer.Serialize(envelope, ObservationJsonOptions));
        }
        catch
        {
            // Classification is derivative metadata. Raw evidence and its observation
            // have already been persisted and must not be invalidated by this failure.
        }
    }

    private CaptureSession GetSession(string captureId)
    {
        ValidateCaptureId(captureId);
        return _sessions.TryGetValue(captureId, out var session)
            ? session
            : throw new InvalidDataException($"Capture '{captureId}' has not started.");
    }

    private static void ValidateCaptureId(string captureId)
    {
        if (!Guid.TryParse(captureId, out _))
        {
            throw new InvalidDataException("Capture ID must be a GUID.");
        }
    }

    private sealed class CaptureSession : IDisposable
    {
        private readonly FileStream _stream;
        private readonly IncrementalHash _hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        private bool _finalized;

        public CaptureSession(CaptureStart start, string partialPath)
        {
            Start = start;
            PartialPath = partialPath;
            _stream = new FileStream(partialPath, FileMode.Create, FileAccess.Write, FileShare.None);
        }

        public CaptureStart Start { get; }
        public string PartialPath { get; }
        public int NextSequence { get; private set; }
        public long BytesWritten { get; private set; }

        public void Append(ReadOnlySpan<byte> bytes)
        {
            if (_finalized)
            {
                throw new InvalidOperationException("Capture has already been finalized.");
            }

            _stream.Write(bytes);
            _hash.AppendData(bytes);
            BytesWritten += bytes.Length;
            NextSequence += 1;
        }

        public CaptureResult FinalizeCapture()
        {
            if (_finalized)
            {
                throw new InvalidOperationException("Capture has already been finalized.");
            }

            _finalized = true;
            _stream.Flush(flushToDisk: true);
            _stream.Dispose();

            var hash = Convert.ToHexString(_hash.GetHashAndReset()).ToLowerInvariant();
            _hash.Dispose();
            return new CaptureResult(hash, BytesWritten, PartialPath);
        }

        public void Dispose()
        {
            if (_finalized)
            {
                return;
            }

            _finalized = true;
            _stream.Dispose();
            _hash.Dispose();
        }
    }

    private sealed record CaptureResult(string Sha256, long ByteLength, string PartialPath);

    private sealed record CaptureObservation(
        CaptureStart Capture,
        string Sha256,
        long ByteLength,
        string BodyPath,
        string StoredAt);

    private sealed record ClassificationEnvelope(
        string Sha256,
        long ByteLength,
        string? ContentType,
        string ClassifiedAt,
        PayloadClassification Classification,
        string? ErrorType);
}
