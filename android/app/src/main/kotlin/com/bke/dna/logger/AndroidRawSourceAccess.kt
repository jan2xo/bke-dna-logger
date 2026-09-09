package com.bke.dna.logger

import android.content.Context
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * Resolves exact RAW bytes by source SHA without exposing the storage backend.
 *
 * New Working Data generations prefer RAW stored inside SQLite. Historical
 * generations that predate raw_source/raw_source_chunk remain readable through
 * the legacy shared captures/bodies/<sha>.body fallback while those files exist.
 */
object AndroidRawSourceAccess {
    fun readAllBytes(
        context: Context,
        sourceSha256: String,
        maxBytes: Long,
    ): ByteArray? {
        val appContext = context.applicationContext
        AndroidRawEvidenceStore(appContext).use { store ->
            if (store.contains(sourceSha256)) {
                return store.readAllBytes(sourceSha256, maxBytes)
            }
        }
        return readLegacyAllBytes(
            File(AndroidDnaPaths.capturesRoot(appContext), "bodies/$sourceSha256.body"),
            maxBytes,
        )
    }

    fun readAllBytes(
        generation: AndroidWorkingDataGeneration,
        captureRoot: File,
        sourceSha256: String,
        maxBytes: Long,
    ): ByteArray? {
        AndroidRawEvidenceStore(generation).use { store ->
            if (store.contains(sourceSha256)) {
                return store.readAllBytes(sourceSha256, maxBytes)
            }
        }
        return readLegacyAllBytes(File(captureRoot, "bodies/$sourceSha256.body"), maxBytes)
    }

    /**
     * Keeps the backing RAW store open for the entire callback so large sources
     * can be consumed incrementally without ever materializing the whole body.
     */
    fun <T> withExactInputStream(
        context: Context,
        sourceSha256: String,
        block: (InputStream) -> T,
    ): T? {
        val appContext = context.applicationContext
        AndroidRawEvidenceStore(appContext).use { store ->
            if (store.contains(sourceSha256)) {
                store.openExactInputStream(sourceSha256).use { input ->
                    return block(input)
                }
            }
        }

        val body = File(AndroidDnaPaths.capturesRoot(appContext), "bodies/$sourceSha256.body")
        if (!body.isFile) return null
        body.inputStream().buffered().use { input -> return block(input) }
    }

    fun readPage(
        generation: AndroidWorkingDataGeneration,
        captureRoot: File,
        sourceSha256: String,
        byteOffset: Long,
        maxBytes: Int,
    ): AndroidRawResolvedPage? {
        require(byteOffset >= 0L)
        require(maxBytes > 0)
        AndroidRawEvidenceStore(generation).use { store ->
            val descriptor = store.descriptor(sourceSha256)
            if (descriptor != null) {
                return AndroidRawResolvedPage(
                    bytes = store.readPage(sourceSha256, byteOffset, maxBytes),
                    sourceLength = descriptor.byteLength,
                    backend = AndroidRawBackend.SQLITE,
                )
            }
        }

        val body = File(captureRoot, "bodies/$sourceSha256.body")
        if (!body.isFile) return null
        val length = body.length()
        if (byteOffset >= length) {
            return AndroidRawResolvedPage(ByteArray(0), length, AndroidRawBackend.LEGACY_BODY)
        }
        RandomAccessFile(body, "r").use { file ->
            file.seek(byteOffset)
            val count = minOf(maxBytes.toLong(), length - byteOffset).toInt()
            val bytes = ByteArray(count)
            val read = file.read(bytes)
            return AndroidRawResolvedPage(
                bytes = if (read <= 0) ByteArray(0) else bytes.copyOf(read),
                sourceLength = length,
                backend = AndroidRawBackend.LEGACY_BODY,
            )
        }
    }

    /**
     * Portable-export helper. A logical conversation may span Working Data
     * rotations, so source lookup must federate Latest plus saved generations.
     */
    fun writeExactSource(
        context: Context,
        sourceSha256: String,
        output: OutputStream,
    ): Boolean {
        val appContext = context.applicationContext
        val captureRoot = AndroidDnaPaths.capturesRoot(appContext)
        for (generation in AndroidWorkingDataManager(appContext).listWorkingData()) {
            if (writeExactSource(generation, captureRoot, sourceSha256, output)) return true
        }
        return false
    }

    fun writeExactSource(
        generation: AndroidWorkingDataGeneration,
        captureRoot: File,
        sourceSha256: String,
        output: OutputStream,
    ): Boolean {
        AndroidRawEvidenceStore(generation).use { store ->
            if (store.contains(sourceSha256)) {
                store.writeExactSource(sourceSha256, output)
                return true
            }
        }
        val body = File(captureRoot, "bodies/$sourceSha256.body")
        if (!body.isFile) return false
        body.inputStream().buffered().use { input -> input.copyTo(output, 128 * 1024) }
        return true
    }

    fun sourceLength(
        generation: AndroidWorkingDataGeneration,
        captureRoot: File,
        sourceSha256: String,
    ): Long? {
        AndroidRawEvidenceStore(generation).use { store ->
            store.descriptor(sourceSha256)?.let { return it.byteLength }
        }
        return File(captureRoot, "bodies/$sourceSha256.body").takeIf { it.isFile }?.length()
    }

    private fun readLegacyAllBytes(body: File, maxBytes: Long): ByteArray? {
        require(maxBytes in 0..Int.MAX_VALUE.toLong())
        if (!body.isFile) return null
        require(body.length() <= maxBytes) { "RAW source exceeds bounded read limit" }
        return body.readBytes()
    }
}

data class AndroidRawResolvedPage(
    val bytes: ByteArray,
    val sourceLength: Long,
    val backend: AndroidRawBackend,
)

enum class AndroidRawBackend {
    SQLITE,
    LEGACY_BODY,
}
