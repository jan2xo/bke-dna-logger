package com.bke.dna.logger

import android.content.Context
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Resolves a conversation identity only from exact capture metadata already
 * persisted with a RAW source. The resolver never fabricates an identity:
 * conflicting metadata fails closed.
 */
object AndroidConversationSourceIdentity {
    fun resolve(context: Context, sourceSha256: String): AndroidConversationIdentityResolution? {
        require(SHA256.matches(sourceSha256)) { "Expected lowercase SHA-256 source identity" }

        val candidates = linkedMapOf<String, MutableSet<String>>()
        AndroidCaptureIndex(context.applicationContext).use { index ->
            index.readableDatabase.query(
                "captures",
                arrayOf("request_url", "page_url"),
                "sha256 = ?",
                arrayOf(sourceSha256),
                null,
                null,
                "stored_at DESC, capture_id DESC",
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val requestUrl = if (cursor.isNull(0)) null else cursor.getString(0)
                    val pageUrl = if (cursor.isNull(1)) null else cursor.getString(1)
                    extractFromRequestUrl(requestUrl)?.let { id ->
                        candidates.getOrPut(id) { linkedSetOf() }.add("request_url")
                    }
                    extractFromPageUrl(pageUrl)?.let { id ->
                        candidates.getOrPut(id) { linkedSetOf() }.add("page_url")
                    }
                }
            }
        }

        if (candidates.size != 1) return null
        val (conversationNativeId, bases) = candidates.entries.single()
        val basis = when {
            "request_url" in bases -> "capture_request_url"
            else -> "capture_page_url"
        }
        return AndroidConversationIdentityResolution(conversationNativeId, basis)
    }

    private fun extractFromRequestUrl(rawUrl: String?): String? {
        val uri = parseUri(rawUrl) ?: return null
        queryValue(uri.rawQuery, "conversation_id")?.let { return normalizeId(it) }
        queryValue(uri.rawQuery, "conversationId")?.let { return normalizeId(it) }

        val segments = pathSegments(uri)
        for (index in 0 until segments.lastIndex) {
            if (segments[index] != "conversation" && segments[index] != "conversations") continue
            if (index == 0 || segments[index - 1] != "backend-api") continue
            normalizeId(segments[index + 1])?.let { return it }
        }
        return null
    }

    private fun extractFromPageUrl(rawUrl: String?): String? {
        val uri = parseUri(rawUrl) ?: return null
        val segments = pathSegments(uri)
        for (index in 0 until segments.lastIndex) {
            if (segments[index] == "c") {
                normalizeId(segments[index + 1])?.let { return it }
            }
        }
        return null
    }

    private fun parseUri(rawUrl: String?): URI? {
        if (rawUrl.isNullOrBlank()) return null
        return runCatching { URI(rawUrl) }.getOrNull()
    }

    private fun pathSegments(uri: URI): List<String> = uri.path.orEmpty()
        .split('/')
        .filter { it.isNotBlank() }

    private fun queryValue(rawQuery: String?, name: String): String? {
        if (rawQuery.isNullOrBlank()) return null
        return rawQuery.split('&').firstNotNullOfOrNull { pair ->
            val separator = pair.indexOf('=')
            val rawName = if (separator >= 0) pair.substring(0, separator) else pair
            if (decode(rawName) != name) return@firstNotNullOfOrNull null
            val rawValue = if (separator >= 0) pair.substring(separator + 1) else ""
            decode(rawValue)
        }
    }

    private fun decode(value: String): String = runCatching {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }.getOrDefault(value)

    private fun normalizeId(value: String?): String? {
        val candidate = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return candidate.takeIf { CONVERSATION_ID.matches(it) }
    }

    private val SHA256 = Regex("[0-9a-f]{64}")
    private val CONVERSATION_ID = Regex("[A-Za-z0-9][A-Za-z0-9_-]{7,127}")
}

data class AndroidConversationIdentityResolution(
    val conversationNativeId: String,
    val basis: String,
)
