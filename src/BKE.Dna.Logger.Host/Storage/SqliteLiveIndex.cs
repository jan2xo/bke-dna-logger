using Microsoft.Data.Sqlite;

namespace BKE.Dna.Logger.Host.Storage;

internal sealed class SqliteLiveIndex : IDisposable
{
    private const int SchemaVersion = 1;
    private readonly SqliteConnection _connection;

    private SqliteLiveIndex(SqliteConnection connection)
    {
        _connection = connection;
    }

    public string DatabasePath => _connection.DataSource;

    public static SqliteLiveIndex? TryOpen(string captureRoot)
    {
        try
        {
            var root = Path.GetFullPath(captureRoot);
            Directory.CreateDirectory(root);
            var databasePath = Path.Combine(root, "dna.sqlite3");
            var connection = new SqliteConnection(new SqliteConnectionStringBuilder
            {
                DataSource = databasePath,
                Mode = SqliteOpenMode.ReadWriteCreate,
                Cache = SqliteCacheMode.Shared
            }.ToString());
            connection.Open();

            var index = new SqliteLiveIndex(connection);
            index.Initialize();
            return index;
        }
        catch (Exception error)
        {
            Console.Error.WriteLine($"BKE DNA SQLite live index unavailable; raw capture remains enabled: {error.Message}");
            return null;
        }
    }

    public void Dispose() => _connection.Dispose();

    private void Initialize()
    {
        ExecuteNonQuery("PRAGMA foreign_keys = ON;");
        ExecuteNonQuery("PRAGMA busy_timeout = 5000;");
        ExecuteScalar("PRAGMA journal_mode = WAL;");

        using var transaction = _connection.BeginTransaction();
        using var command = _connection.CreateCommand();
        command.Transaction = transaction;
        command.CommandText = """
            CREATE TABLE IF NOT EXISTS schema_metadata (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS raw_capture (
                sha256 TEXT PRIMARY KEY,
                byte_length INTEGER NOT NULL CHECK (byte_length >= 0),
                content_type TEXT,
                body_path TEXT NOT NULL,
                first_seen_at TEXT NOT NULL,
                last_seen_at TEXT NOT NULL,
                seen_count INTEGER NOT NULL DEFAULT 1 CHECK (seen_count >= 1)
            );

            CREATE TABLE IF NOT EXISTS capture_observation (
                capture_id TEXT PRIMARY KEY,
                raw_sha256 TEXT NOT NULL REFERENCES raw_capture(sha256) ON DELETE RESTRICT,
                page_url TEXT NOT NULL,
                request_url_sha256 TEXT NOT NULL,
                method TEXT NOT NULL,
                status INTEGER NOT NULL,
                initiator TEXT NOT NULL,
                captured_at TEXT NOT NULL,
                fidelity TEXT NOT NULL,
                stored_at TEXT NOT NULL
            );

            CREATE INDEX IF NOT EXISTS ix_capture_observation_raw_sha256
                ON capture_observation(raw_sha256);
            CREATE INDEX IF NOT EXISTS ix_capture_observation_page_url
                ON capture_observation(page_url);

            CREATE TABLE IF NOT EXISTS conversation_snapshot (
                source_sha256 TEXT PRIMARY KEY REFERENCES raw_capture(sha256) ON DELETE RESTRICT,
                conversation_native_id TEXT,
                current_node_native_id TEXT,
                coverage_status TEXT NOT NULL,
                coverage_basis TEXT NOT NULL,
                normalized_path TEXT NOT NULL,
                normalized_at TEXT NOT NULL
            );

            CREATE INDEX IF NOT EXISTS ix_conversation_snapshot_native_id
                ON conversation_snapshot(conversation_native_id);

            CREATE TABLE IF NOT EXISTS message_node (
                source_sha256 TEXT NOT NULL REFERENCES conversation_snapshot(source_sha256) ON DELETE RESTRICT,
                node_native_id TEXT NOT NULL,
                message_native_id TEXT,
                parent_native_id TEXT,
                role TEXT,
                created_at TEXT,
                content_json TEXT,
                PRIMARY KEY (source_sha256, node_native_id)
            );

            CREATE INDEX IF NOT EXISTS ix_message_node_native_message
                ON message_node(message_native_id);

            CREATE TABLE IF NOT EXISTS message_edge (
                source_sha256 TEXT NOT NULL REFERENCES conversation_snapshot(source_sha256) ON DELETE RESTRICT,
                parent_node_native_id TEXT NOT NULL,
                child_node_native_id TEXT NOT NULL,
                PRIMARY KEY (source_sha256, parent_node_native_id, child_node_native_id)
            );

            CREATE TABLE IF NOT EXISTS message_revision (
                revision_sha256 TEXT PRIMARY KEY,
                source_sha256 TEXT NOT NULL REFERENCES conversation_snapshot(source_sha256) ON DELETE RESTRICT,
                node_native_id TEXT NOT NULL,
                message_native_id TEXT,
                content_json TEXT NOT NULL,
                observed_at TEXT NOT NULL
            );

            CREATE INDEX IF NOT EXISTS ix_message_revision_message_native_id
                ON message_revision(message_native_id);

            CREATE TABLE IF NOT EXISTS dom_witness (
                witness_id TEXT PRIMARY KEY,
                page_url TEXT NOT NULL,
                observed_at TEXT NOT NULL,
                fingerprint_sha256 TEXT NOT NULL,
                witness_path TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS reconciliation (
                witness_id TEXT PRIMARY KEY REFERENCES dom_witness(witness_id) ON DELETE RESTRICT,
                status TEXT NOT NULL,
                reconciliation_path TEXT NOT NULL,
                reconciled_at TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS durability_state (
                raw_sha256 TEXT PRIMARY KEY REFERENCES raw_capture(sha256) ON DELETE RESTRICT,
                dna_archive_id TEXT,
                dna_archive_sha256 TEXT,
                archived_at TEXT,
                clearable INTEGER NOT NULL DEFAULT 0 CHECK (clearable IN (0, 1)),
                CHECK (
                    clearable = 0 OR (
                        dna_archive_id IS NOT NULL AND
                        dna_archive_sha256 IS NOT NULL AND
                        archived_at IS NOT NULL
                    )
                )
            );

            INSERT INTO schema_metadata(key, value)
            VALUES ('schema_version', $schemaVersion)
            ON CONFLICT(key) DO UPDATE SET value = excluded.value;

            PRAGMA user_version = 1;
            """;
        command.Parameters.AddWithValue("$schemaVersion", SchemaVersion.ToString());
        command.ExecuteNonQuery();
        transaction.Commit();
    }

    private void ExecuteNonQuery(string sql)
    {
        using var command = _connection.CreateCommand();
        command.CommandText = sql;
        command.ExecuteNonQuery();
    }

    private object? ExecuteScalar(string sql)
    {
        using var command = _connection.CreateCommand();
        command.CommandText = sql;
        return command.ExecuteScalar();
    }
}
