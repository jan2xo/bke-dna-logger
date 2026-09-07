using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Microsoft.Data.Sqlite;

namespace BKE.Dna.Logger.Host.Storage;

internal sealed class SqliteProjectionEngine
{
    private readonly string _captureRoot;
    private readonly string _databasePath;

    public SqliteProjectionEngine(string captureRoot, string databasePath)
    {
        _captureRoot = Path.GetFullPath(captureRoot);
        _databasePath = Path.GetFullPath(databasePath);
    }

    public void TryProjectAll()
    {
        try
        {
            ProjectAll();
        }
        catch (Exception error)
        {
            Console.Error.WriteLine($"BKE DNA SQLite projection skipped; filesystem evidence is intact: {error.Message}");
        }
    }

    private void ProjectAll()
    {
        using var connection = new SqliteConnection(new SqliteConnectionStringBuilder
        {
            DataSource = _databasePath,
            Mode = SqliteOpenMode.ReadWrite,
            Cache = SqliteCacheMode.Shared
        }.ToString());
        connection.Open();
        Execute(connection, "PRAGMA foreign_keys = ON;");
        Execute(connection, "PRAGMA busy_timeout = 5000;");

        using var transaction = connection.BeginTransaction();
        var observations = ReadObservations();
        ProjectRawCaptures(connection, transaction, observations);
        ProjectObservations(connection, transaction, observations);
        ProjectNormalizedGraphs(connection, transaction);
        ProjectWitnesses(connection, transaction);
        ProjectReconciliations(connection, transaction);
        transaction.Commit();
    }

    private List<ObservationProjection> ReadObservations()
    {
        var directory = Path.Combine(_captureRoot, "observations");
        var result = new List<ObservationProjection>();
        if (!Directory.Exists(directory))
        {
            return result;
        }

        foreach (var path in Directory.EnumerateFiles(directory, "*.json"))
        {
            try
            {
                using var document = JsonDocument.Parse(File.ReadAllBytes(path));
                var root = document.RootElement;
                var capture = root.GetProperty("capture");
                result.Add(new ObservationProjection(
                    CaptureId: RequiredString(capture, "captureId"),
                    Sha256: RequiredString(root, "sha256"),
                    ByteLength: root.GetProperty("byteLength").GetInt64(),
                    BodyPath: RequiredString(root, "bodyPath"),
                    PageUrl: RequiredString(capture, "pageUrl"),
                    RequestUrlSha256: Sha256Text(RequiredString(capture, "requestUrl")),
                    Method: RequiredString(capture, "method"),
                    Status: capture.GetProperty("status").GetInt32(),
                    ContentType: OptionalString(capture, "contentType"),
                    Initiator: RequiredString(capture, "initiator"),
                    CapturedAt: RequiredString(capture, "capturedAt"),
                    Fidelity: RequiredString(capture, "fidelity"),
                    StoredAt: RequiredString(root, "storedAt")));
            }
            catch
            {
                // One malformed derivative observation cannot block projection of valid evidence.
            }
        }

        return result;
    }

    private static void ProjectRawCaptures(
        SqliteConnection connection,
        SqliteTransaction transaction,
        IReadOnlyList<ObservationProjection> observations)
    {
        foreach (var group in observations.GroupBy(static item => item.Sha256, StringComparer.Ordinal))
        {
            var ordered = group.OrderBy(static item => item.CapturedAt, StringComparer.Ordinal).ToArray();
            var first = ordered[0];
            var last = ordered[^1];

            using var command = connection.CreateCommand();
            command.Transaction = transaction;
            command.CommandText = """
                INSERT INTO raw_capture(
                    sha256, byte_length, content_type, body_path,
                    first_seen_at, last_seen_at, seen_count
                ) VALUES (
                    $sha256, $byteLength, $contentType, $bodyPath,
                    $firstSeenAt, $lastSeenAt, $seenCount
                )
                ON CONFLICT(sha256) DO UPDATE SET
                    byte_length = excluded.byte_length,
                    content_type = COALESCE(excluded.content_type, raw_capture.content_type),
                    body_path = excluded.body_path,
                    first_seen_at = MIN(raw_capture.first_seen_at, excluded.first_seen_at),
                    last_seen_at = MAX(raw_capture.last_seen_at, excluded.last_seen_at),
                    seen_count = excluded.seen_count;
                """;
            command.Parameters.AddWithValue("$sha256", first.Sha256);
            command.Parameters.AddWithValue("$byteLength", first.ByteLength);
            command.Parameters.AddWithValue("$contentType", (object?)first.ContentType ?? DBNull.Value);
            command.Parameters.AddWithValue("$bodyPath", first.BodyPath);
            command.Parameters.AddWithValue("$firstSeenAt", first.CapturedAt);
            command.Parameters.AddWithValue("$lastSeenAt", last.CapturedAt);
            command.Parameters.AddWithValue("$seenCount", ordered.Length);
            command.ExecuteNonQuery();

            using var durability = connection.CreateCommand();
            durability.Transaction = transaction;
            durability.CommandText = """
                INSERT INTO durability_state(raw_sha256, clearable)
                VALUES ($sha256, 0)
                ON CONFLICT(raw_sha256) DO NOTHING;
                """;
            durability.Parameters.AddWithValue("$sha256", first.Sha256);
            durability.ExecuteNonQuery();
        }
    }

