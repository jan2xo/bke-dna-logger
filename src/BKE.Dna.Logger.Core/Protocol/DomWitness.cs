namespace BKE.Dna.Logger.Core.Protocol;

public sealed record DomWitness(
    string Type,
    string WitnessId,
    string PageUrl,
    string ObservedAt,
    IReadOnlyList<string> Snippets);
