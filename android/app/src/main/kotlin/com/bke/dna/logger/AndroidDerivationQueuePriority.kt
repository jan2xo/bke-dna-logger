package com.bke.dna.logger

import android.content.ContentValues
import android.content.Context

/**
 * Raises the existing durable derivation-queue priority from capture-route evidence.
 *
 * Priority changes scheduling only. RAW evidence, classification admission, and
 * normalization semantics remain unchanged. Promotions happen while the capture
 * is still active, before the single derivation worker can claim the new row.
 */
object AndroidDerivationQueuePriority {
    fun promote(context: Context, sourceSha256: String, priority: Int) {
        require(SHA256.matches(sourceSha256)) { "Expected lowercase SHA-256 source identity" }
        require(priority >= 0) { "Queue priority cannot be negative" }
        if (priority == 0) return

        AndroidCaptureIndex(context.applicationContext).use { index ->
            val values = ContentValues().apply {
                put("priority", priority)
            }
            index.writableDatabase.update(
                "derivation_queue",
                values,
                "source_sha256 = ? AND priority < ?",
                arrayOf(sourceSha256, priority.toString()),
            )
        }
    }

    private val SHA256 = Regex("[0-9a-f]{64}")
}
