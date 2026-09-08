package com.bke.dna.logger

import org.json.JSONObject
import java.nio.charset.StandardCharsets

/**
 * Kotlin implementation of the platform-neutral BKE DNA wire contract.
 * Windows/macOS implement the same contract in .NET; Android does not load the
 * .NET runtime just to share implementation code.
 */
object DnaWireContract {
    const val MAX_MESSAGE_BYTES: Int = 2 * 1024 * 1024

    private val allowedTypes = setOf(
        "capture_start",
        "capture_chunk",
        "capture_end",
        "dom_witness",
    )

    fun validate(rawMessage: ByteArray): String {
        require(rawMessage.size <= MAX_MESSAGE_BYTES) {
            "DNA wire message exceeds $MAX_MESSAGE_BYTES bytes"
        }

        val json = JSONObject(String(rawMessage, StandardCharsets.UTF_8))
        val type = json.optString("type")
        require(type in allowedTypes) { "Unsupported DNA wire type: $type" }
        return type
    }
}
