package com.bke.dna.logger

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID

/**
 * Temporary content provider for user-initiated camera uploads.
 *
 * These files are browser upload plumbing, not DNA evidence. They live only in
 * app cache, never enter the DNA capture runtime, and are opportunistically
 * removed after one day. Gallery/document selections are never copied here.
 */
class AndroidUserSelectedFileProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.let(::cleanupStaleFiles)
        return true
    }

    override fun getType(uri: Uri): String = when (resolveFile(uri).extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "mp4" -> "video/mp4"
        else -> "application/octet-stream"
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
                OpenableColumns.DISPLAY_NAME -> file.name
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
        throw UnsupportedOperationException("Camera upload provider does not support insert")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("Camera upload provider does not support update")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        if (resolveFile(uri).delete()) 1 else 0

    private fun resolveFile(uri: Uri): File {
        val providerContext = context ?: error("Provider context unavailable")
        require(uri.authority == authority(providerContext)) { "Unexpected provider authority" }
        require(uri.pathSegments.size == 2 && uri.pathSegments[0] == CAMERA_PATH) {
            "Unexpected provider path"
        }
        val name = uri.pathSegments[1]
        require(FILE_NAME.matches(name)) { "Invalid camera upload name" }
        val root = cacheDirectory(providerContext).canonicalFile
        val file = File(root, name).canonicalFile
        require(file.parentFile == root) { "Camera upload escaped cache root" }
        return file
    }

    companion object {
        private const val CAMERA_PATH = "camera"
        private const val MAX_CACHE_AGE_MS = 24L * 60L * 60L * 1000L
        private val FILE_NAME = Regex("capture-[0-9a-f-]{36}\\.(jpg|mp4)")

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

        fun deleteIfOwned(context: Context, uri: Uri?) {
            if (uri == null || uri.authority != authority(context.applicationContext)) return
            runCatching {
                val name = uri.pathSegments.getOrNull(1) ?: return@runCatching
                if (!FILE_NAME.matches(name)) return@runCatching
                File(cacheDirectory(context.applicationContext), name).delete()
            }
        }

        fun cleanupStaleFiles(context: Context) {
            val cutoff = System.currentTimeMillis() - MAX_CACHE_AGE_MS
            cacheDirectory(context.applicationContext).listFiles().orEmpty()
                .filter { it.isFile && FILE_NAME.matches(it.name) && it.lastModified() < cutoff }
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
