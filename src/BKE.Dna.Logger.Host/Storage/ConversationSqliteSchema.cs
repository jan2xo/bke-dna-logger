using Microsoft.Data.Sqlite;

namespace BKE.Dna.Logger.Host.Storage;

internal static class ConversationSqliteSchema
{
    public static bool TryUpgrade(string databasePath)
    {
        try
        {
            Upgrade(databasePath);
            return true;
        }
        catch (Exception error)
        {
            Console.Error.WriteLine($"BKE DNA SQLite conversation schema unavailable; filesystem conversation state remains intact: {error.Message}");
            return false;
        }
    }

    private static void Upgrade(string databasePath)
    {
        using var connection = new SqliteConnection(new SqliteConnectionStringBuilder
        {
            DataSource = Path.GetFullPath(databasePath),
            Mode = SqliteOpenMode.ReadWrite,
            Cache = SqliteCacheMode.Shared
        }.ToString());
        connection.Open();

        using (var pragma = connection.CreateCommand())
        {
            pragma.CommandText = "PRAGMA foreign_keys = ON; PRAGMA busy_timeout = 5000;";
            pragma.ExecuteNonQuery();
        }

        using var transaction = connection.BeginTransaction();
        using var command = connection.CreateCommand();
        command.Transaction = transaction;
        command.CommandText = """
            CREATE TABLE IF NOT EXISTS logical_conversation (
                conversation_key TEXT PRIMARY KEY,
                conversation_native_id TEXT NOT NULL UNIQUE,
                current_node_native_id TEXT,
                state_observed_through TEXT NOT NULL,
                coverage_status TEXT NOT NULL,
                coverage_basis TEXT NOT NULL,
                state_path TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS conversation_source (
                conversation_key TEXT NOT NULL REFERENCES logical_conversation(conversation_key) ON DELETE RESTRICT,
                source_sha256 TEXT NOT NULL REFERENCES raw_capture(sha256) ON DELETE RESTRICT,
                observed_at TEXT NOT NULL,
                current_node_native_id TEXT,
                coverage_status TEXT NOT NULL,
                coverage_basis TEXT NOT NULL,
                PRIMARY KEY (conversation_key, source_sha256)
            );

            CREATE INDEX IF NOT EXISTS ix_conversation_source_sha256
                ON conversation_source(source_sha256);

            CREATE TABLE IF NOT EXISTS logical_message_node (
                conversation_key TEXT NOT NULL REFERENCES logical_conversation(conversation_key) ON DELETE RESTRICT,
                node_native_id TEXT NOT NULL,
                message_native_ids_json TEXT NOT NULL,
                parent_native_ids_json TEXT NOT NULL,
                child_native_ids_json TEXT NOT NULL,
                roles_json TEXT NOT NULL,
                PRIMARY KEY (conversation_key, node_native_id)
            );

            CREATE TABLE IF NOT EXISTS logical_message_edge (
                conversation_key TEXT NOT NULL,
                parent_node_native_id TEXT NOT NULL,
                child_node_native_id TEXT NOT NULL,
                PRIMARY KEY (conversation_key, parent_node_native_id, child_node_native_id),
                FOREIGN KEY (conversation_key, parent_node_native_id)
                    REFERENCES logical_message_node(conversation_key, node_native_id) ON DELETE RESTRICT,
                FOREIGN KEY (conversation_key, child_node_native_id)
                    REFERENCES logical_message_node(conversation_key, node_native_id) ON DELETE RESTRICT
            );

            CREATE TABLE IF NOT EXISTS logical_message_revision (
                conversation_key TEXT NOT NULL,
                node_native_id TEXT NOT NULL,
                revision_sha256 TEXT NOT NULL,
                content_json TEXT NOT NULL,
                text_parts_json TEXT NOT NULL,
                first_observed_at TEXT NOT NULL,
                last_observed_at TEXT NOT NULL,
                PRIMARY KEY (conversation_key, node_native_id, revision_sha256),
                FOREIGN KEY (conversation_key, node_native_id)
                    REFERENCES logical_message_node(conversation_key, node_native_id) ON DELETE RESTRICT
            );

            CREATE TABLE IF NOT EXISTS logical_message_revision_source (
                conversation_key TEXT NOT NULL,
                node_native_id TEXT NOT NULL,
                revision_sha256 TEXT NOT NULL,
                source_sha256 TEXT NOT NULL REFERENCES raw_capture(sha256) ON DELETE RESTRICT,
                PRIMARY KEY (conversation_key, node_native_id, revision_sha256, source_sha256),
                FOREIGN KEY (conversation_key, node_native_id, revision_sha256)
                    REFERENCES logical_message_revision(conversation_key, node_native_id, revision_sha256) ON DELETE RESTRICT
            );

            CREATE INDEX IF NOT EXISTS ix_logical_revision_source_sha256
                ON logical_message_revision_source(source_sha256);

            INSERT INTO schema_metadata(key, value)
            VALUES ('schema_version', '2')
            ON CONFLICT(key) DO UPDATE SET value = excluded.value;

            PRAGMA user_version = 2;
            """;
        command.ExecuteNonQuery();
        transaction.Commit();
    }
}
