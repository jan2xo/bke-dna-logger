package com.bke.dna.logger

import android.content.Context
import android.util.JsonReader
import org.json.JSONObject
import java.io.File
import java.io.StringReader

/**
 * SQLite-first derivative access with transitional loose-file fallback.
 *
 * New large normalized conversation derivatives may use bounded SQLite chunks.
 * Existing PR5 inline TEXT rows and pre-PR5 loose JSON remain readable so saved
 * Working Data generations stay migration-safe.
 *
 * Latest is the live writable generation and must always use the process-wide
 * AndroidCaptureIndex pool. Only saved read-only generations are opened through
 * their standalone SQLite snapshot files.
 */
object AndroidDerivativeSourceAccess {
    fun readClassification(context: Context, sourceSha256: String): String? {
        val appContext = context.applicationContext
        val captureRoot = AndroidDnaPaths.capturesRoot(appContext)
        for (generation in AndroidWorkingDataManager(appContext).listWorkingData()) {
            if (generation.isLatest) {
                AndroidDerivativeStore(appContext).use { store ->
                    store.classificationJson(sourceSha256)?.let { return it }
                }
                legacyDerivative(captureRoot, "classifications", sourceSha256)?.let { return it }
            } else {
                readClassification(generation, captureRoot, sourceSha256)?.let { return it }
            }
        }
        return null
    }

    fun readNormalized(context: Context, sourceSha256: String): String? {
        val appContext = context.applicationContext
        val captureRoot = AndroidDnaPaths.capturesRoot(appContext)
        for (generation in AndroidWorkingDataManager(appContext).listWorkingData()) {
            if (generation.isLatest) {
                AndroidChunkedNormalizedStore(appContext).use { store ->
                    store.normalizedJson(sourceSha256)?.let { return it }
                }
                AndroidDerivativeStore(appContext).use { store ->
                    store.normalizedJson(sourceSha256)?.let { return it }
                }
                legacyDerivative(captureRoot, "normalized", sourceSha256)?.let { return it }
            } else {
                readNormalized(generation, captureRoot, sourceSha256)?.let { return it }
            }
        }
        return null
    }

    fun readNormalizedMetadata(
        context: Context,
        sourceSha256: String,
    ): AndroidNormalizedDerivativeMetadata? {
        val appContext = context.applicationContext
        val captureRoot = AndroidDnaPaths.capturesRoot(appContext)
        for (generation in AndroidWorkingDataManager(appContext).listWorkingData()) {
            if (generation.isLatest) {
                AndroidChunkedNormalizedStore(appContext).use { store ->
                    store.metadata(sourceSha256)?.let { metadata ->
                        return AndroidNormalizedDerivativeMetadata(
                            sourceSha256 = metadata.sourceSha256,
                            conversationNativeId = metadata.conversationNativeId,
                            normalizedAt = metadata.normalizedAt,
                            storage = "sqlite-chunked-v1",
                        )
                    }
                }
                AndroidDerivativeStore(appContext).use { store ->
                    store.normalizedJson(sourceSha256)?.let { payload ->
                        parseNormalizedMetadata(payload)?.let { return it.copy(storage = "sqlite-inline-v1") }
                    }
                }
            } else {
                AndroidChunkedNormalizedStore(generation).use { store ->
                    store.metadata(sourceSha256)?.let { metadata ->
                        return AndroidNormalizedDerivativeMetadata(
                            sourceSha256 = metadata.sourceSha256,
                            conversationNativeId = metadata.conversationNativeId,
                            normalizedAt = metadata.normalizedAt,
                            storage = "sqlite-chunked-v1",
                        )
                    }
                }
                AndroidDerivativeStore(generation).use { store ->
                    store.normalizedJson(sourceSha256)?.let { payload ->
                        parseNormalizedMetadata(payload)?.let { return it.copy(storage = "sqlite-inline-v1") }
                    }
                }
            }
            legacyDerivative(captureRoot, "normalized", sourceSha256)?.let { payload ->
                parseNormalizedMetadata(payload)?.let { return it.copy(storage = "legacy-file-v1") }
            }
        }
        return null
    }

