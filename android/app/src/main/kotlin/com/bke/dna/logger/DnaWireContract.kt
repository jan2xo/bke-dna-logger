package com.bke.dna.logger

import org.json.JSONObject
import java.nio.charset.StandardCharsets

/** Platform-neutral DNA wire validation implemented natively for Android. */
object DnaWireContract {
    const val MAX_MESSAGE_BYTES: Int = 2 * 1024 * 1024

    private val allowedTypes = setOf(
        "capture_start",
        "capture_chunk",
        "capture_end",
        "capture_abort",
        "dom_witness",
    )

    private val forbiddenMetadataKeys = setOf(
        "authorization",
        "cookie",
        "cookies",
        "requestHeaders",
        "responseHeaders",
        "headers",
    )

    fun parse(rawMessage: ByteArray): JSONObject {
        require(rawMessage.size <= MAX_MESSAGE_BYTES) {
            "DNA wire message exceeds $MAX_MESSAGE_BYTES bytes"
        }

        val json = JSONObject(String(rawMessage, StandardCharsets.UTF_8))
        val type = json.optString("type")
        require(type in allowedTypes) { "Unsupported DNA wire type: $type" }

        forbiddenMetadataKeys.forEach { key ->
            require(!json.has(key)) { "Forbidden sensitive metadata field: $key" }
        }

        return json
    }

    fun validate(rawMessage: ByteArray): String = parse(rawMessage).getString("type")
}
