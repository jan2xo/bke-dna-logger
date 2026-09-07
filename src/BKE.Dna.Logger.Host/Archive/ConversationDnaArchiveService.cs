using System.IO.Compression;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Microsoft.Data.Sqlite;

namespace BKE.Dna.Logger.Host.Archive;

internal sealed class ConversationDnaArchiveService
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

    public ConversationDnaArchiveService(string captureRoot, string databasePath)
    {
        _captureRoot = Path.GetFullPath(captureRoot);
        _databasePath = Path.GetFullPath(databasePath);
        _archivesDirectory = Path.Combine(_captureRoot, "archives");
        Directory.CreateDirectory(_archivesDirectory);
    }

    public ConversationDnaArchiveResult BuildVerifyAndRecord(string conversationIdentity)
    {
        var build = BuildArchive(conversationIdentity);
        var verified = VerifyArchive(build.ArchivePath);
        if (!string.Equals(verified.ArchiveId, build.ArchiveId, StringComparison.Ordinal) ||
            !string.Equals(verified.ConversationKey, build.ConversationKey, StringComparison.Ordinal))
        {
            throw new InvalidDataException("Verified conversation archive identity does not match the built archive.");
        }

        var archivedAt = DateTimeOffset.UtcNow.ToString("O");
        RecordVerifiedDurability(
            verified.SourceSha256s,
            verified.ArchiveId,
            verified.ArchiveSha256,
            archivedAt);

        return new ConversationDnaArchiveResult(
            verified.ArchiveId,
            verified.ConversationKey,
            verified.ConversationNativeId,
            verified.SourceSha256s,
            verified.ArchiveSha256,
            build.ArchivePath,
            archivedAt,
            verified.EntryCount);
    }

    public static ConversationDnaArchiveVerification VerifyArchive(string archivePath)
    {
        archivePath = Path.GetFullPath(archivePath);
        if (!File.Exists(archivePath))
        {
            throw new FileNotFoundException("Conversation DNA archive does not exist.", archivePath);
        }

        using var file = new FileStream(archivePath, FileMode.Open, FileAccess.Read, FileShare.Read);
        using var archive = new ZipArchive(file, ZipArchiveMode.Read, leaveOpen: true);
        var entries = new Dictionary<string, ZipArchiveEntry>(StringComparer.Ordinal);
        foreach (var entry in archive.Entries)
        {
            ValidateArchivePath(entry.FullName);
            if (!entries.TryAdd(entry.FullName, entry))
            {
                throw new InvalidDataException($"Conversation DNA contains duplicate entry '{entry.FullName}'.");
            }
        }

        if (!entries.TryGetValue("manifest.json", out var manifestEntry) ||
            !entries.TryGetValue("SHA256SUMS", out var checksumsEntry))
        {
            throw new InvalidDataException("Conversation DNA must contain manifest.json and SHA256SUMS.");
        }

        var manifestBytes = ReadEntryBytes(manifestEntry, 4 * 1024 * 1024);
        var manifest = JsonSerializer.Deserialize<ConversationArchiveManifest>(manifestBytes, JsonOptions)
            ?? throw new InvalidDataException("Conversation DNA manifest could not be parsed.");
        if (manifest.Format != "bke-dna" || manifest.FormatVersion != 2 || manifest.Scope != "conversation")
        {
            throw new InvalidDataException("Unsupported conversation DNA format.");
        }

        ValidateSha256(manifest.ConversationKey);
        var sourceSha256s = manifest.SourceSha256s
            .Select(static value => value.ToLowerInvariant())
            .ToArray();
        if (sourceSha256s.Length == 0 ||
            sourceSha256s.Distinct(StringComparer.Ordinal).Count() != sourceSha256s.Length)
        {
            throw new InvalidDataException("Conversation DNA manifest must contain a unique non-empty source set.");
        }
        foreach (var sourceSha in sourceSha256s)
        {
            ValidateSha256(sourceSha);
        }

        var expectedChecksums = ParseChecksums(
            Encoding.UTF8.GetString(ReadEntryBytes(checksumsEntry, 32 * 1024 * 1024)));
        var payloadEntryNames = entries.Keys
            .Where(static name => name != "SHA256SUMS")
            .OrderBy(static name => name, StringComparer.Ordinal)
            .ToArray();
        if (!payloadEntryNames.SequenceEqual(expectedChecksums.Keys.OrderBy(static name => name, StringComparer.Ordinal)))
        {
            throw new InvalidDataException("Conversation DNA SHA256SUMS does not describe exactly every payload entry.");
        }

        foreach (var entryName in payloadEntryNames)
        {
            using var stream = entries[entryName].Open();
            var actual = Sha256Stream(stream);
            if (!string.Equals(actual, expectedChecksums[entryName], StringComparison.Ordinal))
            {
                throw new InvalidDataException($"Checksum mismatch for conversation DNA entry '{entryName}'.");
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
            throw new InvalidDataException("Conversation DNA manifest references do not match payload entries.");
        }

        foreach (var evidence in manifest.Evidence)
        {
            ValidateArchivePath(evidence.Path);
            ValidateSha256(evidence.Sha256);
            if (evidence.SourceSha256 is not null)
            {
                ValidateSha256(evidence.SourceSha256);
                if (!sourceSha256s.Contains(evidence.SourceSha256, StringComparer.Ordinal))
                {
                    throw new InvalidDataException("Conversation DNA evidence references a source outside the manifest source set.");
                }
            }

            if (!entries.TryGetValue(evidence.Path, out var entry) || entry.Length != evidence.ByteLength)
            {
                throw new InvalidDataException($"Conversation DNA evidence metadata mismatch for '{evidence.Path}'.");
            }
            if (!string.Equals(expectedChecksums[evidence.Path], evidence.Sha256, StringComparison.Ordinal))
            {
                throw new InvalidDataException($"Conversation DNA manifest checksum mismatch for '{evidence.Path}'.");
            }
        }

        var stateEvidence = manifest.Evidence.SingleOrDefault(static item => item.Kind == "conversation_state")
            ?? throw new InvalidDataException("Conversation DNA must contain exactly one aggregated conversation state.");
        if (stateEvidence.Path != "conversation/state.json")
        {
            throw new InvalidDataException("Conversation DNA state must use conversation/state.json.");
        }

        using (var stateDocument = JsonDocument.Parse(ReadEntryBytes(entries[stateEvidence.Path], 32 * 1024 * 1024)))
        {
            var state = stateDocument.RootElement;
            if (!string.Equals(RequiredString(state, "conversationKey"), manifest.ConversationKey, StringComparison.Ordinal) ||
                !string.Equals(RequiredString(state, "conversationNativeId"), manifest.ConversationNativeId, StringComparison.Ordinal) ||
                !string.Equals(RequiredString(state, "coverageStatus"), manifest.CoverageStatus, StringComparison.Ordinal) ||
                !string.Equals(RequiredString(state, "coverageBasis"), manifest.CoverageBasis, StringComparison.Ordinal))
            {
                throw new InvalidDataException("Conversation DNA manifest does not match aggregated state identity/coverage.");
            }

            var stateSources = state.GetProperty("sources")
                .EnumerateArray()
                .Select(static source => RequiredString(source, "sourceSha256"))
                .Distinct(StringComparer.Ordinal)
                .OrderBy(static value => value, StringComparer.Ordinal)
                .ToArray();
            if (!stateSources.SequenceEqual(sourceSha256s.OrderBy(static value => value, StringComparer.Ordinal)))
            {
                throw new InvalidDataException("Conversation DNA aggregated state source set differs from manifest source set.");
            }
        }

        foreach (var sourceSha in sourceSha256s)
        {
            var rawEntries = manifest.Evidence
                .Where(item => item.Kind == "raw_body" && item.SourceSha256 == sourceSha)
                .ToArray();
            if (rawEntries.Length != 1)
            {
                throw new InvalidDataException($"Conversation DNA source '{sourceSha}' must contain exactly one raw body.");
            }
            var expectedRawPath = $"sources/{sourceSha}/raw.body";
            if (rawEntries[0].Path != expectedRawPath || rawEntries[0].Sha256 != sourceSha)
            {
                throw new InvalidDataException($"Conversation DNA raw body identity mismatch for source '{sourceSha}'.");
            }

            var normalizedCount = manifest.Evidence.Count(item =>
                item.Kind == "normalized_snapshot" && item.SourceSha256 == sourceSha);
            var observationCount = manifest.Evidence.Count(item =>
                item.Kind == "capture_observation" && item.SourceSha256 == sourceSha);
            if (normalizedCount != 1 || observationCount < 1)
            {
                throw new InvalidDataException($"Conversation DNA source '{sourceSha}' lacks required normalized/observation evidence.");
            }
        }

        file.Position = 0;
        var archiveSha256 = Sha256Stream(file);
        return new ConversationDnaArchiveVerification(
            manifest.ArchiveId,
            manifest.ConversationKey,
            manifest.ConversationNativeId,
            sourceSha256s.OrderBy(static value => value, StringComparer.Ordinal).ToArray(),
            archiveSha256,
            archivePath,
            entries.Count);
    }

    private ArchiveBuildResult BuildArchive(string conversationIdentity)
    {
        var conversationKey = ResolveConversationKey(conversationIdentity);
        var statePath = Path.Combine(_captureRoot, "conversations", $"{conversationKey}.json");
        if (!File.Exists(statePath))
        {
            throw new FileNotFoundException("Aggregated conversation state was not found.", statePath);
        }

        using var stateDocument = JsonDocument.Parse(File.ReadAllBytes(statePath));
        var state = stateDocument.RootElement;
        if (!string.Equals(RequiredString(state, "conversationKey"), conversationKey, StringComparison.Ordinal))
        {
            throw new InvalidDataException("Aggregated conversation key does not match its file identity.");
        }

        var conversationNativeId = RequiredString(state, "conversationNativeId");
        var sources = state.GetProperty("sources")
            .EnumerateArray()
            .Select(static source => RequiredString(source, "sourceSha256").ToLowerInvariant())
            .Distinct(StringComparer.Ordinal)
            .OrderBy(static value => value, StringComparer.Ordinal)
            .ToArray();
        if (sources.Length == 0)
        {
            throw new InvalidOperationException("Cannot archive a conversation without raw source payloads.");
        }

        var evidence = new List<EvidenceSource>
        {
            EvidenceSource.FromFile("conversation/state.json", "conversation_state", statePath, null)
        };
        var pageUrls = new HashSet<string>(StringComparer.Ordinal);
        var witnessIds = new HashSet<string>(StringComparer.Ordinal);

        foreach (var sourceSha in sources)
        {
            ValidateSha256(sourceSha);
            var rawPath = Path.Combine(_captureRoot, "bodies", $"{sourceSha}.body");
            var normalizedPath = Path.Combine(_captureRoot, "normalized", $"{sourceSha}.json");
            if (!File.Exists(rawPath) || !File.Exists(normalizedPath))
            {
                throw new InvalidOperationException($"Conversation source '{sourceSha}' is missing raw or normalized evidence.");
            }

            var raw = EvidenceSource.FromFile($"sources/{sourceSha}/raw.body", "raw_body", rawPath, sourceSha);
            if (!string.Equals(raw.Sha256, sourceSha, StringComparison.Ordinal))
            {
                throw new InvalidDataException($"Raw body bytes no longer hash to source identity '{sourceSha}'.");
            }
            evidence.Add(raw);
            evidence.Add(EvidenceSource.FromFile(
                $"sources/{sourceSha}/normalized.json",
                "normalized_snapshot",
                normalizedPath,
                sourceSha));

            var classificationPath = Path.Combine(_captureRoot, "classifications", $"{sourceSha}.json");
            if (File.Exists(classificationPath))
            {
                evidence.Add(EvidenceSource.FromFile(
                    $"sources/{sourceSha}/classification.json",
                    "classification",
                    classificationPath,
                    sourceSha));
            }

            var observationCount = 0;
            var observationDirectory = Path.Combine(_captureRoot, "observations");
            if (Directory.Exists(observationDirectory))
            {
                foreach (var path in Directory.EnumerateFiles(observationDirectory, "*.json")
                             .OrderBy(static path => path, StringComparer.Ordinal))
                {
                    try
                    {
                        using var document = JsonDocument.Parse(File.ReadAllBytes(path));
                        var root = document.RootElement;
                        if (RequiredString(root, "sha256") != sourceSha)
                        {
                            continue;
                        }

                        var capture = root.GetProperty("capture");
                        pageUrls.Add(RequiredString(capture, "pageUrl"));
                        evidence.Add(EvidenceSource.FromFile(
                            $"sources/{sourceSha}/observations/{Path.GetFileName(path)}",
                            "capture_observation",
                            path,
                            sourceSha));
                        observationCount += 1;
                    }
                    catch
                    {
                        // Malformed observation derivatives are not admitted to durable archives.
                    }
                }
            }

            if (observationCount == 0)
            {
                throw new InvalidOperationException($"Conversation source '{sourceSha}' has no valid capture observation.");
            }
        }

        var witnessDirectory = Path.Combine(_captureRoot, "witnesses");
        if (Directory.Exists(witnessDirectory))
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
                        path,
                        null));
                }
                catch
                {
                    // Continue with valid witness evidence.
                }
            }
        }

        var reconciliationDirectory = Path.Combine(_captureRoot, "reconciliations");
        if (Directory.Exists(reconciliationDirectory))
        {
            foreach (var path in Directory.EnumerateFiles(reconciliationDirectory, "*.json")
                         .OrderBy(static path => path, StringComparer.Ordinal))
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
                        path,
                        null));
                }
                catch
                {
                    // Continue with valid reconciliation evidence.
                }
            }
        }

        var orderedEvidence = evidence
            .OrderBy(static item => item.ArchivePath, StringComparer.Ordinal)
            .ToArray();
        var identityMaterial = string.Join("\n", orderedEvidence.Select(static item =>
            $"{item.ArchivePath}\t{item.Sha256}\t{item.ByteLength}\t{item.SourceSha256}"));
        var archiveId = $"dna-conversation-v2-{Sha256Bytes(Encoding.UTF8.GetBytes(identityMaterial))}";

        var manifest = new ConversationArchiveManifest(
            "bke-dna",
            2,
            "conversation",
            archiveId,
            conversationKey,
            conversationNativeId,
            OptionalString(state, "currentNodeNativeId"),
            RequiredString(state, "coverageStatus"),
            RequiredString(state, "coverageBasis"),
            sources,
            orderedEvidence.Select(static item => new ConversationArchiveEvidence(
                item.ArchivePath,
                item.Kind,
                item.SourceSha256,
                item.Sha256,
                item.ByteLength)).ToArray());

        var manifestBytes = JsonSerializer.SerializeToUtf8Bytes(manifest, JsonOptions);
        var checksums = new SortedDictionary<string, string>(StringComparer.Ordinal);
        foreach (var item in orderedEvidence)
        {
            checksums.Add(item.ArchivePath, item.Sha256);
        }
        checksums.Add("manifest.json", Sha256Bytes(manifestBytes));
        var checksumBytes = Encoding.UTF8.GetBytes(
            string.Join("\n", checksums.Select(static item => $"{item.Value}  {item.Key}")) + "\n");

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
            if (File.Exists(tempPath)) File.Delete(tempPath);
        }

        return new ArchiveBuildResult(archiveId, conversationKey, archivePath);
    }

    private void RecordVerifiedDurability(
        IReadOnlyList<string> sourceSha256s,
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
        using var transaction = connection.BeginTransaction();

        var updated = 0;
        foreach (var sourceSha in sourceSha256s)
        {
            using var command = connection.CreateCommand();
            command.Transaction = transaction;
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
            command.Parameters.AddWithValue("$sourceSha256", sourceSha);
            updated += command.ExecuteNonQuery();
        }

        if (updated != sourceSha256s.Count)
        {
            throw new InvalidOperationException(
                "Verified conversation .dna could not update every source durability row; no durability changes were committed.");
        }
        transaction.Commit();
    }

    private string ResolveConversationKey(string identity)
    {
        var normalized = identity.Trim();
        if (IsSha256(normalized) && File.Exists(Path.Combine(_captureRoot, "conversations", $"{normalized.ToLowerInvariant()}.json")))
        {
            return normalized.ToLowerInvariant();
        }
        return Sha256Bytes(Encoding.UTF8.GetBytes(normalized));
    }

    private static void WriteFileEntry(ZipArchive archive, string archivePath, string filePath)
    {
        var entry = CreateEntry(archive, archivePath);
        using var output = entry.Open();
        using var input = new FileStream(filePath, FileMode.Open, FileAccess.Read, FileShare.Read);
        input.CopyTo(output);
    }

    private static void WriteBytesEntry(ZipArchive archive, string archivePath, ReadOnlySpan<byte> bytes)
    {
        var entry = CreateEntry(archive, archivePath);
        using var output = entry.Open();
        output.Write(bytes);
    }

    private static ZipArchiveEntry CreateEntry(ZipArchive archive, string path)
    {
        ValidateArchivePath(path);
        var entry = archive.CreateEntry(path, CompressionLevel.NoCompression);
        entry.LastWriteTime = DeterministicZipTime;
        entry.ExternalAttributes = 0;
        return entry;
    }

    private static byte[] ReadEntryBytes(ZipArchiveEntry entry, long maxBytes)
    {
        if (entry.Length > maxBytes)
        {
            throw new InvalidDataException($"Conversation DNA entry '{entry.FullName}' exceeds verification size limit.");
        }
        using var input = entry.Open();
        using var memory = new MemoryStream((int)entry.Length);
        input.CopyTo(memory);
        return memory.ToArray();
    }

    private static SortedDictionary<string, string> ParseChecksums(string text)
    {
        var result = new SortedDictionary<string, string>(StringComparer.Ordinal);
        foreach (var rawLine in text.Split('\n', StringSplitOptions.RemoveEmptyEntries))
        {
            var line = rawLine.TrimEnd('\r');
            var separator = line.IndexOf("  ", StringComparison.Ordinal);
            if (separator != 64) throw new InvalidDataException("Malformed conversation DNA SHA256SUMS line.");
            var sha = line[..separator].ToLowerInvariant();
            var path = line[(separator + 2)..];
            ValidateSha256(sha);
            ValidateArchivePath(path);
            if (!result.TryAdd(path, sha)) throw new InvalidDataException("Duplicate conversation DNA checksum path.");
        }
        return result;
    }

    private static void ValidateArchivePath(string path)
    {
        if (string.IsNullOrWhiteSpace(path) || path.StartsWith("/", StringComparison.Ordinal) ||
            path.Contains('\\') || path.Split('/').Any(static segment => segment is "" or "." or ".."))
        {
            throw new InvalidDataException($"Unsafe conversation DNA path '{path}'.");
        }
    }

    private static bool IsSha256(string value)
        => value.Length == 64 && value.All(static character => Uri.IsHexDigit(character));

    private static void ValidateSha256(string value)
    {
        if (!IsSha256(value)) throw new InvalidDataException("Expected a 64-character SHA-256 value.");
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
        if (!element.TryGetProperty(propertyName, out var value) || value.ValueKind == JsonValueKind.Null) return null;
        return value.ValueKind == JsonValueKind.String ? value.GetString() : null;
    }

    private sealed record EvidenceSource(
        string ArchivePath,
        string Kind,
        string FilePath,
        string? SourceSha256,
        string Sha256,
        long ByteLength)
    {
        public static EvidenceSource FromFile(string archivePath, string kind, string filePath, string? sourceSha256)
        {
            var info = new FileInfo(filePath);
            return new EvidenceSource(archivePath, kind, filePath, sourceSha256, Sha256File(filePath), info.Length);
        }
    }

    private sealed record ArchiveBuildResult(string ArchiveId, string ConversationKey, string ArchivePath);

    private sealed record ConversationArchiveManifest(
        string Format,
        int FormatVersion,
        string Scope,
        string ArchiveId,
        string ConversationKey,
        string ConversationNativeId,
        string? CurrentNodeNativeId,
        string CoverageStatus,
        string CoverageBasis,
        IReadOnlyList<string> SourceSha256s,
        IReadOnlyList<ConversationArchiveEvidence> Evidence);

    private sealed record ConversationArchiveEvidence(
        string Path,
        string Kind,
        string? SourceSha256,
        string Sha256,
        long ByteLength);
}

internal sealed record ConversationDnaArchiveResult(
    string ArchiveId,
    string ConversationKey,
    string ConversationNativeId,
    IReadOnlyList<string> SourceSha256s,
    string ArchiveSha256,
    string ArchivePath,
    string ArchivedAt,
    int EntryCount);

internal sealed record ConversationDnaArchiveVerification(
    string ArchiveId,
    string ConversationKey,
    string ConversationNativeId,
    IReadOnlyList<string> SourceSha256s,
    string ArchiveSha256,
    string ArchivePath,
    int EntryCount);
