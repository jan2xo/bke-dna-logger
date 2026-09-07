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
            using var liveIndex = SqliteLiveIndex.TryOpen(captureRoot);
            var projection = liveIndex is null
                ? null
                : new SqliteProjectionEngine(captureRoot, liveIndex.DatabasePath);
            projection?.TryProjectAll();

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
                projection);
            return 0;
        }
        catch (Exception error)
        {
            Console.Error.WriteLine($"BKE DNA native host failed: {error}");
            return 1;
        }
    }
}
