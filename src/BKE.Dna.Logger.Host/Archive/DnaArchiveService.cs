using System.IO.Compression;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Microsoft.Data.Sqlite;

namespace BKE.Dna.Logger.Host.Archive;

internal sealed class DnaArchiveService
{
    private static readonly DateTimeOffset DeterministicZipTime =
        new(1980, 1, 1, 0, 0, 0, TimeSpan.Zero);

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        PropertyNameCaseInsensitive = true,
        WriteIndented = true
    };

    private readonly string _captureRoot;
    private readonly string _databasePath;
    private readonly string _archivesDirectory;

    public DnaArchiveService(string captureRoot, string databasePath)
    {
        _captureRoot = Path.GetFullPath(captureRoot);
        _databasePath = Path.GetFullPath(databasePath);
        _archivesDirectory = Path.Combine(_captureRoot, "archives");
        Directory.CreateDirectory(_archivesDirectory);
    }

    public DnaArchiveResult BuildVerifyAndRecord(string sourceSha256)
    {
        ValidateSha256(sourceSha256);
        sourceSha256 = sourceSha256.ToLowerInvariant();

        var build = BuildArchive(sourceSha256);
        var verified = VerifyArchive(build.ArchivePath);
        if (!string.Equals(verified.SourceSha256, sourceSha256, StringComparison.Ordinal))
        {
            throw new InvalidDataException("Verified archive source SHA-256 does not match requested source.");
        }

        if (!string.Equals(verified.ArchiveId, build.ArchiveId, StringComparison.Ordinal))
        {
            throw new InvalidDataException("Verified archive ID does not match built archive ID.");
        }

        var archivedAt = DateTimeOffset.UtcNow.ToString("O");
        RecordVerifiedDurability(sourceSha256, verified.ArchiveId, verified.ArchiveSha256, archivedAt);

        return new DnaArchiveResult(
            verified.ArchiveId,
            verified.SourceSha256,
            verified.ArchiveSha256,
            build.ArchivePath,
            archivedAt,
            verified.EntryCount);
    }

    public static DnaArchiveVerification VerifyArchive(string archivePath)
    {
        archivePath = Path.GetFullPath(archivePath);
        if (!File.Exists(archivePath))
        {
            throw new FileNotFoundException("DNA archive does not exist.", archivePath);
        }

        using var file = new FileStream(archivePath, FileMode.Open, FileAccess.Read, FileShare.Read);
        using var archive = new ZipArchive(file, ZipArchiveMode.Read, leaveOpen: true);

        var entries = new Dictionary<string, ZipArchiveEntry>(StringComparer.Ordinal);
        foreach (var entry in archive.Entries)
        {
            ValidateArchivePath(entry.FullName);
            if (!entries.TryAdd(entry.FullName, entry))
            {
                throw new InvalidDataException($"DNA archive contains duplicate entry '{entry.FullName}'.");
            }
        }

        if (!entries.TryGetValue("manifest.json", out var manifestEntry) ||
            !entries.TryGetValue("SHA256SUMS", out var checksumsEntry))
        {
            throw new InvalidDataException("DNA archive must contain manifest.json and SHA256SUMS.");
        }

        var manifestBytes = ReadEntryBytes(manifestEntry, 4 * 1024 * 1024);
        var manifest = JsonSerializer.Deserialize<ArchiveManifest>(manifestBytes, JsonOptions)
            ?? throw new InvalidDataException("DNA manifest could not be parsed.");

        if (manifest.Format != "bke-dna" || manifest.FormatVersion != 1)
        {
            throw new InvalidDataException("Unsupported DNA archive format.");
        }

        ValidateSha256(manifest.SourceSha256);
        if (string.IsNullOrWhiteSpace(manifest.ArchiveId))
        {
            throw new InvalidDataException("DNA manifest has no archive ID.");
        }

        var checksumsText = Encoding.UTF8.GetString(ReadEntryBytes(checksumsEntry, 16 * 1024 * 1024));
        var expectedChecksums = ParseChecksums(checksumsText);
        var payloadEntryNames = entries.Keys
            .Where(static name => !string.Equals(name, "SHA256SUMS", StringComparison.Ordinal))
            .OrderBy(static name => name, StringComparer.Ordinal)
            .ToArray();

        if (!payloadEntryNames.SequenceEqual(expectedChecksums.Keys.OrderBy(static name => name, StringComparer.Ordinal)))
        {
            throw new InvalidDataException("SHA256SUMS does not describe exactly every payload entry.");
        }

        foreach (var entryName in payloadEntryNames)
        {
            using var stream = entries[entryName].Open();
            var actual = Sha256Stream(stream);
            if (!string.Equals(actual, expectedChecksums[entryName], StringComparison.Ordinal))
            {
                throw new InvalidDataException($"Checksum mismatch for DNA entry '{entryName}'.");
            }
        }

        var manifestPaths = manifest.Evidence
            .Select(static item => item.Path)
            .Append("manifest.json")
            .Distinct(StringComparer.Ordinal)
            .OrderBy(static path => path, StringComparer.Ordinal)
            .ToArray();

        if (!manifestPaths.SequenceEqual(payloadEntryNames))
        {
            throw new InvalidDataException("DNA manifest references do not match archive payload entries.");
        }

        foreach (var evidence in manifest.Evidence)
        {
            ValidateArchivePath(evidence.Path);
            ValidateSha256(evidence.Sha256);
            if (!entries.TryGetValue(evidence.Path, out var evidenceEntry))
            {
                throw new InvalidDataException($"DNA manifest references missing entry '{evidence.Path}'.");
            }

            if (evidenceEntry.Length != evidence.ByteLength)
            {
                throw new InvalidDataException($"DNA manifest byte length mismatch for '{evidence.Path}'.");
            }

            if (!string.Equals(expectedChecksums[evidence.Path], evidence.Sha256, StringComparison.Ordinal))
            {
                throw new InvalidDataException($"DNA manifest checksum mismatch for '{evidence.Path}'.");
            }
        }

        var raw = manifest.Evidence.SingleOrDefault(static item => item.Kind == "raw_body")
            ?? throw new InvalidDataException("DNA manifest must reference exactly one raw body.");
        if (!string.Equals(raw.Sha256, manifest.SourceSha256, StringComparison.Ordinal))
        {
            throw new InvalidDataException("DNA raw body hash does not match manifest source SHA-256.");
        }

        file.Position = 0;
        var archiveSha256 = Sha256Stream(file);
        return new DnaArchiveVerification(
            manifest.ArchiveId,
            manifest.SourceSha256,
            archiveSha256,
            archivePath,
            entries.Count);
    }

    private ArchiveBuildResult BuildArchive(string sourceSha256)
    {
        var normalizedPath = Path.Combine(_captureRoot, "normalized", $"{sourceSha256}.json");
        var rawBodyPath = Path.Combine(_captureRoot, "bodies", $"{sourceSha256}.body");
        if (!File.Exists(normalizedPath))
        {
            throw new InvalidOperationException("Cannot create .dna without a normalized conversation graph.");
        }

        if (!File.Exists(rawBodyPath))
        {
            throw new InvalidOperationException("Cannot create .dna without its content-addressed raw body.");
        }

        using var normalizedDocument = JsonDocument.Parse(File.ReadAllBytes(normalizedPath));
        var normalized = normalizedDocument.RootElement;
        if (!string.Equals(RequiredString(normalized, "sourceSha256"), sourceSha256, StringComparison.Ordinal))
        {
            throw new InvalidDataException("Normalized graph source hash does not match requested archive source.");
        }

        var evidence = new List<EvidenceSource>
        {
            EvidenceSource.FromFile("raw/body.body", "raw_body", rawBodyPath),
            EvidenceSource.FromFile("normalized/conversation.json", "normalized_graph", normalizedPath)
        };

        var classificationPath = Path.Combine(_captureRoot, "classifications", $"{sourceSha256}.json");
        if (File.Exists(classificationPath))
        {
            evidence.Add(EvidenceSource.FromFile("classification.json", "classification", classificationPath));
        }

        var pageUrls = new HashSet<string>(StringComparer.Ordinal);
        var observationDirectory = Path.Combine(_captureRoot, "observations");
        if (Directory.Exists(observationDirectory))
        {
            foreach (var path in Directory.EnumerateFiles(observationDirectory, "*.json").OrderBy(static path => path, StringComparer.Ordinal))
            {
                try
                {
                    using var document = JsonDocument.Parse(File.ReadAllBytes(path));
                    var root = document.RootElement;
                    if (!string.Equals(RequiredString(root, "sha256"), sourceSha256, StringComparison.Ordinal))
                    {
                        continue;
                    }

                    var capture = root.GetProperty("capture");
                    pageUrls.Add(RequiredString(capture, "pageUrl"));
                    evidence.Add(EvidenceSource.FromFile(
                        $"observations/{Path.GetFileName(path)}",
                        "capture_observation",
                        path));
                }
                catch
                {
                    // Malformed derivative observations are not admitted to a durable archive.
                }
            }
        }

        var witnessIds = new HashSet<string>(StringComparer.Ordinal);
        var witnessDirectory = Path.Combine(_captureRoot, "witnesses");
        if (Directory.Exists(witnessDirectory) && pageUrls.Count > 0)
        {
            foreach (var path in Directory.EnumerateFiles(witnessDirectory, "*.json").OrderBy(static path => path, StringComparer.Ordinal))
            {
                try
                {
                    using var document = JsonDocument.Parse(File.ReadAllBytes(path));
                    var root = document.RootElement;
                    if (!pageUrls.Contains(RequiredString(root, "pageUrl")))
                    {
                        continue;
                    }

                    witnessIds.Add(RequiredString(root, "witnessId"));
                    evidence.Add(EvidenceSource.FromFile(
                        $"witnesses/{Path.GetFileName(path)}",
                        "dom_witness",
                        path));
                }
                catch
                {
                    // Unsupported witness evidence is excluded rather than weakening archive verification.
                }
            }
        }

        var reconciliationDirectory = Path.Combine(_captureRoot, "reconciliations");
        if (Directory.Exists(reconciliationDirectory) && witnessIds.Count > 0)
        {
            foreach (var path in Directory.EnumerateFiles(reconciliationDirectory, "*.json").OrderBy(static path => path, StringComparer.Ordinal))
            {
                try
                {
                    using var document = JsonDocument.Parse(File.ReadAllBytes(path));
                    if (!witnessIds.Contains(RequiredString(document.RootElement, "witnessId")))
                    {
                        continue;
                    }

                    evidence.Add(EvidenceSource.FromFile(
                        $"reconciliations/{Path.GetFileName(path)}",
                        "reconciliation",
                        path));
                }
                catch
                {
                    // Unsupported reconciliation evidence is excluded.
                }
            }
        }

        if (!evidence.Any(static item => item.Kind == "capture_observation"))
        {
            throw new InvalidOperationException("Cannot create .dna without at least one capture observation.");
        }

        var orderedEvidence = evidence
            .OrderBy(static item => item.ArchivePath, StringComparer.Ordinal)
            .ToArray();

        var evidenceIdentity = string.Join(
            "\n",
            orderedEvidence.Select(static item => $"{item.ArchivePath}\t{item.Sha256}\t{item.ByteLength}"));
        var archiveId = $"dna-v1-{Sha256Bytes(Encoding.UTF8.GetBytes(evidenceIdentity))}";

        var manifest = new ArchiveManifest(
            Format: "bke-dna",
            FormatVersion: 1,
            ArchiveId: archiveId,
            SourceSha256: sourceSha256,
            ConversationNativeId: OptionalString(normalized, "conversationNativeId"),
            CurrentNodeNativeId: OptionalString(normalized, "currentNodeNativeId"),
            CoverageStatus: RequiredString(normalized, "coverageStatus"),
            CoverageBasis: RequiredString(normalized, "coverageBasis"),
            Evidence: orderedEvidence
                .Select(static item => new ArchiveEvidence(item.ArchivePath, item.Kind, item.Sha256, item.ByteLength))
                .ToArray());

        var manifestBytes = JsonSerializer.SerializeToUtf8Bytes(manifest, JsonOptions);
        var payloadChecksums = new SortedDictionary<string, string>(StringComparer.Ordinal);
        foreach (var item in orderedEvidence)
        {
            payloadChecksums.Add(item.ArchivePath, item.Sha256);
        }
        payloadChecksums.Add("manifest.json", Sha256Bytes(manifestBytes));

        var checksumBytes = Encoding.UTF8.GetBytes(string.Join(
            "\n",
            payloadChecksums.Select(static pair => $"{pair.Value}  {pair.Key}")) + "\n");

        var archivePath = Path.Combine(_archivesDirectory, $"{archiveId}.dna");
        var tempPath = Path.Combine(_archivesDirectory, $".{archiveId}.{Guid.NewGuid():N}.tmp");
        try
        {
            using (var stream = new FileStream(tempPath, FileMode.CreateNew, FileAccess.Write, FileShare.None))
            using (var zip = new ZipArchive(stream, ZipArchiveMode.Create, leaveOpen: false))
            {
                foreach (var item in orderedEvidence)
                {
                    WriteFileEntry(zip, item.ArchivePath, item.FilePath);
                }
                WriteBytesEntry(zip, "manifest.json", manifestBytes);
                WriteBytesEntry(zip, "SHA256SUMS", checksumBytes);
            }

            File.Move(tempPath, archivePath, overwrite: true);
        }
        finally
        {
            if (File.Exists(tempPath))
            {
                File.Delete(tempPath);
            }
        }

        return new ArchiveBuildResult(archiveId, archivePath);
    }

    private void RecordVerifiedDurability(
        string sourceSha256,
        string archiveId,
        string archiveSha256,
        string archivedAt)
    {
        using var connection = new SqliteConnection(new SqliteConnectionStringBuilder
        {
            DataSource = _databasePath,
            Mode = SqliteOpenMode.ReadWrite,
            Cache = SqliteCacheMode.Shared
        }.ToString());
        connection.Open();

        using var command = connection.CreateCommand();
        command.CommandText = """
            UPDATE durability_state
            SET dna_archive_id = $archiveId,
                dna_archive_sha256 = $archiveSha256,
                archived_at = $archivedAt,
                clearable = 1
            WHERE raw_sha256 = $sourceSha256;
            """;
        command.Parameters.AddWithValue("$archiveId", archiveId);
        command.Parameters.AddWithValue("$archiveSha256", archiveSha256);
        command.Parameters.AddWithValue("$archivedAt", archivedAt);
        command.Parameters.AddWithValue("$sourceSha256", sourceSha256);
        if (command.ExecuteNonQuery() != 1)
        {
            throw new InvalidOperationException(
                "Verified .dna could not be recorded because the raw capture is not present in the live index.");
        }
    }

    private static void WriteFileEntry(ZipArchive archive, string archivePath, string filePath)
    {
        var entry = CreateDeterministicEntry(archive, archivePath);
        using var output = entry.Open();
        using var input = new FileStream(filePath, FileMode.Open, FileAccess.Read, FileShare.Read);
        input.CopyTo(output);
    }

    private static void WriteBytesEntry(ZipArchive archive, string archivePath, ReadOnlySpan<byte> bytes)
    {
        var entry = CreateDeterministicEntry(archive, archivePath);
        using var output = entry.Open();
        output.Write(bytes);
    }

    private static ZipArchiveEntry CreateDeterministicEntry(ZipArchive archive, string path)
    {
        ValidateArchivePath(path);
        var entry = archive.CreateEntry(path, CompressionLevel.NoCompression);
        entry.LastWriteTime = DeterministicZipTime;
        entry.ExternalAttributes = 0;
        return entry;
    }

    private static byte[] ReadEntryBytes(ZipArchiveEntry entry, long maximumBytes)
    {
        if (entry.Length > maximumBytes)
        {
            throw new InvalidDataException($"DNA entry '{entry.FullName}' exceeds verification size limit.");
        }

        using var stream = entry.Open();
        using var memory = new MemoryStream((int)entry.Length);
        stream.CopyTo(memory);
        return memory.ToArray();
    }

    private static SortedDictionary<string, string> ParseChecksums(string text)
    {
        var result = new SortedDictionary<string, string>(StringComparer.Ordinal);
        foreach (var rawLine in text.Split('\n', StringSplitOptions.RemoveEmptyEntries))
        {
            var line = rawLine.TrimEnd('\r');
            var separator = line.IndexOf("  ", StringComparison.Ordinal);
            if (separator != 64)
            {
                throw new InvalidDataException("Malformed SHA256SUMS line.");
            }

            var sha256 = line[..separator];
            var path = line[(separator + 2)..];
            ValidateSha256(sha256);
            ValidateArchivePath(path);
            if (!result.TryAdd(path, sha256.ToLowerInvariant()))
            {
                throw new InvalidDataException($"Duplicate SHA256SUMS path '{path}'.");
            }
        }

        return result;
    }

    private static void ValidateArchivePath(string path)
    {
        if (string.IsNullOrWhiteSpace(path) ||
            path.StartsWith("/", StringComparison.Ordinal) ||
            path.Contains('\\') ||
            path.Split('/').Any(static segment => segment is "" or "." or ".."))
        {
            throw new InvalidDataException($"Unsafe DNA archive path '{path}'.");
        }
    }

    private static void ValidateSha256(string value)
    {
        if (value.Length != 64 || value.Any(static character => !Uri.IsHexDigit(character)))
        {
            throw new InvalidDataException("Expected a 64-character SHA-256 value.");
        }
    }

    private static string Sha256File(string path)
    {
        using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read);
        return Sha256Stream(stream);
    }

    private static string Sha256Stream(Stream stream)
        => Convert.ToHexString(SHA256.HashData(stream)).ToLowerInvariant();

    private static string Sha256Bytes(ReadOnlySpan<byte> bytes)
        => Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();

    private static string RequiredString(JsonElement element, string propertyName)
    {
        if (!element.TryGetProperty(propertyName, out var value) || value.ValueKind != JsonValueKind.String)
        {
            throw new InvalidDataException($"Required JSON string '{propertyName}' is missing.");
        }

        return value.GetString()!;
    }

    private static string? OptionalString(JsonElement element, string propertyName)
    {
        if (!element.TryGetProperty(propertyName, out var value) || value.ValueKind == JsonValueKind.Null)
        {
            return null;
        }

        return value.ValueKind == JsonValueKind.String ? value.GetString() : null;
    }

    private sealed record EvidenceSource(
        string ArchivePath,
        string Kind,
        string FilePath,
        string Sha256,
        long ByteLength)
    {
        public static EvidenceSource FromFile(string archivePath, string kind, string filePath)
        {
            var info = new FileInfo(filePath);
            return new EvidenceSource(archivePath, kind, filePath, Sha256File(filePath), info.Length);
        }
    }

    private sealed record ArchiveBuildResult(string ArchiveId, string ArchivePath);

    private sealed record ArchiveManifest(
        string Format,
        int FormatVersion,
        string ArchiveId,
        string SourceSha256,
        string? ConversationNativeId,
        string? CurrentNodeNativeId,
        string CoverageStatus,
        string CoverageBasis,
        IReadOnlyList<ArchiveEvidence> Evidence);

    private sealed record ArchiveEvidence(
        string Path,
        string Kind,
        string Sha256,
        long ByteLength);
}

internal sealed record DnaArchiveResult(
    string ArchiveId,
    string SourceSha256,
    string ArchiveSha256,
    string ArchivePath,
    string ArchivedAt,
    int EntryCount);

internal sealed record DnaArchiveVerification(
    string ArchiveId,
    string SourceSha256,
    string ArchiveSha256,
    string ArchivePath,
    int EntryCount);
