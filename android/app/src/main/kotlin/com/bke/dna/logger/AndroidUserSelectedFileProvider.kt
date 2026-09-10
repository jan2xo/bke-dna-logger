package com.bke.dna.logger

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.util.UUID

/**
 * Temporary content provider for user-initiated browser uploads.
 *
 * Camera captures and staged non-media documents live only in app cache. They
 * never enter the DNA capture runtime and are opportunistically removed after
 * one day. Normal photo/video selections continue to use their original picker
 * content URIs and are not copied here.
 */
class AndroidUserSelectedFileProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.let(::cleanupStaleFiles)
        return true
    }

    override fun getType(uri: Uri): String {
        val file = resolveFile(uri)
        val explicit = uri.getQueryParameter(MIME_QUERY)
            ?.trim()
            ?.lowercase()
            ?.takeIf { '/' in it && it != "application/octet-stream" }
        if (explicit != null) return explicit

        val displayName = displayName(uri, file)
        val extension = displayName.substringAfterLast('.', "").lowercase()
        if (extension == "md" || extension == "markdown") return "text/markdown"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: when (file.extension.lowercase()) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "webp" -> "image/webp"
                "mp4" -> "video/mp4"
                else -> "application/octet-stream"
            }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val file = resolveFile(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = arrayOfNulls<Any>(columns.size)
        columns.forEachIndexed { index, column ->
            row[index] = when (column) {
                OpenableColumns.DISPLAY_NAME -> displayName(uri, file)
                OpenableColumns.SIZE -> file.length()
                else -> null
            }
        }
        return MatrixCursor(columns, 1).apply {
            addRow(row)
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = resolveFile(uri)
        val flags = when (mode) {
            "r" -> ParcelFileDescriptor.MODE_READ_ONLY
            "w", "wt" -> ParcelFileDescriptor.MODE_WRITE_ONLY or
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
            "wa" -> ParcelFileDescriptor.MODE_WRITE_ONLY or
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_APPEND
            "rw" -> ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE
            "rwt" -> ParcelFileDescriptor.MODE_READ_WRITE or
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
            else -> throw IllegalArgumentException("Unsupported provider mode '$mode'")
        }
        return ParcelFileDescriptor.open(file, flags)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Browser upload provider does not support insert")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("Browser upload provider does not support update")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        if (resolveFile(uri).delete()) 1 else 0

    private fun resolveFile(uri: Uri): File {
        val providerContext = context ?: error("Provider context unavailable")
        require(uri.authority == authority(providerContext)) { "Unexpected provider authority" }
        val segments = uri.pathSegments
        require(segments.size >= 2) { "Unexpected provider path" }

        val name = segments[1]
        when (segments[0]) {
            CAMERA_PATH -> require(CAMERA_FILE_NAME.matches(name)) { "Invalid camera upload name" }
            STAGED_PATH -> require(STAGED_FILE_NAME.matches(name)) { "Invalid staged upload name" }
            else -> error("Unexpected provider path")
        }

        val root = cacheDirectory(providerContext).canonicalFile
        val file = File(root, name).canonicalFile
        require(file.parentFile == root) { "Browser upload escaped cache root" }
        return file
    }

    private fun displayName(uri: Uri, file: File): String {
        if (uri.pathSegments.firstOrNull() != STAGED_PATH) return file.name
        return uri.pathSegments.getOrNull(2)?.takeIf { it.isNotBlank() } ?: file.name
    }

    companion object {
        private const val CAMERA_PATH = "camera"
        private const val STAGED_PATH = "staged"
        private const val MIME_QUERY = "mime"
        private const val MAX_CACHE_AGE_MS = 24L * 60L * 60L * 1000L
        private val CAMERA_FILE_NAME = Regex("capture-[0-9a-f-]{36}\\.(jpg|mp4)")
        private val STAGED_FILE_NAME = Regex("upload-[0-9a-f-]{36}(\\.[a-z0-9]{1,16})?")

        fun createCameraUri(context: Context, mimeType: String): Uri {
            val appContext = context.applicationContext
            cleanupStaleFiles(appContext)
            val extension = when {
                mimeType.startsWith("video/") -> "mp4"
                else -> "jpg"
            }
            val file = File(cacheDirectory(appContext), "capture-${UUID.randomUUID()}.$extension")
            check(file.createNewFile()) { "Unable to create temporary camera upload" }
            return Uri.Builder()
                .scheme("content")
                .authority(authority(appContext))
                .appendPath(CAMERA_PATH)
                .appendPath(file.name)
                .build()
        }

        fun createStagedUploadFile(context: Context, name: String): File {
            val appContext = context.applicationContext
            require(STAGED_FILE_NAME.matches(name)) { "Invalid staged upload name" }
            cleanupStaleFiles(appContext)
            return File(cacheDirectory(appContext), name).also { file ->
                check(!file.exists()) { "Staged upload already exists" }
            }
        }

        fun stagedUploadUri(
            context: Context,
            file: File,
            displayName: String,
            mimeType: String,
        ): Uri {
            val appContext = context.applicationContext
            require(STAGED_FILE_NAME.matches(file.name)) { "Invalid staged upload file" }
            require(file.parentFile?.canonicalFile == cacheDirectory(appContext).canonicalFile) {
                "Staged upload outside browser cache"
            }
            return Uri.Builder()
                .scheme("content")
                .authority(authority(appContext))
                .appendPath(STAGED_PATH)
                .appendPath(file.name)
                .appendPath(displayName)
                .appendQueryParameter(MIME_QUERY, mimeType)
                .build()
        }

        fun deleteIfOwned(context: Context, uri: Uri?) {
            if (uri == null || uri.authority != authority(context.applicationContext)) return
            runCatching {
                val segments = uri.pathSegments
                val name = segments.getOrNull(1) ?: return@runCatching
                val owned = when (segments.firstOrNull()) {
                    CAMERA_PATH -> CAMERA_FILE_NAME.matches(name)
                    STAGED_PATH -> STAGED_FILE_NAME.matches(name)
                    else -> false
                }
                if (!owned) return@runCatching
                File(cacheDirectory(context.applicationContext), name).delete()
            }
        }

        fun cleanupStaleFiles(context: Context) {
            val cutoff = System.currentTimeMillis() - MAX_CACHE_AGE_MS
            cacheDirectory(context.applicationContext).listFiles().orEmpty()
                .filter { file ->
                    file.isFile &&
                        (CAMERA_FILE_NAME.matches(file.name) || STAGED_FILE_NAME.matches(file.name)) &&
                        file.lastModified() < cutoff
                }
                .forEach(File::delete)
        }

        private fun cacheDirectory(context: Context): File =
            File(context.cacheDir, "user-selected").also { directory ->
                check(directory.exists() || directory.mkdirs()) {
                    "Unable to create temporary user-selected directory"
                }
            }

        private fun authority(context: Context): String =
            "${context.packageName}.user-selected-files"
    }
}
