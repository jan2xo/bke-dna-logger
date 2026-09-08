using System.Text.Json;
using Microsoft.Data.Sqlite;

namespace BKE.Dna.Logger.Host.Storage;

internal sealed class ConversationSqliteProjectionEngine
{
    private readonly string _captureRoot;
    private readonly string _databasePath;

    public ConversationSqliteProjectionEngine(string captureRoot, string databasePath)
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
            Console.Error.WriteLine($"BKE DNA logical conversation projection skipped; filesystem state is intact: {error.Message}");
        }
    }

    private void ProjectAll()
    {
        var directory = Path.Combine(_captureRoot, "conversations");
        if (!Directory.Exists(directory))
        {
            return;
        }

        using var connection = new SqliteConnection(new SqliteConnectionStringBuilder
        {
            DataSource = _databasePath,
            Mode = SqliteOpenMode.ReadWrite,
            Cache = SqliteCacheMode.Shared
        }.ToString());
        connection.Open();

        using (var pragma = connection.CreateCommand())
        {
            pragma.CommandText = "PRAGMA foreign_keys = ON; PRAGMA busy_timeout = 5000;";
            pragma.ExecuteNonQuery();
        }

        foreach (var path in Directory.EnumerateFiles(directory, "*.json").OrderBy(static path => path, StringComparer.Ordinal))
        {
            try
            {
                ProjectOne(connection, path);
            }
            catch
            {
                // One malformed aggregate cannot block indexing of other conversations.
            }
        }
    }

    private void ProjectOne(SqliteConnection connection, string path)
    {
        using var document = JsonDocument.Parse(File.ReadAllBytes(path));
        var root = document.RootElement;
        var conversationKey = RequiredString(root, "conversationKey");

        using var transaction = connection.BeginTransaction();
        using (var conversation = connection.CreateCommand())
        {
            conversation.Transaction = transaction;
            conversation.CommandText = """
                INSERT INTO logical_conversation(
                    conversation_key, conversation_native_id, current_node_native_id,
                    state_observed_through, coverage_status, coverage_basis, state_path
                ) VALUES (
                    $conversationKey, $conversationNativeId, $currentNodeNativeId,
                    $stateObservedThrough, $coverageStatus, $coverageBasis, $statePath
                )
                ON CONFLICT(conversation_key) DO UPDATE SET
                    conversation_native_id = excluded.conversation_native_id,
                    current_node_native_id = excluded.current_node_native_id,
                    state_observed_through = excluded.state_observed_through,
                    coverage_status = excluded.coverage_status,
                    coverage_basis = excluded.coverage_basis,
                    state_path = excluded.state_path;
                """;
            conversation.Parameters.AddWithValue("$conversationKey", conversationKey);
            conversation.Parameters.AddWithValue("$conversationNativeId", RequiredString(root, "conversationNativeId"));
            conversation.Parameters.AddWithValue("$currentNodeNativeId", (object?)OptionalString(root, "currentNodeNativeId") ?? DBNull.Value);
            conversation.Parameters.AddWithValue("$stateObservedThrough", RequiredString(root, "stateObservedThrough"));
            conversation.Parameters.AddWithValue("$coverageStatus", RequiredString(root, "coverageStatus"));
            conversation.Parameters.AddWithValue("$coverageBasis", RequiredString(root, "coverageBasis"));
            conversation.Parameters.AddWithValue("$statePath", RelativePath(path));
            conversation.ExecuteNonQuery();
        }

        DeleteChildren(connection, transaction, conversationKey);

        foreach (var source in root.GetProperty("sources").EnumerateArray())
        {
            using var command = connection.CreateCommand();
            command.Transaction = transaction;
            command.CommandText = """
                INSERT INTO conversation_source(
                    conversation_key, source_sha256, observed_at,
                    current_node_native_id, coverage_status, coverage_basis
                ) VALUES (
                    $conversationKey, $sourceSha256, $observedAt,
                    $currentNodeNativeId, $coverageStatus, $coverageBasis
                );
                """;
            command.Parameters.AddWithValue("$conversationKey", conversationKey);
            command.Parameters.AddWithValue("$sourceSha256", RequiredString(source, "sourceSha256"));
            command.Parameters.AddWithValue("$observedAt", RequiredString(source, "observedAt"));
            command.Parameters.AddWithValue("$currentNodeNativeId", (object?)OptionalString(source, "currentNodeNativeId") ?? DBNull.Value);
            command.Parameters.AddWithValue("$coverageStatus", RequiredString(source, "coverageStatus"));
            command.Parameters.AddWithValue("$coverageBasis", RequiredString(source, "coverageBasis"));
            command.ExecuteNonQuery();
        }

        var nodes = root.GetProperty("nodes").EnumerateArray().ToArray();
        foreach (var node in nodes)
        {
            using var command = connection.CreateCommand();
            command.Transaction = transaction;
            command.CommandText = """
                INSERT INTO logical_message_node(
                    conversation_key, node_native_id, message_native_ids_json,
                    parent_native_ids_json, child_native_ids_json, roles_json
                ) VALUES (
                    $conversationKey, $nodeNativeId, $messageNativeIdsJson,
                    $parentNativeIdsJson, $childNativeIdsJson, $rolesJson
                );
                """;
            command.Parameters.AddWithValue("$conversationKey", conversationKey);
            command.Parameters.AddWithValue("$nodeNativeId", RequiredString(node, "nodeNativeId"));
            command.Parameters.AddWithValue("$messageNativeIdsJson", node.GetProperty("messageNativeIds").GetRawText());
            command.Parameters.AddWithValue("$parentNativeIdsJson", node.GetProperty("parentNativeIds").GetRawText());
            command.Parameters.AddWithValue("$childNativeIdsJson", node.GetProperty("childNativeIds").GetRawText());
            command.Parameters.AddWithValue("$rolesJson", node.GetProperty("roles").GetRawText());
            command.ExecuteNonQuery();
        }

        foreach (var node in nodes)
        {
            var nodeNativeId = RequiredString(node, "nodeNativeId");
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
                    INSERT OR IGNORE INTO logical_message_edge(
                        conversation_key, parent_node_native_id, child_node_native_id
                    ) VALUES ($conversationKey, $parentNodeNativeId, $childNodeNativeId);
                    """;
                edge.Parameters.AddWithValue("$conversationKey", conversationKey);
                edge.Parameters.AddWithValue("$parentNodeNativeId", nodeNativeId);
                edge.Parameters.AddWithValue("$childNodeNativeId", childNativeId);
                edge.ExecuteNonQuery();
            }

            foreach (var revision in node.GetProperty("revisions").EnumerateArray())
            {
                var revisionSha256 = RequiredString(revision, "revisionSha256");
                using (var revisionCommand = connection.CreateCommand())
                {
                    revisionCommand.Transaction = transaction;
                    revisionCommand.CommandText = """
                        INSERT INTO logical_message_revision(
                            conversation_key, node_native_id, revision_sha256,
                            content_json, text_parts_json, first_observed_at, last_observed_at
                        ) VALUES (
                            $conversationKey, $nodeNativeId, $revisionSha256,
                            $contentJson, $textPartsJson, $firstObservedAt, $lastObservedAt
                        );
                        """;
                    revisionCommand.Parameters.AddWithValue("$conversationKey", conversationKey);
                    revisionCommand.Parameters.AddWithValue("$nodeNativeId", nodeNativeId);
                    revisionCommand.Parameters.AddWithValue("$revisionSha256", revisionSha256);
                    revisionCommand.Parameters.AddWithValue("$contentJson", RequiredString(revision, "contentJson"));
                    revisionCommand.Parameters.AddWithValue("$textPartsJson", revision.GetProperty("textParts").GetRawText());
                    revisionCommand.Parameters.AddWithValue("$firstObservedAt", RequiredString(revision, "firstObservedAt"));
                    revisionCommand.Parameters.AddWithValue("$lastObservedAt", RequiredString(revision, "lastObservedAt"));
                    revisionCommand.ExecuteNonQuery();
                }

                foreach (var sourceSha in revision.GetProperty("sourceSha256s").EnumerateArray())
                {
                    var sourceSha256 = sourceSha.GetString();
                    if (string.IsNullOrWhiteSpace(sourceSha256))
                    {
                        continue;
                    }

                    using var sourceCommand = connection.CreateCommand();
                    sourceCommand.Transaction = transaction;
                    sourceCommand.CommandText = """
                        INSERT INTO logical_message_revision_source(
                            conversation_key, node_native_id, revision_sha256, source_sha256
                        ) VALUES (
                            $conversationKey, $nodeNativeId, $revisionSha256, $sourceSha256
                        );
                        """;
                    sourceCommand.Parameters.AddWithValue("$conversationKey", conversationKey);
                    sourceCommand.Parameters.AddWithValue("$nodeNativeId", nodeNativeId);
                    sourceCommand.Parameters.AddWithValue("$revisionSha256", revisionSha256);
                    sourceCommand.Parameters.AddWithValue("$sourceSha256", sourceSha256);
                    sourceCommand.ExecuteNonQuery();
                }
            }
        }

        transaction.Commit();
    }

    private static void DeleteChildren(
        SqliteConnection connection,
        SqliteTransaction transaction,
        string conversationKey)
    {
        foreach (var table in new[]
                 {
                     "logical_message_revision_source",
                     "logical_message_revision",
                     "logical_message_edge",
                     "logical_message_node",
                     "conversation_source"
                 })
        {
            using var command = connection.CreateCommand();
            command.Transaction = transaction;
            command.CommandText = $"DELETE FROM {table} WHERE conversation_key = $conversationKey;";
            command.Parameters.AddWithValue("$conversationKey", conversationKey);
            command.ExecuteNonQuery();
        }
    }

    private string RelativePath(string path)
        => Path.GetRelativePath(_captureRoot, path).Replace('\\', '/');

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
}
