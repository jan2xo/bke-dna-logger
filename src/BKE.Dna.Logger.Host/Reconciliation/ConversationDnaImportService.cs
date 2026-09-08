using System.IO.Compression;
using System.Security.Cryptography;
using BKE.Dna.Logger.Host.Archive;

namespace BKE.Dna.Logger.Host.Reconciliation;

internal sealed class ConversationDnaImportService
{
    private readonly string _captureRoot;

    public ConversationDnaImportService(string captureRoot)
    {
        _captureRoot = Path.GetFullPath(captureRoot);
    }

    public ConversationDnaImportResult ImportVerified(IReadOnlyList<string> archivePaths)
    {
        if (archivePaths.Count == 0)
        {
            throw new ArgumentException("At least one conversation .dna archive is required.", nameof(archivePaths));
        }

        var verified = archivePaths
            .Select(ConversationDnaArchiveService.VerifyArchive)
            .ToArray();
        var conversationNativeId = verified[0].ConversationNativeId;
        if (verified.Any(item => !string.Equals(item.ConversationNativeId, conversationNativeId, StringComparison.Ordinal)))
        {
            throw new InvalidDataException("Cross-device reconciliation cannot mix different conversationNativeId values.");
        }

        var stagingRoot = Path.Combine(_captureRoot, "import-staging", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(stagingRoot);
        try
        {
            var staged = new Dictionary<string, StagedFile>(StringComparer.Ordinal);
            foreach (var verification in verified)
            {
                StageArchive(verification.ArchivePath, verification.SourceSha256s, stagingRoot, staged);
            }

            foreach (var item in staged.Values.OrderBy(static item => item.RelativeTarget, StringComparer.Ordinal))
            {
                CommitStaged(item);
            }

            return new ConversationDnaImportResult(
                DnaReconciliationContract.ContractId,
                conversationNativeId,
                verified.Length,
                verified.SelectMany(static item => item.SourceSha256s)
                    .Distinct(StringComparer.Ordinal)
                    .OrderBy(static item => item, StringComparer.Ordinal)
                    .ToArray(),
                staged.Count);
        }
        finally
        {
            if (Directory.Exists(stagingRoot))
            {
                Directory.Delete(stagingRoot, recursive: true);
            }
        }
    }

    private void StageArchive(
        string archivePath,
        IReadOnlyList<string> sourceSha256s,
        string stagingRoot,
        IDictionary<string, StagedFile> staged)
    {
        var sourceSet = sourceSha256s.ToHashSet(StringComparer.Ordinal);
        using var file = new FileStream(archivePath, FileMode.Open, FileAccess.Read, FileShare.Read);
        using var archive = new ZipArchive(file, ZipArchiveMode.Read, leaveOpen: false);

        foreach (var entry in archive.Entries)
        {
            var target = MapTarget(entry.FullName, sourceSet);
            if (target is null)
            {
                continue;
            }

            var stagePath = Path.Combine(stagingRoot, Guid.NewGuid().ToString("N"));
            var sha256 = CopyEntry(entry, stagePath);
            var relativeTarget = target.Value.RelativeTarget;
            if (target.Value.ExpectedSha256 is not null &&
                !string.Equals(sha256, target.Value.ExpectedSha256, StringComparison.Ordinal))
            {
                throw new InvalidDataException($"Imported raw source does not match SHA-256 identity '{target.Value.ExpectedSha256}'.");
            }

            if (staged.TryGetValue(relativeTarget, out var existing))
            {
                if (!string.Equals(existing.Sha256, sha256, StringComparison.Ordinal))
                {
                    throw new InvalidDataException($"Conflicting cross-device evidence targets '{relativeTarget}'.");
                }
                File.Delete(stagePath);
                continue;
            }

            staged.Add(relativeTarget, new StagedFile(relativeTarget, stagePath, sha256));
        }
    }

    private static ImportTarget? MapTarget(string archivePath, IReadOnlySet<string> sourceSet)
    {
        foreach (var sourceSha in sourceSet)
        {
            var prefix = $"sources/{sourceSha}/";
            if (!archivePath.StartsWith(prefix, StringComparison.Ordinal))
            {
                continue;
            }

            var remainder = archivePath[prefix.Length..];
            if (remainder == "raw.body")
            {
                return new ImportTarget($"bodies/{sourceSha}.body", sourceSha);
            }
            if (remainder == "normalized.json")
            {
                return new ImportTarget($"normalized/{sourceSha}.json", null);
            }
            if (remainder == "classification.json")
            {
                return new ImportTarget($"classifications/{sourceSha}.json", null);
            }
            if (remainder.StartsWith("observations/", StringComparison.Ordinal))
            {
                var name = Path.GetFileName(remainder);
                if (string.IsNullOrWhiteSpace(name) || name != remainder["observations/".Length..])
                {
                    throw new InvalidDataException("Conversation DNA observation path is not a single safe file name.");
                }
                return new ImportTarget($"observations/{name}", null);
            }
        }

        if (archivePath.StartsWith("witnesses/", StringComparison.Ordinal))
        {
            var name = Path.GetFileName(archivePath);
            return new ImportTarget($"witnesses/{name}", null);
        }
        if (archivePath.StartsWith("reconciliations/", StringComparison.Ordinal))
        {
            var name = Path.GetFileName(archivePath);
            return new ImportTarget($"reconciliations/{name}", null);
        }

        return null;
    }

    private void CommitStaged(StagedFile item)
    {
        var targetPath = Path.GetFullPath(Path.Combine(_captureRoot, item.RelativeTarget));
        var rootWithSeparator = _captureRoot.TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
        if (!targetPath.StartsWith(rootWithSeparator, StringComparison.Ordinal))
        {
            throw new InvalidDataException("Imported evidence target escaped the capture root.");
        }

        Directory.CreateDirectory(Path.GetDirectoryName(targetPath)!);
        if (File.Exists(targetPath))
        {
            var existingSha = Sha256File(targetPath);
            if (!string.Equals(existingSha, item.Sha256, StringComparison.Ordinal))
            {
                throw new InvalidDataException($"Existing local evidence conflicts with imported '{item.RelativeTarget}'.");
            }
            File.Delete(item.StagePath);
            return;
        }

        File.Move(item.StagePath, targetPath);
    }

    private static string CopyEntry(ZipArchiveEntry entry, string destination)
    {
        using var input = entry.Open();
        using var output = new FileStream(destination, FileMode.CreateNew, FileAccess.Write, FileShare.None);
        using var digest = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        var buffer = new byte[128 * 1024];
        while (true)
        {
            var read = input.Read(buffer, 0, buffer.Length);
            if (read == 0)
            {
                break;
            }
            output.Write(buffer, 0, read);
            digest.AppendData(buffer, 0, read);
        }
        output.Flush(flushToDisk: true);
        return Convert.ToHexString(digest.GetHashAndReset()).ToLowerInvariant();
    }

    private static string Sha256File(string path)
    {
        using var stream = File.OpenRead(path);
        return Convert.ToHexString(SHA256.HashData(stream)).ToLowerInvariant();
    }

    private readonly record struct ImportTarget(string RelativeTarget, string? ExpectedSha256);
    private sealed record StagedFile(string RelativeTarget, string StagePath, string Sha256);
}

internal sealed record ConversationDnaImportResult(
    string ContractId,
    string ConversationNativeId,
    int ArchiveCount,
    IReadOnlyList<string> SourceSha256s,
    int ImportedFileCount);