    private static void ProjectObservations(
        SqliteConnection connection,
        SqliteTransaction transaction,
        IReadOnlyList<ObservationProjection> observations)
    {
        foreach (var observation in observations)
        {
            using var command = connection.CreateCommand();
            command.Transaction = transaction;
            command.CommandText = """
                INSERT INTO capture_observation(
                    capture_id, raw_sha256, page_url, request_url_sha256,
                    method, status, initiator, captured_at, fidelity, stored_at
                ) VALUES (
                    $captureId, $rawSha256, $pageUrl, $requestUrlSha256,
                    $method, $status, $initiator, $capturedAt, $fidelity, $storedAt
                )
                ON CONFLICT(capture_id) DO UPDATE SET
                    raw_sha256 = excluded.raw_sha256,
                    page_url = excluded.page_url,
                    request_url_sha256 = excluded.request_url_sha256,
                    method = excluded.method,
                    status = excluded.status,
                    initiator = excluded.initiator,
                    captured_at = excluded.captured_at,
                    fidelity = excluded.fidelity,
                    stored_at = excluded.stored_at;
                """;
            command.Parameters.AddWithValue("$captureId", observation.CaptureId);
            command.Parameters.AddWithValue("$rawSha256", observation.Sha256);
            command.Parameters.AddWithValue("$pageUrl", observation.PageUrl);
            command.Parameters.AddWithValue("$requestUrlSha256", observation.RequestUrlSha256);
            command.Parameters.AddWithValue("$method", observation.Method);
            command.Parameters.AddWithValue("$status", observation.Status);
            command.Parameters.AddWithValue("$initiator", observation.Initiator);
            command.Parameters.AddWithValue("$capturedAt", observation.CapturedAt);
            command.Parameters.AddWithValue("$fidelity", observation.Fidelity);
            command.Parameters.AddWithValue("$storedAt", observation.StoredAt);
            command.ExecuteNonQuery();
        }
    }

    private void ProjectNormalizedGraphs(SqliteConnection connection, SqliteTransaction transaction)
    {
        var directory = Path.Combine(_captureRoot, "normalized");
        if (!Directory.Exists(directory))
        {
            return;
        }

        foreach (var path in Directory.EnumerateFiles(directory, "*.json"))
        {
            try
            {
                using var document = JsonDocument.Parse(File.ReadAllBytes(path));
                var root = document.RootElement;
                var sourceSha256 = RequiredString(root, "sourceSha256");

                using (var snapshot = connection.CreateCommand())
                {
                    snapshot.Transaction = transaction;
                    snapshot.CommandText = """
                        INSERT INTO conversation_snapshot(
                            source_sha256, conversation_native_id, current_node_native_id,
                            coverage_status, coverage_basis, normalized_path, normalized_at
                        ) VALUES (
                            $sourceSha256, $conversationNativeId, $currentNodeNativeId,
                            $coverageStatus, $coverageBasis, $normalizedPath, $normalizedAt
                        )
                        ON CONFLICT(source_sha256) DO UPDATE SET
                            conversation_native_id = excluded.conversation_native_id,
                            current_node_native_id = excluded.current_node_native_id,
                            coverage_status = excluded.coverage_status,
                            coverage_basis = excluded.coverage_basis,
                            normalized_path = excluded.normalized_path,
                            normalized_at = excluded.normalized_at;
                        """;
                    snapshot.Parameters.AddWithValue("$sourceSha256", sourceSha256);
                    snapshot.Parameters.AddWithValue("$conversationNativeId", (object?)OptionalString(root, "conversationNativeId") ?? DBNull.Value);
                    snapshot.Parameters.AddWithValue("$currentNodeNativeId", (object?)OptionalString(root, "currentNodeNativeId") ?? DBNull.Value);
                    snapshot.Parameters.AddWithValue("$coverageStatus", RequiredString(root, "coverageStatus"));
                    snapshot.Parameters.AddWithValue("$coverageBasis", RequiredString(root, "coverageBasis"));
                    snapshot.Parameters.AddWithValue("$normalizedPath", RelativePath(path));
                    snapshot.Parameters.AddWithValue("$normalizedAt", RequiredString(root, "normalizedAt"));
                    snapshot.ExecuteNonQuery();
                }

                DeleteGraphRows(connection, transaction, sourceSha256);
                var normalizedAt = RequiredString(root, "normalizedAt");
                foreach (var node in root.GetProperty("nodes").EnumerateArray())
                {
                    ProjectNode(connection, transaction, sourceSha256, normalizedAt, node);
                }
            }
            catch
            {
                // Unsupported/malformed normalized derivative does not block other projections.
            }
        }
    }

