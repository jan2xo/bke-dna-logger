package com.bke.dna.logger

import android.content.Context
import java.io.File

/**
 * SQLite-first derivative access with transitional loose-file fallback.
 *
 * New captures write only to Working Data SQLite. Context-level reads federate
 * Latest plus every saved read-only generation so reconciliation keeps the
 * same multi-snapshot behavior that the former shared loose directories had.
 * Pre-PR5 generations remain readable from shared `classifications/*.json` /
 * `normalized/*.json` files if those legacy files still exist.
 */
object AndroidDerivativeSourceAccess {
    fun readClassification(context: Context, sourceSha256: String): String? {
        val appContext = context.applicationContext
        val captureRoot = AndroidDnaPaths.capturesRoot(appContext)
        for (generation in AndroidWorkingDataManager(appContext).listWorkingData()) {
            readClassification(generation, captureRoot, sourceSha256)?.let { return it }
        }
        return null
    }

    fun readNormalized(context: Context, sourceSha256: String): String? {
        val appContext = context.applicationContext
        val captureRoot = AndroidDnaPaths.capturesRoot(appContext)
        for (generation in AndroidWorkingDataManager(appContext).listWorkingData()) {
            readNormalized(generation, captureRoot, sourceSha256)?.let { return it }
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
            sources += listNormalizedSourceSha256s(generation, captureRoot)
        }
        return sources.sorted()
    }

    fun listNormalizedSourceSha256s(
        generation: AndroidWorkingDataGeneration,
        captureRoot: File,
    ): List<String> {
        val sources = linkedSetOf<String>()
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
