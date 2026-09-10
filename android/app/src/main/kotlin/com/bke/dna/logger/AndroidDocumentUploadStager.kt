package com.bke.dna.logger

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.util.UUID

/**
 * Stages user-selected non-media documents into app cache before Gecko consumes them.
 *
 * Android document providers disagree on MIME labels for developer files such as
 * Markdown. A short-lived app-owned copy gives Gecko a stable readable content URI,
 * preserves the original display name, and avoids relying on provider quirks after
 * the picker Activity closes. These files are browser upload plumbing, never DNA
 * evidence, and are cleaned opportunistically by AndroidUserSelectedFileProvider.
 */
object AndroidDocumentUploadStager {
    fun shouldStage(context: Context, uri: Uri): Boolean {
        val type = context.contentResolver.getType(uri)?.lowercase().orEmpty()
        return !type.startsWith("image/") &&
            !type.startsWith("video/") &&
            !type.startsWith("audio/")
    }

    fun stage(context: Context, source: Uri): Uri? {
        val appContext = context.applicationContext
        val resolver = appContext.contentResolver
        val displayName = sanitizeDisplayName(resolveDisplayName(appContext, source))
        val mimeType = canonicalMimeType(resolver.getType(source), displayName)
        val extension = cacheExtension(displayName)
        val target = AndroidUserSelectedFileProvider.createStagedUploadFile(
            appContext,
            "upload-${UUID.randomUUID()}$extension",
        )

        return runCatching {
            resolver.openInputStream(source)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: error("Selected document does not expose an input stream")

            AndroidUserSelectedFileProvider.stagedUploadUri(
                appContext,
                target,
                displayName,
                mimeType,
            )
        }.getOrElse {
            target.delete()
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
            .replace('/', '_')
            .replace('\\', '_')
            .filterNot { it.code < 32 }
            .trim()
        return (cleaned.ifBlank { "upload" }).take(180)
    }

    private fun cacheExtension(displayName: String): String {
        val extension = displayName.substringAfterLast('.', "")
            .lowercase()
            .takeIf { SAFE_EXTENSION.matches(it) }
            ?: return ""
        return ".$extension"
    }

    private fun canonicalMimeType(providerType: String?, displayName: String): String {
        val extension = displayName.substringAfterLast('.', "").lowercase()
        if (extension == "md" || extension == "markdown") return "text/markdown"

        val normalizedProvider = providerType?.trim()?.lowercase()
        if (!normalizedProvider.isNullOrBlank() && normalizedProvider != "application/octet-stream") {
            return normalizedProvider
        }

        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
    }

    private val SAFE_EXTENSION = Regex("[a-z0-9]{1,16}")
}