    private static void DeleteGraphRows(
        SqliteConnection connection,
        SqliteTransaction transaction,
        string sourceSha256)
    {
        foreach (var table in new[] { "message_edge", "message_revision", "message_node" })
        {
            using var command = connection.CreateCommand();
            command.Transaction = transaction;
            command.CommandText = $"DELETE FROM {table} WHERE source_sha256 = $sourceSha256;";
            command.Parameters.AddWithValue("$sourceSha256", sourceSha256);
            command.ExecuteNonQuery();
        }
    }

    private static void ProjectNode(
        SqliteConnection connection,
        SqliteTransaction transaction,
        string sourceSha256,
        string normalizedAt,
        JsonElement node)
    {
        var nodeNativeId = RequiredString(node, "nodeNativeId");
        var messageNativeId = OptionalString(node, "messageNativeId");
        var contentJson = OptionalString(node, "contentJson");

        using (var command = connection.CreateCommand())
        {
            command.Transaction = transaction;
            command.CommandText = """
                INSERT INTO message_node(
                    source_sha256, node_native_id, message_native_id,
                    parent_native_id, role, created_at, content_json
                ) VALUES (
                    $sourceSha256, $nodeNativeId, $messageNativeId,
                    $parentNativeId, $role, $createdAt, $contentJson
                );
                """;
            command.Parameters.AddWithValue("$sourceSha256", sourceSha256);
            command.Parameters.AddWithValue("$nodeNativeId", nodeNativeId);
            command.Parameters.AddWithValue("$messageNativeId", (object?)messageNativeId ?? DBNull.Value);
            command.Parameters.AddWithValue("$parentNativeId", (object?)OptionalString(node, "parentNativeId") ?? DBNull.Value);
            command.Parameters.AddWithValue("$role", (object?)OptionalString(node, "role") ?? DBNull.Value);
            command.Parameters.AddWithValue("$createdAt", (object?)OptionalString(node, "createdAt") ?? DBNull.Value);
            command.Parameters.AddWithValue("$contentJson", (object?)contentJson ?? DBNull.Value);
            command.ExecuteNonQuery();
        }

        foreach (var child in node.GetProperty("childNativeIds").EnumerateArray())
        {
            var childNativeId = child.GetString();
            if (string.IsNullOrWhiteSpace(childNativeId))
            {
                continue;
            }

            using var edge = connection.CreateCommand();
            edge.Transaction = transaction;
            edge.CommandText = """
                INSERT OR IGNORE INTO message_edge(
                    source_sha256, parent_node_native_id, child_node_native_id
                ) VALUES ($sourceSha256, $parentNodeNativeId, $childNodeNativeId);
                """;
            edge.Parameters.AddWithValue("$sourceSha256", sourceSha256);
            edge.Parameters.AddWithValue("$parentNodeNativeId", nodeNativeId);
            edge.Parameters.AddWithValue("$childNodeNativeId", childNativeId);
            edge.ExecuteNonQuery();
        }

        if (!string.IsNullOrWhiteSpace(contentJson))
        {
            var revisionSha256 = Sha256Text($"{sourceSha256}\n{nodeNativeId}\n{contentJson}");
            using var revision = connection.CreateCommand();
            revision.Transaction = transaction;
            revision.CommandText = """
                INSERT INTO message_revision(
                    revision_sha256, source_sha256, node_native_id,
                    message_native_id, content_json, observed_at
                ) VALUES (
                    $revisionSha256, $sourceSha256, $nodeNativeId,
                    $messageNativeId, $contentJson, $observedAt
                );
                """;
            revision.Parameters.AddWithValue("$revisionSha256", revisionSha256);
            revision.Parameters.AddWithValue("$sourceSha256", sourceSha256);
            revision.Parameters.AddWithValue("$nodeNativeId", nodeNativeId);
            revision.Parameters.AddWithValue("$messageNativeId", (object?)messageNativeId ?? DBNull.Value);
            revision.Parameters.AddWithValue("$contentJson", contentJson);
            revision.Parameters.AddWithValue("$observedAt", normalizedAt);
            revision.ExecuteNonQuery();
        }
    }

