package com.bke.dna.logger

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID

/**
 * Stages user-selected non-media documents into app cache before Gecko consumes them.
 *
 * Gecko's file-prompt bridge ultimately needs a real filesystem path. Android
 * document providers normally return content:// URIs, so developer files such as
 * Markdown are copied into a short-lived app-cache file and returned to Gecko as
 * a file:// URI. These files are browser upload plumbing, never DNA evidence.
 */
object AndroidDocumentUploadStager {
    private const val MAX_CACHE_AGE_MS = 24L * 60L * 60L * 1000L

    fun shouldStage(context: Context, uri: Uri): Boolean {
        val type = context.contentResolver.getType(uri)?.lowercase().orEmpty()
        return !type.startsWith("image/") &&
            !type.startsWith("video/") &&
            !type.startsWith("audio/")
    }

    fun stage(context: Context, source: Uri): Uri? {
        val appContext = context.applicationContext
        val resolver = appContext.contentResolver
        cleanupStaleFiles(appContext)

        val displayName = sanitizeDisplayName(resolveDisplayName(appContext, source))
        val directory = File(cacheRoot(appContext), UUID.randomUUID().toString())
        if (!directory.mkdir()) return null
        val target = File(directory, displayName)

        return runCatching {
            require(target.canonicalFile.parentFile == directory.canonicalFile) {
                "Staged upload escaped browser cache"
            }
            resolver.openInputStream(source)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: error("Selected document does not expose an input stream")

            Uri.fromFile(target)
        }.getOrElse {
            directory.deleteRecursively()
            null
        }
    }

    private fun resolveDisplayName(context: Context, uri: Uri): String {
        val resolver = context.contentResolver
        return runCatching {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "upload"
    }

    private fun sanitizeDisplayName(value: String): String {
        val cleaned = value
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('_')
            .take(180)
        return cleaned
            .takeIf { it.isNotBlank() && it != "." && it != ".." }
            ?: "upload"
    }

    private fun cleanupStaleFiles(context: Context) {
        val cutoff = System.currentTimeMillis() - MAX_CACHE_AGE_MS
        cacheRoot(context).listFiles().orEmpty()
            .filter { it.isDirectory && it.lastModified() < cutoff }
            .forEach(File::deleteRecursively)
    }

    private fun cacheRoot(context: Context): File =
        File(context.cacheDir, "gecko-upload").also { directory ->
            check(directory.exists() || directory.mkdirs()) {
                "Unable to create Gecko upload cache"
            }
        }
}
