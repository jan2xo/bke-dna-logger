using System.Buffers.Binary;
using System.Text.Json;
using BKE.Dna.Logger.Core.Protocol;
using BKE.Dna.Logger.Host.Aggregation;
using BKE.Dna.Logger.Host.Capture;
using BKE.Dna.Logger.Host.Normalization;
using BKE.Dna.Logger.Host.Reconciliation;
using BKE.Dna.Logger.Host.Storage;
using BKE.Dna.Logger.Host.Witness;

namespace BKE.Dna.Logger.Host.NativeMessaging;

internal static class NativeMessageLoop
{
    private const int PrefixBytes = sizeof(uint);

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNameCaseInsensitive = true
    };

    public static void Run(
        Stream input,
        CaptureStore captureStore,
        WitnessStore witnessStore,
        ReconciliationEngine reconciliation,
        GraphNormalizationEngine normalization,
        ConversationAggregationEngine aggregation,
        SqliteProjectionEngine? projection,
        ConversationSqliteProjectionEngine? conversationProjection)
    {
        Span<byte> prefix = stackalloc byte[PrefixBytes];

        while (TryReadExactly(input, prefix))
        {
            var payloadLength = BinaryPrimitives.ReadUInt32LittleEndian(prefix);
            if (payloadLength is 0 or > DnaWireProtocol.MaxMessageBytes)
            {
                throw new InvalidDataException($"Native message length {payloadLength} is outside the allowed range.");
            }

            var payload = new byte[(int)payloadLength];
            ReadExactly(input, payload);
            Dispatch(
                payload,
                captureStore,
                witnessStore,
                reconciliation,
                normalization,
                aggregation,
                projection,
                conversationProjection);
        }
    }

    private static void Dispatch(
        ReadOnlyMemory<byte> payload,
        CaptureStore captureStore,
        WitnessStore witnessStore,
        ReconciliationEngine reconciliation,
        GraphNormalizationEngine normalization,
        ConversationAggregationEngine aggregation,
        SqliteProjectionEngine? projection,
        ConversationSqliteProjectionEngine? conversationProjection)
    {
        var type = DnaWireProtocol.ReadAndValidateType(payload.Span);
        switch (type)
        {
            case DnaWireProtocol.CaptureStartType:
                captureStore.Start(Deserialize<CaptureStart>(payload.Span));
                break;
            case DnaWireProtocol.CaptureChunkType:
                captureStore.Append(Deserialize<CaptureChunk>(payload.Span));
                break;
            case DnaWireProtocol.CaptureEndType:
                captureStore.End(Deserialize<CaptureEnd>(payload.Span));
                TryNormalize(normalization);
                aggregation.TryAggregateAll();
                TryReconcile(reconciliation);
                projection?.TryProjectAll();
                conversationProjection?.TryProjectAll();
                break;
            case DnaWireProtocol.DomWitnessType:
                witnessStore.Record(Deserialize<DomWitness>(payload.Span));
                TryReconcile(reconciliation);
                projection?.TryProjectAll();
                break;
        }
    }

    private static void TryNormalize(GraphNormalizationEngine normalization)
    {
        try
        {
            normalization.NormalizeAllCandidates();
        }
        catch
        {
            // Normalization is derivative metadata and cannot invalidate primary evidence.
        }
    }

    private static void TryReconcile(ReconciliationEngine reconciliation)
    {
        try
        {
            reconciliation.ReconcileAll();
        }
        catch
        {
            // Reconciliation is derivative metadata and cannot invalidate primary evidence.
        }
    }

    private static T Deserialize<T>(ReadOnlySpan<byte> payload)
    {
        return JsonSerializer.Deserialize<T>(payload, JsonOptions)
            ?? throw new InvalidDataException($"Unable to deserialize {typeof(T).Name}.");
    }

    private static bool TryReadExactly(Stream input, Span<byte> destination)
    {
        var offset = 0;
        while (offset < destination.Length)
        {
            var read = input.Read(destination[offset..]);
            if (read == 0)
            {
                if (offset == 0)
                {
                    return false;
                }

                throw new EndOfStreamException("Native message length prefix was truncated.");
            }

            offset += read;
        }

        return true;
    }

    private static void ReadExactly(Stream input, Span<byte> destination)
    {
        var offset = 0;
        while (offset < destination.Length)
        {
            var read = input.Read(destination[offset..]);
            if (read == 0)
            {
                throw new EndOfStreamException("Native message payload was truncated.");
            }

            offset += read;
        }
    }
}
