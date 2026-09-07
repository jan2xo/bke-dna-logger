namespace BKE.Dna.Logger.Host.Protocol;

internal sealed record DomWitness(
    string Type,
    string WitnessId,
    string PageUrl,
    string ObservedAt,
    IReadOnlyList<string> Snippets);