    fun <T> withNormalizedJsonReader(
        context: Context,
        sourceSha256: String,
        block: (JsonReader) -> T,
    ): T? {
        val appContext = context.applicationContext
        val captureRoot = AndroidDnaPaths.capturesRoot(appContext)
        for (generation in AndroidWorkingDataManager(appContext).listWorkingData()) {
            if (generation.isLatest) {
                AndroidChunkedNormalizedStore(appContext).use { store ->
                    if (store.hasPayload(sourceSha256)) {
                        return store.withJsonReader(sourceSha256, block)
                    }
                }
                AndroidDerivativeStore(appContext).use { store ->
                    store.normalizedJson(sourceSha256)?.let { payload ->
                        return JsonReader(StringReader(payload)).use(block)
                    }
                }
            } else {
                AndroidChunkedNormalizedStore(generation).use { store ->
                    if (store.hasPayload(sourceSha256)) {
                        return store.withJsonReader(sourceSha256, block)
                    }
                }
                AndroidDerivativeStore(generation).use { store ->
                    store.normalizedJson(sourceSha256)?.let { payload ->
                        return JsonReader(StringReader(payload)).use(block)
                    }
                }
            }
            val legacy = File(captureRoot, "normalized/$sourceSha256.json")
            if (legacy.isFile) {
                legacy.bufferedReader(Charsets.UTF_8).use { reader ->
                    return JsonReader(reader).use(block)
                }
            }
        }
        return null
    }

    fun readClassification(
        generation: AndroidWorkingDataGeneration,
        captureRoot: File,
        sourceSha256: String,
    ): String? {
        AndroidDerivativeStore(generation).use { store ->
            store.classificationJson(sourceSha256)?.let { return it }
        }
        return legacyDerivative(captureRoot, "classifications", sourceSha256)
    }

    fun readNormalized(
        generation: AndroidWorkingDataGeneration,
        captureRoot: File,
        sourceSha256: String,
    ): String? {
        AndroidChunkedNormalizedStore(generation).use { store ->
            store.normalizedJson(sourceSha256)?.let { return it }
        }
        AndroidDerivativeStore(generation).use { store ->
            store.normalizedJson(sourceSha256)?.let { return it }
        }
        return legacyDerivative(captureRoot, "normalized", sourceSha256)
    }

    fun listNormalizedSourceSha256s(context: Context): List<String> {
        val appContext = context.applicationContext
        val captureRoot = AndroidDnaPaths.capturesRoot(appContext)
        val sources = linkedSetOf<String>()
        AndroidWorkingDataManager(appContext).listWorkingData().forEach { generation ->
            if (generation.isLatest) {
                AndroidChunkedNormalizedStore(appContext).use { store ->
                    sources += store.listSourceSha256s()
                }
                AndroidDerivativeStore(appContext).use { store ->
                    sources += store.listNormalizedSourceSha256s()
                }
                File(captureRoot, "normalized").listFiles().orEmpty()
                    .filter { it.isFile && it.extension == "json" }
                    .map { it.nameWithoutExtension }
                    .filter(SHA256::matches)
                    .sorted()
                    .forEach(sources::add)
            } else {
                sources += listNormalizedSourceSha256s(generation, captureRoot)
            }
        }
        return sources.sorted()
    }

    fun listNormalizedSourceSha256s(
        generation: AndroidWorkingDataGeneration,
        captureRoot: File,
    ): List<String> {
        val sources = linkedSetOf<String>()
        AndroidChunkedNormalizedStore(generation).use { store ->
            sources += store.listSourceSha256s()
        }
        AndroidDerivativeStore(generation).use { store ->
            sources += store.listNormalizedSourceSha256s()
        }
        File(captureRoot, "normalized").listFiles().orEmpty()
            .filter { it.isFile && it.extension == "json" }
            .map { it.nameWithoutExtension }
            .filter(SHA256::matches)
            .sorted()
            .forEach(sources::add)
        return sources.sorted()
    }

    private fun parseNormalizedMetadata(payload: String): AndroidNormalizedDerivativeMetadata? {
        val root = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        val sourceSha256 = root.optString("sourceSha256").takeIf(SHA256::matches) ?: return null
        val conversationNativeId = root.optString("conversationNativeId").trim().takeIf { it.isNotBlank() }
            ?: return null
        val normalizedAt = root.optString("normalizedAt").trim().takeIf { it.isNotBlank() }
            ?: return null
        return AndroidNormalizedDerivativeMetadata(
            sourceSha256 = sourceSha256,
            conversationNativeId = conversationNativeId,
            normalizedAt = normalizedAt,
            storage = "unknown",
        )
    }

    private fun legacyDerivative(
        captureRoot: File,
        directoryName: String,
        sourceSha256: String,
    ): String? {
        require(SHA256.matches(sourceSha256)) { "Expected lowercase SHA-256 source identity" }
        val file = File(captureRoot, "$directoryName/$sourceSha256.json")
        return if (file.isFile) file.readText(Charsets.UTF_8) else null
    }

    private val SHA256 = Regex("[0-9a-f]{64}")
}

data class AndroidNormalizedDerivativeMetadata(
    val sourceSha256: String,
    val conversationNativeId: String,
    val normalizedAt: String,
    val storage: String,
)
