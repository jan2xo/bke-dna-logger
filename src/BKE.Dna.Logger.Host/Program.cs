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

        Console.Error.WriteLine("BKE DNA Logger native host scaffold. Capture ingestion is provided by the next stacked change.");
        return 0;
    }
}