    private void ProjectWitnesses(SqliteConnection connection, SqliteTransaction transaction)
    {
        var directory = Path.Combine(_captureRoot, "witnesses");
        if (!Directory.Exists(directory))
        {
            return;
        }

        foreach (var path in Directory.EnumerateFiles(directory, "*.json"))
        {
            try
            {
                using var document = JsonDocument.Parse(File.ReadAllBytes(path));
                var root = document.RootElement;
                using var command = connection.CreateCommand();
                command.Transaction = transaction;
                command.CommandText = """
                    INSERT INTO dom_witness(
                        witness_id, page_url, observed_at, fingerprint_sha256, witness_path
                    ) VALUES (
                        $witnessId, $pageUrl, $observedAt, $fingerprintSha256, $witnessPath
                    )
                    ON CONFLICT(witness_id) DO UPDATE SET
                        page_url = excluded.page_url,
                        observed_at = excluded.observed_at,
                        fingerprint_sha256 = excluded.fingerprint_sha256,
                        witness_path = excluded.witness_path;
                    """;
                command.Parameters.AddWithValue("$witnessId", RequiredString(root, "witnessId"));
                command.Parameters.AddWithValue("$pageUrl", RequiredString(root, "pageUrl"));
                command.Parameters.AddWithValue("$observedAt", RequiredString(root, "observedAt"));
                command.Parameters.AddWithValue("$fingerprintSha256", RequiredString(root, "fingerprintSha256"));
                command.Parameters.AddWithValue("$witnessPath", RelativePath(path));
                command.ExecuteNonQuery();
            }
            catch
            {
                // Continue projecting other witness evidence.
            }
        }
    }

    private void ProjectReconciliations(SqliteConnection connection, SqliteTransaction transaction)
    {
        var directory = Path.Combine(_captureRoot, "reconciliations");
        if (!Directory.Exists(directory))
        {
            return;
        }

        foreach (var path in Directory.EnumerateFiles(directory, "*.json"))
        {
            try
            {
                using var document = JsonDocument.Parse(File.ReadAllBytes(path));
                var root = document.RootElement;
                using var command = connection.CreateCommand();
                command.Transaction = transaction;
                command.CommandText = """
                    INSERT INTO reconciliation(
                        witness_id, status, reconciliation_path, reconciled_at
                    ) VALUES (
                        $witnessId, $status, $reconciliationPath, $reconciledAt
                    )
                    ON CONFLICT(witness_id) DO UPDATE SET
                        status = excluded.status,
                        reconciliation_path = excluded.reconciliation_path,
                        reconciled_at = excluded.reconciled_at;
                    """;
                command.Parameters.AddWithValue("$witnessId", RequiredString(root, "witnessId"));
                command.Parameters.AddWithValue("$status", RequiredString(root, "status"));
                command.Parameters.AddWithValue("$reconciliationPath", RelativePath(path));
                command.Parameters.AddWithValue("$reconciledAt", RequiredString(root, "reconciledAt"));
                command.ExecuteNonQuery();
            }
            catch
            {
                // Continue projecting other reconciliation evidence.
            }
        }
    }

    private string RelativePath(string path)
        => Path.GetRelativePath(_captureRoot, path).Replace('\\', '/');

    private static string RequiredString(JsonElement element, string propertyName)
    {
        var value = OptionalString(element, propertyName);
        return !string.IsNullOrWhiteSpace(value)
            ? value
            : throw new InvalidDataException($"Missing required string '{propertyName}'.");
    }

    private static string? OptionalString(JsonElement element, string propertyName)
    {
        if (!element.TryGetProperty(propertyName, out var value) || value.ValueKind == JsonValueKind.Null)
        {
            return null;
        }

        return value.ValueKind == JsonValueKind.String ? value.GetString() : value.GetRawText();
    }

    private static string Sha256Text(string value)
        => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(value))).ToLowerInvariant();

    private static void Execute(SqliteConnection connection, string sql)
    {
        using var command = connection.CreateCommand();
        command.CommandText = sql;
        command.ExecuteNonQuery();
    }

    private sealed record ObservationProjection(
        string CaptureId,
        string Sha256,
        long ByteLength,
        string BodyPath,
        string PageUrl,
        string RequestUrlSha256,
        string Method,
        int Status,
        string? ContentType,
        string Initiator,
        string CapturedAt,
        string Fidelity,
        string StoredAt);
}
