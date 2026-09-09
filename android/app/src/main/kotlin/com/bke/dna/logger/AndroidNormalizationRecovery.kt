package com.bke.dna.logger

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import org.json.JSONObject
import java.time.Instant

/**
 * One-shot semantic recovery migrations for already durable SQLite RAW.
 *
 * Alpha.4 repaired the stale loose-classification dispatcher path. Alpha.5 adds
 * explicit modern message-envelope and event-stream normalizers. Each migration
 * only re-arms DONE conversation candidates that still lack a normalized
 * derivative; RAW is never recaptured, deleted, or rewritten here.
 */
object AndroidNormalizationRecovery {
    private const val TAG = "BkeDnaRecovery"
    private const val PREFS = "bke-dna-processing"
    private const val PREF_SQLITE_CLASSIFICATION_RECOVERY = "sqlite-classification-dispatcher-v1"
    private const val PREF_MODERN_MESSAGES_RECOVERY = "modern-messages-normalizer-v2"
    private const val CANDIDATE_KIND = "conversation_payload_candidate"
    private const val STATUS_DONE = "DONE"
    private const val STATUS_WAITING = "WAITING"
    private const val STAGE_SQLITE_RECOVERED = "RECOVERED_SQLITE_CLASSIFICATION"
    private const val STAGE_MODERN_MESSAGES_RECOVERED = "RECOVERED_MODERN_MESSAGES"

    fun rearmOnce(context: Context): Int {
        val appContext = context.applicationContext
        val preferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var recoveredTotal = 0

        if (!preferences.getBoolean(PREF_SQLITE_CLASSIFICATION_RECOVERY, false)) {
            val recovered = rearmEligibleCandidates(appContext, STAGE_SQLITE_RECOVERED)
            persistMarker(preferences, PREF_SQLITE_CLASSIFICATION_RECOVERY)
            if (recovered > 0) {
                Log.d(TAG, "BKE DNA recovery: rearmed_sqlite_classification_candidates")
            }
            recoveredTotal += recovered
        }

        if (!preferences.getBoolean(PREF_MODERN_MESSAGES_RECOVERY, false)) {
            val recovered = rearmEligibleCandidates(appContext, STAGE_MODERN_MESSAGES_RECOVERED)
            persistMarker(preferences, PREF_MODERN_MESSAGES_RECOVERY)
            if (recovered > 0) {
                Log.d(TAG, "BKE DNA recovery: rearmed_modern_message_candidates")
            }
            recoveredTotal += recovered
        }

        return recoveredTotal
    }

    private fun rearmEligibleCandidates(context: Context, recoveredStage: String): Int =
        AndroidCaptureIndex(context).use { index ->
            val database = index.writableDatabase
            if (!hasTable(database, "derivation_queue") ||
                !hasTable(database, "derivative_classification") ||
                !hasTable(database, "derivative_normalized")
            ) {
                0
            } else {
                rearmCandidates(database, recoveredStage)
            }
        }

    private fun persistMarker(
        preferences: android.content.SharedPreferences,
        marker: String,
    ) {
        check(
            preferences.edit()
                .putBoolean(marker, true)
                .commit(),
        ) { "Unable to persist normalization recovery marker" }
    }

    private fun rearmCandidates(database: SQLiteDatabase, recoveredStage: String): Int {
        val candidateSources = mutableListOf<String>()
        database.rawQuery(
            """
            SELECT q.source_sha256, c.payload_json
            FROM derivation_queue q
            INNER JOIN derivative_classification c
                ON c.source_sha256 = q.source_sha256
            LEFT JOIN derivative_normalized n
                ON n.source_sha256 = q.source_sha256
            WHERE q.status = ?
              AND n.source_sha256 IS NULL
            ORDER BY q.created_at ASC
            """.trimIndent(),
            arrayOf(STATUS_DONE),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val classification = runCatching {
                    JSONObject(cursor.getString(1)).getJSONObject("classification")
                }.getOrNull() ?: continue
                if (classification.optString("kind") == CANDIDATE_KIND) {
                    candidateSources += cursor.getString(0)
                }
            }
        }
        if (candidateSources.isEmpty()) return 0

        var recovered = 0
        database.beginTransaction()
        try {
            candidateSources.distinct().forEach { sourceSha256 ->
                val values = ContentValues().apply {
                    put("status", STATUS_WAITING)
                    put("stage", recoveredStage)
                    putNull("last_error_code")
                    put("updated_at", Instant.now().toString())
                }
                recovered += database.update(
                    "derivation_queue",
                    values,
                    "source_sha256 = ? AND status = ?",
                    arrayOf(sourceSha256, STATUS_DONE),
                )
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        return recovered
    }

    private fun hasTable(database: SQLiteDatabase, table: String): Boolean = database.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
        arrayOf(table),
    ).use { it.moveToFirst() }
}
