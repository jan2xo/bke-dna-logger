package com.bke.dna.logger

import android.database.sqlite.SQLiteDatabase

/** Physical-vs-logical RAW storage telemetry for one Working Data generation. */
object AndroidWorkingDataTelemetry {
    fun inspect(generation: AndroidWorkingDataGeneration): AndroidWorkingDataStorageTelemetry {
        val database = SQLiteDatabase.openDatabase(
            generation.databaseFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
        database.use {
            if (!hasTable(it, "raw_source")) return AndroidWorkingDataStorageTelemetry.EMPTY
            it.rawQuery(
                """
                SELECT COUNT(*),
                       COALESCE(SUM(byte_length), 0),
                       COALESCE(SUM(compressed_bytes), 0)
                FROM raw_source
                """.trimIndent(),
                null,
            ).use { cursor ->
                check(cursor.moveToFirst()) { "Unable to read Working Data RAW telemetry" }
                return AndroidWorkingDataStorageTelemetry(
                    verifiedSourceCount = cursor.getInt(0),
                    exactRawBytes = cursor.getLong(1),
                    compressedRawPayloadBytes = cursor.getLong(2),
                )
            }
        }
    }

    private fun hasTable(database: SQLiteDatabase, table: String): Boolean = database.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
        arrayOf(table),
    ).use { it.moveToFirst() }
}

data class AndroidWorkingDataStorageTelemetry(
    val verifiedSourceCount: Int,
    val exactRawBytes: Long,
    val compressedRawPayloadBytes: Long,
) {
    companion object {
        val EMPTY = AndroidWorkingDataStorageTelemetry(0, 0L, 0L)
    }
}
