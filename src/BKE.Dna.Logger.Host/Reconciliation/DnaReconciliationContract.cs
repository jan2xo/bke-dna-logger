namespace BKE.Dna.Logger.Host.Reconciliation;

internal static class DnaReconciliationContract
{
    public const string ContractId = "bke-dna-reconciliation-v1";
    public const long StorageWarningBytes = 1_073_741_824L;
    public const string ArchiveScope = "conversation";
    public const bool AutomaticDnaExport = false;
    public const bool AutomaticMarkdownExport = false;
    public const bool MergeSqliteAcrossDevices = false;

    public static bool ShouldNotifyStorage(long workingBytes)
    {
        if (workingBytes < 0)
        {
            throw new ArgumentOutOfRangeException(nameof(workingBytes));
        }

        return workingBytes >= StorageWarningBytes;
    }
}
