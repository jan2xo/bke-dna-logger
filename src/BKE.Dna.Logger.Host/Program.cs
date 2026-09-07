using System.Text.Json;
using BKE.Dna.Logger.Host.Aggregation;
using BKE.Dna.Logger.Host.Archive;
using BKE.Dna.Logger.Host.Capture;
using BKE.Dna.Logger.Host.NativeMessaging;
using BKE.Dna.Logger.Host.Normalization;
using BKE.Dna.Logger.Host.Reconciliation;
using BKE.Dna.Logger.Host.Storage;
using BKE.Dna.Logger.Host.Witness;

namespace BKE.Dna.Logger.Host;

internal static class Program
{
    private const string Version = "0.0.1-poc0";

    private static readonly JsonSerializerOptions OutputJson = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase
    };

    public static int Main(string[] args)
    {
        if (args.Contains("--version", StringComparer.Ordinal))
        {
            Console.Out.WriteLine(Version);
            return 0;
        }

        var captureRoot = Environment.GetEnvironmentVariable("BKE_DNA_CAPTURE_ROOT");
        if (string.IsNullOrWhiteSpace(captureRoot))
        {
            captureRoot = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "BKE",
                "DNA Logger",
                "captures");
        }

        try
        {
            if (args.Length == 2 && string.Equals(args[0], "--verify-dna", StringComparison.Ordinal))
            {
                var verification = DnaArchiveService.VerifyArchive(args[1]);
                Console.Out.WriteLine(JsonSerializer.Serialize(verification, OutputJson));
                return 0;
            }

            using var liveIndex = SqliteLiveIndex.TryOpen(captureRoot);
            var aggregation = new ConversationAggregationEngine(captureRoot);
            aggregation.TryAggregateAll();

            SqliteProjectionEngine? projection = null;
            ConversationSqliteProjectionEngine? conversationProjection = null;
            if (liveIndex is not null)
            {
                projection = new SqliteProjectionEngine(captureRoot, liveIndex.DatabasePath);
                projection.TryProjectAll();

                if (ConversationSqliteSchema.TryUpgrade(liveIndex.DatabasePath))
                {
                    conversationProjection = new ConversationSqliteProjectionEngine(captureRoot, liveIndex.DatabasePath);
                    conversationProjection.TryProjectAll();
                }
            }

            if (args.Length == 2 && string.Equals(args[0], "--archive", StringComparison.Ordinal))
            {
                if (liveIndex is null)
                {
                    throw new InvalidOperationException(
                        "A .dna archive can be built only when the SQLite durability index is available.");
                }

                var archive = new DnaArchiveService(captureRoot, liveIndex.DatabasePath)
                    .BuildVerifyAndRecord(args[1]);
                Console.Out.WriteLine(JsonSerializer.Serialize(archive, OutputJson));
                return 0;
            }

            if (args.Length != 0)
            {
                throw new ArgumentException("Unknown BKE DNA Logger command.");
            }

            using var captureStore = new CaptureStore(captureRoot);
            var witnessStore = new WitnessStore(captureRoot);
            var reconciliation = new ReconciliationEngine(captureRoot);
            var normalization = new GraphNormalizationEngine(captureRoot);
            NativeMessageLoop.Run(
                Console.OpenStandardInput(),
                captureStore,
                witnessStore,
                reconciliation,
                normalization,
                aggregation,
                projection,
                conversationProjection);
            return 0;
        }
        catch (Exception error)
        {
            Console.Error.WriteLine($"BKE DNA native host failed: {error}");
            return 1;
        }
    }
}
