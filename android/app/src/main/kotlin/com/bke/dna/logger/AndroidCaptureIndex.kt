package com.bke.dna.logger

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Live Android SQLite index. Raw files remain the evidence; this is a projection. */
class AndroidCaptureIndex(context: Context) :
    SQLiteOpenHelper(context, "dna/live.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE captures (
                capture_id TEXT PRIMARY KEY,
                sha256 TEXT NOT NULL,
                byte_length INTEGER NOT NULL,
                body_path TEXT NOT NULL,
                page_url TEXT,
                request_url TEXT,
                method TEXT,
                status INTEGER,
                content_type TEXT,
                initiator TEXT,
                captured_at TEXT,
                fidelity TEXT,
                stored_at TEXT NOT NULL,
                dna_archived INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX captures_sha256_idx ON captures(sha256)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun record(observation: JSONObjectObservation) {
        val values = ContentValues().apply {
            put("capture_id", observation.captureId)
            put("sha256", observation.sha256)
            put("byte_length", observation.byteLength)
            put("body_path", observation.bodyPath)
            put("page_url", observation.pageUrl)
            put("request_url", observation.requestUrl)
            put("method", observation.method)
            if (observation.status != null) put("status", observation.status)
            put("content_type", observation.contentType)
            put("initiator", observation.initiator)
            put("captured_at", observation.capturedAt)
            put("fidelity", observation.fidelity)
            put("stored_at", observation.storedAt)
            put("dna_archived", 0)
        }
        writableDatabase.insertOrThrow("captures", null, values)
    }
}

data class JSONObjectObservation(
    val captureId: String,
    val sha256: String,
    val byteLength: Long,
    val bodyPath: String,
    val pageUrl: String?,
    val requestUrl: String?,
    val method: String?,
    val status: Int?,
    val contentType: String?,
    val initiator: String?,
    val capturedAt: String?,
    val fidelity: String?,
    val storedAt: String,
)
